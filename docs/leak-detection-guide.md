# 频域水印生产集成指南（v3.3 W-6）

- 文档版本：v1.0
- 编制日期：2026-08-27
- 对应方案书：v3.2 第四章 4.4 + POC 闭环决策
- 对应 POC：modify/2026-08-12-频域水印鲁棒性POC报告.md

---

## 一、架构

```
┌────────────────────────────────────────────────────────────────┐
│                       用户上传                                    │
│  视频 / 文档 → MinIO                                              │
└────────┬───────────────────────────────────┬───────────────┘
         │ RabbitMQ                              │
         ▼                                       ▼
┌────────────────────┐               ┌──────────────────────┐
│  Worker:           │               │  Worker:              │
│  PHashService      │               │  DocWatermarkService │
│  - 抽帧 (30 帧)    │               │  - DCT 嵌入            │
│  - 计算 pHash      │               │  - 存 doc_watermark  │
│  - 存指纹库        │               │                      │
└────────┬───────────┘               └──────────┬───────────┘
         │ video_fingerprint                        │ doc_watermark
         ▼                                            ▼
┌────────────────────────────────────────────────────────────────┐
│                    MySQL（指纹库 + 水印记录）                    │
└────────────────────────────────────────────────────────────────┘
                                ▲
                                │
┌────────────────────────────────────────────────────────────────┐
│                  疑似泄露视频上传                                 │
│                       ↓                                          │
│  Worker: LeakDetectionService                                     │
│  - 抽帧 + pHash                                                  │
│  - 与 fingerprint_db 匹配                                         │
│  - 多帧投票（≥ 3 帧 + 平均相似度 > 80%）                       │
│  - 命中 → LeakDetectionAlertService                              │
└────────┬───────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│            告警：钉钉/企微 + WebSocket 实时推送                      │
└────────────────────────────────────────────────────────────────┘
```

---

## 二、视频指纹生产（pHash）

### 2.1 观看时自动生成指纹

每次用户观看视频时，Worker 异步计算 30 帧 pHash 并存储到 `video_fingerprint` 表。

**代码位置**：`worker/src/edam_worker/fingerprint/PHashService.py`

```python
from edam_worker.fingerprint.PHashService import phash_service

# 计算视频指纹（异步任务触发）
fingerprints = phash_service.compute_video_fingerprints(
    video_path="/tmp/encrypted/video_12345.mp4",
    user_id=12345,
    video_id=12345,
    session_id="s_xxxxxxxxxx",
    frame_count=30
)

# 存储到 MySQL（Repository.save_batch）
```

### 2.2 指纹存储

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| video_id | BIGINT | 视频 ID |
| user_id | BIGINT | 观看用户 |
| session_id | CHAR(64) | 会话 ID |
| frame_index | INT | 帧索引 |
| timestamp_sec | DECIMAL | 时间戳 |
| phash | BINARY(8) | 64 bit 哈希 |
| computed_at | DATETIME(3) | 计算时间 |

### 2.3 指纹算法

```
算法：Zauner 2010 pHash
步骤：
1. 帧缩放至 32x32
2. 计算 DCT（离散余弦变换）
3. 取左上 8x8 低频子带（去掉 DC 分量）
4. 计算中位数
5. 大于中位数 → 1，否则 → 0
6. 8x8 = 64 bit 哈希

鲁棒性：
- 压缩（CRF ≤ 35）：高鲁棒
- 亮度变化（±20%）：高鲁棒
- 几何变换（小幅）：高鲁棒
- 重编码 + 缩放：中等鲁棒
- 大幅裁剪 + 滤镜：弱鲁棒
```

---

## 三、文档水印生产（DCT 频域）

### 3.1 文档上传时自动嵌入

每次用户上传或下载文档时，Worker 异步嵌入工号 + 时间戳。

**代码位置**：`worker/src/edam_worker/fingerprint/DocWatermarkService.py`

```python
from edam_worker.fingerprint.DocWatermarkService import doc_watermark_service

# 嵌入水印
result = doc_watermark_service.embed_image_watermark(
    input_path="/tmp/original_doc.jpg",
    output_path="/tmp/watermarked_doc.jpg",
    watermark_text=f"USER_SA0001_2026-08-27_10:30:00",
    password_wm=1,
    password_img=1
)
```

### 3.2 支持格式

| 格式 | 支持 |
| --- | --- |
| JPG / JPEG | ✅ |
| PNG | ✅ |
| BMP | ✅ |
| TIFF | ✅ |
| PDF | ⚠️（需额外实现）|
| Word / Excel | ❌（方案 B 移除驱动层后不支持文件级水印）|

### 3.3 嵌入位置

- **DCT 中频系数**（避开 DC 与高频）
- **强度**：0.10（视觉无感知 + 提取率高）

---

## 四、泄露检测服务

### 4.1 检测流程

```python
from edam_worker.fingerprint.LeakDetectionService import leak_detection_service

# 1. 加载指纹库（从 DB）
fingerprint_db = []
for fp in db.query(VideoFingerprint).filter_by(...):
    fingerprint_db.append(FrameFingerprint(...))

# 2. 执行检测
result = leak_detection_service.detect_from_video(
    leaked_video_path="/oss/leaked/video.mp4",
    fingerprint_db=fingerprint_db,
    frame_sample_count=30
)

# 3. 判断结果
if result.is_leaked:
    print(f"泄露源: user_id={result.matched_user_id}")
    print(f"置信度: {result.best_match_score:.1%}")
    print(f"匹配帧: {result.matched_frames}/{result.total_frames}")
    # 触发告警
```

### 4.2 多帧投票算法

| 阈值 | 数值 | 含义 |
| --- | --- | --- |
| 单帧匹配（汉明距离） | ≤ 10 | 两帧 pHash 相似 |
| 多帧投票数 | ≥ 3 | 至少 3 帧匹配 |
| 平均相似度 | > 80% | 平均相似度超过 80% |
| 整体阈值 | 全部满足 | 判定为泄露 |

### 4.3 性能

- **30 帧指纹库匹配**：< 1 秒
- **100 万帧指纹库**：< 5 秒（ES 加速后）
- **CPU 密集**：8 核 16 GB 即可

---

## 五、告警服务

### 5.1 告警触发

```java
@Service
public class LeakDetectionAlertService {

    public void handleDetectionResult(...) {
        // 1. 持久化到 leak_detection 表
        leakRepository.insert(detection);

        // 2. 通知安全团队（钉钉/企微 webhook）
        sendSecurityAlert(detection);

        // 3. WebSocket 实时推送给管理员
        notificationController.broadcastLeakAlert(detection);
    }
}
```

### 5.2 告警级别

| 状态 | 通知对象 | 告警级别 |
| --- | --- | --- |
| `pending`（首次检测） | 安全值班 | P1 |
| `confirmed`（人工确认） | 安全 + 法务 + HR | P0 |
| `dismissed`（误报） | 安全 | 仅记录 |

### 5.3 响应流程

```
1. pending → 安全值班收到告警
2. 值班审查（看截图、查访问记录）
3. 确认 → confirmed
   ├─ 通知法务（启动司法鉴定流程）
   ├─ 通知 HR（启动员工处理流程）
   └─ 启动 WebAuthn/SSO Token 吊销
4. 误报 → dismissed（记录用于算法优化）
```

---

## 六、数据库表

| 表 | 用途 | 保留期 |
| --- | --- | --- |
| `video_fingerprint` | pHash 帧指纹库 | 365 天 |
| `doc_watermark` | 文档水印记录 | 永久（合规） |
| `leak_detection` | 泄露检测记录 | 730 天（2 年诉讼时效）|

---

## 七、监控与告警

| 指标 | 阈值 | 告警 |
| --- | --- | --- |
| 指纹计算耗时 | > 5s/视频 | P2 |
| 泄露检测耗时 | > 30s/查询 | P2 |
| 命中误报率 | > 30% | 算法优化 |
| 单帧匹配率 | > 10% | 阈值调整 |
| 库大小 | > 1 亿帧 | 容量规划 |

---

## 八、性能与容量

### 8.1 单视频指纹

| 项 | 数值 |
| --- | --- |
| 帧数 | 30 |
| 计算耗时 | 2-5 秒 |
| 存储大小 | 240 字节/视频（30 × 8 字节） |

### 8.2 系统总容量

| 场景 | 容量 |
| --- | --- |
| 10 万视频 × 1000 观看 | 1 亿帧 = 8 GB |
| 100 万视频 × 1000 观看 | 10 亿帧 = 80 GB |
| 1000 万视频 × 100 观看 | 10 亿帧 = 80 GB |

### 8.3 性能优化

- **ES 加速**：指纹库导入 ES，按 phash 索引
- **Redis 缓存**：热门视频指纹缓存
- **预计算**：上传时立即生成（不等待观看）
- **分布式**：Worker 多实例并行

---

## 九、与其他模块集成

| 模块 | 集成方式 |
| --- | --- |
| **HLS 加密** | 观看时同步生成指纹 |
| **频域水印 POC** | 替代 POC，实现生产化 |
| **STRIDE 35 条** | 缓解 I1-I3（信息泄露）|
| **PIA 评估** | 满足溯源要求（GB/T 35273）|
| **法务 SOP** | 5.3.5 节司法鉴定 |
| **HR 离职流程** | 自动吊销 token |

---

## 十一、相关文档

- `modify/2026-08-12-频域水印鲁棒性POC报告.md` — POC 设计 + 实测
- `docs/key-management.md` — 密钥管理
- `modify/2026-08-12-威胁建模报告.md` — STRIDE 35 条
- `modify/2026-08-12-方案B决策记录.md` — 移除驱动层决策

---

**频域水印生产集成指南完成。** 等待团队按 3 周节奏实施 + 灰度发布。