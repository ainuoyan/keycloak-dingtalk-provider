# CLAUDE.md

请使用中文沟通。

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
- 离职禁用在部门拉取失败时是否跳过。
- 日志是否避免泄露手机号、邮箱、token、secret 和 OAuth code/state。
- 钉钉机器人通知是否只在真实执行时发送，是否脱敏，发送失败是否不会中断登录或同步。
- 钉钉机器人测试发信接口是否仅管理端 POST 可用，并且要求 `manage-users` 权限。
- `README.md`、`dist/keycloak-dingtalk-provider.jar` 是否和当前源码一致。

验证命令见 `AI_CONTEXT.md` 的“构建与验证”部分。
