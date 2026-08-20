"""
ES CDC Consumer（v3.3 W-10）

监听 MySQL binlog（通过 Debezium/Kafka Connect），同步数据到 ES 索引。

生产部署建议：
- Debezium Server（推荐，独立部署）
- Kafka + Kafka Connect + Debezium MySQL Connector
- ES Sink Connector（直接同步）或本脚本（自定义处理）
"""
import json
import logging
import os
import signal
import sys
import time
from datetime import datetime
from typing import Dict, Any, Optional

import requests
from confluent_kafka import Consumer, KafkaError, KafkaException

logger = logging.getLogger(__name__)


class ESCdcConsumer:
    """将 MySQL binlog 变更同步到 Elasticsearch"""

    def __init__(self):
        self.bootstrap_servers = os.environ.get("KAFKA_BOOTSTRAP", "kafka:9092")
        self.kafka_topic = os.environ.get("KAFKA_TOPIC", "edam-es-video_resource")
        self.es_host = os.environ.get("ES_HOST", "http://elasticsearch:9200")
        self.group_id = os.environ.get("KAFKA_GROUP", "es-cdc-consumer")

        # 表与索引映射
        self.table_index_map = {
            "video_resource": "edam_resources",
            "doc_resource": "edam_resources",
            "sys_user": "edam_users",
            "distribution_approval": "edam_approvals",
        }

        # 字段映射（MySQL → ES）
        self.field_map = {
            "video_resource": {
                "id": "id",
                "title": "title",
                "description": "description",
                "classification_lv": "classification_lv",
                "uploader_id": "uploader_id",
                "upload_time": "upload_time",
                "view_count": "view_count",
                "deleted_at": "deleted",
            },
            "doc_resource": {
                "id": "id",
                "title": "title",
                "classification_lv": "classification_lv",
                "uploader_id": "uploader_id",
                "upload_time": "upload_time",
                "view_count": "view_count",
                "deleted_at": "deleted",
            },
            "sys_user": {
                "id": "id",
                "employee_no": "employee_no",
                "real_name": "real_name",
                "email": "email",
                "dept_id": "dept_id",
                "status": "status",
                "created_at": "created_at",
            },
            "distribution_approval": {
                "id": "id",
                "doc_id": "doc_id",
                "applicant_id": "applicant_id",
                "external_recipient_email": "external_recipient_email",
                "reason": "reason",
                "valid_hours": "valid_hours",
                "status": "status",
                "created_at": "created_at",
            },
        }

        self.consumer = Consumer({
            "bootstrap.servers": self.bootstrap_servers,
            "group.id": self.group_id,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        })

        self.running = False

    def start(self, topics: list):
        """启动消费"""
        self.consumer.subscribe(topics)
        self.running = True
        logger.info(f"ES CDC consumer started topics={topics} group={self.group_id}")

        # 注册优雅退出
        signal.signal(signal.SIGINT, self._shutdown)
        signal.signal(signal.SIGTERM, self._shutdown)

        try:
            while self.running:
                msg = self.consumer.poll(timeout=1.0)
                if msg is None:
                    continue
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        continue
                    raise KafkaException(msg.error())

                self._process_message(msg)
                self.consumer.commit(msg, asynchronous=False)
        finally:
            self.consumer.close()

    def _process_message(self, msg):
        """处理单条 CDC 消息"""
        try:
            event = json.loads(msg.value())
            op = event.get("op")  # c=create, u=update, d=delete, r=read(snapshot)

            source = event.get("source", {})
            table = source.get("table", "")

            if op == "r":
                # Snapshot（initial load）
                self._index_document(table, event.get("after", {}))
                logger.debug(f"snapshot_indexed table={table}")
            elif op == "c":
                self._index_document(table, event.get("after", {}))
                logger.info(f"cdc_created table={table} id={event.get('after', {}).get('id')}")
            elif op == "u":
                self._index_document(table, event.get("after", {}))
                logger.info(f"cdc_updated table={table} id={event.get('after', {}).get('id')}")
            elif op == "d":
                # 软删除（标记 deleted=true）而非真正删除
                doc_id = event.get("before", {}).get("id")
                self._soft_delete(table, doc_id)
                logger.info(f"cdc_deleted table={table} id={doc_id}")

        except Exception as e:
            logger.error(f"cdc_process_error: {e}")

    def _index_document(self, table: str, mysql_doc: Dict[str, Any]):
        """将 MySQL 文档索引到 ES"""
        index = self.table_index_map.get(table)
        if not index:
            logger.warning(f"unknown_table {table}")
            return

        # 字段映射
        mapping = self.field_map.get(table, {})
        es_doc = {}
        for mysql_field, es_field in mapping.items():
            if mysql_field in mysql_doc:
                value = mysql_doc[mysql_field]
                if mysql_field == "deleted_at":
                    # 软删除标记
                    es_doc[es_field] = value is not None
                else:
                    es_doc[es_field] = value

        if "id" not in es_doc or es_doc["id"] is None:
            return

        # 推送到 ES
        doc_id = str(es_doc["id"])
        url = f"{self.es_host}/{index}/_doc/{doc_id}?refresh=false"

        for attempt in range(3):
            try:
                resp = requests.put(url, json=es_doc, timeout=10)
                if resp.status_code in (200, 201):
                    logger.debug(f"es_indexed index={index} id={doc_id}")
                    return
                logger.warning(f"es_index_retry status={resp.status_code} body={resp.text[:200]}")
            except requests.RequestException as e:
                logger.warning(f"es_index_error attempt={attempt}: {e}")
            time.sleep(2 ** attempt)

        logger.error(f"es_index_failed index={index} id={doc_id}")

    def _soft_delete(self, table: str, doc_id):
        """标记 ES 文档为已删除（不真删，保留历史）"""
        if doc_id is None:
            return
        index = self.table_index_map.get(table)
        if not index:
            return

        url = f"{self.es_host}/{index}/_update/{doc_id}"
        body = {
            "doc": {"deleted": True, "deleted_at": datetime.utcnow().isoformat()},
            "doc_as_upsert": False,
        }

        for attempt in range(3):
            try:
                resp = requests.post(url, json=body, timeout=10)
                if resp.status_code in (200, 201):
                    logger.info(f"es_soft_deleted index={index} id={doc_id}")
                    return
            except requests.RequestException:
                pass
            time.sleep(2 ** attempt)

    def _shutdown(self, signum, frame):
        logger.info(f"shutdown_signal signum={signum}")
        self.running = False


def main():
    logging.basicConfig(level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")

    consumer = ESCdcConsumer()
    topics = ["edam-es-video_resource", "edam-es-doc_resource",
              "edam-es-sys_user", "edam-es-distribution_approval"]
    consumer.start(topics)


if __name__ == "__main__":
    main()