# Keycloak 钉钉登录集成 Provider

为 Keycloak 提供钉钉 OAuth2.0 登录集成，支持独立 JAR 热插拔部署，无需重新编译 Keycloak Docker 镜像。

## 项目来源与致谢

本项目最初基于 [shouqianceshi/keycloak-dingtalk-provider](https://cnb.cool/shouqianceshi/keycloak-dingtalk-provider) 继续改进和维护，在原有钉钉登录 Provider 的基础上，结合实际 Keycloak、AD 域和钉钉通讯录同步场景做了功能扩展、问题修复和文档补充。

感谢原项目作者的开源工作。本仓库保留对原始项目的明确引用，也希望后续改进继续遵循开源协作精神，方便其他有类似 Keycloak + 钉钉集成需求的人复用、审查和继续完善。

## 功能特性

- 支持钉钉 OAuth2.0 标准登录流程
- 独立 JAR 包部署，支持热插拔
- 自动同步用户信息（昵称、手机号、邮箱）
- 支持企业内部应用 OAuth2.0 登录
- 可选按企业 ID 授予 `ent-member:{企业ID}` 和 `ent-plugin-enabled:{企业ID}` 角色
- 支持定时、手动和浏览器调试方式同步钉钉通讯录
- 支持清理由当前钉钉同步任务自动创建的 Keycloak 用户
- 支持钉钉机器人通知新建用户、跳过创建 WARN 和测试发信
- 兼容 Keycloak 26.6.1+

## 技术栈

| 组件 | 版本 |
|------|------|
| Keycloak | 26.6.1+ |
| Java | 17+ |
| Maven | 3.6+ |
| FastJSON2 | 2.0.52 |
| Commons Lang3 | Keycloak 运行时提供 |

## 项目结构

```
keycloak-dingtalk-provider/
├── src/main/java/com/tencent/keycloak/dingtalk/
│   ├── DingTalkIdentityProvider.java                   # 钉钉 OAuth 登录、企业用户校验、登录链路匹配/创建/更新
│   ├── DingTalkIdentityProviderFactory.java            # Identity Provider 配置项、固定 URL 参考字段、定时任务注册
│   ├── DingTalkUserSyncTask.java                       # 钉钉通讯录同步主逻辑，支持 periodic/manual/dry-run
│   ├── DingTalkSyncAdminResource.java                  # 管理端同步、清理、Webhook 测试 REST 入口
│   ├── DingTalkSyncAdminResourceProvider.java          # 管理端 REST Provider
│   ├── DingTalkSyncAdminResourceProviderFactory.java   # 管理端 REST Provider Factory
│   ├── DingTalkSyncBrowserResource.java                # 浏览器公开地址页面和调试入口，执行型入口受 GET 调试开关和密钥保护
│   ├── DingTalkSyncBrowserResourceProvider.java        # 浏览器公开 REST Provider
│   ├── DingTalkSyncBrowserResourceProviderFactory.java # 浏览器公开 REST Provider Factory
│   ├── DingTalkSyncCreatedUserCleanup.java             # 清理由钉钉同步自动创建的用户
│   ├── DingTalkWebhookNotifier.java                    # 钉钉机器人通知与加签
│   ├── DingTalkLoginEventListenerProvider.java         # 登录/注册事件监听器
│   ├── DingTalkLoginEventListenerProviderFactory.java  # 登录/注册事件监听器 Factory
│   ├── PinyinUsername.java                             # 中文姓名转拼音 username 规则
│   ├── UserDto.java                                    # 用户信息 DTO
│   └── UserTokenDto.java                               # Token 响应 DTO
├── src/test/java/com/tencent/keycloak/dingtalk/
│   ├── DingTalkIdentityProviderTest.java
│   └── DingTalkLoginEventListenerProviderTest.java
├── src/main/resources/META-INF/services/
│   ├── org.keycloak.broker.social.SocialIdentityProviderFactory
│   ├── org.keycloak.events.EventListenerProviderFactory
│   ├── org.keycloak.services.resource.RealmResourceProviderFactory
│   └── org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory
├── AI_CONTEXT.md                              # AI 工具项目上下文和审计清单
├── AGENTS.md                                  # Codex/Agent 工作入口说明
├── CLAUDE.md                                  # Claude Code 审计入口说明
├── dist/keycloak-dingtalk-provider.jar        # 已编译 JAR，可直接部署
├── pom.xml                                    # Maven 配置
└── README.md                                  # 本文档
```

---

## 快速开始

### 前置要求

- Keycloak 26.6.1+ 正在运行（Docker 或本地安装）
- Java 17+
- Maven 3.6+
- 钉钉开放平台账号

### Step 1: 获取 JAR

仓库已提交一份可直接部署的 JAR：

```text
dist/keycloak-dingtalk-provider.jar
```

也可以自行编译：

```bash
cd keycloak-dingtalk-provider
mvn clean package -DskipTests
```

自行编译后，JAR 文件位于：`target/keycloak-dingtalk-provider.jar`。JAR 包包含钉钉集成所需的第三方依赖；Keycloak 运行时依赖不打入包内。

### Step 2: 部署到 Keycloak

#### Docker 容器部署

```bash
# 复制 JAR 到容器
docker cp dist/keycloak-dingtalk-provider.jar keycloak:/opt/keycloak/providers/

# 重启容器
docker restart keycloak
```

#### 本地安装部署

```bash
# 复制 JAR 到 providers 目录
cp dist/keycloak-dingtalk-provider.jar /path/to/keycloak/providers/

# 重新构建并重启
/path/to/keycloak/bin/kc.sh build
/path/to/keycloak/bin/kc.sh start
```

#### Docker Compose 挂载

```yaml
version: '3'
services:
  keycloak:
    image: keycloak/keycloak:26.6.1
    volumes:
      - ./dist/keycloak-dingtalk-provider.jar:/opt/keycloak/providers/keycloak-dingtalk-provider.jar:ro
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    command: start-dev
    ports:
      - "8080:8080"
```

### Step 3: 配置 Keycloak

1. 登录 Keycloak 管理控制台：`https://your-keycloak-domain/admin`
2. 选择 Realm → **Identity Providers** → **Add provider**
3. 在下拉列表中选择「**钉钉**」
4. 填写配置：

| 字段 | 说明 |
|------|------|
| Client ID | 钉钉 AppKey |
| Client Secret | 钉钉 AppSecret |
| 登录后是否更新用户信息 | 默认开启，已匹配用户每次登录后写入邮箱、昵称、手机号等信息；关闭后只用于匹配登录，不写入这些用户信息 |
| 手机号为空时补齐手机号 | 默认开启，关闭“登录后是否更新用户信息”时仍会在 `phoneNumber` 为空时从钉钉补齐手机号，不覆盖已有手机号 |
| 企业ID/角色后缀 | 开启“启用企业角色授予”时作为 `ent-member:{企业ID}` 和 `ent-plugin-enabled:{企业ID}` 的角色后缀；为空时使用钉钉返回的 corpId |
| 仅允许本企业钉钉用户登录 | 默认开启；优先用企业 appKey/appSecret 反查 `unionId -> userid` 来确认是否本企业用户，确认失败时再校验允许列表。非本企业钉钉账号不会进入匹配、绑定或自动创建流程 |
| 允许登录的企业 CorpId | 逗号分隔，例如 `dingxxxxxxxx`。作为企业接口无法反查 `unionId` 时的兜底白名单；建议填写本企业 corpId |
| 启用企业角色授予 | 默认关闭；开启后登录成功时尝试授予 `ent-member:{企业ID}` 和 `ent-plugin-enabled:{企业ID}` |
| 匹配失败是否允许登录 | 默认开启，匹配失败时创建新用户 |
| 匹配规则配置 | 默认 `phone,email`，支持 `phone`、`email`、`unionId`、`openId`、`username`；按配置顺序匹配 |
| 启用定期同步钉钉通讯录 | 默认关闭，开启后 Keycloak 定期读取钉钉通讯录，按已绑定身份、手机号、邮箱等规则匹配已有用户并绑定钉钉身份 |
| 定期同步周期秒数 | 默认 `3600`，每个钉钉 Identity Provider 的最小同步间隔 |
| 定期同步部门ID | 默认 `1`，多个根部门 ID 用逗号分隔 |
| 同步子部门用户 | 默认开启，会递归展开“定期同步部门ID”下的所有子部门，再同步每个部门的用户 |
| 定期同步字段 | 默认 `phone`，支持 `phone`、`email`；`nickname`、`dingtalk_userid` 等钉钉身份属性会始终记录 |
| 定期同步覆盖已有字段 | 默认关闭，关闭时只补齐空字段；开启后用钉钉通讯录覆盖已存在的同步字段 |
| 定期同步自动创建用户 | 默认关闭，开启后钉钉通讯录用户匹配不到时会自动创建 Keycloak 用户 |
| 定期同步禁用离职用户 | 默认关闭，开启后只禁用之前由当前钉钉同步任务标记为托管、但本次通讯录不存在的 Keycloak 用户 |
| 定期同步重新启用返聘用户 | 默认开启，离职用户重新出现在钉钉通讯录时自动启用 |
| 记录同步明细日志 | 默认关闭；开启后同步会记录每个钉钉用户的匹配来源、Keycloak 用户名、更新字段和跳过原因。关闭时手动同步和 dry-run 也不会输出逐用户明细 |
| 启用钉钉机器人通知 | 默认关闭；开启并配置 Webhook 后，登录链路新创建用户、同步真实执行中新创建用户，以及同步真实执行中因 `username` 为空或 `username` 冲突跳过创建的 WARN 会发送到钉钉机器人 |
| 钉钉机器人 Webhook 地址 | 钉钉自定义机器人 Webhook 地址，通常包含 `access_token`，按敏感字段保存；为空时不发送通知 |
| 钉钉机器人加签密钥 | 如果钉钉机器人启用了加签安全设置，填写 `SEC...` 密钥；未启用加签时留空 |
| 启用 GET 同步调试入口 | 默认关闭；开启后才允许使用浏览器公开 GET 预览、浏览器公开 GET 真实同步、浏览器公开 GET Webhook 测试、管理端 GET dry-run 和管理端 GET 真实同步。调试完成后请关闭；管理端 POST 同步不受影响。接口地址可通过后台下方的“接口地址页面入口”查看 |
| 浏览器同步调试密钥 | 默认空，配合“启用 GET 同步调试入口”使用；两者同时有效时才允许纯浏览器 GET 预览、正式同步和 Webhook 测试 |

> 后台会显示一个固定的“接口地址页面入口”：`/realms/master/dingtalk-sync/endpoints`。这个路径直接内置在插件 JAR 的 `URL_TYPE` 配置项默认值里，不写入钉钉 IdP config；浏览器会按当前站点域名解析该相对路径。打开页面后可以在页面内填写/切换 Realm、选择钉钉 IdP、临时填写本次浏览器同步调试密钥。页面会把地址分为两组：浏览器直接 GET 地址显示“访问”按钮，管理端 POST API 地址显示“复制地址”按钮。页面本身只展示地址，不会自动执行同步、清理或发信；真实接口地址始终由页面选择的 Realm 对应 REST Provider 路由决定。

> Keycloak 内置的“重定向 URI”是管理台前端专门硬编码的复制组件，自定义 Provider 配置项不能复用该复制组件。插件因此在配置页用 `URL_TYPE` 放一个固定相对路径入口，访问按钮和复制按钮放在插件自己的接口地址页面里。该入口路径只用于后台展示，插件执行同步时不会读取它。

> `/admin/realms/...` 属于 Keycloak 管理 REST API，浏览器地址栏直接 GET 通常会返回 `401`，即使你已经打开了管理台页面。地址栏直接预览或正式同步请先短期开启“启用 GET 同步调试入口”，再使用 `/realms/{realm}/dingtalk-sync/...` 的浏览器地址，并填写实际的“浏览器同步调试密钥”。调试完成后关闭该开关，GET 入口会返回 `403 get_debug_disabled`。

> `登录后是否更新用户信息` 只控制 Provider 是否写回用户属性。它不会关闭 Keycloak 首次第三方登录流程里的 **Review Profile / Update Profile** 页面；如果 AD 用户已经同步完成，只希望钉钉按用户名或邮箱绑定已有用户，请复制 `first broker login` flow，禁用或删除其中的 `Review Profile` 执行项，然后在钉钉 Identity Provider 的 **First Login Flow** 里选择这个副本。

AD 已同步用户的推荐配置：

- `登录后是否更新用户信息`：关闭
- `手机号为空时补齐手机号`：开启
- `仅允许本企业钉钉用户登录`：开启
- `允许登录的企业 CorpId`：填写本企业 corpId，避免外部钉钉账号误入登录创建流程
- `匹配失败是否允许登录`：关闭
- `匹配规则配置`：`phone,email`
- `启用定期同步钉钉通讯录`：按需开启
- `定期同步字段`：`phone`
- `定期同步覆盖已有字段`：关闭；如果要让 Keycloak 字段始终跟随钉钉最新值，再开启覆盖
- `定期同步自动创建用户`：如果以钉钉为用户主源，开启
- `定期同步禁用离职用户`：确认钉钉通讯录部门覆盖完整后再开启
- `同步子部门用户`：开启
- `定期同步重新启用返聘用户`：开启
- First Login Flow：使用禁用了 `Review Profile` 的自定义 flow

定期同步开启后，任务会按下面顺序处理每个钉钉通讯录用户：

1. 把“定期同步部门ID”视为根部门；开启“同步子部门用户”时，递归获取所有下级部门 ID。
2. 逐个部门拉取用户，并按钉钉外部 ID 去重，避免同一用户在多个部门里重复处理。
3. 先查是否已经绑定当前钉钉 Identity Provider。
4. 未绑定时，按“匹配规则配置”的顺序匹配已有 Keycloak 用户，例如 `phone,email` 会先查手机号，再查邮箱；同一手机号、邮箱或候选用户名匹配到多个用户时会拒绝绑定并记录 WARN，避免错绑。
5. 匹配成功后补充钉钉 federated identity 绑定，并标记为当前钉钉 IDP 托管用户。
6. 匹配失败且开启“定期同步自动创建用户”时，按姓名拼音规则创建 Keycloak 用户，并绑定钉钉身份。
7. 按“定期同步字段”和“定期同步覆盖已有字段”更新 `phoneNumber`、`email`；同时始终记录 `nickname`、`dingtalk_userid`、`dingtalk_last_sync_at` 等钉钉身份和排障属性。
8. 开启“定期同步禁用离职用户”时，仅禁用此前由当前钉钉同步任务标记为托管、但本次完整通讯录中不存在的用户。

默认只同步 `phoneNumber` 且不覆盖已有值，适合 AD 没有手机号、Keycloak 只需要从钉钉补齐手机号的场景。

### 日志脱敏说明（重点）

为避免排障日志泄露 OAuth 敏感参数，插件在输出登录跳转日志时会对 `redirect_uri` 做脱敏处理：
- 保留：协议、域名、端口、路径（便于定位是哪个 realm / broker 回调路径）
- 隐藏：查询参数（例如 `code`、`state`），统一显示为 `?***`

示例：

```text
原始 redirect_uri:
https://sso.example.com/auth/realms/demo/broker/dingtalk/endpoint?code=abc&state=xyz

日志输出:
https://sso.example.com/auth/realms/demo/broker/dingtalk/endpoint?***
```

建议线上环境仅在排障窗口短期开启 DEBUG，并结合日志平台字段脱敏策略一起使用。

如果要把钉钉作为 Keycloak 第一阶段用户主源，推荐：

- `仅允许本企业钉钉用户登录`：开启
- `允许登录的企业 CorpId`：填写本企业 corpId
- `匹配失败是否允许登录`：开启；登录时只允许本企业用户自动创建
- `启用定期同步钉钉通讯录`：开启
- `定期同步自动创建用户`：开启
- `定期同步禁用离职用户`：开启前先确认 `定期同步部门ID` 和“同步子部门用户”已覆盖所有应同步部门
- `定期同步字段`：`phone,email`
- `定期同步覆盖已有字段`：开启则 Keycloak 字段跟随钉钉最新值；关闭则只补空字段
- `记录同步明细日志`：测试期可开启，上线稳定后建议关闭，避免日志量过大

离职禁用有保护条件：如果任一部门拉取失败，本轮不会执行禁用，避免接口故障导致误禁用。

实现上对应钉钉官方的“获取企业下所有员工信息”流程：先通过“获取子部门ID列表”逐级遍历部门树，再对每个部门调用“获取部门用户详情”。钉钉的“获取用户通讯录个人信息”是登录态用户 token 的个人信息接口，本插件在钉钉登录回调中使用它补齐当前登录用户信息；定时全量同步使用企业内部应用 access_token 和通讯录管理接口。

### 手动触发钉钉同步

插件提供了一个管理端手动同步入口，方便测试和排障。POST 会执行真实同步，并且会先校验当前调用者是否具备当前 realm 的 `manage-users` 权限。GET 真实同步默认关闭；只有短期开启“启用 GET 同步调试入口”后才可用，并且还要求显式追加 `confirm=RUN_DINGTALK_SYNC`，避免浏览器误点或 CSRF 式误触发。

如需页面化查看浏览器 GET 地址和管理端 POST API 地址，可以访问插件接口地址页面：

```text
https://your-keycloak-domain/realms/master/dingtalk-sync/endpoints?realm={realm}&alias={idpAlias}
```

后台入口默认使用 `master` 作为固定页面入口；页面里的 `realm` 字段才决定最终生成哪一个 Realm 的接口地址。提交表单时如果填写的 Realm 和当前路径不同，服务端会跳转到对应的 `/realms/<realm>/dingtalk-sync/endpoints` 页面。`alias` 可以省略，当前 Realm 只有一个启用的钉钉 IdP 时页面会自动选中，否则需要在页面里选择或在 URL 中传入。

如果在 URL 中追加本次调试密钥，页面会为浏览器 GET 入口显示“访问”按钮；POST API 入口仍显示“复制地址”按钮：

```text
https://your-keycloak-domain/realms/master/dingtalk-sync/endpoints?realm={realm}&alias={idpAlias}&key={浏览器同步调试密钥}
```

该页面不会从服务端读取密钥，也不会自动执行同步、清理或发信。密钥只用于本次页面生成浏览器 GET 地址，因此同样可能进入浏览器历史或截图；调试完成后请关闭“启用 GET 同步调试入口”并清空密钥。

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-access-token>" \
  "https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/run?alias={idpAlias}"
```

这个路径属于 Keycloak 管理 REST API，普通浏览器地址栏不会自动带上 `Authorization: Bearer ...`，直接打开通常会返回 `401`。如需用 GET 触发真实同步，需要同时满足：已开启“启用 GET 同步调试入口”、调用方能设置 Bearer token、具备 `manage-users` 权限、带上确认参数：

```text
https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/run?alias={idpAlias}&confirm=RUN_DINGTALK_SYNC
```

`alias` 是钉钉 Identity Provider 的别名；如果不传 `alias`，会同步当前 realm 下所有启用的钉钉 Identity Provider。

如果使用脚本或 API 客户端，也可以调用管理端 dry-run 预览入口。它同样需要先开启“启用 GET 同步调试入口”，并提供 `Authorization: Bearer <admin-access-token>` 和当前 realm 的 `manage-users` 权限：

```text
https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/debug?alias={idpAlias}
```

如果需要不带 Admin Bearer token、直接在浏览器地址栏访问公开预览入口，请先在钉钉 Identity Provider 配置里开启“启用 GET 同步调试入口”，并填写“浏览器同步调试密钥”，然后访问：

```text
https://your-keycloak-domain/realms/{realm}/dingtalk-sync/debug?alias={idpAlias}&key={浏览器同步调试密钥}
```

如果需要在浏览器地址栏正式执行同步，请使用浏览器执行入口。它会真实创建、绑定、更新、启用或禁用 Keycloak 用户，并写入 lastSync，因此额外要求确认参数：

```text
https://your-keycloak-domain/realms/{realm}/dingtalk-sync/run?alias={idpAlias}&key={浏览器同步调试密钥}&confirm=RUN_DINGTALK_SYNC
```

两个预览入口都会调用钉钉接口并返回 `dryRun=true` 的统计，但不会创建、绑定、更新、禁用 Keycloak 用户，也不会写入 lastSync。浏览器执行入口返回 `dryRun=false` 并执行真实同步。公开浏览器入口要求开关、`alias` 和密钥都正确；密钥为空或开关关闭时入口禁用。调试密钥会出现在浏览器历史、反向代理访问日志和截图里，建议只在测试期临时启用，调试完成后关闭开关并清空密钥。

如需清理由当前钉钉 IDP 同步创建到 Keycloak 的用户，可以先使用受同一 GET 调试开关和调试密钥保护的浏览器入口预览名单。它只会匹配同时满足以下条件的用户：当前钉钉 IDP 托管、已绑定当前钉钉 IDP，并且带有 `dingtalk_created_by_sync=true` 标记。仅被钉钉同步匹配、绑定或更新过的既有 Keycloak/AD 用户不会被纳入删除名单。

先 dry-run 查看名单：

```text
https://your-keycloak-domain/realms/{realm}/dingtalk-sync/cleanup-sync-created-users?alias={idpAlias}&key={浏览器同步调试密钥}
```

浏览器入口永远只做 dry-run，不会删除用户。管理端也可以用 GET 做 dry-run 预览；该 GET 预览同样要求调用者具备 `manage-users` 权限，并且需要先开启“启用 GET 同步调试入口”：

```text
https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/cleanup-sync-created-users?alias={idpAlias}
```

确认名单无误后，使用管理端 POST 执行删除；POST 不依赖 GET 调试开关，但调用者必须具备 `manage-users` 权限，并且必须带确认口令：

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-access-token>" \
  "https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/cleanup-sync-created-users?alias={idpAlias}&confirm=DELETE_DINGTALK_SYNC_CREATED_USERS"
```

浏览器 POST 地址同样受“启用 GET 同步调试入口”和调试密钥保护，并且只会返回 dry-run 结果，不会执行删除：

```bash
curl -X POST \
  "https://your-keycloak-domain/realms/{realm}/dingtalk-sync/cleanup-sync-created-users?alias={idpAlias}&key={浏览器同步调试密钥}&confirm=DELETE_DINGTALK_SYNC_CREATED_USERS"
```

删除后用管理端 POST 手动同步或等待定时同步，会按姓名拼音规则重新创建用户，或按手机号、邮箱等规则绑定已有用户。浏览器预览地址只做 dry-run，不会重新创建用户。

接口返回本次同步统计，例如：

```json
{
  "alias": "dingtalk",
  "listed": 419,
  "matched": 419,
  "created": 0,
  "linked": 12,
  "updated": 37,
  "reenabled": 0,
  "disabled": 0,
  "skipped": false,
  "reason": ""
}
```

只有开启“记录同步明细日志”后，手动同步、dry-run 和定时同步才会输出逐用户明细。明细会记录每个用户的匹配来源，例如 `linked-identity`、`phone:phoneNumber`、`email`、`created`，以及创建、绑定、更新字段、重新启用、禁用离职用户等动作。日志只记录字段名、Keycloak 用户名、脱敏后的钉钉标识和手机号/邮箱是否存在，不记录手机号、邮箱、token、secret 的明文值。

### 钉钉机器人通知

如需把关键自动创建结果发到钉钉群，可以在钉钉 Identity Provider 配置中开启“启用钉钉机器人通知”，并填写“钉钉机器人 Webhook 地址”。如果机器人安全设置启用了“加签”，同时填写“钉钉机器人加签密钥”。

通知范围：

- 登录链路首次创建 Keycloak 用户时，立即发送一条通知。
- 同步真实执行时，本轮新创建的用户会汇总发送。
- 同步真实执行时，因为生成的 `username` 为空，或生成的 `username` 已存在但没有通过已绑定身份、手机号、邮箱等可信规则匹配到该用户而跳过创建，会汇总发送 WARN 通知。

不会发送通知的场景：

- dry-run 预览，包括管理端 dry-run 和浏览器预览。
- 定时同步或手动同步没有创建用户、也没有跳过创建 WARN。
- Webhook 未启用或地址为空。

通知内容只包含 realm、IdP alias、Keycloak username、脱敏后的钉钉标识，以及手机号/邮箱是否存在，不包含手机号、邮箱、access token、client secret、Webhook token 或加签密钥明文。Webhook 发送失败不会中断登录或同步流程，只会在 Keycloak 日志里记录 WARN。

配置完成后，可以用管理端测试接口发送一条测试消息。该接口是 `POST`，需要 `Authorization: Bearer <admin-access-token>` 和当前 realm 的 `manage-users` 权限；它不受“启用 GET 同步调试入口”影响。

```bash
curl -X POST \
  -H "Authorization: Bearer <admin-access-token>" \
  "https://your-keycloak-domain/admin/realms/{realm}/dingtalk-sync/test-webhook?alias={idpAlias}"
```

如果只是临时在浏览器地址栏测试发信，可以先开启“启用 GET 同步调试入口”并配置“浏览器同步调试密钥”，然后访问：

```text
https://your-keycloak-domain/realms/{realm}/dingtalk-sync/test-webhook?alias={idpAlias}&key={浏览器同步调试密钥}
```

这个浏览器 GET 测试入口会真实发送一条钉钉机器人消息。调试完成后请关闭“启用 GET 同步调试入口”并清空调试密钥。

成功时返回：

```json
{
  "alias": "dingtalk",
  "success": true,
  "error": "",
  "response": "{\"errcode\":0,\"errmsg\":\"ok\"}"
}
```

如果钉钉机器人因为关键词、安全设置或签名问题拒绝消息，接口会返回 `success=false` 和钉钉返回的错误摘要。若机器人开启“关键词”安全设置，请确保关键词包含在测试消息中，例如 `Keycloak` 或 `钉钉`。

5. 保存后，复制生成的「Redirect URI」：
   ```
   https://your-keycloak-domain/realms/{realm}/broker/dingtalk/endpoint
   ```

### Step 4: 配置钉钉开放平台

1. 访问 [钉钉开放平台](https://open.dingtalk.com/) → 创建「企业内部应用」
2. 记录凭证：
   - **应用信息** → **凭证与基础信息**
   - 记录 **AppKey**（作为 Client ID）
   - 记录 **AppSecret**（作为 Client Secret）
3. 配置回调地址：
   - **开发配置** → **安全设置** → **重定向URL**
   - 添加从 Keycloak 复制的回调地址
4. 开通权限（**开发配置** → **权限管理**）：
   - ✅ 个人手机号信息 (`Contact.User.mobile`)
   - ✅ 通讯录个人信息读权限 (`Contact.User.Read`)
5. 发布应用：**应用发布** → **版本管理与发布** → 点击发布

### Step 5: 测试登录

1. 访问登录页面：`https://your-keycloak-domain/realms/{realm}/account`
2. 点击「钉钉登录」按钮
3. 在钉钉 OAuth 页面完成账号授权
4. 授权后自动返回 Keycloak

---

## 技术实现

### OAuth2.0 流程

```
用户触发登录
    ↓
1. 构造授权 URL
   https://login.dingtalk.com/oauth2/auth
    ↓
2. 用户授权并回调（携带 code 参数）
    ↓
3. 换取 access_token
   POST https://api.dingtalk.com/v1.0/oauth2/userAccessToken
   Content-Type: application/json
   Body: {"clientId": "xxx", "clientSecret": "xxx", "code": "xxx", "grantType": "authorization_code"}
    ↓
4. 获取用户信息
   GET https://api.dingtalk.com/v1.0/contact/users/me
   Header: x-acs-dingtalk-access-token: <token>
    ↓
5. 如果 OAuth 用户信息缺少手机号、邮箱或通讯录 userId，则通过企业通讯录 API 补齐
   POST https://oapi.dingtalk.com/topapi/v2/user/get
    ↓
6. 匹配已有 Keycloak 用户；未匹配时按配置创建或拒绝登录
```

### 钉钉 API 特性

与企业微信、飞书不同，钉钉有以下特性：

| 特性 | 钉钉 | 企业微信 | 飞书 |
|------|------|---------|------|
| Token 请求格式 | JSON Body | Query 参数 | Query 参数 |
| 用户信息请求头 | `x-acs-dingtalk-access-token` | Query 参数 | `Authorization` |
| 参数名 | `clientId/clientSecret` | `appid/secret` | `app_id/app_secret` |

### 用户属性映射

| 钉钉字段 | Keycloak 属性 | 处理逻辑 |
|---------|--------------|---------|
| `unionId` | 用户唯一ID | 优先使用，跨应用唯一 |
| `userId` | `dingtalk_userid` | 企业通讯录 userId，始终写入用户属性；不参与 username 生成，也不作为 `username` 匹配候选 |
| `openId` | `dingtalk_openid` | 应用内唯一标识 |
| `corpId` | `dingtalk_corpid` | 用于定位企业角色后缀 |
| `nick` | `nickname` | 用户昵称 |
| `mobile` | `phoneNumber` | 自动去除 `+86` 前缀 |
| `email` | `email` | 邮箱地址 |

### 用户名生成规则

创建新 Keycloak 用户时，登录链路和定时同步链路使用同一套 username 规则：

1. 先把钉钉姓名转换为拼音，例如 `张三` → `zhangsan`。
2. 如果钉钉邮箱存在，且邮箱前缀与姓名拼音一致，例如 `zhangsan@rzon.tech`，则使用邮箱前缀 `zhangsan`。
3. 其他情况下优先使用姓名拼音作为 username。
4. 如果姓名无法转换出拼音，则回退到邮箱前缀；如果姓名和邮箱都不可用，本轮不会自动创建用户，会记录 WARN 方便补齐钉钉资料。

`dingtalk_userid` 会始终写入用户属性，方便排障和后续同步定位。它不会作为新用户 username，也不会作为 `username` 匹配规则的候选值。

如果生成出的 username 已存在，但该钉钉用户没有通过已绑定身份、手机号或邮箱等可信规则匹配到这个用户，定时同步会跳过创建并输出 WARN，避免同名拼音导致绑错账号。

---

## 验证部署

### 检查 JAR 文件

```bash
# Docker 容器
docker exec keycloak ls -lh /opt/keycloak/providers/keycloak-dingtalk-provider.jar

# 检查 JAR 内容
jar tf dist/keycloak-dingtalk-provider.jar | grep -E "(DingTalk|fastjson|pinyin4j)"
```

预期输出应包含：
- `com/tencent/keycloak/dingtalk/DingTalkIdentityProvider.class`
- `com/tencent/keycloak/dingtalk/DingTalkIdentityProviderFactory.class`
- `com/tencent/keycloak/dingtalk/DingTalkUserSyncTask.class`
- `com/tencent/keycloak/dingtalk/DingTalkSyncAdminResource.class`
- `com/tencent/keycloak/dingtalk/DingTalkSyncBrowserResource.class`
- `com/tencent/keycloak/dingtalk/DingTalkSyncCreatedUserCleanup.class`
- `com/tencent/keycloak/dingtalk/DingTalkWebhookNotifier.class`
- `META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory`
- `META-INF/services/org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory`
- `com/alibaba/fastjson2/...`
- `net/sourceforge/pinyin4j/...`

不应包含旧的 `DingTalkNumericUserCleanup` 类。

### 检查日志

```bash
# Docker 容器
docker logs keycloak 2>&1 | grep -i dingtalk
```

预期输出：
```
INFO  [org.keycloak.services] (main) KC-SERVICES0050: Initializing provider dingtalk
INFO  [org.keycloak.services] (main) KC-SERVICES0051: Loaded SPI social (provider = dingtalk)
```

### 验证管理控制台

1. 登录 Keycloak Admin Console
2. 选择 Realm → Identity Providers → Add provider
3. 确认下拉列表中存在「钉钉」选项

---

## 故障排查

### 问题 1: Provider 未出现

**症状**：在「Add provider」下拉列表中找不到「钉钉」

**解决方案**：
```bash
# 1. 检查 JAR 是否存在
docker exec keycloak ls -lh /opt/keycloak/providers/keycloak-dingtalk-provider.jar

# 2. 强制重建
docker exec keycloak /opt/keycloak/bin/kc.sh build --verbose

# 3. 重启容器
docker restart keycloak

# 4. 查看启动日志
docker logs keycloak 2>&1 | grep -E "(dingtalk|SPI|provider)"
```

### 问题 2: ClassNotFoundException

**症状**：日志中出现类未找到错误

**解决方案**：
```bash
# 确认 JAR 包含所有依赖
jar tf dist/keycloak-dingtalk-provider.jar | grep -E "(fastjson|pinyin4j)"

# 如果缺失，重新打包
mvn clean package -DskipTests

# 确认 JAR 大小 > 2MB（包含依赖）
ls -lh dist/keycloak-dingtalk-provider.jar
```

### 问题 3: 启动失败 "Streams.of(java.lang.Iterable)"

**症状**：Keycloak 启动时报错：

```text
ERROR: 'java.util.stream.Stream org.apache.commons.lang3.stream.Streams.of(java.lang.Iterable)'
```

**原因**：`/opt/keycloak/providers` 里存在旧版或重复的 `commons-lang3`，或使用了包含 `commons-lang3` 的旧版插件 JAR，覆盖了 Keycloak 运行时自带版本。

**解决方案**：

```bash
# 当前插件 JAR 不应包含 commons-lang3，下面命令正常应无输出
jar tf dist/keycloak-dingtalk-provider.jar | grep 'org/apache/commons/lang3'

# 检查 Keycloak providers 目录，删除独立 commons-lang3 或旧插件 JAR
docker exec keycloak ls -lh /opt/keycloak/providers/

# 替换为最新 JAR 后重新 build 并重启
docker exec keycloak /opt/keycloak/bin/kc.sh build --verbose
docker restart keycloak
```

### 问题 4: 启动失败 "currentRealm is null"

**症状**：Keycloak 启动时报错：

```text
ERROR: Cannot invoke "Object.equals(Object)" because "currentRealm" is null
```

**原因**：Provider 启动阶段没有浏览器请求上下文，不能依赖“当前 realm”，也不能在 `postInit` 中遍历 Realm 并改写 IdP 配置。后台入口固定相对路径 `/realms/master/dingtalk-sync/endpoints` 直接内置在 JAR 的只读 URL 配置项默认值里，不需要保存到 IdP config；接口地址页面打开后可切换 Realm，最终仍必须通过 `/realms/<realm>/dingtalk-sync/endpoints` 运行时请求路径生成实际地址。

**解决方案**：确认使用的是不在启动阶段依赖当前请求 Realm 的新 JAR，替换后重新执行 `kc.sh build` 并重启 Keycloak。

### 问题 5: OAuth 错误 "invalid_client"

**症状**：授权时钉钉返回错误

**解决方案**：
1. 检查钉钉后台的 AppKey 和 AppSecret
2. 确保没有多余的空格
3. 重新配置 Keycloak IDP

### 问题 6: 回调地址错误 "redirect_uri_mismatch"

**症状**：授权后跳转失败

**解决方案**：
确保钉钉后台配置的回调地址与 Keycloak 生成的**完全一致**：
- ✅ 协议：`https://`（生产环境）或 `http://`（开发环境）
- ✅ 域名：必须完全匹配
- ✅ 路径：`/realms/{realm}/broker/dingtalk/endpoint`
- ❌ 末尾不要加多余的 `/`

### 问题 7: 用户信息未同步

**症状**：登录成功但用户属性为空

**解决方案**：
检查钉钉后台权限是否开通：
- ✅ 个人手机号信息 (`Contact.User.mobile`)
- ✅ 通讯录个人信息读权限 (`Contact.User.Read`)

---

## 编译说明

### 环境配置

#### 检查 Java 版本
```bash
java -version
# 应显示 openjdk version "17.x.x" 或更高
```

#### 配置 JAVA_HOME（macOS/Linux）
```bash
# 查找 Java 安装路径
/usr/libexec/java_home -V

# 设置 JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 永久配置（添加到 ~/.zshrc 或 ~/.bash_profile）
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc
source ~/.zshrc
```

### 编译命令

```bash
# 完整编译（推荐）
mvn clean package -DskipTests

# 仅编译不打包
mvn clean compile

# 并行编译（加速）
mvn -T 4 clean package -DskipTests

# 离线模式（依赖已下载后）
mvn clean package -o -DskipTests
```

### 常见编译问题

#### Maven 依赖下载失败

```bash
# 清理本地仓库缓存
rm -rf ~/.m2/repository/com/alibaba/fastjson2

# 重新下载
mvn clean install -U

# 或使用国内镜像（编辑 ~/.m2/settings.xml）
```

```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <mirrorOf>central</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
</mirrors>
```

---

## 更新与卸载

### 更新 Provider

```bash
# 1. 编译新版本
mvn clean package -DskipTests

# 2. 替换 JAR
docker cp target/keycloak-dingtalk-provider.jar keycloak:/opt/keycloak/providers/

# 如果直接使用仓库内已编译版本：
# docker cp dist/keycloak-dingtalk-provider.jar keycloak:/opt/keycloak/providers/

# 3. 重启
docker restart keycloak
```

### 卸载 Provider

```bash
# 1. 删除 JAR
docker exec keycloak rm /opt/keycloak/providers/keycloak-dingtalk-provider.jar

# 2. 重建
docker exec keycloak /opt/keycloak/bin/kc.sh build

# 3. 重启
docker restart keycloak

# 4. 在管理控制台删除 IDP 配置
# Identity Providers → dingtalk → Delete
```

---

## 生产环境建议

### 安全加固

1. **使用 HTTPS**
   ```bash
   KC_HTTPS_CERTIFICATE_FILE=/path/to/cert.pem
   KC_HTTPS_CERTIFICATE_KEY_FILE=/path/to/key.pem
   ```

2. **定期轮换密钥**
   - 每 90 天更新 AppSecret
   - 在钉钉后台和 Keycloak 同步更新

3. **启用审计日志**
   ```bash
   KC_LOG_LEVEL=INFO
   KC_FEATURES=token-exchange,admin-fine-grained-authz
   ```

### 性能特性

- **JAR 大小**：约 2.3MB（包含 fastjson2、pinyin4j 等插件运行所需依赖；Keycloak 运行时依赖由 Keycloak 提供）
- **编译时间**：约 30 秒
- **部署时间**：< 5 秒
- **Provider 加载**：< 1 秒

---

## 参考资源

- [钉钉 OAuth2.0 文档](https://open.dingtalk.com/document/orgapp/tutorial-obtaining-user-personal-information)
- [Keycloak SPI 开发文档](https://www.keycloak.org/docs/latest/server_development/)
- [Keycloak Identity Broker](https://www.keycloak.org/docs/latest/server_admin/#_identity_broker)

---

## 许可证

Apache License 2.0
