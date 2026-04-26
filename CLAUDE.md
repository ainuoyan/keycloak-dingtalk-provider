# CLAUDE.md

这是 Keycloak 钉钉登录与通讯录同步 Provider。开始审计或修改前，请先阅读：

1. `AI_CONTEXT.md`
2. `README.md`
3. 与任务相关的源码文件

重点审计方向：

- 企业用户校验是否发生在匹配、绑定、自动创建前。
- 浏览器公开入口是否必须开启 GET 调试开关并校验调试密钥。
- GET 真实同步是否必须带 `confirm=RUN_DINGTALK_SYNC`。
- 浏览器清理入口是否始终只是 dry-run。
- 清理逻辑是否只删除 `dingtalk_created_by_sync=true` 的同步创建用户。
- dry-run 是否不会写入用户、绑定、lastSync 或 IdP 配置。
- 离职禁用在部门拉取失败或本轮没有有效钉钉身份时是否跳过；外部用户缺失扫描是否发生在本轮同步写入之前，是否在 User Storage 开启 `removeInvalidUsersEnabled` 时跳过外部全量扫描，是否排除 AD 机器账号和 service account，避免 Keycloak LDAP federated validation / invalid-user 删除与同步写入互相锁住；返聘重新启用是否只处理本插件按 `missing_from_dingtalk` 禁用的用户；禁用结果是否发送 Webhook 且不泄露手机号、邮箱或 LDAP DN。
- 日志是否避免泄露手机号、邮箱、token、secret 和 OAuth code/state。
- 浏览器公开入口失败时是否避免向调用方返回原始异常 message。
- 钉钉机器人通知是否只在真实执行事务提交后发送成功类通知，创建成功通知是否会在提交后重新按 username 确认用户可检索，事务回滚或不可检索时是否改发告警；创建/跳过创建/同步失败告警是否脱敏且正文标签中文化，发送失败是否不会中断登录或同步。
- 同步新用户 `username`、邮箱、`firstName`、`phoneNumber`、`nickname` 和钉钉 UserID 是否通过 `UserProfileProvider.create(USER_API, rawAttributes).create()` 在创建事务内一次性传入，而不是先 `addUser()` 再补字段；中文昵称是否完整写入 `firstName` 和 `nickname` 且不拆分 `lastName`（如 `丁杰 -> firstName=丁杰, nickname=丁杰`），避免 LDAP FullName/CN mapper 生成带空格的 `cn`；同步创建是否只创建账号，不写密码、不在创建事务内强制启用、不在创建后补写托管标记或用户名锁定标记；创建后是否通过 `UserModel.setEmailVerified(true)` 开启 Keycloak 电子邮箱验证标记，而不是把 `emailVerified` 当成普通 UserProfile raw attribute；创建后初始化密码是否只在事务提交后独立事务执行，是否生成强随机临时密码、添加 `UPDATE_PASSWORD`、再启用用户，且临时密码不进入日志、Webhook 或同步响应；初始化提交成功后是否再用第二个独立事务通过 `setSingleAttribute` 拆分中文 `firstName` / `lastName` 用户属性，且不调用 `setFirstName` / `setLastName` 触发 FullName/CN mapper；创建通知是否展示钉钉昵称并提示人工校验且不泄露个人邮箱明文。
- 钉钉机器人管理端测试发信接口是否要求 `manage-users` 权限。
- 钉钉机器人浏览器 GET 测试发信接口是否必须受 `syncGetDebugEnabled` 和 `browserSyncDebugKey` 保护。
- `README.md` 的项目结构、`AI_CONTEXT.md` 的文件地图、`dist/keycloak-dingtalk-provider.jar` 是否和当前源码一致。

验证命令见 `AI_CONTEXT.md` 的“构建与验证”部分。
