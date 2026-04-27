package com.ainuoyan.keycloak.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Stream;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ReadOnlyException;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.timer.ScheduledTask;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileContext;
import org.keycloak.userprofile.UserProfileProvider;

/**
 * 定期把钉钉通讯录信息同步到已存在的 Keycloak 用户。
 */
public class DingTalkUserSyncTask implements ScheduledTask {

    static final String TASK_NAME = "dingtalk-user-sync";
    static final String PERIODIC_SYNC_ENABLED = "periodicSyncEnabled";
    static final String PERIODIC_SYNC_PERIOD_SECONDS = "periodicSyncPeriodSeconds";
    static final String PERIODIC_SYNC_DEPARTMENT_IDS = "periodicSyncDepartmentIds";
    static final String PERIODIC_SYNC_FIELDS = "periodicSyncFields";
    static final String PERIODIC_SYNC_OVERWRITE_EXISTING = "periodicSyncOverwriteExisting";
    static final String PERIODIC_SYNC_CREATE_USERS = "periodicSyncCreateUsers";
    static final String PERIODIC_SYNC_DISABLE_MISSING_USERS = "periodicSyncDisableMissingUsers";
    static final String PERIODIC_SYNC_DISABLE_EXTERNAL_USERS = "periodicSyncDisableExternalUsers";
    static final String PERIODIC_SYNC_REENABLE_USERS = "periodicSyncReenableUsers";
    static final String PERIODIC_SYNC_DETAILED_LOG = "periodicSyncDetailedLog";
    static final String PERIODIC_SYNC_INCLUDE_CHILD_DEPARTMENTS = "periodicSyncIncludeChildDepartments";
    static final String PROVISIONED_EMAIL_DOMAIN = "provisionedEmailDomain";
    static final String LAST_PERIODIC_SYNC = "lastPeriodicSync";

    private static final Logger logger = Logger.getLogger(DingTalkUserSyncTask.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROVIDER_ID = DingTalkIdentityProviderFactory.PROVIDER_ID;
    private static final String CORP_TOKEN_URL = "https://oapi.dingtalk.com/gettoken";
    private static final String USER_LIST_URL = "https://oapi.dingtalk.com/topapi/v2/user/list";
    private static final String DEPARTMENT_SUB_IDS_URL = "https://oapi.dingtalk.com/topapi/v2/department/listsubid";
    private static final String PHONE_NUMBER = "phoneNumber";
    private static final String NICK_NAME = "nickname";
    private static final String OPEN_ID = "dingtalk_openid";
    private static final String UNION_ID = "dingtalk_unionid";
    private static final String DINGTALK_USER_ID = "dingtalk_userid";
    private static final String CORP_ID = "dingtalk_corpid";
    static final String DINGTALK_MANAGED = "dingtalk_managed";
    static final String DINGTALK_IDP_ALIAS = "dingtalk_idp_alias";
    private static final String DINGTALK_EXTERNAL_ID = "dingtalk_external_id";
    private static final String DINGTALK_LAST_SYNC = "dingtalk_last_sync";
    private static final String DINGTALK_LAST_SYNC_AT = "dingtalk_last_sync_at";
    static final String DINGTALK_CREATED_BY_SYNC = "dingtalk_created_by_sync";
    static final String DINGTALK_USERNAME_LOCKED = "dingtalk_username_locked";
    static final String DINGTALK_USERNAME_SOURCE = "dingtalk_username_source";
    static final String DINGTALK_USERNAME_SUGGESTED = "dingtalk_username_suggested";
    private static final String DINGTALK_DISABLED_REASON = "dingtalk_disabled_reason";
    private static final String DINGTALK_DISABLED_REASON_MISSING = "missing_from_dingtalk";
    static final String USERNAME_SOURCE_AUTO_PINYIN = "auto-pinyin";
    static final String USERNAME_SOURCE_LOGIN_AUTO_PINYIN = "login-auto-pinyin";
    private static final List<String> PHONE_ATTRIBUTE_NAMES = List.of("phoneNumber", "mobile", "telephoneNumber");
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BEIJING_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'CST'").withZone(BEIJING_ZONE);

    @Override
    public void run(KeycloakSession session) {
        List<String> realmIds = session.realms().getRealmsStream()
                .map(RealmModel::getId)
                .toList();

        for (String realmId : realmIds) {
            RealmModel realm = session.realms().getRealm(realmId);
            if (realm != null) {
                withRealmContext(session, realm, () -> syncRealm(session, realm));
            }
        }
    }

    @Override
    public String getTaskName() {
        return TASK_NAME;
    }

    private void syncRealm(KeycloakSession session, RealmModel realm) {
        List<IdentityProviderModel> providers = realm.getIdentityProvidersStream()
                .filter(idp -> PROVIDER_ID.equals(idp.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .toList();

        for (IdentityProviderModel idp : providers) {
            try {
                syncProvider(session, realm, idp, false);
            } catch (Exception e) {
                logger.errorf(e, "DingTalk periodic sync failed. realm=%s, idp=%s",
                        realm.getName(), idp.getAlias());
                DingTalkWebhookNotifier.notifySyncFailed(session, realm, idp, "periodic", false, e);
            }
        }
    }

    private void withRealmContext(KeycloakSession session, RealmModel realm, Runnable task) {
        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            task.run();
        } finally {
            restorePreviousRealm(session, previousRealm);
        }
    }

    private void restorePreviousRealm(KeycloakSession session, RealmModel previousRealm) {
        session.getContext().setRealm(previousRealm);
    }

    SyncResult syncProviderNow(KeycloakSession session, RealmModel realm, String alias) throws Exception {
        return syncProviderNow(session, realm, alias, "manual");
    }

    SyncResult syncProviderNow(KeycloakSession session, RealmModel realm, String alias, String failureNotificationMode)
            throws Exception {
        List<IdentityProviderModel> providers = realm.getIdentityProvidersStream()
                .filter(idp -> PROVIDER_ID.equals(idp.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(idp -> StringUtils.isBlank(alias) || alias.equals(idp.getAlias()))
                .toList();

        if (providers.isEmpty()) {
            throw new IllegalArgumentException("No enabled DingTalk identity provider matched alias: " + alias);
        }

        List<SyncResult> results = new ArrayList<>();
        for (IdentityProviderModel idp : providers) {
            try {
                results.add(syncProvider(session, realm, idp, true, false));
            } catch (Exception e) {
                DingTalkWebhookNotifier.notifySyncFailed(session, realm, idp, failureNotificationMode, false, e);
                throw e;
            }
        }
        return SyncResult.aggregate(StringUtils.defaultIfBlank(alias, "*"), results);
    }

    SyncResult previewProviderNow(KeycloakSession session, RealmModel realm, String alias) throws Exception {
        List<IdentityProviderModel> providers = realm.getIdentityProvidersStream()
                .filter(idp -> PROVIDER_ID.equals(idp.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(idp -> StringUtils.isBlank(alias) || alias.equals(idp.getAlias()))
                .toList();

        if (providers.isEmpty()) {
            throw new IllegalArgumentException("No enabled DingTalk identity provider matched alias: " + alias);
        }

        List<SyncResult> results = new ArrayList<>();
        for (IdentityProviderModel idp : providers) {
            results.add(syncProvider(session, realm, idp, true, true));
        }
        return SyncResult.aggregate(StringUtils.defaultIfBlank(alias, "*"), results);
    }

    private SyncResult syncProvider(KeycloakSession session, RealmModel realm,
                                    IdentityProviderModel idp, boolean force) throws Exception {
        return syncProvider(session, realm, idp, force, false);
    }

    private SyncResult syncProvider(KeycloakSession session, RealmModel realm,
                                    IdentityProviderModel idp, boolean force, boolean dryRun) throws Exception {
        Map<String, String> config = idp.getConfig();
        if (!force && !isPeriodicSyncEnabled(config)) {
            return SyncResult.skipped(idp.getAlias(), "periodic sync disabled");
        }

        long now = System.currentTimeMillis() / 1000;
        long periodSeconds = parsePositiveLong(config.get(PERIODIC_SYNC_PERIOD_SECONDS), 3600);
        long lastSync = parsePositiveLong(config.get(LAST_PERIODIC_SYNC), 0);
        if (!force && lastSync > 0 && now - lastSync < periodSeconds) {
            return SyncResult.skipped(idp.getAlias(), "periodic sync interval not reached");
        }

        String clientId = config.get("clientId");
        String clientSecret = config.get("clientSecret");
        if (StringUtils.isAnyBlank(clientId, clientSecret)) {
            logger.warnf("Skip DingTalk periodic sync: clientId/clientSecret is missing. realm=%s, idp=%s",
                    realm.getName(), idp.getAlias());
            return SyncResult.skipped(idp.getAlias(), "clientId/clientSecret missing");
        }

        Set<String> syncFields = parseSyncFields(config.get(PERIODIC_SYNC_FIELDS));
        List<String> matchRules = DingTalkIdentityProvider.parseMatchRules(config.get("matchRules"));
        boolean createUsers = isCreateUsersEnabled(config);
        boolean disableMissingUsers = isDisableMissingUsersEnabled(config);
        boolean disableExternalUsers = isDisableExternalUsersEnabled(config);
        boolean reenableUsers = isReenableUsersEnabled(config);
        boolean detailedLog = isDetailedLogEnabled(config);
        boolean includeChildDepartments = isIncludeChildDepartmentsEnabled(config);
        boolean overwriteExisting = Boolean.parseBoolean(
                config.getOrDefault(PERIODIC_SYNC_OVERWRITE_EXISTING, "false"));

        String corpAccessToken = getCorpAccessToken(session, clientId, clientSecret);
        if (StringUtils.isBlank(corpAccessToken)) {
            return SyncResult.skipped(idp.getAlias(), "failed to get corp access token");
        }

        if (detailedLog) {
            logger.infof("DingTalk sync detail started. realm=%s, idp=%s, mode=%s, dryRun=%s, departments=%s, includeChildDepartments=%s, matchRules=%s, syncFields=%s, createUsers=%s, disableMissingUsers=%s, disableExternalUsers=%s, overwriteExisting=%s",
                    realm.getName(), idp.getAlias(), force ? "manual" : "periodic", dryRun,
                    parseDepartmentIds(config.get(PERIODIC_SYNC_DEPARTMENT_IDS)), includeChildDepartments,
                    matchRules, syncFields, createUsers, disableMissingUsers, disableExternalUsers, overwriteExisting);
        }
        DingTalkWebhookNotifier.Batch notifications = DingTalkWebhookNotifier.syncBatch(
                session, realm, idp, force ? "manual" : "periodic", dryRun);

        int listed = 0;
        int matched = 0;
        int linked = 0;
        int updated = 0;
        int created = 0;
        int disabled = 0;
        int reenabled = 0;
        boolean allDepartmentsLoaded = true;
        ActiveDingTalkUsers activeUsers = new ActiveDingTalkUsers();
        Set<String> seenUserKeys = new HashSet<>();
        List<UserDto> dingtalkUsers = new ArrayList<>();
        String corpId = DingTalkIdentityProvider.resolveEnterpriseId(config, null);
        DepartmentPlan departmentPlan = resolveDepartmentsToSync(
                session,
                corpAccessToken,
                parseDepartmentIds(config.get(PERIODIC_SYNC_DEPARTMENT_IDS)),
                includeChildDepartments);
        allDepartmentsLoaded &= departmentPlan.successful();

        if (detailedLog) {
            logger.infof("DingTalk sync detail departments resolved. realm=%s, idp=%s, count=%d, successful=%s, departments=%s",
                    realm.getName(), idp.getAlias(), departmentPlan.departmentIds().size(),
                    departmentPlan.successful(), departmentPlan.departmentIds());
        }

        for (String departmentId : departmentPlan.departmentIds()) {
            DepartmentUsers departmentUsers = listDepartmentUsers(session, corpAccessToken, departmentId);
            allDepartmentsLoaded &= departmentUsers.successful();
            if (detailedLog) {
                logger.infof("DingTalk sync detail department loaded. realm=%s, idp=%s, departmentId=%s, count=%d, successful=%s",
                        realm.getName(), idp.getAlias(), departmentId,
                        departmentUsers.users().size(), departmentUsers.successful());
            }

            for (UserDto dingtalkUser : departmentUsers.users()) {
                String userKey = resolveUserKey(dingtalkUser);
                if (StringUtils.isNotBlank(userKey) && !seenUserKeys.add(userKey)) {
                    if (detailedLog) {
                        logger.infof("DingTalk sync detail skipped duplicate user. realm=%s, idp=%s, departmentId=%s, dingtalkUser=%s",
                                realm.getName(), idp.getAlias(), departmentId, describeDingTalkUser(dingtalkUser));
                    }
                    continue;
                }

                listed++;
                activeUsers.add(dingtalkUser);
                dingtalkUsers.add(dingtalkUser);
            }
        }

        if (!dryRun && disableMissingUsers && allDepartmentsLoaded && activeUsers.hasAnyIdentity()) {
            disabled = disableMissingManagedUsers(session, realm, idp, activeUsers, disableExternalUsers, notifications);
        } else if (dryRun && disableMissingUsers && allDepartmentsLoaded && activeUsers.hasAnyIdentity()) {
            disabled = findMissingManagedUsers(session, realm, idp, activeUsers, disableExternalUsers).size();
        } else if (disableMissingUsers && !allDepartmentsLoaded) {
            logger.warnf("Skip disabling missing DingTalk users because at least one department failed to load. realm=%s, idp=%s",
                    realm.getName(), idp.getAlias());
        } else if (disableMissingUsers && !activeUsers.hasAnyIdentity()) {
            logger.warnf("Skip disabling missing DingTalk users because no active DingTalk identities were loaded. realm=%s, idp=%s",
                    realm.getName(), idp.getAlias());
        }

        for (UserDto dingtalkUser : dingtalkUsers) {
            MatchResult matchResult = findBoundOrMatchedUser(session, realm, idp, dingtalkUser, matchRules);
            UserModel user = matchResult.user();
            String matchSource = matchResult.source();
            Optional<ProvisionResult> provisionResult = Optional.empty();
            boolean createdThisSync = false;
            if (user == null) {
                if (dryRun && createUsers) {
                    String provisionedUsername = resolveProvisionedUsername(dingtalkUser);
                    UserModel existing = StringUtils.isBlank(provisionedUsername)
                            ? null
                            : session.users().getUserByUsername(realm, provisionedUsername);
                    if (StringUtils.isBlank(provisionedUsername) || existing != null) {
                        if (detailedLog) {
                            logger.infof("DingTalk sync detail would skip creating user. realm=%s, idp=%s, dingtalkUser=%s, username=%s, reason=%s",
                                    realm.getName(), idp.getAlias(), describeDingTalkUser(dingtalkUser),
                                    provisionedUsername,
                                    StringUtils.isBlank(provisionedUsername) ? "username is empty" : "username already exists");
                        }
                        continue;
                    }
                    created++;
                    matched++;
                    if (StringUtils.isNotBlank(resolveExternalId(dingtalkUser))) {
                        linked++;
                    }
                    if (detailedLog) {
                        logger.infof("DingTalk sync detail would create user. realm=%s, idp=%s, dingtalkUser=%s, username=%s",
                                realm.getName(), idp.getAlias(), describeDingTalkUser(dingtalkUser),
                                provisionedUsername);
                    }
                    continue;
                }
                provisionResult = createUsers
                        ? createUser(session, realm, idp, dingtalkUser, notifications)
                        : Optional.empty();
                if (provisionResult.isEmpty()) {
                    if (detailedLog) {
                        logger.infof("DingTalk sync detail skipped user. realm=%s, idp=%s, dingtalkUser=%s, reason=no matched Keycloak user and auto-create disabled or impossible",
                                realm.getName(), idp.getAlias(), describeDingTalkUser(dingtalkUser));
                    }
                    continue;
                }
                user = provisionResult.get().user();
                matchSource = provisionResult.get().source();
                if (provisionResult.get().created()) {
                    created++;
                    createdThisSync = true;
                }
            }

            matched++;
            if (detailedLog) {
                logger.infof("DingTalk sync detail matched user. realm=%s, idp=%s, dingtalkUser=%s, keycloakUsername=%s, source=%s",
                        realm.getName(), idp.getAlias(), describeDingTalkUser(dingtalkUser),
                        user.getUsername(), matchSource);
            }
            boolean createOnlyUser = shouldKeepCreateOnly(user, matchSource, dingtalkUser, createdThisSync);
            if (dryRun) {
                if (reenableUsers && isReenableCandidate(user, false)) {
                    reenabled++;
                }
                boolean linkChanged = wouldBindFederatedIdentity(session, realm, idp, user, dingtalkUser);
                if (linkChanged) {
                    linked++;
                }
                if (createOnlyUser) {
                    if (detailedLog && !linkChanged) {
                        logger.infof("DingTalk sync detail would leave create-only user unchanged. realm=%s, idp=%s, username=%s",
                                realm.getName(), idp.getAlias(), user.getUsername());
                    }
                    continue;
                }
                boolean managedChanged = wouldMarkManagedUser(user, idp, dingtalkUser, corpId);
                if (wouldApplyUserUpdates(user, dingtalkUser, corpId, syncFields, overwriteExisting)) {
                    updated++;
                } else if (managedChanged) {
                    updated++;
                } else if (detailedLog && !linkChanged) {
                    logger.infof("DingTalk sync detail would leave user unchanged. realm=%s, idp=%s, username=%s, syncFields=%s, overwriteExisting=%s",
                            realm.getName(), idp.getAlias(), user.getUsername(), syncFields, overwriteExisting);
                }
                continue;
            }
            if (reenableUsers && isReenableCandidate(user, createdThisSync)) {
                try {
                    user.setEnabled(true);
                    try {
                        user.removeAttribute(DINGTALK_DISABLED_REASON);
                    } catch (ReadOnlyException e) {
                        logger.warnf("Re-enabled DingTalk user but could not remove disable reason. realm=%s, idp=%s, username=%s, reason=%s",
                                realm.getName(), idp.getAlias(), user.getUsername(), e.getMessage());
                    }
                    reenabled++;
                    logger.infof("DingTalk sync re-enabled user. realm=%s, idp=%s, username=%s",
                            realm.getName(), idp.getAlias(), user.getUsername());
                } catch (ReadOnlyException e) {
                    logger.warnf("Skip re-enabling read-only user during DingTalk sync. realm=%s, idp=%s, username=%s, reason=%s",
                            realm.getName(), idp.getAlias(), user.getUsername(), e.getMessage());
                }
            }

            boolean linkChanged = false;
            try {
                linkChanged = bindFederatedIdentityIfMissing(session, realm, idp, user, dingtalkUser);
            } catch (ReadOnlyException e) {
                logger.warnf("Skip DingTalk federated identity binding for read-only user. realm=%s, idp=%s, username=%s, reason=%s",
                        realm.getName(), idp.getAlias(), user.getUsername(), e.getMessage());
            }
            if (linkChanged) {
                linked++;
            }
            if (createOnlyUser) {
                if (detailedLog && !linkChanged) {
                    logger.infof("DingTalk sync detail unchanged create-only user. realm=%s, idp=%s, username=%s",
                            realm.getName(), idp.getAlias(), user.getUsername());
                }
                continue;
            }
            boolean managedChanged = markManagedUser(user, realm.getName(), idp, dingtalkUser, corpId, now);
            if (applyUserUpdates(user, realm.getName(), idp.getAlias(), dingtalkUser, corpId, syncFields, overwriteExisting)) {
                updated++;
            } else if (managedChanged) {
                updated++;
            } else if (detailedLog && !linkChanged) {
                logger.infof("DingTalk sync detail unchanged user. realm=%s, idp=%s, username=%s, syncFields=%s, overwriteExisting=%s",
                        realm.getName(), idp.getAlias(), user.getUsername(), syncFields, overwriteExisting);
            }
        }

        if (!dryRun) {
            config.put(LAST_PERIODIC_SYNC, String.valueOf(now));
            idp.setConfig(config);
            realm.updateIdentityProvider(idp);
        }

        logger.infof("DingTalk sync finished. realm=%s, idp=%s, dryRun=%s, listed=%d, matched=%d, created=%d, linked=%d, updated=%d, reenabled=%d, disabled=%d",
                realm.getName(), idp.getAlias(), dryRun, listed, matched, created, linked, updated, reenabled, disabled);
        notifications.flush();

        return new SyncResult(idp.getAlias(), listed, matched, created, linked, updated, reenabled, disabled, false, null);
    }

    private String getCorpAccessToken(KeycloakSession session, String clientId, String clientSecret) {
        try {
            String url = UriBuilder.fromUri(CORP_TOKEN_URL)
                    .queryParam("appkey", clientId)
                    .queryParam("appsecret", clientSecret)
                    .build()
                    .toString();
            String response = SimpleHttp.create(session).doGet(url).asString();
            logger.debugf("DingTalk periodic sync token response: %s",
                    DingTalkIdentityProvider.sanitizeForLog(response));

            JsonNode json = MAPPER.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                logger.errorf("Failed to get DingTalk corp token for periodic sync: %s",
                        json.path("errmsg").asText());
                return null;
            }
            return json.path("access_token").asText(null);
        } catch (Exception e) {
            logger.error("Failed to get DingTalk corp token for periodic sync", e);
            return null;
        }
    }

    private DepartmentUsers listDepartmentUsers(KeycloakSession session, String accessToken, String departmentId) {
        try {
            long deptId = Long.parseLong(departmentId);
            long cursor = 0;
            List<UserDto> users = new java.util.ArrayList<>();

            while (true) {
                String url = UriBuilder.fromUri(USER_LIST_URL)
                        .queryParam("access_token", accessToken)
                        .build()
                        .toString();
                Map<String, Object> body = Map.of("dept_id", deptId, "cursor", cursor, "size", 100);
                String response = SimpleHttp.create(session).doPost(url)
                        .header("Content-Type", "application/json")
                        .json(body)
                        .asString();
                logger.debugf("DingTalk user list response: %s",
                        DingTalkIdentityProvider.sanitizeForLog(response));

                JsonNode json = MAPPER.readTree(response);
                if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                    logger.errorf("Failed to list DingTalk users. deptId=%s, error=%s",
                            departmentId, json.path("errmsg").asText());
                    return new DepartmentUsers(users, false);
                }

                JsonNode result = json.path("result");
                for (JsonNode item : result.path("list")) {
                    users.add(toUserDto(item));
                }

                if (!result.path("has_more").asBoolean(false)) {
                    return new DepartmentUsers(users, true);
                }
                cursor = result.path("next_cursor").asLong(0);
                if (cursor <= 0) {
                    return new DepartmentUsers(users, true);
                }
            }
        } catch (NumberFormatException e) {
            logger.warnf("Skip invalid DingTalk department id: %s", departmentId);
            return new DepartmentUsers(List.of(), false);
        } catch (Exception e) {
            logger.errorf(e, "Failed to list DingTalk department users. deptId=%s", departmentId);
            return new DepartmentUsers(List.of(), false);
        }
    }

    private DepartmentPlan resolveDepartmentsToSync(KeycloakSession session, String accessToken,
                                                    List<String> rootDepartmentIds,
                                                    boolean includeChildDepartments) {
        if (!includeChildDepartments) {
            return new DepartmentPlan(rootDepartmentIds, true);
        }

        LinkedHashSet<String> departmentIds = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>(rootDepartmentIds);
        boolean successful = true;

        while (!queue.isEmpty()) {
            String departmentId = queue.poll();
            if (!departmentIds.add(departmentId)) {
                continue;
            }

            DepartmentIds childDepartments = listChildDepartmentIds(session, accessToken, departmentId);
            successful &= childDepartments.successful();
            for (String childDepartmentId : childDepartments.departmentIds()) {
                if (!departmentIds.contains(childDepartmentId)) {
                    queue.add(childDepartmentId);
                }
            }
        }

        return new DepartmentPlan(List.copyOf(departmentIds), successful);
    }

    private DepartmentIds listChildDepartmentIds(KeycloakSession session, String accessToken, String departmentId) {
        try {
            long deptId = Long.parseLong(departmentId);
            String url = UriBuilder.fromUri(DEPARTMENT_SUB_IDS_URL)
                    .queryParam("access_token", accessToken)
                    .build()
                    .toString();
            String response = SimpleHttp.create(session).doPost(url)
                    .header("Content-Type", "application/json")
                    .json(Map.of("dept_id", deptId))
                    .asString();
            logger.debugf("DingTalk child department list response: %s",
                    DingTalkIdentityProvider.sanitizeForLog(response));

            JsonNode json = MAPPER.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                logger.errorf("Failed to list DingTalk child departments. deptId=%s, error=%s",
                        departmentId, json.path("errmsg").asText());
                return new DepartmentIds(List.of(), false);
            }

            List<String> childDepartmentIds = new java.util.ArrayList<>();
            for (JsonNode childDeptId : json.path("result").path("dept_id_list")) {
                String childDepartmentId = childDeptId.asText(null);
                if (StringUtils.isNotBlank(childDepartmentId)) {
                    childDepartmentIds.add(childDepartmentId);
                }
            }
            return new DepartmentIds(childDepartmentIds, true);
        } catch (NumberFormatException e) {
            logger.warnf("Skip invalid DingTalk department id when listing child departments: %s", departmentId);
            return new DepartmentIds(List.of(), false);
        } catch (Exception e) {
            logger.errorf(e, "Failed to list DingTalk child departments. deptId=%s", departmentId);
            return new DepartmentIds(List.of(), false);
        }
    }

    private UserDto toUserDto(JsonNode item) {
        UserDto userDto = new UserDto();
        userDto.setUserId(item.path("userid").asText(null));
        userDto.setUnionId(item.path("unionid").asText(null));
        userDto.setOpenId(item.path("open_id").asText(null));
        userDto.setNick(item.path("name").asText(null));
        userDto.setMobile(item.path("mobile").asText(null));
        userDto.setEmail(item.path("email").asText(null));
        return userDto;
    }

    private MatchResult findBoundOrMatchedUser(KeycloakSession session, RealmModel realm,
                                               IdentityProviderModel idp, UserDto dingtalkUser,
                                               List<String> matchRules) {
        String externalId = resolveExternalId(dingtalkUser);
        if (StringUtils.isNotBlank(externalId)) {
            UserModel bound = session.users().getUserByFederatedIdentity(
                    realm,
                    new FederatedIdentityModel(idp.getAlias(), externalId, resolveExternalUsername(dingtalkUser)));
            if (bound != null) {
                return new MatchResult(bound, "linked-identity");
            }
        }

        for (String rule : matchRules) {
            MatchResult matched = findMatchedUserByRule(session, realm, dingtalkUser, rule);
            if (matched.user() != null) {
                return matched;
            }
        }

        return MatchResult.empty();
    }

    private Optional<ProvisionResult> createUser(KeycloakSession session, RealmModel realm,
                                                 IdentityProviderModel idp, UserDto dingtalkUser,
                                                 DingTalkWebhookNotifier.Batch notifications) {
        String username = resolveProvisionedUsername(dingtalkUser);
        String describedUser = describeDingTalkUser(dingtalkUser);
        String notificationUser = describeDingTalkUserForNotification(dingtalkUser);
        if (StringUtils.isBlank(username)) {
            logger.warnf("Skip creating Keycloak user from DingTalk: username is empty. realm=%s, idp=%s, dingtalkUser=%s",
                    realm.getName(), idp.getAlias(), describedUser);
            notifications.addSkippedCreate("", "username is empty", notificationUser);
            return Optional.empty();
        }

        UserModel existing = session.users().getUserByUsername(realm, username);
        if (existing != null) {
            logger.warnf("Skip creating Keycloak user from DingTalk: username already exists and no trusted match was found. realm=%s, username=%s, dingtalkUser=%s",
                    realm.getName(), username, describedUser);
            notifications.addSkippedCreate(username, "username already exists and no trusted match", notificationUser);
            return Optional.empty();
        }

        ProvisioningName provisioningName = resolveProvisioningName(dingtalkUser.getNick());
        String provisionedEmail = resolveProvisionedEmail(idp.getConfig(), username, dingtalkUser.getEmail());
        String mobile = DingTalkIdentityProvider.formatMobile(dingtalkUser.getMobile());
        UserModel user = createProvisionedUser(session, realm, idp, username, provisioningName,
                provisionedEmail, mobile, dingtalkUser);
        // Keep LDAP-mapped profile fields create-only in this transaction. Only the
        // Keycloak email verification flag is set post-create, matching the admin
        // REST creation path without adding another LDAP-mapped profile update.

        logger.infof("Created Keycloak user from DingTalk. realm=%s, username=%s",
                realm.getName(), user.getUsername());
        notifications.addCreatedUser(user.getUsername(), notificationUser);
        DingTalkCreatedUserInitializer.initializeAfterCommit(session, realm, idp, user.getUsername());
        return Optional.of(new ProvisionResult(user, true, "created"));
    }

    private UserModel createProvisionedUser(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                            String username, ProvisioningName provisioningName,
                                            String provisionedEmail, String mobile, UserDto dingtalkUser) {
        UserProfileProvider profileProvider = session.getProvider(UserProfileProvider.class);
        if (profileProvider == null) {
            throw new IllegalStateException("UserProfileProvider is not available");
        }

        Map<String, List<String>> rawAttributes = buildProvisioningRawAttributes(
                username, provisioningName, provisionedEmail, mobile, dingtalkUser);
        UserProfile profile = profileProvider.create(UserProfileContext.USER_API, rawAttributes);
        profile.validate();
        UserModel user = profile.create();
        // This flag is a dedicated UserModel property, not a UserProfile raw attribute.
        user.setEmailVerified(true);
        logger.debugf("Created DingTalk provisioned user through UserProfile. realm=%s, idp=%s, username=%s, rawAttributeNames=%s",
                realm.getName(), idp.getAlias(), username, rawAttributes.keySet());
        return user;
    }

    static Map<String, List<String>> buildProvisioningRawAttributes(String username, ProvisioningName provisioningName,
                                                                    String provisionedEmail, String mobile,
                                                                    UserDto dingtalkUser) {
        Map<String, List<String>> rawAttributes = new LinkedHashMap<>();
        addRawAttribute(rawAttributes, UserModel.USERNAME, username);
        addRawAttribute(rawAttributes, UserModel.EMAIL, provisionedEmail);
        if (provisioningName != null) {
            addRawAttribute(rawAttributes, UserModel.FIRST_NAME, provisioningName.firstName());
            addRawAttribute(rawAttributes, UserModel.LAST_NAME, provisioningName.lastName());
            addRawAttribute(rawAttributes, NICK_NAME, provisioningName.displayName());
        }
        if (dingtalkUser != null) {
            addRawAttribute(rawAttributes, DINGTALK_USER_ID, dingtalkUser.getUserId());
        }
        if (StringUtils.isNotBlank(mobile)) {
            addRawAttribute(rawAttributes, PHONE_NUMBER, mobile);
        }
        return rawAttributes;
    }

    private static void addRawAttribute(Map<String, List<String>> rawAttributes, String name, String value) {
        if (StringUtils.isBlank(name) || StringUtils.isBlank(value)) {
            return;
        }
        rawAttributes.put(name, List.of(value.trim()));
    }

    static String resolveProvisionedUsername(UserDto dingtalkUser) {
        if (dingtalkUser == null) {
            return null;
        }
        String namePinyin = PinyinUsername.fromChineseName(dingtalkUser.getNick());
        String emailPrefix = emailPrefix(dingtalkUser.getEmail());
        if (StringUtils.isNotBlank(emailPrefix) && emailPrefix.equalsIgnoreCase(namePinyin)) {
            return emailPrefix.toLowerCase(Locale.ROOT);
        }

        return Stream.of(namePinyin, emailPrefix)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .findFirst()
                .orElse(null);
    }

    static String resolveProvisionedEmail(Map<String, String> config, String username, String fallbackEmail) {
        String configuredDomain = config == null ? null : config.get(PROVISIONED_EMAIL_DOMAIN);
        String domain = normalizeProvisionedEmailDomain(configuredDomain);
        if (StringUtils.isNotBlank(configuredDomain) && StringUtils.isBlank(domain)) {
            throw new IllegalArgumentException("Invalid provisionedEmailDomain: " + configuredDomain);
        }
        if (StringUtils.isBlank(domain) || StringUtils.isBlank(username)) {
            String email = StringUtils.trimToNull(fallbackEmail);
            return email == null ? null : email.toLowerCase(Locale.ROOT);
        }
        return username.trim().toLowerCase(Locale.ROOT) + "@" + domain;
    }

    static String normalizeProvisionedEmailDomain(String configuredDomain) {
        if (StringUtils.isBlank(configuredDomain)) {
            return null;
        }
        String domain = configuredDomain.trim();
        while (domain.startsWith("@")) {
            domain = domain.substring(1).trim();
        }
        domain = domain.toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(domain) || domain.contains("@") || StringUtils.containsWhitespace(domain)) {
            return null;
        }
        return domain;
    }

    static ProvisioningName resolveProvisioningName(String nickname) {
        String displayName = StringUtils.trimToNull(nickname);
        if (displayName == null) {
            return ProvisioningName.empty();
        }
        return new ProvisioningName(displayName, null, displayName);
    }

    private MatchResult findMatchedUserByRule(KeycloakSession session, RealmModel realm,
                                              UserDto dingtalkUser, String rule) {
        switch (rule) {
            case "phone":
                String mobile = DingTalkIdentityProvider.formatMobile(dingtalkUser.getMobile());
                for (String attributeName : PHONE_ATTRIBUTE_NAMES) {
                    Optional<UserModel> byPhone = findUniqueByAttribute(
                            session, realm, attributeName, mobile, "sync phone");
                    if (byPhone.isPresent()) {
                        return new MatchResult(byPhone.get(), "phone:" + attributeName);
                    }
                }
                return MatchResult.empty();
            case "email":
                if (StringUtils.isBlank(dingtalkUser.getEmail())) {
                    return MatchResult.empty();
                }
                return findUniqueByEmail(session, realm, dingtalkUser.getEmail(), "sync")
                        .map(user -> new MatchResult(user, "email"))
                        .orElseGet(MatchResult::empty);
            case "username":
                return findByUsernameCandidates(session, realm, dingtalkUser)
                        .map(user -> new MatchResult(user, "username"))
                        .orElseGet(MatchResult::empty);
            case "unionid":
                return findUniqueByAttribute(session, realm, UNION_ID, dingtalkUser.getUnionId(), "sync unionid")
                        .map(user -> new MatchResult(user, "unionid"))
                        .orElseGet(MatchResult::empty);
            case "openid":
                return findUniqueByAttribute(session, realm, OPEN_ID, dingtalkUser.getOpenId(), "sync openid")
                        .map(user -> new MatchResult(user, "openid"))
                        .orElseGet(MatchResult::empty);
            default:
                logger.warnf("Unsupported DingTalk periodic sync match rule: %s", rule);
                return MatchResult.empty();
        }
    }

    private Optional<UserModel> findByUsernameCandidates(KeycloakSession session, RealmModel realm,
                                                        UserDto dingtalkUser) {
        List<UserModel> matches = new ArrayList<>();
        for (String username : usernameCandidates(dingtalkUser)) {
            UserModel byUsername = session.users().getUserByUsername(realm, username);
            if (byUsername != null && matches.stream().noneMatch(existing -> existing.getId().equals(byUsername.getId()))) {
                matches.add(byUsername);
                if (matches.size() > 1) {
                    break;
                }
            }
        }
        return uniqueMatch(matches, "sync username", usernameCandidates(dingtalkUser));
    }

    private boolean bindFederatedIdentityIfMissing(KeycloakSession session, RealmModel realm,
                                                   IdentityProviderModel idp, UserModel user,
                                                   UserDto dingtalkUser) {
        String externalId = resolveExternalId(dingtalkUser);
        if (StringUtils.isBlank(externalId)) {
            return false;
        }

        FederatedIdentityModel existing = session.users().getFederatedIdentity(realm, user, idp.getAlias());
        if (existing != null) {
            if (!externalId.equals(existing.getUserId())) {
                logger.warnf("Skip DingTalk binding update for %s: existing external id does not match",
                        user.getUsername());
                return false;
            }

            String externalUsername = resolveExternalUsername(dingtalkUser);
            if (StringUtils.isNotBlank(externalUsername) && !externalUsername.equals(existing.getUserName())) {
                session.users().updateFederatedIdentity(
                        realm,
                        user,
                        new FederatedIdentityModel(idp.getAlias(), externalId, externalUsername, existing.getToken()));
                logger.infof("DingTalk sync updated linked account display name. idp=%s, username=%s, externalUsername=%s",
                        idp.getAlias(), user.getUsername(), externalUsername);
                return true;
            }
            return false;
        }

        session.users().addFederatedIdentity(
                realm,
                user,
                new FederatedIdentityModel(idp.getAlias(), externalId, resolveExternalUsername(dingtalkUser)));
        logger.infof("DingTalk sync linked user. idp=%s, username=%s, externalId=%s",
                idp.getAlias(), user.getUsername(), DingTalkIdentityProvider.mask(externalId));
        return true;
    }

    private boolean wouldBindFederatedIdentity(KeycloakSession session, RealmModel realm,
                                               IdentityProviderModel idp, UserModel user,
                                               UserDto dingtalkUser) {
        String externalId = resolveExternalId(dingtalkUser);
        if (StringUtils.isBlank(externalId)) {
            return false;
        }

        FederatedIdentityModel existing = session.users().getFederatedIdentity(realm, user, idp.getAlias());
        if (existing != null) {
            if (!externalId.equals(existing.getUserId())) {
                return false;
            }

            String externalUsername = resolveExternalUsername(dingtalkUser);
            return StringUtils.isNotBlank(externalUsername) && !externalUsername.equals(existing.getUserName());
        }

        return true;
    }

    boolean markManagedUser(UserModel user, String realmName, IdentityProviderModel idp, UserDto dingtalkUser,
                            String corpId, long syncTimestamp) {
        boolean changed = false;
        try {
            changed |= putAttribute(user, DINGTALK_MANAGED, "true", true);
            changed |= putAttribute(user, DINGTALK_IDP_ALIAS, idp.getAlias(), true);
            changed |= putAttribute(user, DINGTALK_EXTERNAL_ID, resolveExternalId(dingtalkUser), true);
            putAttribute(user, DINGTALK_LAST_SYNC, String.valueOf(syncTimestamp), true);
            putAttribute(user, DINGTALK_LAST_SYNC_AT, formatBeijingTime(syncTimestamp), true);
            changed |= putDingTalkIdentityAttributes(user, dingtalkUser, corpId);
            changed |= putAttribute(user, NICK_NAME, dingtalkUser.getNick(), true);
        } catch (ReadOnlyException e) {
            logger.warnf("Skip DingTalk metadata writes for read-only user. realm=%s, idp=%s, username=%s, reason=%s",
                    realmName, idp.getAlias(), user.getUsername(), e.getMessage());
        }
        return changed;
    }

    private boolean wouldMarkManagedUser(UserModel user, IdentityProviderModel idp, UserDto dingtalkUser,
                                         String corpId) {
        return wouldPutAttribute(user, DINGTALK_MANAGED, "true", true)
                || wouldPutAttribute(user, DINGTALK_IDP_ALIAS, idp.getAlias(), true)
                || wouldPutAttribute(user, DINGTALK_EXTERNAL_ID, resolveExternalId(dingtalkUser), true)
                || wouldPutAttribute(user, DINGTALK_USER_ID, dingtalkUser.getUserId(), true)
                || wouldPutAttribute(user, UNION_ID, dingtalkUser.getUnionId(), true)
                || wouldPutAttribute(user, OPEN_ID, dingtalkUser.getOpenId(), true)
                || wouldPutAttribute(user, CORP_ID, corpId, true)
                || wouldPutAttribute(user, NICK_NAME, dingtalkUser.getNick(), true);
    }

    private int disableMissingManagedUsers(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                           ActiveDingTalkUsers activeUsers, boolean includeExternalUsers,
                                           DingTalkWebhookNotifier.Batch notifications) {
        int disabled = 0;
        for (UserModel user : findMissingManagedUsers(session, realm, idp, activeUsers, includeExternalUsers)) {
            try {
                user.setEnabled(false);
                disabled++;
                try {
                    putAttribute(user, DINGTALK_DISABLED_REASON, DINGTALK_DISABLED_REASON_MISSING, true);
                } catch (ReadOnlyException e) {
                    logger.warnf("Disabled user missing from DingTalk but could not write disable reason. realm=%s, idp=%s, username=%s, reason=%s",
                            realm.getName(), idp.getAlias(), user.getUsername(), e.getMessage());
                }
                logger.infof("Disabled Keycloak user missing from DingTalk. realm=%s, username=%s",
                        realm.getName(), user.getUsername());
                notifications.addDisabledUser(user.getUsername(), DINGTALK_DISABLED_REASON_MISSING);
            } catch (ReadOnlyException e) {
                logger.warnf("Skip disabling read-only user missing from DingTalk. realm=%s, idp=%s, username=%s, reason=%s",
                        realm.getName(), idp.getAlias(), user.getUsername(), e.getMessage());
            }
        }
        return disabled;
    }

    List<UserModel> findMissingManagedUsers(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                            ActiveDingTalkUsers activeUsers, boolean includeExternalUsers) {
        Map<String, UserModel> candidates = new LinkedHashMap<>();
        try (Stream<UserModel> managedUsers = session.users()
                .searchForUserByUserAttributeStream(realm, DINGTALK_IDP_ALIAS, idp.getAlias())) {
            managedUsers
                    .filter(UserModel::isEnabled)
                    .filter(user -> isMissingKnownDingTalkUser(session, realm, user, idp.getAlias(), activeUsers))
                    .forEach(user -> candidates.put(candidateKey(user), user));
        }

        if (includeExternalUsers && isExternalUserFullScanAllowed(realm, idp)) {
            try (Stream<UserModel> externalUsers = session.users().searchForUserStream(realm, Map.of())) {
                externalUsers
                        .filter(UserModel::isEnabled)
                        .filter(user -> isMissingLinkedUser(session, realm, user, idp.getAlias(), activeUsers)
                                || isMissingExternalStorageUser(session, realm, user, idp.getAlias(), activeUsers))
                        .forEach(user -> candidates.put(candidateKey(user), user));
            }
        }

        return new ArrayList<>(candidates.values());
    }

    private boolean isExternalUserFullScanAllowed(RealmModel realm, IdentityProviderModel idp) {
        List<ComponentModel> userStorageProviders;
        try (Stream<ComponentModel> providers = realm.getStorageProviders(UserStorageProvider.class)) {
            userStorageProviders = providers.toList();
        } catch (RuntimeException e) {
            logger.warnf(e, "Skip DingTalk external missing-user scan because user storage provider configuration cannot be inspected. realm=%s, idp=%s",
                    realm.getName(), idp.getAlias());
            return false;
        }

        for (ComponentModel provider : userStorageProviders) {
            UserStorageProviderModel storageProvider = new UserStorageProviderModel(provider);
            if (storageProvider.isRemoveInvalidUsersEnabled()) {
                logger.warnf("Skip DingTalk external missing-user scan because user storage provider has removeInvalidUsersEnabled=true. realm=%s, idp=%s, provider=%s",
                        realm.getName(), idp.getAlias(), provider.getName());
                return false;
            }
        }
        return true;
    }

    static boolean isReenableCandidate(UserModel user, boolean createdThisSync) {
        return user != null
                && !createdThisSync
                && !user.isEnabled()
                && DINGTALK_DISABLED_REASON_MISSING.equals(user.getFirstAttribute(DINGTALK_DISABLED_REASON));
    }

    private boolean isMissingKnownDingTalkUser(KeycloakSession session, RealmModel realm, UserModel user,
                                               String idpAlias, ActiveDingTalkUsers activeUsers) {
        if (activeUsers.matchesUser(user)) {
            return false;
        }
        if ("true".equals(user.getFirstAttribute(DINGTALK_MANAGED)) && isMissingManagedUser(user, activeUsers)) {
            return true;
        }
        return isMissingLinkedUser(session, realm, user, idpAlias, activeUsers);
    }

    private boolean isMissingManagedUser(UserModel user, ActiveDingTalkUsers activeUsers) {
        String externalId = resolveManagedExternalId(user);
        return StringUtils.isNotBlank(externalId) && !activeUsers.hasExternalId(externalId);
    }

    private boolean isMissingLinkedUser(KeycloakSession session, RealmModel realm, UserModel user,
                                        String idpAlias, ActiveDingTalkUsers activeUsers) {
        FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
        return identity != null
                && StringUtils.isNotBlank(identity.getUserId())
                && !activeUsers.hasExternalId(identity.getUserId());
    }

    private boolean isMissingExternalStorageUser(KeycloakSession session, RealmModel realm, UserModel user,
                                                 String idpAlias, ActiveDingTalkUsers activeUsers) {
        if (!isExternalStorageDisableCandidate(user)) {
            return false;
        }
        FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, idpAlias);
        if (identity != null && activeUsers.hasExternalId(identity.getUserId())) {
            return false;
        }
        return !activeUsers.matchesUser(user);
    }

    private boolean isExternalStorageDisableCandidate(UserModel user) {
        if (StringUtils.isBlank(user.getFederationLink())) {
            return false;
        }
        if (StringUtils.isNotBlank(user.getServiceAccountClientLink())) {
            return false;
        }
        String username = StringUtils.trimToEmpty(user.getUsername());
        // AD computer accounts use sAMAccountName ending in "$"; they are not employee accounts.
        return !username.endsWith("$");
    }

    private String candidateKey(UserModel user) {
        String id = user.getId();
        return StringUtils.isNotBlank(id) ? id : user.getUsername();
    }

    private String resolveManagedExternalId(UserModel user) {
        String externalId = user.getFirstAttribute(DINGTALK_EXTERNAL_ID);
        if (StringUtils.isNotBlank(externalId)) {
            return externalId;
        }
        return Stream.of(
                        user.getFirstAttribute(UNION_ID),
                        user.getFirstAttribute(OPEN_ID),
                        user.getFirstAttribute(DINGTALK_USER_ID))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    boolean applyUserUpdates(UserModel user, String realmName, String idpAlias, UserDto dingtalkUser, String corpId,
                             Set<String> syncFields, boolean overwriteExisting) {
        boolean changed = false;
        List<String> changedFields = new java.util.ArrayList<>();

        String mobile = DingTalkIdentityProvider.formatMobile(dingtalkUser.getMobile());
        if (syncFields.contains("phone")
                && putUserSyncAttribute(user, realmName, idpAlias, PHONE_NUMBER, mobile, overwriteExisting)) {
            changed = true;
            changedFields.add(PHONE_NUMBER);
        }
        if (syncFields.contains("email")
                && StringUtils.isNotBlank(dingtalkUser.getEmail())
                && (overwriteExisting || StringUtils.isBlank(user.getEmail()))) {
            try {
                user.setEmail(dingtalkUser.getEmail());
                changed = true;
                changedFields.add("email");
            } catch (ReadOnlyException e) {
                logger.warnf("Skip DingTalk sync field write for read-only user. realm=%s, idp=%s, username=%s, field=email, reason=%s",
                        realmName, idpAlias, user.getUsername(), e.getMessage());
            }
        }
        try {
            changed |= putDingTalkIdentityAttributes(user, dingtalkUser, corpId);
        } catch (ReadOnlyException e) {
            logger.warnf("Skip DingTalk identity metadata writes for read-only user. realm=%s, idp=%s, username=%s, reason=%s",
                    realmName, idpAlias, user.getUsername(), e.getMessage());
        }

        if (!changedFields.isEmpty()) {
            logger.infof("DingTalk sync updated user fields. username=%s, fields=%s",
                    user.getUsername(), changedFields);
        }
        return changed;
    }

    private boolean wouldApplyUserUpdates(UserModel user, UserDto dingtalkUser, String corpId,
                                          Set<String> syncFields, boolean overwriteExisting) {
        String mobile = DingTalkIdentityProvider.formatMobile(dingtalkUser.getMobile());
        return (syncFields.contains("phone") && wouldPutAttribute(user, PHONE_NUMBER, mobile, overwriteExisting))
                || (syncFields.contains("email")
                        && StringUtils.isNotBlank(dingtalkUser.getEmail())
                        && (overwriteExisting || StringUtils.isBlank(user.getEmail())))
                || wouldPutAttribute(user, DINGTALK_USER_ID, dingtalkUser.getUserId(), true)
                || wouldPutAttribute(user, UNION_ID, dingtalkUser.getUnionId(), true)
                || wouldPutAttribute(user, OPEN_ID, dingtalkUser.getOpenId(), true)
                || wouldPutAttribute(user, CORP_ID, corpId, true);
    }

    static boolean markUsernameLocked(UserModel user, String realmName, String idpAlias,
                                      String username, String source) {
        if (user == null) {
            return false;
        }
        String resolvedUsername = StringUtils.defaultIfBlank(username, user.getUsername());
        boolean changed = false;
        try {
            changed |= putAttribute(user, DINGTALK_USERNAME_LOCKED, "true", true);
            changed |= putAttribute(user, DINGTALK_USERNAME_SOURCE,
                    StringUtils.defaultIfBlank(source, USERNAME_SOURCE_AUTO_PINYIN), true);
            changed |= putAttribute(user, DINGTALK_USERNAME_SUGGESTED, resolvedUsername, true);
        } catch (ReadOnlyException e) {
            logger.warnf("Skip DingTalk username lock metadata writes for read-only user. realm=%s, idp=%s, username=%s, reason=%s",
                    realmName, idpAlias, user.getUsername(), e.getMessage());
        }
        return changed;
    }

    static boolean shouldKeepCreateOnly(UserModel user, String matchSource, UserDto dingtalkUser,
                                        boolean createdThisSync) {
        if (createdThisSync) {
            return true;
        }
        if (user == null || !"linked-identity".equals(matchSource)) {
            return false;
        }
        if ("true".equalsIgnoreCase(user.getFirstAttribute(DINGTALK_MANAGED))) {
            return false;
        }

        String dingtalkUserId = dingtalkUser == null ? null : dingtalkUser.getUserId();
        if (StringUtils.isNotBlank(dingtalkUserId)
                && dingtalkUserId.equals(user.getFirstAttribute(DINGTALK_USER_ID))) {
            return true;
        }

        String provisionedUsername = resolveProvisionedUsername(dingtalkUser);
        return StringUtils.isNotBlank(provisionedUsername)
                && StringUtils.equalsIgnoreCase(provisionedUsername, user.getUsername());
    }

    private static boolean putAttribute(UserModel user, String attributeName, String value, boolean overwriteExisting) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String current = user.getFirstAttribute(attributeName);
        if (StringUtils.isNotBlank(current) && !overwriteExisting) {
            return false;
        }
        if (value.equals(current)) {
            return false;
        }
        user.setSingleAttribute(attributeName, value);
        return true;
    }

    private boolean wouldPutAttribute(UserModel user, String attributeName, String value, boolean overwriteExisting) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        String current = user.getFirstAttribute(attributeName);
        if (StringUtils.isNotBlank(current) && !overwriteExisting) {
            return false;
        }
        return !value.equals(current);
    }

    private boolean putUserSyncAttribute(UserModel user, String realmName, String idpAlias, String attributeName,
                                         String value, boolean overwriteExisting) {
        try {
            return putAttribute(user, attributeName, value, overwriteExisting);
        } catch (ReadOnlyException e) {
            logger.warnf("Skip DingTalk sync field write for read-only user. realm=%s, idp=%s, username=%s, field=%s, reason=%s",
                    realmName, idpAlias, user.getUsername(), attributeName, e.getMessage());
            return false;
        }
    }

    private boolean putDingTalkIdentityAttributes(UserModel user, UserDto dingtalkUser, String corpId) {
        boolean changed = false;
        changed |= putAttribute(user, DINGTALK_USER_ID, dingtalkUser.getUserId(), true);
        changed |= putAttribute(user, UNION_ID, dingtalkUser.getUnionId(), true);
        changed |= putAttribute(user, OPEN_ID, dingtalkUser.getOpenId(), true);
        changed |= putAttribute(user, CORP_ID, corpId, true);
        return changed;
    }

    final class ActiveDingTalkUsers {
        private final Set<String> externalIds = new HashSet<>();
        private final Set<String> usernames = new HashSet<>();
        private final Set<String> emails = new HashSet<>();
        private final Set<String> phones = new HashSet<>();

        void add(UserDto dingtalkUser) {
            addValue(externalIds, resolveExternalId(dingtalkUser));
            usernameCandidates(dingtalkUser).forEach(username -> addValue(usernames, username));
            addValue(emails, normalizeEmail(dingtalkUser.getEmail()));
            addValue(phones, DingTalkIdentityProvider.formatMobile(dingtalkUser.getMobile()));
        }

        boolean hasAnyIdentity() {
            return !externalIds.isEmpty() || !usernames.isEmpty() || !emails.isEmpty() || !phones.isEmpty();
        }

        boolean hasExternalId(String externalId) {
            return StringUtils.isNotBlank(externalId)
                    && externalIds.contains(externalId.trim().toLowerCase(Locale.ROOT));
        }

        boolean matchesUser(UserModel user) {
            if (user == null) {
                return false;
            }
            if (StringUtils.isNotBlank(user.getUsername())
                    && usernames.contains(user.getUsername().trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (StringUtils.isNotBlank(user.getEmail()) && emails.contains(normalizeEmail(user.getEmail()))) {
                return true;
            }
            for (String attributeName : PHONE_ATTRIBUTE_NAMES) {
                String phone = DingTalkIdentityProvider.formatMobile(user.getFirstAttribute(attributeName));
                if (StringUtils.isNotBlank(phone) && phones.contains(phone)) {
                    return true;
                }
            }
            return false;
        }

        private void addValue(Set<String> values, String value) {
            String normalized = StringUtils.trimToNull(value);
            if (normalized != null) {
                values.add(normalized.toLowerCase(Locale.ROOT));
            }
        }

        private static String normalizeEmail(String email) {
            return StringUtils.isBlank(email) ? null : email.trim().toLowerCase(Locale.ROOT);
        }
    }

    private Optional<UserModel> findUniqueByEmail(KeycloakSession session, RealmModel realm,
                                                  String email, String source) {
        if (StringUtils.isBlank(email)) {
            return Optional.empty();
        }
        List<UserModel> matches = session.users()
                .searchForUserStream(realm, Map.of(UserModel.EMAIL, email), 0, 2)
                .filter(user -> email.equalsIgnoreCase(user.getEmail()))
                .limit(2)
                .toList();
        if (matches.isEmpty()) {
            return Optional.ofNullable(session.users().getUserByEmail(realm, email));
        }
        return uniqueMatch(matches, source + " email", email);
    }

    private Optional<UserModel> findUniqueByAttribute(KeycloakSession session, RealmModel realm,
                                                     String attributeName, String attributeValue,
                                                     String source) {
        if (StringUtils.isBlank(attributeValue)) {
            return Optional.empty();
        }
        List<UserModel> matches = session.users()
                .searchForUserByUserAttributeStream(realm, attributeName, attributeValue)
                .limit(2)
                .toList();
        return uniqueMatch(matches, source + " attribute=" + attributeName, attributeValue);
    }

    private Optional<UserModel> uniqueMatch(List<UserModel> matches, String source, Object valueForLog) {
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            logger.warnf("Skip DingTalk sync match by %s: multiple Keycloak users matched value=%s",
                    source, valueForLog);
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    private String resolveExternalId(UserDto userDto) {
        return Stream.of(userDto.getUnionId(), userDto.getOpenId(), userDto.getUserId())
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private String resolveUserKey(UserDto userDto) {
        return Stream.of(resolveExternalId(userDto), userDto.getUserId(), userDto.getEmail(), userDto.getMobile())
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private String resolveExternalUsername(UserDto userDto) {
        return Stream.of(userDto.getUserId(), userDto.getEmail(), userDto.getNick(), resolveExternalId(userDto))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("dingtalk_user");
    }

    private String describeDingTalkUser(UserDto userDto) {
        return String.format(
                "userId=%s, externalId=%s, hasPhone=%s, hasEmail=%s",
                DingTalkIdentityProvider.mask(userDto.getUserId()),
                DingTalkIdentityProvider.mask(resolveExternalId(userDto)),
                StringUtils.isNotBlank(DingTalkIdentityProvider.formatMobile(userDto.getMobile())),
                StringUtils.isNotBlank(userDto.getEmail()));
    }

    private String describeDingTalkUserForNotification(UserDto userDto) {
        return String.format(
                "userId=%s, externalId=%s, nickname=%s, hasPhone=%s, hasEmail=%s",
                DingTalkIdentityProvider.mask(userDto.getUserId()),
                DingTalkIdentityProvider.mask(resolveExternalId(userDto)),
                StringUtils.defaultString(userDto.getNick()),
                StringUtils.isNotBlank(DingTalkIdentityProvider.formatMobile(userDto.getMobile())),
                StringUtils.isNotBlank(userDto.getEmail()));
    }

    private List<String> usernameCandidates(UserDto userDto) {
        return Arrays.asList(emailPrefix(userDto.getEmail()), DingTalkIdentityProvider.resolveUsername(userDto))
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String emailPrefix(String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return null;
        }
        return email.substring(0, atIndex);
    }

    static boolean isPeriodicSyncEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_ENABLED, "false"));
    }

    static boolean isCreateUsersEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_CREATE_USERS, "false"));
    }

    static RealmModel resolveRealmAndBindContext(KeycloakSession session, String realmId) {
        if (session == null || StringUtils.isBlank(realmId)) {
            return null;
        }

        RealmModel realm = session.realms().getRealm(realmId);
        if (realm != null && session.getContext() != null) {
            session.getContext().setRealm(realm);
        }
        return realm;
    }

    private static List<String> distinctUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        return usernames.stream()
                .map(StringUtils::trimToNull)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    static boolean isDisableMissingUsersEnabled(Map<String, String> config) {
        return config != null
                && Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_DISABLE_MISSING_USERS, "false"));
    }

    static boolean isDisableExternalUsersEnabled(Map<String, String> config) {
        return config != null
                && Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_DISABLE_EXTERNAL_USERS, "false"));
    }

    static boolean isReenableUsersEnabled(Map<String, String> config) {
        return config == null || Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_REENABLE_USERS, "true"));
    }

    static boolean isDetailedLogEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_DETAILED_LOG, "false"));
    }

    static boolean isIncludeChildDepartmentsEnabled(Map<String, String> config) {
        return config == null
                || Boolean.parseBoolean(config.getOrDefault(PERIODIC_SYNC_INCLUDE_CHILD_DEPARTMENTS, "true"));
    }

    static Set<String> parseSyncFields(String rawFields) {
        Set<String> fields = new HashSet<>();
        if (StringUtils.isBlank(rawFields)) {
            fields.add("phone");
            return fields;
        }

        Arrays.stream(rawFields.split("[,，;；\\s]+"))
                .map(StringUtils::trimToEmpty)
                .map(field -> field.toLowerCase(Locale.ROOT))
                .map(DingTalkUserSyncTask::normalizeSyncField)
                .filter(StringUtils::isNotBlank)
                .forEach(fields::add);
        return fields.isEmpty() ? Set.of("phone") : fields;
    }

    static List<String> parseDepartmentIds(String rawDepartmentIds) {
        if (StringUtils.isBlank(rawDepartmentIds)) {
            return List.of("1");
        }

        List<String> ids = Arrays.stream(rawDepartmentIds.split("[,，;；\\s]+"))
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        return ids.isEmpty() ? List.of("1") : ids;
    }

    private static String normalizeSyncField(String field) {
        switch (field) {
            case "mobile":
            case "phonenumber":
            case "phone_number":
            case "手机号":
                return "phone";
            case "mail":
            case "邮箱":
                return "email";
            case "nick":
            case "name":
            case "昵称":
                return null;
            default:
                return field;
        }
    }

    static long parsePositiveLong(String rawValue, long defaultValue) {
        if (StringUtils.isBlank(rawValue)) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(rawValue.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static String formatBeijingTime(long epochSeconds) {
        return BEIJING_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds));
    }

    private record MatchResult(UserModel user, String source) {
        static MatchResult empty() {
            return new MatchResult(null, "none");
        }
    }

    private record ProvisionResult(UserModel user, boolean created, String source) {
    }

    private record DepartmentPlan(List<String> departmentIds, boolean successful) {
    }

    private record DepartmentIds(List<String> departmentIds, boolean successful) {
    }

    private record DepartmentUsers(List<UserDto> users, boolean successful) {
    }

    record ProvisioningName(String firstName, String lastName, String displayName) {
        static ProvisioningName empty() {
            return new ProvisioningName(null, null, null);
        }
    }

    record SyncResult(String alias, int listed, int matched, int created, int linked, int updated,
                      int reenabled, int disabled, boolean skipped, String reason) {
        static SyncResult empty(String alias) {
            return new SyncResult(alias, 0, 0, 0, 0, 0, 0, 0, false, null);
        }

        static SyncResult skipped(String alias, String reason) {
            return new SyncResult(alias, 0, 0, 0, 0, 0, 0, 0, true, reason);
        }

        static SyncResult aggregate(String alias, List<SyncResult> results) {
            if (results == null || results.isEmpty()) {
                return empty(alias);
            }
            SyncResult total = empty(alias);
            boolean allSkipped = true;
            for (SyncResult result : results) {
                total = total.plus(result);
                allSkipped &= result.skipped;
            }
            return new SyncResult(
                    alias,
                    total.listed,
                    total.matched,
                    total.created,
                    total.linked,
                    total.updated,
                    total.reenabled,
                    total.disabled,
                    allSkipped,
                    allSkipped ? total.reason : null);
        }

        private SyncResult plus(SyncResult other) {
            return new SyncResult(
                    alias,
                    listed + other.listed,
                    matched + other.matched,
                    created + other.created,
                    linked + other.linked,
                    updated + other.updated,
                    reenabled + other.reenabled,
                    disabled + other.disabled,
                    skipped && other.skipped,
                    reason != null ? reason : other.reason);
        }
    }
}
