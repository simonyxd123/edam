# 运维 SOP-03：性能调优

> 适用范围：EDAM 系统性能优化
> 版本：v3.1 / 2026-08-12

## 1. 性能基线

参考 `database_schema.md` 第九章 + 方案书第十五章非功能性需求。

| 指标 | 目标 | 当前 |
| --- | --- | --- |
| 视频首屏 | < 2s | 待测 |
| 文档预览首屏 | < 1.5s | 待测 |
| 视频并发 | 5000 路 | 待测 |
| 文档预览并发 | 10000 用户 | 待测 |
| API P95 | < 200ms | 待测 |
| API P99 | < 500ms | 待测 |
| 转码吞吐 | 4 路 1080p/GPU | 待测 |
| 水印嵌入 | 50 文档/分钟 | 待测 |
| 可用性 | 99.9% | 99.95% |

## 2. 性能监控

### 2.1 关键指标

**应用层**（Spring Boot Actuator + Prometheus）：
- `http_server_requests_seconds_count{uri, method, status}`
- `jvm_memory_used_bytes{area=heap}`
- `hikaricp_connections_active`
- `rabbitmq_queue_messages_ready`

**基础设施**：
- CPU / Memory / Disk / Network
- Pod 重启次数
- 节点负载

**业务层**：
- 视频转码耗时
- 水印嵌入耗时
- 缓存命中率
- 数据库慢查询数

### 2.2 Grafana 仪表板

预置仪表板（JSON 在 `monitoring/grafana/dashboards/`）：

1. **overview.json**：总览（QPS / 错误率 / 延迟）
2. **backend.json**：后端 JVM / DB / MQ
3. **nginx.json**：Nginx 流量 / Secure Link 命中率
4. **worker.json**：Worker 队列 / GPU 利用率
5. **mysql.json**：MySQL 慢查询 / 锁等待 / 主从延迟
6. **business.json**：业务指标（播放/上传/审批）

## 3. 性能调优步骤

### 3.1 定位瓶颈

```bash
# 1. 应用层 Profiling（用 Arthas / async-profiler）
kubectl exec -it <pod> -- java -jar arthas-boot.jar
# 进入后执行：
#   dashboard    # 总览
#   thread -n 3  # CPU 占用最高的 3 个线程
#   trace com.example.edam.service.VideoService play
#   profiler start --event cpu
#   profiler stop --format html

# 2. 数据库慢查询
mysql -e "SELECT * FROM performance_schema.events_statements_summary_by_digest
          WHERE avg_timer_wait > 100000000
          ORDER BY avg_timer_wait DESC LIMIT 10\G"

# 3. JVM 分析
# GC 日志
kubectl logs <pod> | grep -E "GC|gc"
# 堆 Dump
kubectl exec <pod> -- jcmd 1 GC.heap_dump /tmp/heap.hprof
kubectl cp <pod>:/tmp/heap.hprof ./heap.hprof
# 用 Eclipse MAT 分析
```

### 3.2 常见瓶颈与优化

#### 瓶颈 1：数据库慢查询

**症状**：API P99 > 1s，Grafana 显示 `mysql_slow_queries` 上升

**诊断**：
```sql
-- 1. 查看慢查询
SHOW FULL PROCESSLIST;

-- 2. 查看慢查询日志
SELECT * FROM mysql.slow_log ORDER BY start_time DESC LIMIT 10;

-- 3. EXPLAIN 分析
EXPLAIN SELECT * FROM video_resource WHERE uploader_id = ? ORDER BY upload_time DESC;
```

**优化**：
1. **加索引**：根据 WHERE / JOIN / ORDER BY 列
2. **改写 SQL**：避免 `SELECT *`，减少 JOIN
3. **分页优化**：深分页用 cursor 而非 OFFSET
4. **读写分离**：查询路由到从库
5. **缓存**：热点数据用 Redis 缓存

#### 瓶颈 2：JVM 频繁 GC

**症状**：Grafana 显示 GC 频繁，内存使用率高

**诊断**：
```bash
# 查看 GC 类型
jstat -gc <pid>

# 堆内存分布
jmap -histo <pid> | head -20
```

**优化**：
1. **调整堆大小**：根据实际数据量
2. **选择 GC 算法**：
   - 低延迟：G1 / ZGC（推荐）
   - 高吞吐：Parallel GC
3. **优化对象分配**：避免大对象短期分配
4. **JVM 参数调优**：
   ```bash
   JAVA_OPTS="-Xms4g -Xmx4g \
              -XX:+UseG1GC \
              -XX:MaxGCPauseMillis=200 \
              -XX:+UseStringDeduplication \
              -XX:+HeapDumpOnOutOfMemoryError \
              -XX:HeapDumpPath=/tmp"
   ```

#### 瓶颈 3：Redis 慢查询

**症状**：Redis 监控显示 `latency` 高 / `blocked_clients` 多

**诊断**：
```bash
redis-cli --latency -h <host>
redis-cli SLOWLOG GET 10
```

**优化**：
1. **避免大 Key**（> 1MB）
2. **避免 O(N) 命令**：禁用 `KEYS *`，改用 `SCAN`
3. **Pipeline 批量操作**
4. **Lua 脚本减少 RTT**

#### 瓶颈 4：RabbitMQ 积压

**症状**：`rabbitmq_queue_messages_ready` 持续上升

**诊断**：
```bash
rabbitmqctl list_queues name messages_ready consumers
```

**优化**：
1. **扩容 Worker**
2. **优化任务处理逻辑**（如批处理）
3. **分片队列**：按资源类型拆分
4. **死信队列监控**

#### 瓶颈 5：Nginx Secure Link 性能

**症状**：HLS 播放 P99 高 / Nginx CPU 高

**诊断**：
```bash
# 查看 Nginx 状态
nginx -V 2>&1 | grep -- '--with-http_stub_status_module'
curl http://nginx/nginx_status

# 查看 Secure Link 验证耗时
tail -f /var/log/nginx/access.log | awk '{print $NF}' | sort -n | tail
```

**优化**：
1. **升级 Nginx**：1.21+ 支持 SHA-256（更快）
2. **缓存 Token 验证结果**（短期）
3. **使用共享内存**：`proxy_cache_path`
4. **优化 HLS 分片大小**：10s → 6s（更多并发）

### 3.3 容量规划

#### CPU 估算

```
单次 API 请求 CPU 时间：~50ms
单 Pod CPU：2000m
QPS per Pod：2000 / 50 = 40 qps
5000 并发 / 40 = 125 Pod
保留 50% 冗余 = 188 Pod
```

#### 内存估算

```
JVM 堆：4 GB
DirectBuffer：1 GB
元数据：2 GB
Pod 总内存：8 GB
```

#### 存储估算

```
5000 员工 × 每人 10 GB = 50 TB
+ 30% 冗余 = 65 TB
+ 备份 3 倍 = 195 TB
```

#### 网络估算

```
5000 并发 × 5 Mbps = 25 Gbps
+ 30% 冗余 = 32.5 Gbps
```

## 4. 性能测试

### 4.1 工具

- **wrk**：HTTP 压测
- **JMeter**：复杂场景
- **k6**：现代压测（支持 JS）
- **Locust**：Python 分布式压测

### 4.2 基准测试

```bash
# wrk 示例
wrk -t 100 -c 1000 -d 60s \
    -H "Authorization: Bearer xxx" \
    https://api.example.com/api/v1/videos
```

### 4.3 关键场景

| 场景 | 工具 | 目标 |
| --- | --- | --- |
| 视频列表查询 | wrk | 5000 QPS |
| 视频播放鉴权 | wrk | 5000 QPS |
| 文档预览 | wrk | 10000 QPS |
| 混合场景 | k6 | 真实用户行为模拟 |
| 长时间稳定性 | Locust | 24h 稳定运行 |

## 5. 性能调优 Checklist

每次性能调优：

- [ ] 明确瓶颈（应用 / DB / Cache / MQ / 网络）
- [ ] 量化当前性能（baseline）
- [ ] 制定优化目标
- [ ] 实施优化
- [ ] A/B 对比
- [ ] 记录变更（ChangeLog）
- [ ] 监控回归

## 6. 持续优化

每月一次性能 review：
- 慢查询 Top 10
- API P99 趋势
- 资源利用率
- 容量规划
- 优化 backlog

## 7. 参考

- [《Java 性能调优实战》](https://time.geekbang.org/column/intro/100028001)
- [《高性能 MySQL》](https://www.highperformancemysql.com/)
- [Nginx 性能调优](https://www.nginx.com/blog/performance-tuning-tips/)
- [Redis 性能调优](https://redis.io/docs/manual/optimization/)