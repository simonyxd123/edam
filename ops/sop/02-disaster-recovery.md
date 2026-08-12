# 运维 SOP-02：灾备演练

> 适用范围：EDAM 系统跨可用区灾备
> 版本：v3.1 / 2026-08-12

## 1. 灾备目标

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| RTO | ≤ 30 分钟 | 故障到恢复时间 |
| RPO | ≤ 5 分钟 | 数据丢失容忍度 |
| 可用性 | 99.9% | 全年停机 < 8.76 小时 |

## 2. 灾备架构

### 2.1 多可用区部署

```
可用区 A（主）          可用区 B（备）
┌────────────────┐    ┌────────────────┐
│ K8s master × 1  │    │ K8s master × 1  │
│ K8s worker × 3  │    │ K8s worker × 3  │
│ MySQL 主库       │    │ MySQL 从库       │  ← 同步复制
│ Redis Cluster   │    │ Redis Cluster   │  ← Cluster
│ Vault Raft (3)  │    │ Vault Raft (3)  │
│ MinIO 节点 1-2  │    │ MinIO 节点 3-4  │  ← 纠删码
└────────────────┘    └────────────────┘
```

### 2.2 异地灾备（冷备）

```
异地机房（300km+）
┌────────────────┐
│ 备份 Vault 数据  │  ← 每天同步
│ MinIO 备份     │  ← 每天同步
│ MySQL binlog   │  ← 实时同步
│ K8s 配置备份    │  ← 每天
└────────────────┘
```

## 3. 备份策略

| 数据 | 频率 | 保留 | 存储 |
| --- | --- | --- | --- |
| MySQL 全量 | 每天 02:00 | 30 天 | OSS + 本地 |
| MySQL binlog | 实时 | 7 天 | OSS |
| Redis AOF | everysec | 7 天 | 本地 |
| MinIO | 每天 03:00 | 90 天 | OSS-IA + 异地 |
| Vault | 实时同步 | 永久 | Vault Raft |
| K8s ConfigMap | 每天 | 永久 | Git 仓库 |
| 审计日志 | 实时归档 | 365 天 | OSS-Archive |

## 4. 灾备演练流程

### 4.1 演练前准备

**T-7 天**：
- 通知业务部门（演练窗口：周日 02:00-06:00）
- 准备演练 Runbook
- 备份验证

**T-1 天**：
- 检查备份完整性
- 确认监控告警正常
- 通知值班人员

**T-0 演练日**：
- 启动应急群
- 记录开始时间
- 按 Runbook 执行

### 4.2 演练场景

#### 场景 1：MySQL 主库故障

**目标**：验证主从切换 + 应用自动重连

**步骤**：
1. 模拟主库故障：`kubectl exec -it mysql-master -- kill -9 1`
2. 观察从库自动提升（read_only=0）
3. 业务后端重连新主库（连接池自动重连）
4. 验证数据完整性
5. 恢复时间记录

**通过标准**：
- RTO ≤ 5 分钟
- 数据零丢失

#### 场景 2：整个可用区 A 故障

**目标**：验证跨可用区切换

**步骤**：
1. 模拟可用区 A 整体故障（驱逐所有 Pod）
2. K8s 集群自动在可用区 B 重新调度
3. 数据库从主切到从
4. 验证所有服务恢复
5. 记录切换时间

**通过标准**：
- RTO ≤ 30 分钟
- RPO ≤ 5 分钟

#### 场景 3：Vault 全部封存

**目标**：验证 unseal 流程

**步骤**：
1. 模拟 Vault seal（`vault operator seal`）
2. 业务后端进入只读降级模式
3. SRE 执行 unseal
4. 验证密钥可访问
5. 业务恢复

**通过标准**：
- Unseal 时间 ≤ 5 分钟

#### 场景 4：MinIO 数据损坏

**目标**：验证数据自愈

**步骤**：
1. 模拟 MinIO 节点宕机
2. 剩余节点降级运行
3. 恢复节点
4. 触发 heal
5. 验证数据完整性

**通过标准**：
- 数据 100% 恢复
- Heal 时间 ≤ 1 小时

## 5. 恢复操作清单

### 5.1 MySQL 主从切换

```bash
# 1. 停止写入（应用层）
kubectl scale deployment edam-edam-backend --replicas=0

# 2. 等待现有连接结束
sleep 30

# 3. 提升从库
kubectl exec -it mysql-slave -- mysql -e "
  STOP SLAVE;
  RESET SLAVE ALL;
  SET GLOBAL read_only = 0;
"

# 4. 修改应用连接
kubectl set env statefulset/edam-mysql MYSQL_HOST=mysql-slave

# 5. 恢复应用
kubectl scale deployment edam-edam-backend --replicas=3

# 6. 验证
kubectl exec -it <backend-pod> -- curl -s http://localhost:8080/health/ready
```

### 5.2 跨可用区切换

```bash
# 1. 隔离可用区 A 节点
kubectl cordon -l topology.kubernetes.io/zone=a

# 2. 驱逐可用区 A Pod
kubectl drain -l topology.kubernetes.io/zone=a --ignore-daemonsets --delete-emptydir-data

# 3. K8s 自动在可用区 B 调度

# 4. 切换数据库主从（如主在 A）
# 参照 5.1 步骤

# 5. 恢复节点
kubectl uncordon -l topology.kubernetes.io/zone=a
```

### 5.3 异地切换

```bash
# 1. 启动异地 K8s 集群
# 2. 拉取最新镜像
# 3. 应用 K8s manifest
# 4. 恢复数据库（从 binlog）
# 5. 恢复 MinIO（从备份）
# 6. 恢复 Vault（从备份 unseal）
# 7. 切换 DNS / Ingress
# 8. 验证业务
```

## 6. 验证清单

每次演练后必须验证：

- [ ] 登录功能
- [ ] 视频播放
- [ ] 文档预览
- [ ] 外发审批
- [ ] 水印溯源
- [ ] 审计日志查询
- [ ] 监控指标正常
- [ ] 告警渠道畅通

## 7. 演练报告模板

```markdown
# 灾备演练报告 - YYYY-MM-DD

## 基本信息
- 演练时间：
- 演练场景：
- 参与人员：
- 演练负责人：

## 时间线
- 02:00 启动演练
- 02:05 故障注入
- 02:10 检测到故障
- 02:25 恢复完成
- 02:35 验证完成
- RTO: 25 分钟
- RPO: 3 分钟

## 问题与改进
1. ...
2. ...

## 下次演练
- 时间：
- 场景：
```

## 8. 常见问题

**Q：演练会影响生产吗？**
A：不会。演练窗口选在业务低峰（02:00-06:00），且仅模拟故障，不影响真实数据。

**Q：演练失败怎么办？**
A：立即回滚，恢复生产，记录问题，下季度重试。

**Q：多久演练一次？**
A：场景 1-3 每季度一次，场景 4 每半年一次。