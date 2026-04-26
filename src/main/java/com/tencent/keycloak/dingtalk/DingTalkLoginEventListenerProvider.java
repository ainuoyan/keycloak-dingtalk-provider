package com.tencent.keycloak.dingtalk;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * 钉钉登录事件监听器
 * 在钉钉用户登录后自动分配 ent-plugin-enabled 角色
 */
public class DingTalkLoginEventListenerProvider implements EventListenerProvider {

    private static final Logger logger = Logger.getLogger(DingTalkLoginEventListenerProvider.class);
    
    private static final String ROLE_PLUGIN_ENABLE = "ent-plugin-enabled:";
    private static final String DINGTALK_PROVIDER_ID = "dingtalk";
    private static final String ENT_USER_SOURCE_PREFIX = "ent-user-source:";

    private final KeycloakSession session;

    public DingTalkLoginEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        // 监听登录事件和注册事件
        if (event.getType() == EventType.LOGIN || event.getType() == EventType.REGISTER) {
            handleLoginOrRegister(event);
        }
    }

    private void handleLoginOrRegister(Event event) {
        String userId = event.getUserId();
        if (userId == null) {
            return;
        }

        RealmModel realm = session.realms().getRealm(event.getRealmId());
        if (realm == null) {
            return;
        }

        UserModel user = session.users().getUserById(realm, userId);
        if (user == null) {
            return;
        }

        Set<String> enterpriseIds = findDingTalkEnterpriseIds(user.getAttributes());
        if (!enterpriseIds.isEmpty()) {
            grantPluginEnabledRole(realm, user, enterpriseIds);
        }
    }

    /**
     * 分配 ent-plugin-enabled 角色
     */
    private void grantPluginEnabledRole(RealmModel realm, UserModel user, Set<String> enterpriseIds) {
        enterpriseIds.forEach(enterpriseId -> {
            String roleName = ROLE_PLUGIN_ENABLE + enterpriseId;
            RoleModel role = realm.getRole(roleName);
            if (role == null) {
                logger.warnf("DingTalk EventListener: role %s does not exist, skip user %s",
                        roleName, user.getUsername());
                return;
            }
            if (!user.hasRole(role)) {
                user.grantRole(role);
                logger.infof("DingTalk EventListener: Granted plugin role %s to user %s",
                        role.getName(), user.getUsername());
            }
        });
    }

    static Set<String> findDingTalkEnterpriseIds(Map<String, List<String>> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> enterpriseIds = new LinkedHashSet<>();
        attributes.forEach((key, values) -> {
            if (!key.startsWith(ENT_USER_SOURCE_PREFIX) || values == null) {
                return;
            }
            boolean fromDingTalk = values.stream().anyMatch(DINGTALK_PROVIDER_ID::equals);
            if (!fromDingTalk) {
                return;
            }

            String enterpriseId = key.substring(ENT_USER_SOURCE_PREFIX.length()).trim();
            if (!enterpriseId.isEmpty()) {
                enterpriseIds.add(enterpriseId);
            }
        });
        return enterpriseIds;
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // 不处理管理员事件
    }

    @Override
    public void close() {
        // 无需清理资源
    }
}
