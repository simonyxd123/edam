# 运维 SOP 索引

| 编号 | 文档 | 适用范围 |
| --- | --- | --- |
| SOP-01 | [故障应急响应](./01-incident-response.md) | 所有 P0/P1/P2/P3 故障 |
| SOP-02 | [灾备演练](./02-disaster-recovery.md) | 跨可用区、异地灾备 |
| SOP-03 | [性能调优](./03-performance-tuning.md) | 性能瓶颈定位与优化 |
| SOP-04 | [变更管理](./04-change-management.md) | 所有生产变更 |

## 使用指南

1. **故障发生**：参考 SOP-01
2. **季度演练**：参考 SOP-02
3. **性能问题**：参考 SOP-03
4. **计划变更**：参考 SOP-04

## 配套资源

- `runbook/`：常见故障的快速操作手册
- `change-log/`：历史变更记录
- `monitoring/grafana/dashboards/`：监控仪表板
- `monitoring/grafana/alerts/`：告警规则
- `scripts/`：运维自动化脚本

## 持续改进

每季度回顾 SOP：
- 新增场景
- 更新过时内容
- 优化流程
- 培训值班人员