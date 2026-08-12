# 运维 SOP-01：故障应急响应

> 适用范围：EDAM 系统所有生产服务
> 版本：v3.1 / 2026-08-12

## 1. 故障分级

| 等级 | 影响 | 响应 SLA | 解决 SLA | 升级路径 |
| --- | --- | --- | --- | --- |
| **P0** | 全站不可用 / 数据泄露 / 安全事件 | 5 分钟 | 1 小时 | CTO + 安全负责人 |
| **P1** | 核心功能不可用（登录/播放/审批） | 15 分钟 | 4 小时 | 技术总监 |
| **P2** | 非核心功能受影响 | 1 小时 | 24 小时 | 模块负责人 |
| **P3** | 轻微问题 / 性能降级 | 4 小时 | 72 小时 | 团队内部 |

## 2. 应急响应流程

```
发现告警 → 5 分钟内响应 → 15 分钟内确认影响 → 启动应急群
  ↓
初步诊断（看 dashboard）→ 启动 Runbook
  ↓
执行缓解措施 → 持续监控 → 记录时间线
  ↓
故障解决 → 24 小时内输出 Postmortem
```

## 3. 关键 Runbook

### 3.1 后端服务不可用

**症状**：Grafana 显示后端 Pod 全部 NotReady / 5xx 错误率 > 50%

**诊断步骤**：

```bash
# 1. 查看 Pod 状态
kubectl get pods -n edam -l app.kubernetes.io/component=backend

# 2. 查看 Pod 日志
kubectl logs -n edam -l app.kubernetes.io/component=backend --tail=100 --previous

# 3. 查看事件
kubectl get events -n edam --sort-by=.lastTimestamp | tail -20

# 4. 检查依赖
kubectl exec -n edam <pod-name> -- curl -s http://mysql:3306 || echo "MySQL 不可达"
kubectl exec -n edam <pod-name> -- curl -s http://vault:8200/v1/sys/health
```

**缓解措施**：

| 原因 | 措施 |
| --- | --- |
| OOM | 调整 resources.limits.memory；扩容 |
| 数据库连接耗尽 | 重启后端 Pod；检查慢查询；增加 connection pool |
| Vault 不可达 | 切换到本地缓存密钥；告警 SRE |
| 镜像问题 | 回滚到上一个版本：`helm rollback edam <revision>` |
| 配置错误 | 检查 ConfigMap / Secret；对比 git 仓库 |

### 3.2 MySQL 故障

**症状**：数据库连接失败 / 慢查询激增 / 主从延迟

```bash
# 1. 主从状态
mysql -h <master> -e "SHOW MASTER STATUS\G"
mysql -h <slave> -e "SHOW SLAVE STATUS\G"

# 2. 慢查询
mysql -e "SELECT * FROM information_schema.processlist WHERE command != 'Sleep' AND time > 10\G"

# 3. 锁等待
mysql -e "SELECT * FROM information_schema.innodb_lock_waits\G"

# 4. 磁盘空间
df -h /var/lib/mysql
```

**缓解措施**：
- 慢查询：kill 长事务；优化索引
- 锁等待：kill 阻塞线程
- 磁盘满：清理 binlog（保留 24h）；扩容磁盘
- 主库宕机：提升从库；切换应用连接串

### 3.3 Vault 故障

**症状**：业务后端无法获取密钥 / 认证失败

```bash
# 1. Vault 状态
vault status

# 2. Seal 状态
vault operator unseal <key1> <key2> <key3>

# 3. 业务降级（业务后端缓存会话密钥）
kubectl set env deployment/edam-edam-backend VAULT_FAIL_OPEN=true
```

**严重程度**：Vault 是核心依赖，必须在 30 分钟内恢复，否则触发 P0 升级。

### 3.4 RabbitMQ 积压

**症状**：HLS 切片、文档水印等异步任务积压

```bash
# 1. 查看队列深度
rabbitmqctl list_queues name messages_ready messages_unacknowledged

# 2. 临时扩容 Worker
kubectl scale deployment edam-edam-worker --replicas=10

# 3. 清空死信队列（谨慎）
rabbitmqctl purge_queue <queue-name>
```

### 3.5 MinIO 不可用

**症状**：视频/文档无法下载 / 上传

```bash
# 1. MinIO 健康
mc admin info local

# 2. 查看磁盘
df -h /data

# 3. 重建单节点（灾难场景）
# 1) 停服务
# 2) 删除损坏的 .minio.sys
# 3) 启动服务
# 4) 触发 heal：mc admin heal --recursive local/edam-videos
```

## 4. 告警渠道

| 等级 | 渠道 | 通知人 |
| --- | --- | --- |
| P0 | 电话 + 短信 + 钉钉 @所有人 | 全员 |
| P1 | 钉钉群 + 短信 | 技术团队 + 模块负责人 |
| P2 | 钉钉群 | 模块负责人 |
| P3 | 邮件 | 团队 |

## 5. 升级路径

```
值班 SRE → 技术总监（30 分钟未解决）→ CTO（1 小时未解决）→ CEO（重大安全事件）
```

## 6. 事后总结（Postmortem）

**所有 P0/P1 必须在 24 小时内输出 Postmortem**：

模板见 `04-postmortem-template.md`。

包含：
- 时间线（首次告警 → 缓解 → 恢复）
- 根因分析（5 Whys）
- 影响评估（用户数 / 业务损失 / 数据损失）
- 改进措施（短期 / 长期）
- 责任人 + 完成期限

## 7. 工具与资源

- **Grafana**：https://grafana.example.com
- **Prometheus**：https://prometheus.example.com
- **Kibana**：https://kibana.example.com（ELK）
- **ArgoCD**：https://argocd.example.com
- **Vault UI**：https://vault.example.com
- **Runbook 仓库**：https://git.example.com/edam/runbook
- **应急群**：（钉钉）EDAM 应急响应群

## 8. 演练计划

| 演练 | 频率 | 形式 |
| --- | --- | --- |
| 后端故障切换 | 月度 | 杀 Pod / 节点驱逐 |
| MySQL 主从切换 | 季度 | 主动 failover |
| Vault seal/unseal | 季度 | 实战演练 |
| 灾备切换 | 半年 | 跨可用区切换 |
| 完整业务中断 | 年度 | 桌面推演 + 实战 |