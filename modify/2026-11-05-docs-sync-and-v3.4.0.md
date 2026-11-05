# 2026-11-05 文档与代码对齐 —— v3.4.0 发版

> 需求：**根据现有的实现情况，整理 README 文档与其他相关文档，让文档与代码保持一致，并更新版本信息。**
>
> 方案书对应章节：
> - §5.4 细粒度权限与外发管控 —— 权限模型必须能被文档准确描述
> - §6.3 密级模型（L1-L4）—— 可见性判定的两套规则
> - §7.1 核心数据表 —— 35 张表的字段定义
> - §8 安全策略总览 —— 七层纵深防御（含管理层）
> - §10.2 风险与应对 —— 「哪些能力其实没接线」必须写清楚

---

## 一、为什么要做

v3.3.0（2026-08-28）之后有 **90 次提交**，但文档几乎没动。盘点下来，
文档与代码的偏差已经大到会误导人：

| 文档说的 | 实际是 | 后果 |
| --- | --- | --- |
| 版本 3.3.0 / 3.1.0 到处不一 | 7 个文件各写各的 | 部署时不知道该用哪个 tag |
| 「65 个端点」 | **95 个** | 漏掉 30 个端点的文档 |
| 「26 张表」 | **35 张** | 10 张表没写 |
| 「22 张表 + v3.1 新增 8 张」 | 同一句话里两个矛盾的数字 | 读者无从判断 |
| 「45 条权限码」 | **46 条** | 少了一条 |
| 权限目录只列了 12 个示例 | 46 条，且**漏了 `action` 字段** | `action` 才是只读基线的判据 |
| 普通员工手册 §1「上传自己的工作视频/文档」 | **普通员工已是只读** | **手册在教一个已经取消的操作** |
| 管理员手册「employee = 看 + 上传 + 预览」 | 同上 | 同上 |
| 「视频上传上限 2GB」 | **8GB** | 用户会以为传不上去 |
| 「防 DevTools 抓取」「频域盲水印」等已实现 | 部分**没接线** | 按文档验收会拿到空壳 |

最后一条最严重：**普通员工手册教用户去点一个点了会 403 的按钮**。
这不是措辞问题 —— 是文档把一个已经撤销的授权当成正式功能在教。

---

## 二、版本号：统一到 3.4.0

改动前，版本号散落在 7 个地方且互相矛盾：

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| `backend/pom.xml` | 3.1.0 | **3.4.0** |
| `backend/src/main/resources/application.yml` | 3.1.0 | **3.4.0** |
| `web/package.json` | 3.1.0 | **3.4.0** |
| `worker/src/edam_worker/__init__.py` | 3.1.0 | **3.4.0** |
| `worker/src/edam_worker/main.py`（FastAPI title） | 3.1.0 | **3.4.0** |
| `e2e/package.json` | 3.3.0 | **3.4.0** |
| `helm/edam/Chart.yaml`（appVersion） | 3.1.0 | **3.4.0** |
| `helm/edam/values.yaml`（三个镜像 tag） | 3.1.0 | **3.4.0** |
| `doc/openapi.yaml`（info.version） | 3.1.0 | **3.4.0** |
| `README.md` 徽章 | 3.3.0 | **3.4.0** |
| `gitops/argocd/applications/edam-prod.yaml` | v3.1.0 | **v3.4.0** |

**为什么是 3.4.0 而不是 3.3.1 / 3.5.0**：

- **不是 3.3.1** —— 期间新增了资源级 ACL、审计切面、RBAC 用户管理、分享界面等**功能**，
  不是纯修复。语义化版本里这属于 minor。
- **不是 3.5.0** —— `modify/2026-08-29-v3.4路线图.md` 已经把 v3.4 定为
  「把 v3.3 的可发布条件转化为已发布 + 已合规」的版本，路线图编号已经占用了 v3.4。
  越过它另起 3.5 会让路线图与实际版本对不上。
- Helm Chart 自身的 `version` 是**独立的版本线**（chart 打包格式），1.0.0 → **1.1.0**。

> `gitops/argocd/applications/edam-prod.yaml` 的 `targetRevision` 与
> `backend/worker.image.tag` 一起改了 —— 生产 ArgoCD 是按 tag 部署的，
> 留着 v3.1.0 会让 prod 一直跑旧版本。**上线前需先打 `v3.4.0` 的 git tag。**

---

## 三、README：按实现重写

整篇重写（不是打补丁）。主要改动：

### 3.1 新增「权限模型」章节

README 原来只有一句「统一权限中心：RBAC + 资源级 ACL + 细粒度授权」，
完全没说清怎么运作。现在把**两层模型**写明白：

- RBAC 层：46 条权限码、四个预置角色、普通员工的只读白名单、
  **为什么要用白名单而不是减法**（防权限漂移）
- 资源级 ACL 层：动作位掩码表、可见性判定的 6 步顺序、
  **下载权与可见性分开判定**（方案书 5.6.1 的 L1 例外）

### 3.2 新增「已知限制」章节（**本次最重要的一节**）

把**代码里没接线**的能力逐条列出来 —— 这是文档最容易骗人的地方：

| 能力 | 现状 |
| --- | --- |
| 盲水印 | 只有 Python Worker 侧有；Java 后端无实现，`/watermarks/extract` 是 mock |
| 密级自动识别 | `ClassificationEnforcement` / `DataClassificationService` **无调用方** |
| 国密链路 | `AlgorithmRouter` / `SmJwtTokenProvider` **无调用方**，JWT 仍是 HMAC-SHA256 |
| 通知 / Webhook 的 HTTP 端点 | 返回 **mock**；真投递逻辑在 `webhook/` 包，实时通道是 STOMP |
| `q.watermark` 队列 | 已声明绑定，**后端无生产者** |
| 文档处理回调 | `DocumentProcessor` **不回写后端状态** |
| ES CDC | 在 `dev/cdc/` 是独立 Python 进程，**后端无 ES 客户端**，搜索其实是模糊匹配 |
| `fingerprint/` 三个 Service | **零引用** |
| SSO | 默认 `edam.sso.enabled: false` |
| 驱动层 | **完全没有实现** |

### 3.3 修正的其它数字

| 项 | 改前 | 改后 |
| --- | --- | --- |
| API 端点 | 65 | **95**（74 paths / 17 tag / 40 schema） |
| 数据库表 | 26 / 「22 + 8」 | **35** |
| Controller | 「9 个」（覆盖报告里） | **19 个**（含 `security/webauthn/`） |
| 纵深防御 | 「七层」（未说明含管理层） | 七层，**列出管理层**（方案书 §10.1 明确「含管理层」） |
| 密级 | 「三级密级：L1/L2/L3/L4」 | **四级**（四个值叫三级是错的） |
| 后端测试 | — | 23 类 / **161 用例** |
| 前端测试 | — | 7 文件 / **58 用例** |
| Worker 测试 | — | 6 文件 / **68 用例**（2 个需 ffmpeg / PyPDF2，跳过） |
| 默认管理员 | `SA0001` | **`E000001`**（`AdminBootstrap.DEFAULT_ADMIN_EMPLOYEE_NO`） |
| 上传上限 | 2GB | **8GB**（视频）/ 50MB（文档） |
| 密钥引用 | `docs/SECURITY.md`（**该文件不存在**） | `SECURITY.md` |
| ArgoCD 部署 | `gitops/argocd/applicationset.yaml`（**不存在**） | `gitops/argocd/applications/edam-{dev,staging,prod}.yaml` |
| 迁移目录 | `dev/db/migration/` | **`backend/src/main/resources/db/migration/`**（前者已废弃） |

> 迁移目录这条尤其要紧 —— README 教人用
> `flyway -locations=filesystem:dev/db/migration migrate`，
> 而那个目录**现在只有一个 README.md**，照着做等于什么都没执行。

---

## 四、doc/openapi.yaml：从 87 个端点到 95 个

### 4.1 修掉 4 组重复 path 键（**规范里有一半是死代码**）

```
/playback/{video_id}/token   出现 2 次
/playback/{video_id}/key     出现 2 次
/rbac/permissions            出现 2 次
/rbac/roles                  出现 2 次
```

**YAML 重复键只保留后一份** —— 也就是说规范里前 4 个定义是**完全不会被读到的死代码**。
更糟的是其中 `/playback/{video_id}/token` 的旧定义描述的是
**过时的 `X-Session-Token` 请求头方案**，而实现早已改成 query string token。
留着它，读者会按错的方式集成。

处理：删除过时的那份，保留与实现一致的那份，并在原位留注释说明去哪看。

### 4.2 补 8 个缺失端点

| 端点 | 说明 |
| --- | --- |
| `POST /auth/webauthn/register/begin` | WebAuthn 注册第一步 |
| `POST /auth/webauthn/register/complete` | WebAuthn 注册第二步 |
| `POST /auth/webauthn/login/begin` | 无密码登录第一步 |
| `POST /auth/webauthn/login/complete` | 无密码登录第二步 |
| `GET /auth/webauthn/credentials` | 凭据列表 |
| `DELETE /auth/webauthn/credentials/{credentialId}` | 撤销凭据 |
| `GET /audit/logs/operation-types` | 操作类型枚举（前端下拉框用） |
| `GET /audit/logs/resource-types` | 资源类型枚举 |

新增 `webauthn` tag（原 16 个 → **17 个**）。

### 4.3 新增 `x-required-permission` 扩展（46 个端点）

规范此前**完全没有记录**「哪个端点要哪个权限码」。对一套以权限为中心的系统，
这是最大的缺口 —— 前后端都只能去翻 Java 源码。

现在每个有 `@PreAuthorize` 的端点都带一行：

```yaml
  /videos:
    post:
      tags: [videos]
      summary: 上传视频
      x-required-permission: video:upload
```

同步靠新脚本 `scripts/sync_openapi_permissions.py` ——
它扫 Controller 解析权限门，再文本级插入规范（**不用 `yaml.dump` 重排**，
那会把 3000 行文件重排一遍，diff 根本没法审）。

### 4.4 补齐缺失的 schema

| schema | 状态 |
| --- | --- |
| `PermissionDTO` | **缺失** —— 引用它的 `/rbac/permissions` 解析不到。已按 `dto/PermissionDTO.java` 补齐（含 `action` / `description`） |
| `VideoResource` | **缺失** —— 按 `model/VideoResource.java` 补齐（snake_case） |
| `DocResource` | **缺失** —— 按 `model/DocResource.java` 补齐 |
| `Permission` | 旧的、内容不全（无 `action`），已并入 `PermissionDTO` |
| `UserRole` | 引用错了 —— 该端点实际返回**角色对象**，已改指 `Role` |

现在：**74 paths / 95 operations / 40 schemas / 17 tags**，
45 处 `$ref` **全部可解析**，无重复键。

---

## 五、doc/database_schema.md

- **表目录补全**：22 → **35 张**（此前只列 18 张，且文内说「22 张 + v3.1 新增 8 张」自相矛盾）
- **`sys_permission` 补 `action` / `description` 两列** —— 此前只写了 3 列，
  而 `action` 正是普通员工只读基线的判据
- **权限目录 12 个示例 → 46 条完整清单**，并附 `action` 分组表
- **新增「本文件未展开的表」一节**：18 张没写字段的表逐个列出
  引入迁移 + 用途，并说明 `operation_log` 的三态 `result` 与
  `user_id` 改可空的原因（未登录的越权尝试也要留痕）
- **新增「权威来源与核对方式」**：表结构的唯一权威是迁移脚本，
  附一条可直接复制执行的核对命令

---

## 六、使用手册：把「教用户点 403 按钮」的章节改掉

### 6.1 普通员工手册

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| §1 核心 4 件事 | 含「**上传** 自己的工作视频/文档」 | 核心 **3** 件事：查看 / 播放 / 预览 + 明示不能上传删除 |
| §5.2 上传视频 | 完整上传操作说明 | **整节删除**，合并进 §5.2 播放视频 |
| §6.2 上传文档 | 完整上传操作说明 | **整节删除**，§6.2 改为「预览文档」 |
| §8.3 系统配置 | 「仅 dept_manager / admin 可见」 | 补一句「设置页本身你进得去 —— 改密码不受角色限制」 |
| §9 高频问题 | — | **新增**「我没有『上传』按钮了？」并说明原因 |
| §9 误删除 | 说软删除可恢复 | 改写为「普通员工没有删除入口，不会误删」 |
| §11 Q2 上传 413 | 2GB | **8GB**（视频）/ 50MB（文档） |
| §3 界面导览 | 5 个菜单 | 仍是 5 个（多了设置、少了什么）—— 补一句说明 |

### 6.2 管理员手册

- §1 角色矩阵：`employee` 从「看 + 上传 + 预览」改为「**只读**」；
  `dept_manager` 补上删除权
- 新增 v3.4 角色变更提示 + **「权限是数据不是代码」的警告**
- §3.1 Tab 3：45 条 → **46 条**权限码，补充 `action` 字段说明
- §7：普通员工能力描述同步
- §9 配置项：视频上限 2048MB → **8192MB**
- **新增 §14.1「改完权限后必做：抽查 SQL」** —— 给出验证 employee 只读的 SQL，
  并提醒「改了权限要让用户重新登录」

### 6.3 PDF 重新导出 —— **并且补上了缺失的构建方式**

之前的两份 PDF 是手工渲染后提交进来的，**仓库里没有留下任何构建脚本**。
这就是「手册内容改了但 PDF 没重做」能悄悄发生的原因。

新增 `scripts/build_manual_pdf.py`：

```
Markdown --pandoc--> HTML --Chrome headless--> PDF
```

脚本里记下了三个踩过的坑（都表现为**返回码 0 但不产出文件**，极易误判成成功）：

1. 必须 `--headless=new` —— 新版 Chrome 已移除旧 headless 实现，旧写法**静默什么都不做**
2. 必须给**全新的 `--user-data-dir`** —— 机器上常驻着交互式 Chrome，默认 profile 被锁时 headless 会放弃
3. 进程返回后 PDF **可能还没落盘** —— 必须轮询等文件出现，不能只看返回码

另外：**先写临时文件再原子替换**，否则「目标文件存在」不能证明这次生成成功
（旧文件在就会误判成功，PDF 其实是上一次的陈旧版本 —— 第一次跑就踩了这个坑，
脚本还一度报「OK」但文件根本没变）。

PDF 文件名随之更新为 `2026-11-05-*.pdf`（旧的两份 `2026-09-03-*.pdf` 已删除），
符合仓库「按日期命名」的约定。

---

## 七、ARCHITECTURE.md / SECURITY.md

### ARCHITECTURE.md
- 「REST API（51 端点）」→ **95**
- 「51 端点 / 36 schema」→ **95 端点 / 40 schema**
- 核心表「22 张 + 2 张分表」→ **35 张 + 2 张分表**（并按模块重新归类）
- 补一句「表结构的权威来源是迁移脚本，不是本文件」

### SECURITY.md
- 支持版本表：当前主版本 3.1.x → **3.4.x**；3.3.x 列入「安全修复」
- 补一条 **3.4.0 行为变更提示**（普通员工转只读，需重新登录）
- **已知安全限制从 6 条扩到 10 条**，新增的 4 条都是本次盘点发现的真问题：

| # | 新增的限制 |
| --- | --- |
| 7 | **密级由客户端传入** —— 服务端密级自动识别未接线，客户端可自行声明 `classification_lv` |
| 8 | **国密链路未接线** —— 声称「国密合规」前必须先接线 |
| 9 | **Java 后端无盲水印** —— `/watermarks/extract` 是 mock，溯源未闭环 |
| 10 | **WebAuthn 登录绕过 RBAC** —— 见下 |

> **第 10 条值得单独说**：`POST /auth/webauthn/login/complete` 签发 JWT 时
> **硬编码**了角色 `ROLE_EMPLOYEE` 和三条固定权限码
> （`dashboard:read` / `video:read` / `document:read`），**没有走 `PermissionService`**。
> 用 WebAuthn 登录的用户拿不到自己的真实角色权限 —— 管理员用 WebAuthn 登录会退化成普通员工。
> 该端点当前默认未对外开放，但代码路径在，已记入 SECURITY.md 与 README 的已知限制。

---

## 八、CLAUDE.md：修正一个严重过时的事实

项目 CLAUDE.md 开头写着：

> 本仓库为 **企业全格式数字资产防泄密系统** 的方案文档库，目前 **尚无源代码**，
> 仅包含一份 `doc/` 下的技术方案书（`.docx`）。

**这已经不成立了** —— 仓库里有完整的 Java 后端、Vue 前端、Python Worker、
Helm Chart、CI 配置和几千行测试。这条描述会误导每一个新来的 AI 会话
（包括我自己）：它会以为这是一个只能写文档的仓库。

重写要点：

- 目录结构换成**实际**的（并标出 Flyway 迁移的唯一权威位置）
- 补「常用命令」—— **本机 `mvn` 不在 PATH 上**，要用完整路径
- 补「权限模型（**最容易改坏的地方**）」一节，写清两层模型、白名单的理由、
  以及「别给下载端点加角色门」（会破坏方案书 5.6.1 的 L1 例外）
- 补「文档同步约定」表 —— 什么改动要同步哪份文档、怎么同步
- 把「六层纵深防御」改成**七层（含管理层）**，与方案书 §10.1 一致
- 明确「**方案书落后于代码时以代码为准**，但要在变更记录里写明差异」

---

## 九、新增 / 修改的脚本

| 脚本 | 作用 |
| --- | --- |
| `scripts/sync_openapi_permissions.py` | 扫 Controller 的 `@PreAuthorize` → 插入 openapi 的 `x-required-permission` |
| `scripts/build_manual_pdf.py` | Markdown → HTML → PDF（使用手册） |
| `scripts/check_controller_coverage.py` | **修了两个真 bug**（见下） |

### 对账脚本的两个 bug

它此前报「84 个实现 / 95 个规范 / 11 个未实现」，但**实现确实是 95 个**：

1. **漏扫 `security/webauthn/`** —— `WebAuthnController` 不在 `controller/` 目录下，
   脚本只扫了那一个目录 ⇒ 6 个端点被误报成「未实现」
2. **裸注解匹配器遇到多行 `@AuditOp` 就 break** ——

   ```java
   @AuditOp(op = AuditOpType.CREATE, resourceType = "video",
            detail = "title=#title, classification_lv=#classificationLv")
   ```

   向下找方法声明时，第二行不以 `@` 开头 ⇒ 判定「不是方法」⇒ 直接 break
   ⇒ `POST /videos` / `POST /documents` / `POST /users` 全被漏掉

   （脚本原注释里已经提到过前一个 bug —— 全限定名 `@org.springframework...PreAuthorize`
   里的嵌套括号会让 `\([^)]*\)` 提前结束。**两个 bug 叠加**，才导致 11 个误报。）

改用**逐行扫描**（记下 `@XxxMapping` → 记下 `@PreAuthorize` → 遇到方法签名才结算），
现在输出 **95 ↔ 95 / 100%**。

---

## 十、验证

| 项目 | 结果 |
| --- | --- |
| 后端测试 | **161 用例，0 失败**（6 个 error 是既有的 `ApplicationStartupTest`，需要真实 MySQL，与本次无关） |
| 前端测试 | 58 用例全绿（本次未改前端代码，仅确认未回归） |
| Worker 测试 | 68 用例：66 通过 / 2 跳过（缺 ffmpeg 与 PyPDF2）；本次未改 Worker 逻辑 |
| OpenAPI 校验 | YAML 语法 OK；74 paths / 95 ops / 40 schemas / 17 tags；**45 处 `$ref` 全部可解析**；无重复键 |
| 端点对账 | **95 ↔ 95，100% 覆盖** |
| PDF 导出 | 两份手册重新导出成功（员工 6 页 / 管理员 8 页），抽验首页与关键段落正确 |

新增脚本的幂等性：`sync_openapi_permissions.py` 对已改过的规范再跑一次，
插入数仍是 46 —— 说明它不会重复插入。

---

## 十一、留给后续的事

本次只做**文档与代码对齐**，没有改业务逻辑。以下几项是盘点时发现的
**真问题**，已写进 `SECURITY.md` 与 README 的「已知限制」，但没有修：

1. **WebAuthn 登录绕过 RBAC**（最要紧）—— 硬编码 `ROLE_EMPLOYEE` + 三条固定权限码
2. **密级由客户端传入** —— 服务端密级裁决（`ClassificationEnforcement`）未接线
3. **国密链路未接线** —— `AlgorithmRouter` / `SmJwtTokenProvider` 无调用方
4. **Java 侧盲水印缺失** —— `/watermarks/extract` 返回 mock
5. **通知 / Webhook 的 HTTP 端点是 mock**
6. **`q.watermark` 队列没有生产者**
7. **文档处理不回写后端状态** —— `watermark_status` / `preview_status` 没有自动闭环
8. **`fingerprint/` 三个 Service 零引用**（且与 `processors/` 下的实现算法不一致）

这些需要单独排期，不适合混在文档同步里改。

**上线前必做**：

- [ ] 打 `v3.4.0` 的 git tag（prod ArgoCD 按 tag 部署）
- [ ] 重新构建并推送 backend / worker / web 三个镜像
- [ ] 部署后抽查 employee 权限（SQL 见 §六 6.2）
- [ ] **通知普通员工重新登录** —— JWT 里的权限码是登录时签发的
