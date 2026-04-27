package com.ainuoyan.keycloak.dingtalk;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;

public class DingTalkSyncAdminResourceProviderFactory implements AdminRealmResourceProviderFactory {

    static final String PROVIDER_ID = "dingtalk-sync";

    @Override
    public AdminRealmResourceProvider create(KeycloakSession session) {
        return new DingTalkSyncAdminResourceProvider();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
