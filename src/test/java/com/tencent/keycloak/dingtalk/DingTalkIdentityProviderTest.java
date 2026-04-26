package com.tencent.keycloak.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DingTalkIdentityProviderTest {

    @Test
    void parseMatchRulesUsesSafeDefaults() {
        assertEquals(List.of("phone", "email"), DingTalkIdentityProvider.parseMatchRules(null));
        assertEquals(List.of("phone", "email"), DingTalkIdentityProvider.parseMatchRules(" "));
    }

    @Test
    void parseMatchRulesNormalizesAliasesAndRemovesDuplicates() {
        assertEquals(
                List.of("phone", "email", "unionid", "openid", "username"),
                DingTalkIdentityProvider.parseMatchRules("手机号, email；union_id openId 用户名 phone"));
    }

    @Test
    void createOnNoMatchDefaultsToAllowedAndHonorsExplicitFalse() {
        assertTrue(DingTalkIdentityProvider.isCreateOnNoMatchAllowed(null));
        assertTrue(DingTalkIdentityProvider.isCreateOnNoMatchAllowed(Map.of()));
        assertTrue(DingTalkIdentityProvider.isCreateOnNoMatchAllowed(Map.of("matchAction", "true")));
        assertFalse(DingTalkIdentityProvider.isCreateOnNoMatchAllowed(Map.of("matchAction", "false")));
    }

    @Test
    void enterpriseLoginGuardDefaultsToRequiredAndRequiresCorpId() {
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginRequired(null));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginRequired(Map.of()));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), null));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), " "));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), "dingcorp001"));
    }

    @Test
    void enterpriseLoginGuardHonorsAllowedCorpIdsAndExplicitDisable() {
        Map<String, String> config = Map.of(
                "allowedCorpIds", "dingcorp001, dingcorp002",
                "requireEnterpriseUser", "true");

        assertEquals(List.of("dingcorp001", "dingcorp002"),
                DingTalkIdentityProvider.parseAllowedCorpIds(config));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "dingcorp001"));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "DINGCORP002"));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "dingcorp003"));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(
                Map.of("requireEnterpriseUser", "false"), null));
    }

    @Test
    void missingPhoneNumberSyncDefaultsToEnabledAndHonorsExplicitFalse() {
        assertTrue(DingTalkIdentityProvider.isMissingPhoneNumberSyncEnabled(null));
        assertTrue(DingTalkIdentityProvider.isMissingPhoneNumberSyncEnabled(Map.of()));
        assertTrue(DingTalkIdentityProvider.isMissingPhoneNumberSyncEnabled(
                Map.of("syncPhoneNumberIfMissing", "true")));
        assertFalse(DingTalkIdentityProvider.isMissingPhoneNumberSyncEnabled(
                Map.of("syncPhoneNumberIfMissing", "false")));
    }

    @Test
    void resolveEnterpriseIdPrefersConfiguredValueThenCorpId() {
        assertEquals("ent-001", DingTalkIdentityProvider.resolveEnterpriseId(
                Map.of("enterpriseId", " ent-001 "), "corp-001"));
        assertEquals("corp-001", DingTalkIdentityProvider.resolveEnterpriseId(Map.of(), " corp-001 "));
        assertNull(DingTalkIdentityProvider.resolveEnterpriseId(Map.of(), " "));
    }

    @Test
    void sanitizeForLogRedactsSecretsTokensAndPersonalInfo() {
        String raw = "{\"accessToken\":\"token-123\",\"refreshToken\":\"refresh-456\","
                + "\"clientSecret\":\"secret-789\",\"mobile\":\"13800000000\","
                + "\"email\":\"a@example.com\",\"userid\":\"user-001\","
                + "\"unionid\":\"union-001\",\"openId\":\"open-001\","
                + "\"name\":\"张三\",\"nick\":\"昵称\"}";

        String sanitized = DingTalkIdentityProvider.sanitizeForLog(raw);

        assertFalse(sanitized.contains("token-123"));
        assertFalse(sanitized.contains("refresh-456"));
        assertFalse(sanitized.contains("secret-789"));
        assertFalse(sanitized.contains("13800000000"));
        assertFalse(sanitized.contains("a@example.com"));
        assertFalse(sanitized.contains("user-001"));
        assertFalse(sanitized.contains("union-001"));
        assertFalse(sanitized.contains("open-001"));
        assertFalse(sanitized.contains("张三"));
        assertFalse(sanitized.contains("昵称"));
        assertTrue(sanitized.contains("\"accessToken\":\"***\""));
        assertTrue(sanitized.contains("\"userid\":\"***\""));
    }

    @Test
    void formatMobileRemovesChinaCountryCodeOnly() {
        assertEquals("13800000000", DingTalkIdentityProvider.formatMobile("+8613800000000"));
        assertEquals("8613800000000", DingTalkIdentityProvider.formatMobile("8613800000000"));
        assertEquals("+11234567890", DingTalkIdentityProvider.formatMobile("+11234567890"));
    }

    @Test
    void resolveUsernameUsesSamePinyinRuleAsPeriodicSync() {
        UserDto userDto = new UserDto();
        userDto.setNick("张 三");
        userDto.setEmail("zhangsan@rzon.tech");

        assertEquals("zhangsan", DingTalkIdentityProvider.resolveUsername(userDto));

        userDto.setUserId("ad_zhangsan");
        assertEquals("zhangsan", DingTalkIdentityProvider.resolveUsername(userDto));
    }

    @Test
    void resolveUsernameDoesNotGenerateFallbackWhenNameAndEmailAreMissing() {
        UserDto userDto = new UserDto();
        userDto.setUserId("998877");
        userDto.setUnionId("union-998877");

        assertNull(DingTalkIdentityProvider.resolveUsername(userDto));
    }

    @Test
    void resolveBrokerUsernamePrefersExternalAccountNameForLinkedAccountDisplay() {
        UserDto userDto = new UserDto();
        userDto.setNick("张三");
        userDto.setEmail("zhangsan@rzon.tech");
        assertEquals("zhangsan@rzon.tech",
                DingTalkIdentityProvider.resolveBrokerUsername(userDto, "local-user"));

        userDto.setUserId("yangyingming");
        assertEquals("yangyingming",
                DingTalkIdentityProvider.resolveBrokerUsername(userDto, "local-user"));
    }

    @Test
    void mergeMissingUserInfoCompletesMobileWithoutOverwritingPrimaryValues() {
        UserDto primary = new UserDto();
        primary.setOpenId("open-primary");
        primary.setUnionId("union-primary");
        primary.setNick("primary-name");

        UserDto fallback = new UserDto();
        fallback.setOpenId("open-fallback");
        fallback.setUnionId("union-fallback");
        fallback.setUserId("ad-user");
        fallback.setMobile("+8613800000000");
        fallback.setEmail("user@rzon.tech");

        UserDto merged = DingTalkIdentityProvider.mergeMissingUserInfo(primary, fallback);

        assertEquals("open-primary", merged.getOpenId());
        assertEquals("union-primary", merged.getUnionId());
        assertEquals("primary-name", merged.getNick());
        assertEquals("ad-user", merged.getUserId());
        assertEquals("+8613800000000", merged.getMobile());
        assertEquals("user@rzon.tech", merged.getEmail());
    }

    @Test
    void needsCorpUserInfoWhenMobileOrUserIdIsMissing() {
        UserDto complete = new UserDto();
        complete.setOpenId("open");
        complete.setUserId("ad-user");
        complete.setMobile("13800000000");
        complete.setEmail("user@rzon.tech");

        assertFalse(DingTalkIdentityProvider.needsCorpUserInfo(complete));

        complete.setMobile(null);
        assertTrue(DingTalkIdentityProvider.needsCorpUserInfo(complete));
    }

    @Test
    void periodicSyncProvisionedUsernameFollowsAdPinyinRule() {
        UserDto sameEmailPrefix = new UserDto();
        sameEmailPrefix.setNick("张三");
        sameEmailPrefix.setEmail("zhangsan@rzon.tech");
        sameEmailPrefix.setUserId("123456");
        assertEquals("zhangsan", DingTalkUserSyncTask.resolveProvisionedUsername(sameEmailPrefix));

        UserDto differentEmailPrefix = new UserDto();
        differentEmailPrefix.setNick("李四");
        differentEmailPrefix.setEmail("employee-001@rzon.tech");
        differentEmailPrefix.setUserId("654321");
        assertEquals("lisi", DingTalkUserSyncTask.resolveProvisionedUsername(differentEmailPrefix));

        UserDto noChineseName = new UserDto();
        noChineseName.setEmail("fallback@rzon.tech");
        noChineseName.setUserId("998877");
        assertEquals("fallback", DingTalkUserSyncTask.resolveProvisionedUsername(noChineseName));

        UserDto noNameOrEmail = new UserDto();
        noNameOrEmail.setUserId("998877");
        noNameOrEmail.setUnionId("union-998877");
        assertNull(DingTalkUserSyncTask.resolveProvisionedUsername(noNameOrEmail));
    }

    @Test
    void periodicSyncConfigParsersUseSafeDefaults() {
        assertFalse(DingTalkUserSyncTask.isPeriodicSyncEnabled(null));
        assertFalse(DingTalkUserSyncTask.isPeriodicSyncEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isPeriodicSyncEnabled(Map.of("periodicSyncEnabled", "true")));
        assertFalse(DingTalkUserSyncTask.isCreateUsersEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isCreateUsersEnabled(Map.of("periodicSyncCreateUsers", "true")));
        assertFalse(DingTalkUserSyncTask.isDisableMissingUsersEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isDisableMissingUsersEnabled(
                Map.of("periodicSyncDisableMissingUsers", "true")));
        assertTrue(DingTalkUserSyncTask.isReenableUsersEnabled(Map.of()));
        assertFalse(DingTalkUserSyncTask.isReenableUsersEnabled(
                Map.of("periodicSyncReenableUsers", "false")));
        assertFalse(DingTalkUserSyncTask.isDetailedLogEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isDetailedLogEnabled(
                Map.of("periodicSyncDetailedLog", "true")));
        assertTrue(DingTalkUserSyncTask.isIncludeChildDepartmentsEnabled(Map.of()));
        assertFalse(DingTalkUserSyncTask.isIncludeChildDepartmentsEnabled(
                Map.of("periodicSyncIncludeChildDepartments", "false")));
        assertFalse(DingTalkIdentityProvider.isEnterpriseRoleGrantEnabled(Map.of()));
        assertTrue(DingTalkIdentityProvider.isEnterpriseRoleGrantEnabled(
                Map.of("enableEnterpriseRoleGrant", "true")));

        assertEquals(Set.of("phone"), DingTalkUserSyncTask.parseSyncFields(null));
        assertEquals(Set.of("phone", "email"),
                DingTalkUserSyncTask.parseSyncFields("手机号,email,nick"));

        assertEquals(List.of("1"), DingTalkUserSyncTask.parseDepartmentIds(null));
        assertEquals(List.of("1", "23"), DingTalkUserSyncTask.parseDepartmentIds("1,23,1"));

        assertEquals(3600, DingTalkUserSyncTask.parsePositiveLong(null, 3600));
        assertEquals(3600, DingTalkUserSyncTask.parsePositiveLong("-1", 3600));
        assertEquals(600, DingTalkUserSyncTask.parsePositiveLong("600", 3600));

        assertEquals("2026-04-26 00:06:23 CST",
                DingTalkUserSyncTask.formatBeijingTime(1777133183));
    }
}
