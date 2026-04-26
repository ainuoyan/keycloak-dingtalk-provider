package com.tencent.keycloak.dingtalk;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

public class DingTalkSyncBrowserResourceProviderFactory implements RealmResourceProviderFactory {

    static final String PROVIDER_ID = "dingtalk-sync";

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new DingTalkSyncBrowserResourceProvider(session);
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
