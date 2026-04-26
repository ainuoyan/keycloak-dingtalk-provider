package com.tencent.keycloak.dingtalk;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public class DingTalkSyncBrowserResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    DingTalkSyncBrowserResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new DingTalkSyncBrowserResource(session);
    }

    @Override
    public void close() {
    }
}
