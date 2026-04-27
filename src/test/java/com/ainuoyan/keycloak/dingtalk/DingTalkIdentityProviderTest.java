package com.ainuoyan.keycloak.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;

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
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), null, false));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), " ", false));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), "dingcorp001", false));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(Map.of(), null, true));
    }

    @Test
    void enterpriseLoginGuardHonorsAllowedCorpIdsAndExplicitDisable() {
        Map<String, String> config = Map.of(
                "allowedCorpIds", "dingcorp001, dingcorp002",
                "requireEnterpriseUser", "true");

        assertEquals(List.of("dingcorp001", "dingcorp002"),
                DingTalkIdentityProvider.parseAllowedCorpIds(config));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "dingcorp001", false));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "DINGCORP002", false));
        assertFalse(DingTalkIdentityProvider.isEnterpriseLoginAllowed(config, "dingcorp003", false));
        assertTrue(DingTalkIdentityProvider.isEnterpriseLoginAllowed(
                Map.of("requireEnterpriseUser", "false"), null, false));
    }

    @Test
    void enterpriseLoginGuardCanUseUnionIdForCorpApiVerification() {
        UserTokenDto tokenDto = new UserTokenDto();
        assertFalse(DingTalkIdentityProvider.hasUnionId(tokenDto, null));

        tokenDto.setUnionId("union-001");
        assertTrue(DingTalkIdentityProvider.hasUnionId(tokenDto, null));

        UserDto userDto = new UserDto();
        userDto.setUnionId("union-002");
        assertTrue(DingTalkIdentityProvider.hasUnionId(null, userDto));
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

        String prose = DingTalkIdentityProvider.sanitizeForLog(
                "Can't import user because email 'employee@example.com' already exists, mobile 13800000000");
        assertFalse(prose.contains("employee@example.com"));
        assertFalse(prose.contains("13800000000"));
    }

    @Test
    void sanitizeUriForLogKeepsHostAndPathButDropsQuery() {
        assertEquals("https://sso.example.com/auth/realms/demo/broker/dingtalk/endpoint?***",
                DingTalkIdentityProvider.sanitizeUriForLog(
                        "https://sso.example.com/auth/realms/demo/broker/dingtalk/endpoint?code=abc&state=xyz"));
        assertEquals("***", DingTalkIdentityProvider.sanitizeUriForLog("not a valid uri"));
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
        userDto.setEmail("zhangsan@example.com");

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
        userDto.setEmail("zhangsan@example.com");
        assertEquals("zhangsan@example.com",
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
        fallback.setEmail("user@example.com");

        UserDto merged = DingTalkIdentityProvider.mergeMissingUserInfo(primary, fallback);

        assertEquals("open-primary", merged.getOpenId());
        assertEquals("union-primary", merged.getUnionId());
        assertEquals("primary-name", merged.getNick());
        assertEquals("ad-user", merged.getUserId());
        assertEquals("+8613800000000", merged.getMobile());
        assertEquals("user@example.com", merged.getEmail());
    }

    @Test
    void needsCorpUserInfoWhenMobileOrUserIdIsMissing() {
        UserDto complete = new UserDto();
        complete.setOpenId("open");
        complete.setUserId("ad-user");
        complete.setMobile("13800000000");
        complete.setEmail("user@example.com");

        assertFalse(DingTalkIdentityProvider.needsCorpUserInfo(complete));

        complete.setMobile(null);
        assertTrue(DingTalkIdentityProvider.needsCorpUserInfo(complete));
    }

    @Test
    void periodicSyncProvisionedUsernameFollowsAdPinyinRule() {
        UserDto sameEmailPrefix = new UserDto();
        sameEmailPrefix.setNick("张三");
        sameEmailPrefix.setEmail("zhangsan@example.com");
        sameEmailPrefix.setUserId("123456");
        assertEquals("zhangsan", DingTalkUserSyncTask.resolveProvisionedUsername(sameEmailPrefix));

        UserDto differentEmailPrefix = new UserDto();
        differentEmailPrefix.setNick("李四");
        differentEmailPrefix.setEmail("employee-001@example.com");
        differentEmailPrefix.setUserId("654321");
        assertEquals("lisi", DingTalkUserSyncTask.resolveProvisionedUsername(differentEmailPrefix));

        UserDto noChineseName = new UserDto();
        noChineseName.setEmail("fallback@example.com");
        noChineseName.setUserId("998877");
        assertEquals("fallback", DingTalkUserSyncTask.resolveProvisionedUsername(noChineseName));

        UserDto noNameOrEmail = new UserDto();
        noNameOrEmail.setUserId("998877");
        noNameOrEmail.setUnionId("union-998877");
        assertNull(DingTalkUserSyncTask.resolveProvisionedUsername(noNameOrEmail));

        assertEquals("zhangsan@example.com", DingTalkUserSyncTask.resolveProvisionedEmail(
                Map.of(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "@EXAMPLE.COM"),
                "ZhangSan",
                "other@example.com"));
        assertEquals("other@example.com", DingTalkUserSyncTask.resolveProvisionedEmail(
                Map.of(),
                "zhangsan",
                " Other@EXAMPLE.COM "));
        assertThrows(IllegalArgumentException.class, () -> DingTalkUserSyncTask.resolveProvisionedEmail(
                Map.of(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "bad @ domain"),
                "zhangsan",
                null));

        DingTalkUserSyncTask.ProvisioningName chineseName = DingTalkUserSyncTask.resolveProvisioningName("丁杰");
        assertEquals("丁杰", chineseName.firstName());
        assertNull(chineseName.lastName());
        assertEquals("丁杰", chineseName.displayName());
    }

    @Test
    void provisioningRawAttributesContainAdCreationFields() {
        UserDto user = new UserDto();
        user.setUserId("dingtalk-user-001");
        user.setNick("丁杰");
        user.setMobile("+8613800000000");

        DingTalkUserSyncTask.ProvisioningName name = DingTalkUserSyncTask.resolveProvisioningName(user.getNick());
        Map<String, List<String>> rawAttributes = DingTalkUserSyncTask.buildProvisioningRawAttributes(
                "dingjie",
                name,
                "dingjie@example.com",
                DingTalkIdentityProvider.formatMobile(user.getMobile()),
                user);

        assertEquals(List.of("dingjie"), rawAttributes.get(UserModel.USERNAME));
        assertEquals(List.of("dingjie@example.com"), rawAttributes.get(UserModel.EMAIL));
        assertFalse(rawAttributes.containsKey(UserModel.EMAIL_VERIFIED));
        assertEquals(List.of("丁杰"), rawAttributes.get(UserModel.FIRST_NAME));
        assertFalse(rawAttributes.containsKey(UserModel.LAST_NAME));
        assertEquals(List.of("丁杰"), rawAttributes.get("nickname"));
        assertEquals(List.of("dingtalk-user-001"), rawAttributes.get("dingtalk_userid"));
        assertEquals(List.of("13800000000"), rawAttributes.get("phoneNumber"));
        assertFalse(rawAttributes.containsKey(DingTalkUserSyncTask.DINGTALK_CREATED_BY_SYNC));
        assertFalse(rawAttributes.containsKey("mobile"));
        assertFalse(rawAttributes.containsKey("telephoneNumber"));
    }

    @Test
    void postCommitUserLookupBindsRealmContextForKeycloakOrganizationValidation() {
        AtomicBoolean realmResolved = new AtomicBoolean(false);
        AtomicBoolean realmBound = new AtomicBoolean(false);
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "master-id";
                    case "getName" -> "master";
                    case "toString" -> "master";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RealmProvider realms = (RealmProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRealm" -> {
                        assertEquals("master-id", args[0]);
                        realmResolved.set(true);
                        yield realm;
                    }
                    case "toString" -> "realms";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakContext context = (KeycloakContext) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setRealm" -> {
                        if (args[0] == realm) {
                            realmBound.set(true);
                        }
                        yield null;
                    }
                    case "toString" -> "context";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "realms" -> realms;
                    case "getContext" -> context;
                    case "toString" -> "session";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        RealmModel resolved = DingTalkUserSyncTask.resolveRealmAndBindContext(session, "master-id");

        assertSame(realm, resolved);
        assertTrue(realmResolved.get());
        assertTrue(realmBound.get());
    }

    @Test
    void syncCreatedUserSetsEmailVerifiedWithoutPasswordOrEnablement() throws Exception {
        List<String> calls = new ArrayList<>();
        UserModel user = (UserModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUsername" -> "dingjie";
                    case "getFirstAttribute" -> null;
                    case "setEmailVerified" -> {
                        calls.add("emailVerified:" + args[0]);
                        yield null;
                    }
                    case "setSingleAttribute" -> {
                        calls.add("attribute:" + args[0]);
                        yield null;
                    }
                    case "setEnabled" -> {
                        calls.add("enabled");
                        yield null;
                    }
                    case "toString" -> "dingjie";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProfile profile = (UserProfile) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProfile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "validate" -> {
                        calls.add("profile.validate");
                        yield null;
                    }
                    case "create" -> {
                        calls.add("profile.create");
                        yield user;
                    }
                    case "toString" -> "profile";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProfileProvider profileProvider = (UserProfileProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProfileProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "create" -> {
                        assertSame(UserProfileContext.USER_API, args[0]);
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> rawAttributes = (Map<String, List<String>>) args[1];
                        calls.add("profile.raw");
                        calls.add("raw:email:" + rawAttributes.get(UserModel.EMAIL).get(0));
                        calls.add("raw:hasEmailVerified:" + rawAttributes.containsKey(UserModel.EMAIL_VERIFIED));
                        calls.add("raw:firstName:" + rawAttributes.get(UserModel.FIRST_NAME).get(0));
                        calls.add("raw:hasLastName:" + rawAttributes.containsKey(UserModel.LAST_NAME));
                        calls.add("raw:nickname:" + rawAttributes.get("nickname").get(0));
                        calls.add("raw:dingtalk_userid:" + rawAttributes.get("dingtalk_userid").get(0));
                        calls.add("raw:phoneNumber:" + rawAttributes.get("phoneNumber").get(0));
                        calls.add("raw:hasCreatedBySync:" + rawAttributes.containsKey(
                                DingTalkUserSyncTask.DINGTALK_CREATED_BY_SYNC));
                        calls.add("raw:hasMobile:" + rawAttributes.containsKey("mobile"));
                        calls.add("raw:hasTelephoneNumber:" + rawAttributes.containsKey("telephoneNumber"));
                        yield profile;
                    }
                    case "close" -> null;
                    case "toString" -> "profileProvider";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserProvider users = (UserProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUserByUsername" -> null;
                    case "toString" -> "users";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "users" -> users;
                    case "getProvider" -> {
                        assertSame(UserProfileProvider.class, args[0]);
                        yield profileProvider;
                    }
                    case "toString" -> "session";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "master";
                    case "toString" -> "master";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setConfig(Map.of(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "@rzon.tech"));
        UserDto dingtalkUser = new UserDto();
        dingtalkUser.setNick("丁杰");
        dingtalkUser.setUserId("9633");
        dingtalkUser.setMobile("13800138000");

        Method createUser = DingTalkUserSyncTask.class.getDeclaredMethod(
                "createUser",
                KeycloakSession.class,
                RealmModel.class,
                IdentityProviderModel.class,
                UserDto.class,
                DingTalkWebhookNotifier.Batch.class);
        createUser.setAccessible(true);
        createUser.invoke(new DingTalkUserSyncTask(), session, realm, idp, dingtalkUser,
                DingTalkWebhookNotifier.Batch.disabled());

        assertTrue(calls.contains("raw:email:dingjie@rzon.tech"));
        assertTrue(calls.contains("raw:hasEmailVerified:false"));
        assertTrue(calls.contains("raw:firstName:丁杰"));
        assertTrue(calls.contains("raw:hasLastName:false"));
        assertTrue(calls.contains("raw:nickname:丁杰"));
        assertTrue(calls.contains("raw:dingtalk_userid:9633"));
        assertTrue(calls.contains("raw:phoneNumber:13800138000"));
        assertTrue(calls.contains("raw:hasCreatedBySync:false"));
        assertTrue(calls.contains("raw:hasMobile:false"));
        assertTrue(calls.contains("raw:hasTelephoneNumber:false"));
        assertTrue(calls.indexOf("profile.raw") < calls.indexOf("profile.create"));
        assertTrue(calls.contains("emailVerified:true"));
        assertTrue(calls.indexOf("profile.create") < calls.indexOf("emailVerified:true"));
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("attribute:")));
        assertFalse(calls.contains("enabled"));
    }

    @Test
    void createOnlyDetectionProtectsUnmanagedLinkedProvisionedUsers() {
        UserDto dingtalkUser = new UserDto();
        dingtalkUser.setNick("丁杰");
        dingtalkUser.setUserId("9633");
        Map<String, String> attributes = new HashMap<>();
        attributes.put("dingtalk_userid", "9633");
        UserModel unmanagedLinked = userWithReadOnlyAttributes("dingjie", attributes, Set.of());

        assertTrue(DingTalkUserSyncTask.shouldKeepCreateOnly(
                unmanagedLinked, "linked-identity", dingtalkUser, false));

        attributes.put(DingTalkUserSyncTask.DINGTALK_MANAGED, "true");
        assertFalse(DingTalkUserSyncTask.shouldKeepCreateOnly(
                unmanagedLinked, "linked-identity", dingtalkUser, false));
        assertFalse(DingTalkUserSyncTask.shouldKeepCreateOnly(
                unmanagedLinked, "phone:phoneNumber", dingtalkUser, false));
        assertTrue(DingTalkUserSyncTask.shouldKeepCreateOnly(
                unmanagedLinked, "created", dingtalkUser, true));
    }

    @Test
    void temporaryPasswordGeneratorMeetsAdComplexityShape() {
        String password = DingTalkCreatedUserInitializer.generateTemporaryPassword();

        assertEquals(DingTalkCreatedUserInitializer.TEMPORARY_PASSWORD_LENGTH, password.length());
        assertTrue(password.chars().anyMatch(Character::isUpperCase));
        assertTrue(password.chars().anyMatch(Character::isLowerCase));
        assertTrue(password.chars().anyMatch(Character::isDigit));
        assertTrue(password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch)));
    }

    @Test
    void createdUserInitializerResetsPasswordForcesUpdatePasswordAndEnablesUser() {
        AtomicReference<CredentialInput> credential = new AtomicReference<>();
        AtomicBoolean enabled = new AtomicBoolean(false);
        List<String> requiredActions = new ArrayList<>();
        SubjectCredentialManager credentialManager = (SubjectCredentialManager) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {SubjectCredentialManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "updateCredential" -> {
                        credential.set((CredentialInput) args[0]);
                        yield true;
                    }
                    case "toString" -> "credentialManager";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        UserModel user = (UserModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUsername" -> "dingjie";
                    case "credentialManager" -> credentialManager;
                    case "addRequiredAction" -> {
                        requiredActions.add(String.valueOf(args[0]));
                        yield null;
                    }
                    case "setEnabled" -> {
                        enabled.set((Boolean) args[0]);
                        yield null;
                    }
                    case "toString" -> "dingjie";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DingTalkCreatedUserInitializer.InitializationResult result =
                DingTalkCreatedUserInitializer.initializeExistingUser(
                        realmWithName("master"), "dingtalk", user, "Aa2!Aa2!Aa2!Aa2!");

        assertTrue(result.success());
        assertEquals("password", credential.get().getType());
        assertEquals("Aa2!Aa2!Aa2!Aa2!", credential.get().getChallengeResponse());
        assertTrue(requiredActions.contains(UserModel.RequiredAction.UPDATE_PASSWORD.toString()));
        assertTrue(enabled.get());
    }

    @Test
    void createdUserInitializerStoresChineseNamePartsWithoutProfileRootUpdates() {
        Map<String, String> attributes = new HashMap<>();
        UserModel user = (UserModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUsername" -> "dingjie";
                    case "getFirstName" -> "丁";
                    case "getFirstAttribute" -> "nickname".equals(args[0]) ? "丁杰" : null;
                    case "setSingleAttribute" -> {
                        assertFalse(UserModel.FIRST_NAME.equals(args[0]));
                        assertFalse(UserModel.LAST_NAME.equals(args[0]));
                        attributes.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    case "setFirstName", "setLastName" ->
                            throw new AssertionError("profile root setters must not run after activation");
                    case "toString" -> "dingjie";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        assertTrue(DingTalkCreatedUserInitializer.applyPostActivationNameMetadata(
                realmWithName("master"), "dingtalk", user));

        assertEquals("丁", attributes.get(DingTalkCreatedUserInitializer.DINGTALK_FIRST_NAME));
        assertEquals("杰", attributes.get(DingTalkCreatedUserInitializer.DINGTALK_LAST_NAME));
        assertFalse(attributes.containsKey(UserModel.FIRST_NAME));
        assertFalse(attributes.containsKey(UserModel.LAST_NAME));
    }

    @Test
    void createdUserInitializerSkipsUnsafeProfileNameSplitInputs() {
        assertFalse(DingTalkCreatedUserInitializer.splitChineseDisplayName(null).hasBoth());
        assertFalse(DingTalkCreatedUserInitializer.splitChineseDisplayName("A").hasBoth());
        assertFalse(DingTalkCreatedUserInitializer.splitChineseDisplayName("John Smith").hasBoth());

        DingTalkCreatedUserInitializer.NameParts nameParts =
                DingTalkCreatedUserInitializer.splitChineseDisplayName("丁 杰");

        assertEquals("丁", nameParts.firstName());
        assertEquals("杰", nameParts.lastName());
    }

    @Test
    void periodicSyncConfigParsersUseSafeDefaults() {
        assertFalse(DingTalkUserSyncTask.isPeriodicSyncEnabled(null));
        assertFalse(DingTalkUserSyncTask.isPeriodicSyncEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isPeriodicSyncEnabled(Map.of("periodicSyncEnabled", "true")));
        assertFalse(DingTalkUserSyncTask.isCreateUsersEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isCreateUsersEnabled(Map.of("periodicSyncCreateUsers", "true")));
        assertFalse(DingTalkCreatedUserInitializer.isEnabled(Map.of()));
        assertTrue(DingTalkCreatedUserInitializer.isEnabled(
                Map.of(DingTalkCreatedUserInitializer.INITIALIZE_CREATED_USERS, "true")));
        assertFalse(DingTalkUserSyncTask.isDisableMissingUsersEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isDisableMissingUsersEnabled(
                Map.of("periodicSyncDisableMissingUsers", "true")));
        assertFalse(DingTalkUserSyncTask.isDisableExternalUsersEnabled(Map.of()));
        assertTrue(DingTalkUserSyncTask.isDisableExternalUsersEnabled(
                Map.of("periodicSyncDisableExternalUsers", "true")));
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
        assertFalse(DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(Map.of()));
        assertTrue(DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(
                Map.of("syncGetDebugEnabled", "true")));
        assertFalse(DingTalkWebhookNotifier.isEnabled(Map.of()));
        assertFalse(DingTalkWebhookNotifier.isEnabled(Map.of(
                "notificationWebhookEnabled", "true")));
        assertTrue(DingTalkWebhookNotifier.isEnabled(Map.of(
                "notificationWebhookEnabled", "true",
                "notificationWebhookUrl", "https://oapi.dingtalk.com/robot/send?access_token=token")));

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

    @Test
    void aggregateSyncResultsReportsSkippedOnlyWhenEveryProviderSkipped() {
        DingTalkUserSyncTask.SyncResult first =
                DingTalkUserSyncTask.SyncResult.skipped("dingtalk-a", "periodic sync disabled");
        DingTalkUserSyncTask.SyncResult second =
                DingTalkUserSyncTask.SyncResult.skipped("dingtalk-b", "periodic sync interval not reached");

        DingTalkUserSyncTask.SyncResult allSkipped =
                DingTalkUserSyncTask.SyncResult.aggregate("*", List.of(first, second));

        assertTrue(allSkipped.skipped());
        assertEquals("periodic sync disabled", allSkipped.reason());

        DingTalkUserSyncTask.SyncResult ran =
                new DingTalkUserSyncTask.SyncResult("dingtalk-c", 1, 1, 0, 0, 0, 0, 0, false, null);
        DingTalkUserSyncTask.SyncResult mixed =
                DingTalkUserSyncTask.SyncResult.aggregate("*", List.of(first, ran));

        assertFalse(mixed.skipped());
        assertEquals(1, mixed.listed());
        assertEquals(1, mixed.matched());
        assertNull(mixed.reason());
    }

    @Test
    void readOnlyDingTalkMetadataDoesNotBlockPhoneSyncField() {
        Map<String, String> attributes = new HashMap<>();
        UserModel user = userWithReadOnlyAttributes(
                "ldap-user",
                attributes,
                Set.of(
                        DingTalkUserSyncTask.DINGTALK_MANAGED,
                        DingTalkUserSyncTask.DINGTALK_IDP_ALIAS,
                        "dingtalk_external_id",
                        "dingtalk_last_sync",
                        "dingtalk_last_sync_at",
                        "dingtalk_userid",
                        "dingtalk_unionid",
                        "dingtalk_openid",
                        "dingtalk_corpid",
                        "nickname"));

        UserDto dingtalkUser = new UserDto();
        dingtalkUser.setUserId("dingtalk-user");
        dingtalkUser.setUnionId("union-id");
        dingtalkUser.setOpenId("open-id");
        dingtalkUser.setMobile("+8613800000000");
        dingtalkUser.setNick("钉钉用户");

        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");

        DingTalkUserSyncTask task = new DingTalkUserSyncTask();
        task.markManagedUser(user, "master", idp, dingtalkUser, "corp-id", 1777188541L);

        assertTrue(task.applyUserUpdates(
                user,
                "master",
                "dingtalk",
                dingtalkUser,
                "corp-id",
                Set.of("phone"),
                false));
        assertEquals("13800000000", attributes.get("phoneNumber"));
    }

    @Test
    void usernameLockMetadataRecordsCreateTimeUsernameDecision() {
        Map<String, String> attributes = new HashMap<>();
        UserModel user = userWithReadOnlyAttributes("created-user", attributes, Set.of());

        assertTrue(DingTalkUserSyncTask.markUsernameLocked(
                user,
                "master",
                "dingtalk",
                "zhangdan",
                DingTalkUserSyncTask.USERNAME_SOURCE_AUTO_PINYIN));

        assertEquals("true", attributes.get(DingTalkUserSyncTask.DINGTALK_USERNAME_LOCKED));
        assertEquals(DingTalkUserSyncTask.USERNAME_SOURCE_AUTO_PINYIN,
                attributes.get(DingTalkUserSyncTask.DINGTALK_USERNAME_SOURCE));
        assertEquals("zhangdan", attributes.get(DingTalkUserSyncTask.DINGTALK_USERNAME_SUGGESTED));
        assertTrue(DingTalkWebhookNotifier.CREATED_USERNAME_REVIEW_ACTION.contains("用户名"));
        assertTrue(DingTalkWebhookNotifier.CREATED_USERNAME_REVIEW_ACTION.contains("邮箱"));
    }

    @Test
    void reenableCandidateRequiresPreviousMissingUserDisableReason() {
        Map<String, String> disabledBySyncAttributes = new HashMap<>();
        disabledBySyncAttributes.put("enabled", "false");
        disabledBySyncAttributes.put("dingtalk_disabled_reason", "missing_from_dingtalk");
        UserModel disabledBySync = userWithReadOnlyAttributes("returning-user", disabledBySyncAttributes, Set.of());

        Map<String, String> newlyCreatedAttributes = new HashMap<>();
        newlyCreatedAttributes.put("enabled", "false");
        newlyCreatedAttributes.put(DingTalkUserSyncTask.DINGTALK_CREATED_BY_SYNC, "true");
        UserModel newlyCreated = userWithReadOnlyAttributes("newly-created-user", newlyCreatedAttributes, Set.of());

        assertTrue(DingTalkUserSyncTask.isReenableCandidate(disabledBySync, false));
        assertFalse(DingTalkUserSyncTask.isReenableCandidate(disabledBySync, true));
        assertFalse(DingTalkUserSyncTask.isReenableCandidate(newlyCreated, false));
    }

    @Test
    void activeDingTalkUsersMatchExternalUsersByStableIdentifiers() {
        DingTalkUserSyncTask task = new DingTalkUserSyncTask();
        DingTalkUserSyncTask.ActiveDingTalkUsers activeUsers = task.new ActiveDingTalkUsers();
        UserDto dingtalkUser = new UserDto();
        dingtalkUser.setNick("张三");
        dingtalkUser.setEmail("zhangsan@example.com");
        dingtalkUser.setMobile("+8613800000000");
        dingtalkUser.setUserId("ding-user-001");
        activeUsers.add(dingtalkUser);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("email", "ZHANGSAN@example.com");
        attributes.put("phoneNumber", "13800000000");
        UserModel user = userWithReadOnlyAttributes("manual-username", attributes, Set.of());

        assertTrue(activeUsers.hasExternalId("DING-USER-001"));
        assertTrue(activeUsers.matchesUser(user));

        UserModel missing = userWithReadOnlyAttributes("missing-user", Map.of(), Set.of());
        assertFalse(activeUsers.matchesUser(missing));
    }

    @Test
    void missingUserScanUsesFullUserSearchOnlyForExplicitExternalDisable() {
        DingTalkUserSyncTask task = new DingTalkUserSyncTask();
        DingTalkUserSyncTask.ActiveDingTalkUsers activeUsers = task.new ActiveDingTalkUsers();
        UserDto activeDingTalkUser = new UserDto();
        activeDingTalkUser.setUserId("active-dingtalk-user");
        activeUsers.add(activeDingTalkUser);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(DingTalkUserSyncTask.DINGTALK_MANAGED, "true");
        attributes.put("dingtalk_external_id", "missing-dingtalk-user");
        UserModel missing = userWithReadOnlyAttributes("left-user", attributes, Set.of());

        Map<String, String> externalAttributes = new HashMap<>();
        externalAttributes.put("federationLink", "ldap-provider");
        UserModel missingExternal = userWithReadOnlyAttributes("left-external-user", externalAttributes, Set.of());
        UserModel machineExternal = userWithReadOnlyAttributes("drive$", externalAttributes, Set.of());

        AtomicBoolean attributeSearchCalled = new AtomicBoolean(false);
        AtomicBoolean fullSearchCalled = new AtomicBoolean(false);
        UserProvider users = (UserProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "searchForUserByUserAttributeStream" -> {
                        attributeSearchCalled.set(true);
                        assertEquals(DingTalkUserSyncTask.DINGTALK_IDP_ALIAS, args[1]);
                        assertEquals("dingtalk", args[2]);
                        yield Stream.of(missing);
                    }
                    case "searchForUserStream" -> {
                        fullSearchCalled.set(true);
                        yield Stream.of(missingExternal, machineExternal);
                    }
                    case "getFederatedIdentity" -> null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "users" -> users;
                    case "isClosed" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "master";
                    case "getStorageProviders" -> {
                        assertEquals(UserStorageProvider.class, args[0]);
                        yield Stream.empty();
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");

        List<UserModel> managedOnly = task.findMissingManagedUsers(session, realm, idp, activeUsers, false);

        assertTrue(attributeSearchCalled.get());
        assertFalse(fullSearchCalled.get());
        assertEquals(List.of(missing), managedOnly);

        List<UserModel> withExternal = task.findMissingManagedUsers(session, realm, idp, activeUsers, true);

        assertTrue(fullSearchCalled.get());
        assertEquals(List.of(missing, missingExternal), withExternal);
    }

    @Test
    void missingUserScanSkipsExternalFullSearchWhenInvalidUserCleanupEnabled() {
        DingTalkUserSyncTask task = new DingTalkUserSyncTask();
        DingTalkUserSyncTask.ActiveDingTalkUsers activeUsers = task.new ActiveDingTalkUsers();
        UserDto activeDingTalkUser = new UserDto();
        activeDingTalkUser.setUserId("active-dingtalk-user");
        activeUsers.add(activeDingTalkUser);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(DingTalkUserSyncTask.DINGTALK_MANAGED, "true");
        attributes.put("dingtalk_external_id", "missing-dingtalk-user");
        UserModel missing = userWithReadOnlyAttributes("left-user", attributes, Set.of());

        AtomicBoolean fullSearchCalled = new AtomicBoolean(false);
        UserProvider users = (UserProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "searchForUserByUserAttributeStream" -> Stream.of(missing);
                    case "searchForUserStream" -> {
                        fullSearchCalled.set(true);
                        yield Stream.empty();
                    }
                    case "getFederatedIdentity" -> null;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "users" -> users;
                    case "isClosed" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        ComponentModel storageProvider = new ComponentModel();
        storageProvider.setName("Rzon AD");
        storageProvider.put(UserStorageProviderModel.REMOVE_INVALID_USERS_ENABLED, true);
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "master";
                    case "getStorageProviders" -> Stream.of(storageProvider);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");

        List<UserModel> candidates = task.findMissingManagedUsers(session, realm, idp, activeUsers, true);

        assertFalse(fullSearchCalled.get());
        assertEquals(List.of(missing), candidates);
    }

    @Test
    void endpointReferenceConfigUsesUrlTypeAndFormConfigValue() {
        ProviderConfigProperty property = new DingTalkIdentityProviderFactory().getConfigProperties().stream()
                .filter(candidate -> DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_FORM_FIELD
                        .equals(candidate.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(ProviderConfigProperty.URL_TYPE, property.getType());
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL, property.getHelpText());
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL, property.getDefaultValue());
        assertTrue(property.isReadOnly());
        assertTrue(new DingTalkIdentityProviderFactory().getConfigProperties().stream()
                .anyMatch(candidate -> DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN.equals(candidate.getName())));
        assertTrue(new DingTalkIdentityProviderFactory().getConfigProperties().stream()
                .anyMatch(candidate -> DingTalkCreatedUserInitializer.INITIALIZE_CREATED_USERS.equals(
                        candidate.getName())));

        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setConfig(new HashMap<>(Map.of("existing", "value")));
        assertTrue(DingTalkIdentityProviderFactory.ensureEndpointReferenceConfig(idp));
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL,
                idp.getConfig().get(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_CONFIG));
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL,
                idp.getConfig().get(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_HREF_CONFIG));
        assertEquals("value", idp.getConfig().get("existing"));
        assertFalse(DingTalkIdentityProviderFactory.ensureEndpointReferenceConfig(idp));

        IdentityProviderModel config = new DingTalkIdentityProviderFactory().createConfig();
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL,
                config.getConfig().get(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_CONFIG));
        assertEquals(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_URL,
                config.getConfig().get(DingTalkIdentityProviderFactory.ENDPOINT_REFERENCE_PAGE_HREF_CONFIG));
    }

    @Test
    void webhookSigningAndRobotResponseParsingAreSafe() {
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=token",
                DingTalkWebhookNotifier.signedWebhookUrl(
                        "https://oapi.dingtalk.com/robot/send?access_token=token", ""));

        String signedUrl = DingTalkWebhookNotifier.signedWebhookUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=token", "secret");
        assertTrue(signedUrl.startsWith(
                "https://oapi.dingtalk.com/robot/send?access_token=token&timestamp="));
        assertTrue(signedUrl.contains("&sign="));

        DingTalkWebhookNotifier.SendResult success =
                DingTalkWebhookNotifier.parseRobotResponse("{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertTrue(success.success());

        DingTalkWebhookNotifier.SendResult failure =
                DingTalkWebhookNotifier.parseRobotResponse("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}");
        assertFalse(failure.success());
        assertTrue(failure.error().contains("310000"));
        assertTrue(failure.response().contains("\"errmsg\":\"keywords not in content\""));
    }

    @Test
    void syncWebhookMarkdownUsesReadableSections() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setConfig(Map.of(
                DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_ENABLED, "true",
                DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_URL,
                "https://oapi.dingtalk.com/robot/send?access_token=token",
                DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "rzon.tech"));

        DingTalkWebhookNotifier.Batch batch = DingTalkWebhookNotifier.syncBatch(
                null, realmWithName("master"), idp, "manual", false);
        batch.addCreatedUser("dingjie",
                "userId=9633, externalId=F5TbiEiE, nickname=丁杰, hasPhone=true, hasEmail=false");

        String markdown = batch.toMarkdown();

        assertTrue(markdown.contains("### 汇总"));
        assertTrue(markdown.contains("- 新创建：1"));
        assertTrue(markdown.contains("> **领域**：`master`"));
        assertTrue(markdown.contains("> **身份源**：`dingtalk`"));
        assertTrue(markdown.contains("> **执行方式**：`手动同步`"));
        assertTrue(markdown.contains("- 邮箱规则：`用户名 + @rzon.tech`"));
        assertTrue(markdown.contains("### 新创建用户"));
        assertTrue(markdown.contains("#### 用户名：`dingjie`"));
        assertTrue(markdown.contains("- 昵称：`丁杰`"));
        assertTrue(markdown.contains("- 钉钉标识：`钉钉用户ID=9633, 外部ID=F5TbiEiE`"));
        assertTrue(markdown.contains("- 手机：有"));
        assertTrue(markdown.contains("- 邮箱：无"));
        assertFalse(markdown.contains("Created: 1"));
        assertFalse(markdown.contains("Realm"));
        assertFalse(markdown.contains("IdP"));
        assertFalse(markdown.contains("Mode"));
        assertFalse(markdown.contains("WARN"));
        assertFalse(markdown.contains("userId="));
        assertFalse(markdown.contains("hasPhone=true"));
    }

    @Test
    void syncWebhookUnconfirmedMarkdownNamesPostCommitVisibilityProblem() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setConfig(Map.of(
                DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_ENABLED, "true",
                DingTalkWebhookNotifier.NOTIFICATION_WEBHOOK_URL,
                "https://oapi.dingtalk.com/robot/send?access_token=token",
                DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "rzon.tech"));

        DingTalkWebhookNotifier.Batch batch = DingTalkWebhookNotifier.syncBatch(
                null, realmWithName("master"), idp, "manual", false);
        batch.addCreatedUser("dingjie",
                "userId=9633, externalId=F5TbiEiE, nickname=丁杰, hasPhone=true, hasEmail=false");

        String markdown = batch.toUnconfirmedMarkdown(
                new DingTalkWebhookNotifier.CreatedUserVerification(List.of("dingjie"), ""));

        assertTrue(markdown.contains("Keycloak 钉钉同步创建未确认告警"));
        assertTrue(markdown.contains("事务提交后，部分新创建用户无法确认可查询。"));
        assertTrue(markdown.contains("### 未确认用户名"));
        assertTrue(markdown.contains("- `dingjie`"));
        assertTrue(markdown.contains("- 未确认原因：`用户不存在`"));
        assertTrue(markdown.contains("- 昵称：`丁杰`"));
        assertTrue(markdown.contains("- 钉钉标识：`钉钉用户ID=9633, 外部ID=F5TbiEiE`"));
        assertFalse(markdown.contains("hasEmail=false"));
        assertFalse(markdown.contains("userId="));
    }

    @Test
    void loginCreatedWebhookMarkdownUsesSameReadableLayout() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setConfig(Map.of(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "rzon.tech"));

        String markdown = DingTalkWebhookNotifier.loginUserCreatedMarkdown(
                "master",
                idp,
                "dingjie",
                "userId=9633, externalId=F5TbiEiE, nickname=丁杰, hasPhone=true, hasEmail=false");

        assertTrue(markdown.contains("Keycloak 钉钉登录创建用户"));
        assertTrue(markdown.contains("#### 用户名：`dingjie`"));
        assertTrue(markdown.contains("- 昵称：`丁杰`"));
        assertTrue(markdown.contains("- 钉钉标识：`钉钉用户ID=9633, 外部ID=F5TbiEiE`"));
        assertTrue(markdown.contains("- 手机：有"));
        assertTrue(markdown.contains("- 邮箱：无"));
        assertTrue(markdown.contains("- 邮箱规则：`用户名 + @rzon.tech`"));
        assertFalse(markdown.contains("hasEmail=false"));
        assertFalse(markdown.contains("userId="));
    }

    @Test
    void loginCreatedWebhookCanRenderRollbackAndUnconfirmedWarnings() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setConfig(Map.of(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN, "rzon.tech"));

        String dingtalkUser = "userId=9633, externalId=F5TbiEiE, nickname=丁杰, hasPhone=true, hasEmail=false";
        String rollback = DingTalkWebhookNotifier.loginUserCreatedRollbackMarkdown(
                "master", idp, "dingjie", dingtalkUser);
        String unconfirmed = DingTalkWebhookNotifier.loginUserCreatedUnconfirmedMarkdown(
                "master",
                idp,
                "dingjie",
                dingtalkUser,
                new DingTalkWebhookNotifier.CreatedUserVerification(List.of("dingjie"), ""));

        assertTrue(rollback.contains("Keycloak 钉钉登录创建未落库告警"));
        assertTrue(rollback.contains("事务已回滚，用户没有落库。"));
        assertTrue(unconfirmed.contains("Keycloak 钉钉登录创建未确认告警"));
        assertTrue(unconfirmed.contains("事务提交后未能确认该用户可查询。"));
        assertTrue(unconfirmed.contains("### 未确认用户名"));
        assertTrue(unconfirmed.contains("- `dingjie`"));
        assertTrue(unconfirmed.contains("- 未确认原因：`用户不存在`"));
        assertFalse(unconfirmed.contains("hasEmail=false"));
        assertFalse(unconfirmed.contains("userId="));
    }

    @Test
    void syncFailureWebhookMarkdownSanitizesSensitiveReason() {
        String text = DingTalkWebhookNotifier.syncFailureMarkdown(
                "master",
                "dingtalk",
                "browser",
                new IllegalStateException(
                        "mobile=13800000000 email=user@example.com access_token=token-123 clientSecret=secret-456\n"
                                + "userid=ding-user"));

        assertTrue(text.contains("Keycloak 钉钉同步失败告警"));
        assertTrue(text.contains("- 执行方式：浏览器同步"));
        assertTrue(text.contains("- 异常类型：IllegalStateException"));
        assertFalse(text.contains("13800000000"));
        assertFalse(text.contains("user@example.com"));
        assertFalse(text.contains("token-123"));
        assertFalse(text.contains("secret-456"));
        assertFalse(text.contains("ding-user"));
        assertTrue(text.contains("mobile=***"));
        assertTrue(text.contains("email=***"));
        assertTrue(text.contains("access_token=***"));

        String duplicateEmailText = DingTalkWebhookNotifier.syncFailureMarkdown(
                "master",
                "dingtalk",
                "browser",
                new IllegalStateException(
                        "Can't import user because email 'employee@example.com' already exists; mobile 13800000000"));
        assertFalse(duplicateEmailText.contains("employee@example.com"));
        assertFalse(duplicateEmailText.contains("13800000000"));
    }

    @Test
    void browserCleanupPreviewHidesStorageExceptionMessage() {
        IdentityProviderModel idp = new IdentityProviderModel();
        idp.setAlias("dingtalk");
        idp.setProviderId(DingTalkIdentityProviderFactory.PROVIDER_ID);
        idp.setEnabled(true);
        idp.setConfig(Map.of(
                DingTalkIdentityProviderFactory.SYNC_GET_DEBUG_ENABLED, "true",
                DingTalkIdentityProviderFactory.BROWSER_SYNC_DEBUG_KEY, "secret-key"));

        RuntimeException storageFailure = new RuntimeException(
                "LDAP failure mobile=13800000000 email=employee@example.com access_token=token-123");
        UserProvider users = (UserProvider) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserProvider.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "searchForUserByUserAttributeStream" -> throw storageFailure;
                    case "toString" -> "users";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        RealmModel realm = (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "master";
                    case "getIdentityProvidersStream" -> Stream.of(idp);
                    case "toString" -> "master";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakContext context = (KeycloakContext) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRealm" -> realm;
                    case "toString" -> "context";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        KeycloakSession session = (KeycloakSession) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {KeycloakSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContext" -> context;
                    case "users" -> users;
                    case "toString" -> "session";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        DingTalkSyncBrowserResource.BrowserJsonResponse response = new DingTalkSyncBrowserResource(session)
                .previewSyncCreatedUserCleanupJson("dingtalk", "secret-key");

        assertEquals(Response.Status.INTERNAL_SERVER_ERROR, response.status());
        Map<String, Object> entity = response.body();
        assertEquals("cleanup_preview_failed", entity.get("error"));
        assertEquals("dingtalk", entity.get("alias"));
        assertEquals(true, entity.get("dryRun"));
        assertEquals("Cleanup preview failed. Check Keycloak server logs for details.", entity.get("message"));

        String entityText = entity.toString();
        assertFalse(entityText.contains("13800000000"));
        assertFalse(entityText.contains("employee@example.com"));
        assertFalse(entityText.contains("token-123"));
        assertFalse(entityText.contains("LDAP failure"));
    }

    private static RealmModel realmWithName(String name) {
        return (RealmModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> name;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }

                    return switch (method.getName()) {
                        case "getName" -> name;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static UserModel userWithReadOnlyAttributes(
            String username, Map<String, String> attributes, Set<String> readOnlyAttributes) {
        return (UserModel) Proxy.newProxyInstance(
                DingTalkIdentityProviderTest.class.getClassLoader(),
                new Class<?>[] {UserModel.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> username;
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }

                    return switch (method.getName()) {
                        case "getId" -> "id-" + username;
                        case "getUsername" -> username;
                        case "isEnabled" -> !"false".equals(attributes.get("enabled"));
                        case "setEnabled" -> null;
                        case "getFederationLink" -> attributes.get("federationLink");
                        case "getServiceAccountClientLink" -> attributes.get("serviceAccountClientLink");
                        case "getFirstAttribute" -> attributes.get((String) args[0]);
                        case "setSingleAttribute" -> {
                            String attributeName = (String) args[0];
                            if (readOnlyAttributes.contains(attributeName)) {
                                throw new ReadOnlyException("read-only " + attributeName);
                            }
                            attributes.put(attributeName, (String) args[1]);
                            yield null;
                        }
                        case "getEmail" -> attributes.get("email");
                        case "setEmail" -> {
                            if (readOnlyAttributes.contains("email")) {
                                throw new ReadOnlyException("read-only email");
                            }
                            attributes.put("email", (String) args[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
