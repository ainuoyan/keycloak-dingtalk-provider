package com.tencent.keycloak.dingtalk;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

public class DingTalkSyncAdminResourceProvider implements AdminRealmResourceProvider {

    @Override
    public Object getResource(KeycloakSession session, RealmModel realm,
                              AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        return new DingTalkSyncAdminResource(session, realm, auth, adminEvent);
    }

    @Override
    public void close() {
    }
}
