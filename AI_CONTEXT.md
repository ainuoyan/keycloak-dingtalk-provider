# AI_CONTEXT.md

本文档用于让 Codex、Claude Code、Copilot 等 AI 工具快速理解和审计本项目。修改代码前先读本文档，再结合 `README.md` 和相关源码确认当前实现。

创建时间：2026-04-26  
创建参考基线：`ff981e9 Refresh DingTalk sync cleanup release artifact`

## 项目定位

这是一个 Keycloak 26.6.1+ 的钉钉 Identity Provider 插件，核心目标是：

- 支持钉钉 OAuth2.0 登录。
- 只允许本企业钉钉用户进入匹配、绑定或自动创建流程。
- 支持定期或手动同步钉钉通讯录到 Keycloak。
- 支持用浏览器临时 dry-run 或正式执行同步，但必须受调试开关和密钥保护。
- 支持清理由钉钉同步任务自动创建的 Keycloak 用户。
- 支持用钉钉自定义机器人通知登录或同步真实执行中新创建的用户，以及同步真实执行中跳过创建用户的 WARN。

## 核心文件地图

| 文件 | 作用 |
|------|------|
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkIdentityProvider.java` | 钉钉 OAuth 登录、企业用户校验、登录时匹配/创建/更新用户、企业角色授予 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkIdentityProviderFactory.java` | Keycloak Provider 配置项、只读提示 URL、定时同步任务注册 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkUserSyncTask.java` | 钉钉通讯录同步主逻辑，支持 periodic/manual/dry-run、创建、绑定、更新、重新启用、禁用离职用户 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkSyncAdminResource.java` | 管理端 REST 入口，需要 `manage-users` 权限 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkSyncBrowserResource.java` | 浏览器公开入口，不需要 Bearer token，但必须开启 GET 调试开关并提供调试密钥 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkSyncCreatedUserCleanup.java` | 清理由钉钉同步自动创建的 Keycloak 用户 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkWebhookNotifier.java` | 钉钉自定义机器人通知，支持加签、登录创建通知和同步批量通知 |
| `src/main/java/com/tencent/keycloak/dingtalk/DingTalkLoginEventListenerProvider.java` | 登录/注册事件监听，给历史钉钉用户补齐企业插件角色 |
| `src/main/java/com/tencent/keycloak/dingtalk/PinyinUsername.java` | 中文姓名转拼音 username 规则 |
| `README.md` | 用户文档、配置说明、接口说明 |
| `dist/keycloak-dingtalk-provider.jar` | 仓库跟踪的可部署 JAR。代码变更后如要交付部署包，必须同步更新 |

## 高风险边界

### 企业用户校验

登录流程必须在匹配、绑定、自动创建用户前完成企业用户校验。

关键位置：

- `DingTalkIdentityProvider.doGetFederatedIdentityInternal`
- `DingTalkIdentityProvider.preprocessFederatedIdentity`
- `DingTalkIdentityProvider.isEnterpriseLoginAllowed`

默认行为：

- `requireEnterpriseUser` 默认开启。
- 优先通过企业 appKey/appSecret 反查 `unionId -> userid` 确认用户属于当前企业。
- 企业 API 不能确认时，再使用 `allowedCorpIds` 白名单兜底。
- 校验失败时拒绝登录，不应进入匹配、绑定或创建流程。

### 自动创建用户

登录自动创建由 `matchAction` 控制，默认开启。定期同步自动创建由 `periodicSyncCreateUsers` 控制，默认关闭。

创建 username 的规则集中在：

- `DingTalkUserSyncTask.resolveProvisionedUsername`
- `DingTalkIdentityProvider.resolveUsername`

当前规则：

- 优先中文姓名拼音。
- 如果邮箱前缀与姓名拼音一致，也使用邮箱前缀。
- 没有可解析 username 时不创建。
- 不再使用纯数字钉钉 userid 作为 Keycloak username。

### 禁用离职用户

禁用逻辑只在同步配置开启 `periodicSyncDisableMissingUsers=true` 后执行。

关键保护：

- 只处理当前钉钉 IdP 标记为托管的用户。
- 任一部门或子部门拉取失败时，本轮不禁用任何用户。
- `activeExternalIds` 为空时不禁用。

关键位置：

- `DingTalkUserSyncTask.disableMissingManagedUsers`
- `DingTalkUserSyncTask.findMissingManagedUsers`

### 清理同步创建用户

清理入口不是按“纯数字 username”删除。当前逻辑只删除由当前钉钉同步任务自动创建的用户。

候选条件必须同时满足：

- `dingtalk_idp_alias == 当前 IdP alias`
- `dingtalk_managed == true`
- `dingtalk_created_by_sync == true`
- 用户已绑定当前钉钉 IdP 的 federated identity

这意味着：仅被同步匹配、绑定、更新过的既有 Keycloak/AD 用户不会被删除。

关键文件：

- `DingTalkSyncCreatedUserCleanup.java`

确认口令：

- `DELETE_DINGTALK_SYNC_CREATED_USERS`

### 浏览器公开入口

浏览器公开入口位于 `/realms/{realm}/dingtalk-sync/...`，不走 Admin Bearer token，因此必须严格受以下条件保护：

- IdP 必须启用 `syncGetDebugEnabled=true`
- IdP 必须配置非空 `browserSyncDebugKey`
- 请求必须带正确 `key`
- 真实同步必须额外带 `confirm=RUN_DINGTALK_SYNC`

浏览器清理入口永远只返回 dry-run，不执行删除。

关键位置：

- `DingTalkSyncBrowserResource.validateBrowserAccess`
- `DingTalkSyncBrowserResource.sync`
- `DingTalkSyncBrowserResource.previewSyncCreatedUserCleanup`

### 钉钉机器人通知

通知由 `notificationWebhookEnabled` 和 `notificationWebhookUrl` 共同控制，`notificationWebhookSecret` 可选用于钉钉机器人加签。

发送范围：

- 登录链路新创建 Keycloak 用户。
- 同步真实执行中新创建 Keycloak 用户。
- 同步真实执行中，因生成 username 为空或 username 已存在但无可信匹配而跳过创建的 WARN。

关键边界：

- dry-run 不发送通知。
- 发送失败不能中断登录或同步，只能记录 WARN。
- 通知内容必须脱敏，不输出手机号、邮箱、token、secret、Webhook access_token 或加签密钥。
- 同步通知必须批量汇总，避免每个用户一条消息刷屏。

关键文件：

- `DingTalkWebhookNotifier.java`
- `DingTalkIdentityProvider.importNewUser`
- `DingTalkUserSyncTask.createUser`

## REST 入口清单

### 管理端入口

这些入口位于 `/admin/realms/{realm}/dingtalk-sync/...`，都需要当前调用者具备 `manage-users` 权限。

| 方法和路径 | 行为 | 额外条件 |
|-----------|------|----------|
| `POST /run?alias={alias}` | 真实同步 | 不需要 GET 调试开关 |
| `GET /run?alias={alias}&confirm=RUN_DINGTALK_SYNC` | 真实同步 | 需要 GET 调试开关 |
| `GET /debug?alias={alias}` | dry-run 同步预览 | 需要 GET 调试开关 |
| `GET /cleanup-sync-created-users?alias={alias}` | dry-run 清理预览 | 需要 GET 调试开关 |
| `POST /cleanup-sync-created-users?alias={alias}&confirm=DELETE_DINGTALK_SYNC_CREATED_USERS` | 删除同步创建用户 | 不需要 GET 调试开关 |

### 浏览器公开入口

这些入口位于 `/realms/{realm}/dingtalk-sync/...`，不需要 Admin Bearer token，但必须满足浏览器公开入口保护条件。

| 方法和路径 | 行为 |
|-----------|------|
| `GET /debug?alias={alias}&key={debugKey}` | dry-run 同步预览 |
| `GET /run?alias={alias}&key={debugKey}&confirm=RUN_DINGTALK_SYNC` | 真实同步 |
| `GET /cleanup-sync-created-users?alias={alias}&key={debugKey}` | dry-run 清理预览 |
| `POST /cleanup-sync-created-users?alias={alias}&key={debugKey}` | dry-run 清理预览，不删除 |

后台 Identity Provider 配置页会显示以下只读 URL 提示项：

- 管理 API 同步地址
- 浏览器同步执行地址
- 浏览器同步预览地址
- 浏览器清理同步创建用户预览地址
- 管理 API 清理同步创建用户执行地址

## 同步结果语义

`DingTalkUserSyncTask.SyncResult` 字段：

- `listed`: 从钉钉通讯录列出的去重用户数。
- `matched`: 找到或预计找到 Keycloak 用户的数量。
- `created`: 创建或 dry-run 预计创建的用户数。
- `linked`: 绑定或 dry-run 预计绑定 federated identity 的数量。
- `updated`: 更新或 dry-run 预计更新的用户数。
- `reenabled`: 重新启用或 dry-run 预计重新启用的用户数。
- `disabled`: 禁用或 dry-run 预计禁用的用户数。
- `skipped`: 本轮同步是否跳过。
- `reason`: 跳过原因。

dry-run 必须尽量反映真实执行会发生的变化，但不能写入用户、绑定、lastSync 或 IdP 配置。

## 日志与脱敏

逐用户明细日志由 `periodicSyncDetailedLog` 控制，默认关闭。

关闭时：

- 定时同步、管理端手动同步、管理端 dry-run、浏览器 dry-run、浏览器真实同步都不应输出 `DingTalk sync detail ...` 的逐用户明细。

开启时：

- 可以输出匹配来源、Keycloak username、字段名、跳过原因。
- 不应输出手机号、邮箱、token、secret 明文。

脱敏工具：

- `DingTalkIdentityProvider.sanitizeForLog`
- `DingTalkIdentityProvider.sanitizeUriForLog`
- `DingTalkIdentityProvider.mask`

注意：同步完成的汇总日志 `DingTalk sync finished...` 不受明细日志开关控制，属于正常摘要日志。

钉钉机器人通知不受 `periodicSyncDetailedLog` 控制。它只受 `notificationWebhookEnabled`、`notificationWebhookUrl` 和 dry-run 状态控制。

## 构建与验证

常用命令：

```bash
mvn test
mvn clean package
git diff --check
shasum -a 256 dist/keycloak-dingtalk-provider.jar target/keycloak-dingtalk-provider.jar
jar tf dist/keycloak-dingtalk-provider.jar | rg "DingTalk(SyncCreatedUserCleanup|WebhookNotifier|NumericUserCleanup)"
```

期望：

- `mvn test` 通过。
- `mvn clean package` 通过。
- `git diff --check` 无输出。
- 如果更新了可部署包，`dist/keycloak-dingtalk-provider.jar` 应与 `target/keycloak-dingtalk-provider.jar` SHA 一致。
- JAR 中应有 `DingTalkSyncCreatedUserCleanup` 和 `DingTalkWebhookNotifier`，不应有旧的 `DingTalkNumericUserCleanup`。

## 发布包注意事项

仓库跟踪了 `dist/keycloak-dingtalk-provider.jar`。如果代码改动会影响运行行为，并且用户可能直接部署 `dist`，必须：

1. 执行 `mvn clean package`。
2. 将 `target/keycloak-dingtalk-provider.jar` 复制到 `dist/keycloak-dingtalk-provider.jar`。
3. 校验两个 JAR 的 SHA256 一致。
4. 将 `dist/keycloak-dingtalk-provider.jar` 一并提交。

## AI 审计检查清单

审计时优先检查这些点：

- 非本企业钉钉用户是否可能进入匹配、绑定或创建流程。
- 浏览器公开入口是否能绕过 `syncGetDebugEnabled` 或 `browserSyncDebugKey`。
- 浏览器公开入口是否可能执行删除。
- 真实同步是否需要确认参数，尤其是 GET 真实同步。
- 清理同步创建用户是否可能删除既有 AD/Keycloak 用户。
- dry-run 是否会写入用户、federated identity、lastSync 或 IdP 配置。
- dry-run 统计是否明显低报真实执行会发生的创建、绑定、更新、启用、禁用。
- 任一部门拉取失败时，离职禁用是否会被跳过。
- 日志是否泄露手机号、邮箱、token、secret、OAuth code、state。
- 钉钉机器人通知是否只在真实执行时发送，是否脱敏，发送失败是否不会中断主流程。
- README 中的 URL、确认口令、权限说明是否和代码一致。
- `dist` JAR 是否和当前源码构建结果一致。

## 不要轻易做的改动

- 不要重新引入按纯数字 username 删除用户的逻辑。
- 不要让浏览器清理入口执行真实删除。
- 不要让管理端 POST 同步或 POST 清理依赖 GET 调试开关。
- 不要把 `dingtalk_managed=true` 直接等同于“可删除用户”。
- 不要在 dry-run 中调用任何会改变 Keycloak 状态的方法。
- 不要在日志里输出完整 access token、client secret、手机号、邮箱、userid、unionid、openid。
- 不要把 Webhook access_token 或加签密钥写入日志或通知正文。
