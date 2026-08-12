# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目性质

本仓库为 **企业全格式数字资产防泄密系统** 的方案文档库，目前 **尚无源代码**，仅包含一份 `doc/` 下的技术方案书（`.docx`）。

后续若进入开发阶段，所有代码、配置、脚本的实现都必须以该方案书为唯一基准依据；如方案书未明确说明的技术细节，应先与用户确认再实现，不要自行假设。

## 目录结构

```
edam/
├── CLAUDE.md          # 本文件
├── doc/
│   └── 企业全格式数字资产防泄密系统技术方案书.docx   # 系统唯一权威技术方案
└── .claude/
    └── settings.local.json   # 仅用于允许 unzip 解析 docx
```

`doc/` 目录约定：所有项目相关说明、变更记录、总结类 Markdown 文件均放在 `modify/` 子目录下，不直接放在 `doc/`。

## 文档处理

`.docx` 是加密的 ZIP，可使用 `unzip` 解压查看内部 XML（`word/document.xml` 中存放正文文本），本仓库已在 `.claude/settings.local.json` 中预先授予该命令权限：

```bash
unzip -o "doc/企业全格式数字资产防泄密系统技术方案书.docx" -d extracted
```

如需长期保存解析结果，请输出到 `modify/` 而非仓库根目录。

## 系统架构摘要（来自技术方案书）

系统采用 **四层架构**，支撑视频与文档双通道：

1. **前端展示层** — Vue3/React + DPlayer/Video.js/hls.js + Canvas 动态明水印 + 防 DevTools 抓取
2. **业务后端层** — Java 17 + Spring Boot 3.x + MyBatis-Plus，统一鉴权、动态 Token、密钥下发
3. **异步处理流水线** — RabbitMQ + Python Worker（FFmpeg HLS 切片/AES 加密、OpenCV + blind-watermark 频域盲水印，支持 GPU 加速）
4. **流媒体与文档服务层** — Nginx（secure_link）+ FFmpeg；文档安全网关负责透明加解密、外发审批

### 核心技术栈

| 类别 | 选型 |
| --- | --- |
| 后端 | Java 17 + Spring Boot 3.x + MyBatis-Plus |
| 流媒体 | Nginx（secure_link）+ FFmpeg |
| 异步队列 | RabbitMQ / RocketMQ |
| 盲水印 | Python 3.10 + OpenCV + blind-watermark |
| 前端 | Vue3/React + DPlayer + Video.js + hls.js |
| 存储 | MySQL 8.0 + Redis + MinIO |
| 容器化 | Docker + Kubernetes |

### 六层纵深防御

传输层（TLS/SSL）→ 鉴权层（动态 Token + RBAC）→ 存储层（AES-128/AES-256/SM4）→ 视觉层（Canvas 动态明水印）→ 频域层（DWT 频域盲水印/隐写术）→ 前端/终端层（禁用右键/F12、剪贴板管控、打印点阵水印）

### 核心数据表

`sys_user`、`sys_role_permission`、`video_resource`、`doc_resource`、`video_permission`、`doc_permission`、`play_log`、`watermark_cache`；高频查询需在 `file_hash`、`upload_time`、`user_id`、`access_time` 上建联合索引。

## 开发约定

- **所有代码变更需对应方案书章节**：在 commit/修改记录中引用方案书中的章节号（如"4.2 HLS 切片加密"）。
- **未在方案书中明确的技术细节**（如具体 ORM 用法、日志框架、CI/CD 工具）需主动询问用户，不要默认。
- **数据存储**：业务数据 MySQL 8.0；高频缓存 Redis；视频/文档分片 MinIO；密钥与用户敏感信息加密存储。
- **接口设计**：视频鉴权走 Nginx `secure_link`（MD5(视频ID+过期时间戳+随机盐+密钥)），密钥接口需校验 Session/Token；前端水印信息通过 Token 下发。
- **不要自动 git 提交**：所有变更需用户确认后再手动提交。
- **修改记录**：实现完成或方案调整后，在 `modify/` 下新建 `<日期>-<主题>.md` Markdown 文件记录变更。