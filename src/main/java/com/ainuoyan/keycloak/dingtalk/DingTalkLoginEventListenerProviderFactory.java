package com.ainuoyan.keycloak.dingtalk;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * 钉钉登录事件监听器工厂
 */
public class DingTalkLoginEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "dingtalk-login-event-listener";

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new DingTalkLoginEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // 无需初始化配置
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // 无需后初始化
    }

    @Override
    public void close() {
        // 无需清理资源
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
