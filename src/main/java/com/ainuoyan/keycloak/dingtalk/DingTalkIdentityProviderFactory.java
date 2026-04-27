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

package com.ainuoyan.keycloak.dingtalk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.AbstractIdentityProviderFactory;
import org.keycloak.broker.social.SocialIdentityProviderFactory;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
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
    static final String SYNC_GET_DEBUG_ENABLED = "syncGetDebugEnabled";
    static final String BROWSER_SYNC_DEBUG_KEY = "browserSyncDebugKey";
    static final String ENDPOINT_REFERENCE_PAGE_CONFIG = "dingtalkEndpointReferencePage";
    static final String ENDPOINT_REFERENCE_PAGE_HREF_CONFIG = "value";
    // Keycloak 26 URL_TYPE reads useWatch(name).value, so the field watches the config object.
    static final String ENDPOINT_REFERENCE_PAGE_FORM_FIELD = "config";
    static final String ENDPOINT_REFERENCE_PAGE_URL = "/realms/master/dingtalk-sync/endpoints";

    private static final long PERIODIC_SYNC_CHECK_INTERVAL_MS = 60_000L;
    private static final String ENDPOINT_REFERENCE_HELP =
            "接口地址可通过下方“接口地址页面入口”查看；打开页面后可切换 Realm，并生成、复制和打开同步、清理、Webhook 测试地址。";
    private static final List<EndpointReference> ENDPOINT_REFERENCES = List.of(
            new EndpointReference(
                    ENDPOINT_REFERENCE_PAGE_FORM_FIELD,
                    "接口地址页面入口",
                    ENDPOINT_REFERENCE_PAGE_URL));

    @Override
    public String getName() {
        return "钉钉";
    }

    @Override
    public DingTalkIdentityProvider create(KeycloakSession session, IdentityProviderModel model) {
        boolean updated = ensureEndpointReferenceConfig(model);
        persistEndpointReferenceConfig(session, model, updated);
        return new DingTalkIdentityProvider(session, new OAuth2IdentityProviderConfig(model));
    }

    @Override
    public IdentityProviderModel createConfig() {
        IdentityProviderModel config = new OAuth2IdentityProviderConfig();
        ensureEndpointReferenceConfig(config);
        return config;
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
            persistEndpointReferenceConfigs(session);
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
                .property().name(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN)
                .label("新用户邮箱后缀")
                .helpText("创建新 Keycloak/AD 用户时使用 username@该后缀 作为邮箱，例如 example.com；为空时使用钉钉返回的邮箱。只影响新建用户，不修改已有用户邮箱")
                .type(ProviderConfigProperty.STRING_TYPE)
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
                .property().name(DingTalkCreatedUserInitializer.INITIALIZE_CREATED_USERS)
                .label("同步创建后初始化密码并启用")
                .helpText("默认关闭。开启后，同步创建用户的事务提交成功后，会在独立事务中生成随机临时密码、要求首次登录修改密码，并启用用户；临时密码不会写入日志或 Webhook")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DISABLE_MISSING_USERS)
                .label("定期同步禁用离职用户")
                .helpText("开启后，禁用已由当前钉钉 IdP 托管或已绑定当前钉钉 IdP、但本次通讯录不存在的 Keycloak 用户；默认关闭。AD 是否同步禁用取决于 LDAP provider 和 MSAD account controls mapper")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DISABLE_EXTERNAL_USERS)
                .label("禁用未出现在钉钉的外部存储用户")
                .helpText("危险开关，默认关闭。仅在钉钉同步部门完整代表 AD 员工范围时开启；开启后会在写入同步字段前扫描 LDAP/AD 等外部存储用户，用 username、email、phoneNumber/mobile/telephoneNumber 与本次钉钉通讯录比对。若 User Storage 开启 removeInvalidUsersEnabled 会跳过外部扫描；AD 机器账号（username 以 $ 结尾）和 service account 不会被禁用")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_REENABLE_USERS)
                .label("定期同步重新启用返聘用户")
                .helpText("开启后，仅将之前由本插件按 missing_from_dingtalk 禁用、且重新出现在钉钉通讯录的用户自动启用；默认开启")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(true)
                .add()
                .property().name(DingTalkUserSyncTask.PERIODIC_SYNC_DETAILED_LOG)
                .label("记录同步明细日志")
                .helpText("开启后，同步会记录每个钉钉用户的匹配来源、Keycloak 用户名、更新字段和跳过原因；关闭时手动同步和 dry-run 也不会输出逐用户明细。日志不会输出手机号、邮箱、token、secret 明文")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_ENABLED)
                .label("启用钉钉机器人通知")
                .helpText("默认关闭。开启并配置 Webhook 地址后，会通知登录链路新创建用户、同步真实执行中新创建用户、同步真实执行中因用户名为空或用户名冲突而跳过创建的告警，以及定时/手动/浏览器真实同步失败告警；dry-run 不发送通知")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_URL)
                .label("钉钉机器人 Webhook 地址")
                .helpText("钉钉自定义机器人 Webhook 地址，通常包含 access_token；会作为敏感字段保存。为空或未启用通知时不发送")
                .type(ProviderConfigProperty.PASSWORD)
                .secret(true)
                .add()
                .property().name(DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_SECRET)
                .label("钉钉机器人加签密钥")
                .helpText("如果钉钉机器人开启了加签安全设置，请填写 SEC 开头的密钥；未开启加签时可留空")
                .type(ProviderConfigProperty.PASSWORD)
                .secret(true)
                .add()
                .property().name(SYNC_GET_DEBUG_ENABLED)
                .label("启用 GET 同步调试入口")
                .helpText("默认关闭。开启后才允许使用浏览器公开 GET 预览、浏览器公开 GET 真实同步、浏览器公开 GET Webhook 测试、管理端 GET dry-run 和管理端 GET 真实同步。调试完成后请关闭；管理端 POST 同步不受影响。" + ENDPOINT_REFERENCE_HELP)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue(false)
                .add()
                .property().name(BROWSER_SYNC_DEBUG_KEY)
                .label("浏览器同步调试密钥")
                .helpText("配合“启用 GET 同步调试入口”使用。两者同时有效时才允许纯浏览器 GET 预览、正式同步和 Webhook 测试")
                .type(ProviderConfigProperty.PASSWORD)
                .secret(true)
                .add()
                .build());

        for (EndpointReference reference : ENDPOINT_REFERENCES) {
            properties.add(endpointReferenceProperty(reference));
        }

        return properties;
    }

    private ProviderConfigProperty endpointReferenceProperty(EndpointReference reference) {
        ProviderConfigProperty property = new ProviderConfigProperty(
                reference.name(),
                reference.label(),
                reference.url(),
                ProviderConfigProperty.URL_TYPE,
                reference.url());
        property.setReadOnly(true);
        return property;
    }

    static boolean ensureEndpointReferenceConfig(IdentityProviderModel model) {
        if (model == null) {
            return false;
        }
        Map<String, String> config = model.getConfig();
        if (config == null) {
            config = new HashMap<>();
            model.setConfig(config);
        } else if (hasEndpointReferenceConfig(config)) {
            return false;
        } else {
            config = new HashMap<>(config);
            model.setConfig(config);
        }
        config.put(ENDPOINT_REFERENCE_PAGE_CONFIG, ENDPOINT_REFERENCE_PAGE_URL);
        config.put(ENDPOINT_REFERENCE_PAGE_HREF_CONFIG, ENDPOINT_REFERENCE_PAGE_URL);
        return true;
    }

    private static boolean hasEndpointReferenceConfig(Map<String, String> config) {
        return ENDPOINT_REFERENCE_PAGE_URL.equals(config.get(ENDPOINT_REFERENCE_PAGE_CONFIG))
                && ENDPOINT_REFERENCE_PAGE_URL.equals(config.get(ENDPOINT_REFERENCE_PAGE_HREF_CONFIG));
    }

    private static void persistEndpointReferenceConfig(
            KeycloakSession session,
            IdentityProviderModel model,
            boolean updated) {
        if (!updated || session == null || model == null || model.getAlias() == null) {
            return;
        }
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return;
        }
        IdentityProviderModel persisted = realm.getIdentityProviderByAlias(model.getAlias());
        if (persisted == null || !PROVIDER_ID.equals(persisted.getProviderId())) {
            return;
        }
        if (persisted == model) {
            realm.updateIdentityProvider(model);
            return;
        }
        Map<String, String> config = persisted.getConfig() == null
                ? new HashMap<>()
                : new HashMap<>(persisted.getConfig());
        if (hasEndpointReferenceConfig(config)) {
            return;
        }
        config.put(ENDPOINT_REFERENCE_PAGE_CONFIG, ENDPOINT_REFERENCE_PAGE_URL);
        config.put(ENDPOINT_REFERENCE_PAGE_HREF_CONFIG, ENDPOINT_REFERENCE_PAGE_URL);
        persisted.setConfig(config);
        realm.updateIdentityProvider(persisted);
    }

    private static void persistEndpointReferenceConfigs(KeycloakSession session) {
        if (session == null) {
            return;
        }
        RealmModel originalRealm = session.getContext().getRealm();
        try {
            session.realms().getRealmsStream().forEach(realm -> {
                RealmModel previousRealm = session.getContext().getRealm();
                session.getContext().setRealm(realm);
                try {
                    realm.getIdentityProvidersStream()
                            .filter(idp -> PROVIDER_ID.equals(idp.getProviderId()))
                            .forEach(idp -> {
                                if (ensureEndpointReferenceConfig(idp)) {
                                    realm.updateIdentityProvider(idp);
                                }
                            });
                } finally {
                    session.getContext().setRealm(previousRealm);
                }
            });
        } finally {
            session.getContext().setRealm(originalRealm);
        }
    }

    private record EndpointReference(String name, String label, String url) {}

    static boolean isSyncGetDebugEnabled(IdentityProviderModel idp) {
        return idp != null && isSyncGetDebugEnabled(idp.getConfig());
    }

    static boolean isSyncGetDebugEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(SYNC_GET_DEBUG_ENABLED, "false"));
    }
}
