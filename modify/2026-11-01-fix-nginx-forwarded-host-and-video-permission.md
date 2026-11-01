# 两个遗留：nginx 转发头丢端口 + 视频权限模型落地

- 日期：2026-11-01
- 触发：上一轮修完"换个账号就播不了"后，遗留的两条提醒

---

## 一、提醒一：nginx 的 `X-Forwarded-Host` 丢端口

### 问题

后端原先靠 `X-Forwarded-Host` 反推浏览器侧的 origin，而 nginx 的标准配置是：

```nginx
proxy_set_header X-Forwarded-Host $host;
```

**`$host` 不含端口**（含端口的是 `$http_host`）。
部署环境跑在 `https://host:65173`，于是后端拿到的是 `host`，
拼出来的地址少了 `:65173` ⇒ 指向另一个 origin ⇒ 浏览器连不上。

### 改动

| 文件 | 改动 |
|---|---|
| `helm/edam/values.yaml` | `X-Forwarded-Host $host` → `$http_host` |
| `dev/waf/nginx-modsecurity.conf` | `Host $host` → `$http_host`；**补上** `X-Forwarded-Host $http_host`（原来根本没这个头） |

### 为什么现在改还有意义

上一轮已经把播放地址改成**根相对路径**（由 hls.js 用 manifest 自身的 URL 解析），
所以播放**不再**依赖这个头。但它还影响别处 —— 比如审计日志里的真实 URL ——
少了端口就少了定位信息。nginx 是**部署时**改的配置，
所以这次必须同时提醒：改了 nginx 配置要 `nginx -s reload`。

---

## 二、提醒二：`video_permission` 表和 `batch-permission` 接口至今是 501

### 问题

`video_permission` 表在 `V20260812_1000__init_schema.sql` 里就建好了
（`actions` 位掩码 + `expire_at` + `granted_by` + 唯一键 `uk_video_user`），
但**没有任何代码往里写** —— `POST /videos/batch-permission` 直接返回 501，
理由是"授权模型还没定"。

结果是：**任何登录用户都能播任何视频**（`GET /videos/{id}` 和
`POST /playback/{id}/token` 都没有可见性校验）。

### 授权模型（照仓库自己的文档定的，不是我发明的）

依据三处已有文档，彼此一致：

| 来源 | 内容 |
|---|---|
| `doc/database_schema.md` §3.1 | `actions` 位掩码 **1=view 2=download 4=edit 8=delete 16=distribute**；`expire_at`；`granted_by`；唯一键 `uk_video_user` |
| `doc/openapi.yaml` `/videos/batch-permission` | 请求体 `video_id / user_ids / actions / expire_at`，响应 `granted_count` |
| 方案书 5.4 / 6.3.1 | 「细粒度权限」「越权分享自动阻断」；密级 L1-L4 的**保护措施**与**触发条件** |

据此定下四条规则：

**① 动作位掩码**（`video_permission.actions`）

| 位 | 含义 |
|---|---|
| 1 | view（能看/能播） |
| 2 | download（能下原片） |
| 4 | edit（能改元数据） |
| 8 | delete（能删） |
| 16 | distribute（能再分享给别人） |

同一 `(video_id, user_id)` 唯一 ⇒ **再次授权是覆盖，不是叠加**。

**② 有效期**：`expire_at` 为空 = 永不过期；非空则到期自动失效。
过期的授权行**留着**（要留痕），但判定时当它不存在。

**③ 再分享**：只有**上传者本人、管理员、或持有 `distribute` 位**的人
才能把授权转授出去 —— 方案书 5.4「越权分享自动阻断」。

**④ 可见性 = 密级 + 显式授权**（方案书 6.3.1 的密级模型）

| 密级 | 谁能看 |
|---|---|
| L1 公开 | 任何登录用户 |
| L2 内部 | 任何登录用户（内部员工） |
| L3 机密 | 上传者本人、**同部门**、被显式授权 |
| L4 绝密 | 上传者本人、被显式授权（"少数核心人员"） |

显式授权**优先于**密级 —— 它是"项目组"这个维度的唯一载体
（`video_permission` 是逐用户的表，没有部门字段）。

上传者天然有全部权限：视频是他上传的，方案书没要求他给自己授权；
做成"上传者天然全权"能避免"视频上传了但谁都看不了"的死角。

### 改动

**新增**

| 文件 | 作用 |
|---|---|
| `model/VideoPermission.java` | `video_permission` 实体（表已存在，无需 migration） |
| `repository/VideoPermissionRepository.java` | 仓储 |
| `service/VideoPermissionService.java` | 授权模型的全部判定：`grant/revoke/effectiveActions/canView/canDistribute/applyVisibility` |

**改造**

| 文件 | 改动 |
|---|---|
| `controller/VideoController.java` | `batch-permission` 从 501 改成**真正落库**；新增 `DELETE`（撤销）、`GET`（查列表）；`GET /videos/{id}` 加可见性校验 |
| `controller/PlaybackController.java` | `POST /playback/{id}/token` 加可见性校验（没权限 ⇒ 403） |
| `service/VideoService.java` | 列表加可见性过滤（与 `canView` 同源） |
| `security/JwtTokenProvider.java` | 新增 `createPlaybackToken` / `isPlaybackTokenFor` |
| `repository/SysUserRepository.java` | 新增 `findListByDeptId`（L3 同部门判定用） |

### 顺带堵上的一个洞：播放 token 必须绑定视频

`m3u8` / `segment` / `key` 三个端点在 Spring Security 层是 `permitAll`
（Hls.js 拉不了自定义头，token 只能放 query string），端点内部**只验签**。

⇒ **普通登录 token 也能拿来拉任意视频的播放流**，鉴权形同虚设。

所以播放 token 额外带两个 claim：

- `scope=playback` —— 标记"这是播放 token"
- `video_id` —— 绑定到具体视频

端点侧据此校验：A 视频的播放 token 拉不了 B 视频，普通登录 token 拉不了任何视频。

### 前端

`VideoDetail.vue` 的 catch 里区分 403：原来 axios 的通用提示是
"没有权限执行该操作"，看不出是哪个视频、什么原因；现在显示后端给的
`detail`（含密级），或"没有该视频的查看权限，请联系上传者或管理员授权"。

---

## 三、验证

- backend 全量：**93 个用例，87 passed**，另有 6 个 `ApplicationStartupTest` 报错 ——
  `Communications link failure`，本机没有 MySQL。
  **改动前同样报这 6 个错**（本机验证过 485875d 基线），与本次无关
  （CI 里这两个用例配了 MySQL/RabbitMQ，见 d3f0330）
- 新增用例：
  - `VideoPermissionServiceTest` **17 个** —— 密级兜底、有效期、动作位、再分享、落库字段
  - `PlaybackTokenScopeTest` **5 个** —— 播放 token 的绑定语义
  - `PlaybackControllerTest` +1 —— 非本视频的播放 token ⇒ 401
- web：**34 passed**（无新增），`vue-tsc --noEmit` 无报错
- `doc/openapi.yaml`：YAML 解析通过，65 个 path；`batch-permission` 三个方法齐全

---

## 四、服务器上怎么收尾

```bash
cd /var/data/project/edam && git pull
sudo systemctl restart edam-backend
```

**nginx 也要改**（仓库里改的是模板，服务器上是独立文件）：

```nginx
# 原来
proxy_set_header X-Forwarded-Host $host;
# 改成
proxy_set_header X-Forwarded-Host $http_host;
```

改完 `nginx -t && nginx -s reload`。

**数据库**：不需要 migration —— `video_permission` 表在初始 schema 里就有。

### 验证

```bash
# 1. 授权接口不再是 501
curl -s -X POST http://127.0.0.1:8092/api/v1/videos/batch-permission \
  -H "Authorization: Bearer <上传者的登录token>" \
  -H "Content-Type: application/json" \
  -d '{"video_id":7,"user_ids":[101],"actions":1,"expire_at":"2026-12-01T00:00:00Z"}'
# 期望：{"granted_count":1,...}

# 2. 被授权人现在能拿到播放 token
curl -s -X POST http://127.0.0.1:8092/api/v1/playback/7/token \
  -H "Authorization: Bearer <被授权人的登录token>"
# 期望：200 + m3u8_url
```

### 注意

**已上传的视频没有 `video_permission` 行。** 这不影响 L1/L2 的可见性
（那两个密级对所有登录用户可见），但 **L3/L4 的视频在被显式授权前谁都看不了** ——
除了上传者本人。存量数据如果有大密级的，需要给该上传者补一次授权
（上传者对自己有全权，所以其实只有"别人要看"才需要授权）。

---

## 五、遗留

1. **`doc_permission` 还没做** —— 文档侧的 `doc_permission` 表同样是空的，
   `VideoPermissionService` 的模式可以直接照搬一个 `DocPermissionService`。
   文档预览入口 `DocumentPreviewService.load` 目前也没做可见性校验。

2. **前端没有分享界面** —— `batch-permission` 只有 API，
   `Videos.vue` 里还没"分享给某人"的按钮。

3. **`video_permission` 的 `granted_by` 没做外发审批联动** ——
   方案书 5.4 提到外发要走 `distribution_approval`，
   视频的分享目前不走审批流程（文档的走）。

4. **K8s 侧要重建镜像**（后端代码改了）。
