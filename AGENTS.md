# AGENTS.md

修改或审计本仓库前，请先阅读 `AI_CONTEXT.md`，再结合 `README.md` 和源码确认当前实现。`AI_CONTEXT.md` 是给 AI 工具使用的项目上下文入口，包含安全边界、REST 入口、同步语义、清理逻辑和验证命令。

基本要求：

- 优先关注可维护性、边界条件和回归风险。
- 修改运行逻辑后运行 `mvn test`、`mvn clean package` 和 `git diff --check`。
- 如果代码改动会影响部署行为，并且需要交付可部署包，必须同步更新 `dist/keycloak-dingtalk-provider.jar`，并校验它与 `target/keycloak-dingtalk-provider.jar` 的 SHA256 一致。
- 不要绕过或弱化 `AI_CONTEXT.md` 中列出的企业用户校验、浏览器公开入口保护、dry-run 不落库、清理同步创建用户等安全边界。
