/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.keycloak.dingtalk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.timer.TimerProvider;

/**
 * 钉钉登录集成Factory
 *
 * @author: generated
 * @date: 2025-11-26
 */
public class DingTalkIdentityProviderFactory extends AbstractIdentityProviderFactory<DingTalkIdentityProvider>
        implements SocialIdentityProviderFactory<DingTalkIdentityProvider> {

    public static final String PROVIDER_ID = "dingtalk";

    // 配置项常量
    private static final String IS_UPDATE_USER_INFO = "isUpdateUserInfo";
    private static final String SYNC_PHONE_NUMBER_IF_MISSING = "syncPhoneNumberIfMissing";
    private static final String ENTERPRISE_ID = "enterpriseId";
    private static final String REQUIRE_ENTERPRISE_USER = "requireEnterpriseUser";
    private static final String ALLOWED_CORP_IDS = "allowedCorpIds";
    private static final String ENABLE_ENTERPRISE_ROLE_GRANT = DingTalkIdentityProvider.ENABLE_ENTERPRISE_ROLE_GRANT;
    private static final String MATCH_ACTION = "matchAction";
    private static final String MATCH_RULES = "matchRules";
    private static final String MANUAL_SYNC_URL = "manualSyncUrl";
    static final String SYNC_GET_DEBUG_ENABLED = "syncGetDebugEnabled";
    static final String BROWSER_SYNC_DEBUG_KEY = "browserSyncDebugKey";
    private static final String BROWSER_SYNC_RUN_URL = "browserSyncRunUrl";
    private static final String BROWSER_SYNC_PREVIEW_URL = "browserSyncPreviewUrl";

    private static final long PERIODIC_SYNC_CHECK_INTERVAL_MS = 60_000L;

    @Override
    public String getName() {
        return "钉钉";
    }

    @Override
    public DingTalkIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        return new DingTalkIdentityProvider(session, new OAuth2IdentityProviderConfig(model));
    }

    @Override
    public IdentityProviderModel createConfig() {
        return new OAuth2IdentityProviderConfig();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        super.postInit(factory);
        KeycloakModelUtils.runJobInTransaction(factory, session -> {
            TimerProvider timer = session.getProvider(TimerProvider.class);
            if (timer != null && !timer.getTasks().containsKey(DingTalkUserSyncTask.TASK_NAME)) {
                timer.scheduleTask(
                        new DingTalkUserSyncTask(),
                        PERIODIC_SYNC_CHECK_INTERVAL_MS,
                        DingTalkUserSyncTask.TASK_NAME);
            }
        });
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        List<ProviderConfigProperty> properties = new ArrayList<>(ProviderConfigurationBuilder.create()
                .property().name(IS_UPDATE_USER_INFO)
                .label("登录后是否更新用户信息")
                .helpText("如果开启则在已匹配用户登录后写入邮箱、昵称、手机号等信息；关闭后只用于匹配登录。此项不控制 Keycloak First Login Flow 的 Review Profile 页面")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(SYNC_PHONE_NUMBER_IF_MISSING)
                .label("手机号为空时补齐手机号")
                .helpText("开启后，即使关闭“登录后是否更新用户信息”，也会在用户 phoneNumber 为空时从钉钉补齐手机号；不会覆盖已有手机号")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(ENTERPRISE_ID)
                .label("企业ID/角色后缀")
                .helpText("开启“启用企业角色授予”时作为 ent-member:{企业ID} 和 ent-plugin-enabled:{企业ID} 的角色后缀；为空时使用钉钉返回的 corpId")
                .type(ProviderConfigProperty.STRING_TYPE)
                .add()
                .property().name(REQUIRE_ENTERPRISE_USER)
                .label("仅允许本企业钉钉用户登录")
                .helpText("默认开启。开启后优先用企业 appKey/appSecret 反查 unionId 是否属于本企业；无法反查时再校验“允许登录的企业 CorpId”，否则拒绝登录且不会自动创建用户")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(ALLOWED_CORP_IDS)
                .label("允许登录的企业 CorpId")
                .helpText("逗号分隔，例如 dingxxxxxxxx。作为企业接口无法反查 unionId 时的兜底白名单；建议填写本企业 corpId")
                .type(ProviderConfigProperty.TEXT_TYPE)
                .add()
                .property().name(ENABLE_ENTERPRISE_ROLE_GRANT)
                .label("启用企业角色授予")
                .helpText("开启后，登录成功时会尝试授予 ent-member:{企业ID} 和 ent-plugin-enabled:{企业ID} 角色；默认关闭，避免未创建角色时刷 WARN 日志")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(MATCH_ACTION)
                .label("匹配失败是否允许登录")
                .helpText("如果开启规则匹配失败则直接创建用户，否则拒绝登录，默认打开")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(MATCH_RULES)
                .label("匹配规则配置")
                .helpText("第三方SSO平台的匹配规则，逗号分隔，支持：phone、email、unionId、openId、username；按配置顺序匹配，默认 phone,email")
                .type(ProviderConfigProperty.TEXT_TYPE)
                .defaultValue("phone,email")
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_ENABLED)
                .label("启用定期同步钉钉通讯录")
                .helpText("开启后 Keycloak 会定期读取钉钉通讯录，先按已绑定钉钉身份定位，再按配置的 phone、email、unionId、openId、username 规则匹配已有用户并绑定钉钉身份；默认关闭")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_PERIOD_SECONDS)
                .label("定期同步周期秒数")
                .helpText("每个钉钉 Identity Provider 的最小同步间隔，默认 3600 秒")
                .type(ProviderConfigProperty.STRING_TYPE)
                .defaultValue("3600")
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DEPARTMENT_IDS)
                .label("定期同步部门ID")
                .helpText("逗号分隔的钉钉根部门 ID，默认 1。开启“同步子部门用户”时会递归同步这些部门下的所有子部门")
                .type(ProviderConfigProperty.TEXT_TYPE)
                .defaultValue("1")
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_INCLUDE_CHILD_DEPARTMENTS)
                .label("同步子部门用户")
                .helpText("默认开启。开启后会递归展开“定期同步部门ID”下的所有子部门，再同步每个部门的用户")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_FIELDS)
                .label("定期同步字段")
                .helpText("逗号分隔，支持 phone、email；默认只同步 phone。nickname 和 dingtalk_userid 等钉钉身份属性会始终记录")
                .type(ProviderConfigProperty.TEXT_TYPE)
                .defaultValue("phone")
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_OVERWRITE_EXISTING)
                .label("定期同步覆盖已有字段")
                .helpText("关闭时只补齐空字段；开启后会用钉钉通讯录覆盖已存在的同步字段")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_CREATE_USERS)
                .label("定期同步自动创建用户")
                .helpText("开启后，钉钉通讯录用户在 Keycloak 中匹配不到时会自动创建 Keycloak 用户；默认关闭")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DISABLE_MISSING_USERS)
                .label("定期同步禁用离职用户")
                .helpText("开启后，仅禁用之前由当前钉钉同步任务标记为托管、但本次通讯录不存在的 Keycloak 用户；默认关闭")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_REENABLE_USERS)
                .label("定期同步重新启用返聘用户")
                .helpText("开启后，已被禁用的钉钉托管用户重新出现在通讯录时会自动启用；默认开启")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DETAILED_LOG)
                .label("记录同步明细日志")
                .helpText("开启后，同步会记录每个钉钉用户的匹配来源、Keycloak 用户名、更新字段和跳过原因；关闭时手动同步和 dry-run 也不会输出逐用户明细。日志不会输出手机号、邮箱、token、secret 明文")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(SYNC_GET_DEBUG_ENABLED)
                .label("启用 GET 同步调试入口")
                .helpText("默认关闭。开启后才允许使用浏览器公开 GET 预览、浏览器公开 GET 真实同步、管理端 GET dry-run 和管理端 GET 真实同步。调试完成后请关闭；管理端 POST 同步不受影响")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(BROWSER_SYNC_DEBUG_KEY)
                .label("浏览器同步调试密钥")
                .helpText("配合“启用 GET 同步调试入口”使用。两者同时有效时才允许纯浏览器 GET 预览和正式同步")
                .type(ProviderConfigProperty.PASSWORD)
                .secret(true)
                .add()
                .build());

        ProviderConfigProperty manualSyncUrl = new ProviderConfigProperty(
                MANUAL_SYNC_URL,
                "管理 API 同步地址",
                "管理 API 支持 POST /admin/realms/{realm}/dingtalk-sync/run?alias={alias}；GET 真实同步仅在开启 GET 同步调试入口后可用，并额外要求 confirm=RUN_DINGTALK_SYNC。两者都需要 Authorization Bearer 管理端 token 和 manage-users 权限。",
                ProviderConfigProperty.STRING_TYPE,
                "/admin/realms/{realm}/dingtalk-sync/run?alias={alias}");
        manualSyncUrl.setReadOnly(true);
        properties.add(manualSyncUrl);

        ProviderConfigProperty browserSyncRunUrl = new ProviderConfigProperty(
                BROWSER_SYNC_RUN_URL,
                "浏览器同步执行地址",
                "开启 GET 同步调试入口并配置正确密钥后，可在浏览器地址栏访问 GET /realms/{realm}/dingtalk-sync/run?alias={alias}&key={浏览器同步调试密钥}&confirm=RUN_DINGTALK_SYNC；会真实同步并写入 Keycloak。",
                ProviderConfigProperty.STRING_TYPE,
                "/realms/{realm}/dingtalk-sync/run?alias={alias}&key={浏览器同步调试密钥}&confirm=RUN_DINGTALK_SYNC");
        browserSyncRunUrl.setReadOnly(true);
        properties.add(browserSyncRunUrl);

        ProviderConfigProperty browserSyncPreviewUrl = new ProviderConfigProperty(
                BROWSER_SYNC_PREVIEW_URL,
                "浏览器同步预览地址",
                "开启 GET 同步调试入口并配置正确密钥后，可在浏览器地址栏访问 GET /realms/{realm}/dingtalk-sync/debug?alias={alias}&key={浏览器同步调试密钥}；返回 dry-run 统计，不创建、更新、禁用用户。",
                ProviderConfigProperty.STRING_TYPE,
                "/realms/{realm}/dingtalk-sync/debug?alias={alias}&key={浏览器同步调试密钥}");
        browserSyncPreviewUrl.setReadOnly(true);
        properties.add(browserSyncPreviewUrl);

        return properties;
    }

    static boolean isSyncGetDebugEnabled(IdentityProviderModel idp) {
        return idp != null && isSyncGetDebugEnabled(idp.getConfig());
    }

    static boolean isSyncGetDebugEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(SYNC_GET_DEBUG_ENABLED, "false"));
    }
}
