package com.tencent.keycloak.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DingTalkLoginEventListenerProviderTest {

    @Test
    void findDingTalkEnterpriseIdsReadsSuffixedSourceAttributes() {
        Set<String> enterpriseIds = DingTalkLoginEventListenerProvider.findDingTalkEnterpriseIds(Map.of(
                "ent-user-source:corp-a", List.of("dingtalk"),
                "ent-user-source:corp-b", List.of("wecom", "dingtalk"),
                "ent-user-source:corp-c", List.of("feishu"),
                "nickname", List.of("Alice")));

        assertEquals(Set.of("corp-a", "corp-b"), enterpriseIds);
    }

    @Test
    void findDingTalkEnterpriseIdsIgnoresUnsuffixedOrEmptyAttributes() {
        assertTrue(DingTalkLoginEventListenerProvider.findDingTalkEnterpriseIds(null).isEmpty());
        assertTrue(DingTalkLoginEventListenerProvider.findDingTalkEnterpriseIds(Map.of()).isEmpty());
        assertTrue(DingTalkLoginEventListenerProvider.findDingTalkEnterpriseIds(Map.of(
                "ent-user-source:", List.of("dingtalk"),
                "ent-user-source:corp-a", List.of("wecom"))).isEmpty());
    }
}
