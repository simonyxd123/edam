# ES 索引策略（v3.2 V-7）

## 1. 索引设计

### 1.1 单索引方案（推荐）

| 项 | 值 |
| --- | --- |
| 索引名（实体） | `edam_resources_v1` |
| 别名（查询使用） | `edam_resources` |
| 资源类型字段 | `resource_type: video | document` |

**优点**：
- 单一别名简化查询逻辑
- 跨资源类型检索（如同时搜视频+文档）天然支持
- Mapping 演进：v2 → v3 通过别名切换

### 1.2 分片策略

| 项 | 值 | 理由 |
| --- | --- | --- |
| number_of_shards | 3 | 单集群 ≤ 5 节点，主分片 3 个 |
| number_of_replicas | 2 | 允许 2 节点故障 |
| refresh_interval | 5s | 平衡实时性 vs 写入性能 |

### 1.3 Mapping 关键字段

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `title` | text + ik_max_word | 主标题搜索 |
| `title.raw` | keyword | 聚合 / 排序 |
| `title.pinyin` | text + pinyin | 拼音搜索（如 "机密" → "jimi"） |
| `description` | text + ik_max_word | 描述搜索 |
| `tags` | keyword | 标签精确匹配 / 聚合 |
| `asr_text` | text + ik_max_word | 视频字幕搜索 |
| `classification_lv` | keyword | L1-L4 过滤 |
| `file_hash` | keyword | 去重 |

## 2. 别名切换流程

### 2.1 索引版本切换（滚动升级）

```bash
# 1. 创建 v2 索引
PUT edam_resources_v2
  （使用新 mapping + aliases: {}）

# 2. 双写（新数据同时写入 v1 + v2）
POST edam_resources/_doc   # 通过别名，自动路由到 v1
POST edam_resources_v2/_doc  # 显式写入 v2

# 3. 回填历史数据
POST _reindex
{
  "source": { "index": "edam_resources_v1" },
  "dest": { "index": "edam_resources_v2" }
}

# 4. 切换别名（原子操作）
POST _aliases
{
  "actions": [
    { "remove": { "index": "edam_resources_v1", "alias": "edam_resources" } },
    { "add": { "index": "edam_resources_v2", "alias": "edam_resources" } }
  ]
}

# 5. 删除 v1
DELETE edam_resources_v1
```

## 3. 索引模板（Index Template）

### 3.1 自动应用

```json
PUT _index_template/edam_resources_template
{
  "index_patterns": ["edam_resources_v*"],
  "template": {
    "settings": { ... },
    "mappings": { ... }
  },
  "priority": 100
}
```

### 3.2 应用方式

- 新建 `edam_resources_v3` 时自动应用模板
- 字段 `dynamic: strict` 强制严格模式（防止字段爆炸）

## 4. 查询 DSL 示例

### 4.1 多字段全文搜索

```json
GET edam_resources/_search
{
  "query": {
    "bool": {
      "should": [
        { "match": { "title": "机密视频" } },
        { "match": { "title.pinyin": "jimi" } },
        { "match": { "description": "机密" } },
        { "match": { "asr_text": "机密" } },
        { "match": { "tags": "机密" } }
      ],
      "minimum_should_match": 1,
      "filter": [
        { "term": { "classification_lv": "L3" } },
        { "term": { "deleted": false } }
      ]
    }
  },
  "highlight": {
    "fields": {
      "title": {},
      "asr_text": {}
    }
  }
}
```

### 4.2 聚合查询（管理后台）

```json
GET edam_resources/_search
{
  "size": 0,
  "aggs": {
    "by_classification": {
      "terms": { "field": "classification_lv" }
    },
    "by_uploader": {
      "terms": { "field": "uploader_id", "size": 20 }
    },
    "size_stats": {
      "stats": { "field": "size_bytes" }
    }
  }
}
```

## 5. 容量规划

| 资源数 | 存储估算 | 分片数 |
| --- | --- | --- |
| 10 万 | ~5 GB | 3（标准） |
| 100 万 | ~50 GB | 3 + 1 副本 = 6 副本 |
| 1000 万 | ~500 GB | 9（3 倍分片）+ 2 副本 = 27 副本 |

## 6. 与 MySQL 一致性

- **数据源**：MySQL 是权威源（sys_* / video_resource / doc_resource）
- **同步方式**：CDC（Canal / Debezium）监听 binlog → 写入 ES
- **冲突解决**：以 MySQL 为准；ES 失效时强制 rebuild
- **删除同步**：MySQL 软删除 → ES 设置 `deleted=true`（不真删）

## 7. 安全与权限

### 7.1 字段级权限

- 视频/文档实际访问控制走 MySQL RBAC + 资源 ACL
- ES 只用于检索（不暴露原始 MinIO 路径）
- 搜索结果中包含敏感字段（如 `description`）需脱敏后返回

### 7.2 索引访问

- ES 集群部署在内网，不暴露公网
- 应用层走 Spring Data Elasticsearch，限制为 service 账号
- 定期审计搜索查询日志（审计员权限）

## 8. 监控

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| 索引延迟（MySQL → ES） | ≤ 30s | > 60s 告警 |
| 查询 P99 | ≤ 200ms | > 500ms 告警 |
| 集群健康 | green | yellow 告警 / red 立即 |
| 磁盘使用率 | ≤ 70% | > 80% 告警 |
| JVM heap | ≤ 70% | > 85% 告警 |

## 9. 实施清单

- [ ] 安装 ES 8.x + IK + pinyin 插件
- [ ] 创建索引模板 `edam_resources_template`
- [ ] 部署 CDC 同步（Canal/Debezium）
- [ ] SearchController 改造为真 ES 查询（替换 mock）
- [ ] Prometheus + Grafana ES 监控仪表板

## 10. 切换策略

| 阶段 | 状态 | 入口 |
| --- | --- | --- |
| 当前（v3.2） | 占位实现：MySQL 查询 | `GET /search/videos` 返回 videoService.list() |
| v3.3 | 灰度：50% 流量走 ES | OpenFeature 切换 |
| v3.4 | 全量：100% 走 ES | 移除占位实现 |