package com.tencent.keycloak.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

final class DingTalkWebhookNotifier {

    static final String NOTIFICATION_WEBHOOK_ENABLED = "notificationWebhookEnabled";
    static final String NOTIFICATION_WEBHOOK_URL = "notificationWebhookUrl";
    static final String NOTIFICATION_WEBHOOK_SECRET = "notificationWebhookSecret";

    private static final Logger logger = Logger.getLogger(DingTalkWebhookNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int MAX_ITEMS_PER_SECTION = 20;
    private static final int MAX_FAILURE_REASON_LENGTH = 300;
    static final String CREATED_USERNAME_REVIEW_ACTION =
            "核对用户名和邮箱；如多音字或邮箱不正确，请在 Keycloak/AD 后台修正。";

    private DingTalkWebhookNotifier() {
    }

    static Batch syncBatch(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                           String mode, boolean dryRun) {
        if (dryRun || !isEnabled(idp)) {
            return Batch.disabled();
        }
        return new Batch(session, realm, idp, mode);
    }

    static void notifyLoginUserCreated(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                       UserModel user, String externalId, String dingtalkUserId,
                                       String nickname, boolean hasPhone, boolean hasEmail) {
        if (!isEnabled(idp)) {
            return;
        }

        String title = "Keycloak 钉钉登录创建用户";
        String realmName = realm.getName();
        String username = user.getUsername();
        String dingtalkUser = "userId=" + DingTalkIdentityProvider.mask(dingtalkUserId)
                + ", externalId=" + DingTalkIdentityProvider.mask(externalId)
                + ", nickname=" + sanitizeLine(nickname)
                + ", hasPhone=" + hasPhone
                + ", hasEmail=" + hasEmail;
        String text = loginUserCreatedMarkdown(
                realmName,
                idp,
                username,
                dingtalkUser);
        sendCreatedMarkdownAfterCommit(
                session,
                realm,
                idp,
                title,
                text,
                List.of(username),
                "Keycloak 钉钉登录创建未落库告警",
                loginUserCreatedRollbackMarkdown(realmName, idp, username, dingtalkUser),
                "Keycloak 钉钉登录创建未确认告警",
                verification -> loginUserCreatedUnconfirmedMarkdown(
                        realmName, idp, username, dingtalkUser, verification));
    }

    static void notifySyncFailed(KeycloakSession session, RealmModel realm, IdentityProviderModel idp,
                                 String mode, boolean dryRun, Throwable error) {
        if (dryRun || !isEnabled(idp)) {
            return;
        }

        String title = "Keycloak 钉钉同步失败告警";
        sendMarkdown(session, idp, title,
                syncFailureMarkdown(realm.getName(), idp.getAlias(), mode, error));
    }

    static SendResult sendTest(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        if (!isEnabled(idp)) {
            return SendResult.failed("webhook_disabled_or_url_missing", "");
        }

        String title = "Keycloak 钉钉机器人测试";
        String text = new StringBuilder()
                .append("## ").append(title).append("\n")
                .append("- 领域：").append(realm.getName()).append("\n")
                .append("- 身份源：").append(idp.getAlias()).append("\n")
                .append("- 时间：")
                .append(DingTalkUserSyncTask.formatBeijingTime(System.currentTimeMillis() / 1000))
                .append("\n")
                .append("- 结果：Webhook 配置可用")
                .toString();
        return sendMarkdown(session, idp, title, text);
    }

    static boolean isEnabled(IdentityProviderModel idp) {
        return idp != null && isEnabled(idp.getConfig());
    }

    static boolean isEnabled(Map<String, String> config) {
        return config != null
                && Boolean.parseBoolean(config.getOrDefault(NOTIFICATION_WEBHOOK_ENABLED, "false"))
                && StringUtils.isNotBlank(config.get(NOTIFICATION_WEBHOOK_URL));
    }

    private static SendResult sendMarkdown(KeycloakSession session, IdentityProviderModel idp,
                                           String title, String text) {
        String webhookUrl = idp.getConfig().get(NOTIFICATION_WEBHOOK_URL);
        String secret = idp.getConfig().get(NOTIFICATION_WEBHOOK_SECRET);
        String targetUrl = signedWebhookUrl(webhookUrl, secret);
        try {
            Map<String, Object> body = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of(
                            "title", title,
                            "text", text));
            String response = SimpleHttp.create(session)
                    .doPost(targetUrl)
                    .header("Content-Type", "application/json")
                    .json(body)
                    .asString();
            SendResult result = parseRobotResponse(response);
            if (!result.success()) {
                logger.warnf("DingTalk webhook notification rejected. idp=%s, response=%s",
                        idp.getAlias(), DingTalkIdentityProvider.sanitizeForLog(response));
                return result;
            }
            logger.debugf("DingTalk webhook notification response: %s",
                    DingTalkIdentityProvider.sanitizeForLog(response));
            return result;
        } catch (Exception e) {
            logger.warnf(e, "Failed to send DingTalk webhook notification. idp=%s, webhook=%s",
                    idp.getAlias(), DingTalkIdentityProvider.sanitizeUriForLog(targetUrl));
            return SendResult.failed("send_failed: " + e.getClass().getSimpleName(), "");
        }
    }

    private static void sendCreatedMarkdownAfterCommit(KeycloakSession session, RealmModel realm,
                                                       IdentityProviderModel idp, String title, String text,
                                                       List<String> createdUsernames,
                                                       String rollbackTitle, String rollbackText,
                                                       String unconfirmedTitle,
                                                       Function<CreatedUserVerification, String> unconfirmedText) {
        String realmId = realm == null ? null : realm.getId();
        String realmName = realm == null ? "" : realm.getName();
        String idpAlias = idp == null ? "" : idp.getAlias();
        List<String> usernamesToVerify = distinctUsernames(createdUsernames);
        KeycloakTransactionManager transactionManager =
                session == null ? null : session.getTransactionManager();
        if (transactionManager == null || !transactionManager.isActive()) {
            sendVerifiedCreatedMarkdown(session, realmId, realmName, idp, idpAlias, title, text,
                    usernamesToVerify, unconfirmedTitle, unconfirmedText);
            return;
        }

        transactionManager.enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override
            protected void commitImpl() {
                sendVerifiedCreatedMarkdown(session, realmId, realmName, idp, idpAlias, title, text,
                        usernamesToVerify, unconfirmedTitle, unconfirmedText);
            }

            @Override
            protected void rollbackImpl() {
                logger.warnf("Skip DingTalk webhook success notification because transaction rolled back. idp=%s",
                        idpAlias);
                if (StringUtils.isNotBlank(rollbackTitle) && StringUtils.isNotBlank(rollbackText)) {
                    sendMarkdown(session, idp, rollbackTitle, rollbackText);
                }
            }
        });
    }

    private static void sendVerifiedCreatedMarkdown(KeycloakSession session, String realmId, String realmName,
                                                    IdentityProviderModel idp, String idpAlias,
                                                    String title, String text,
                                                    List<String> createdUsernames,
                                                    String unconfirmedTitle,
                                                    Function<CreatedUserVerification, String> unconfirmedText) {
        CreatedUserVerification verification =
                verifyCreatedUsersAfterCommit(session, realmId, realmName, idpAlias, createdUsernames);
        if (verification.isSuccess()) {
            sendMarkdown(session, idp, title, text);
            return;
        }

        logger.warnf("DingTalk created users are not searchable after transaction commit. realm=%s, idp=%s, usernames=%s, error=%s",
                realmName, idpAlias, verification.missingUsernames(), verification.error());
        sendMarkdown(session, idp, unconfirmedTitle, unconfirmedText.apply(verification));
    }

    private static CreatedUserVerification verifyCreatedUsersAfterCommit(KeycloakSession session, String realmId,
                                                                         String realmName, String idpAlias,
                                                                         List<String> createdUsernames) {
        List<String> expectedUsernames = distinctUsernames(createdUsernames);
        if (expectedUsernames.isEmpty()
                || session == null
                || StringUtils.isBlank(realmId)
                || session.getKeycloakSessionFactory() == null) {
            return CreatedUserVerification.ok();
        }

        KeycloakSessionFactory sessionFactory = session.getKeycloakSessionFactory();
        KeycloakContext sourceContext = session.getContext();
        try {
            List<String> missing = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, sourceContext, verifySession -> {
                RealmModel currentRealm = DingTalkUserSyncTask.resolveRealmAndBindContext(verifySession, realmId);
                if (currentRealm == null) {
                    return expectedUsernames;
                }

                List<String> result = new ArrayList<>();
                for (String username : expectedUsernames) {
                    UserModel user = verifySession.users().getUserByUsername(currentRealm, username);
                    if (user == null) {
                        result.add(username);
                    }
                }
                return result;
            }, "Verify DingTalk created users after commit");
            return new CreatedUserVerification(missing, "");
        } catch (Exception e) {
            logger.warnf(e, "Failed to verify DingTalk created users after transaction commit. realm=%s, idp=%s",
                    realmName, idpAlias);
            return new CreatedUserVerification(
                    expectedUsernames,
                    "事务提交后无法确认用户是否可检索：" + e.getClass().getSimpleName());
        }
    }

    static SendResult parseRobotResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return SendResult.success("");
        }

        try {
            JsonNode json = MAPPER.readTree(response);
            if (json.has("errcode") && json.path("errcode").asInt() != 0) {
                String error = "dingtalk_error: " + json.path("errcode").asInt()
                        + " " + json.path("errmsg").asText("");
                return SendResult.failed(sanitizeLine(error), DingTalkIdentityProvider.sanitizeForLog(response));
            }
            return SendResult.success(DingTalkIdentityProvider.sanitizeForLog(response));
        } catch (Exception ignored) {
            return SendResult.success(DingTalkIdentityProvider.sanitizeForLog(response));
        }
    }

    static String signedWebhookUrl(String webhookUrl, String secret) {
        if (StringUtils.isBlank(secret)) {
            return webhookUrl;
        }

        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            String sign = Base64.getEncoder()
                    .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
            String encodedSign = URLEncoder.encode(sign, StandardCharsets.UTF_8);
            String separator = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + encodedSign;
        } catch (Exception e) {
            logger.warn("Failed to sign DingTalk webhook URL, sending unsigned notification", e);
            return webhookUrl;
        }
    }

    private static String sanitizeLine(String value) {
        return StringUtils.defaultString(value).replace('\n', ' ').replace('\r', ' ');
    }

    private static String codeSpan(String value) {
        return "`" + sanitizeLine(value).replace('`', '\'') + "`";
    }

    private static String displayFlag(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "有";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "无";
        }
        return "未知";
    }

    private static String displayText(String value, String fallback) {
        return StringUtils.defaultIfBlank(sanitizeLine(value), fallback);
    }

    private static List<String> distinctUsernames(List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String username : usernames) {
            String sanitized = StringUtils.trimToNull(sanitizeLine(username));
            if (sanitized != null) {
                distinct.add(sanitized);
            }
        }
        return new ArrayList<>(distinct);
    }

    private static String displayMode(String mode) {
        return switch (StringUtils.defaultString(mode)) {
            case "manual" -> "手动同步";
            case "periodic" -> "定时同步";
            case "browser" -> "浏览器同步";
            case "admin-get" -> "管理端 GET 同步";
            case "admin-post" -> "管理端同步";
            default -> sanitizeLine(mode);
        };
    }

    private static String displayCreateReason(String reason) {
        return switch (StringUtils.defaultString(reason)) {
            case "username is empty" -> "无法生成用户名";
            case "username already exists and no trusted match" -> "用户名已存在，且没有通过可信规则匹配到该用户";
            default -> sanitizeLine(reason);
        };
    }

    private static String describeDingTalkIdentity(String dingtalkUser) {
        List<String> parts = new ArrayList<>();
        String userId = describeField(dingtalkUser, "userId");
        String externalId = describeField(dingtalkUser, "externalId");
        if (StringUtils.isNotBlank(userId)) {
            parts.add("钉钉用户ID=" + userId);
        }
        if (StringUtils.isNotBlank(externalId)) {
            parts.add("外部ID=" + externalId);
        }
        if (parts.isEmpty()) {
            return sanitizeLine(dingtalkUser);
        }
        return String.join(", ", parts);
    }

    private static String describeField(String source, String field) {
        if (StringUtils.isBlank(source) || StringUtils.isBlank(field)) {
            return "";
        }
        String prefix = field + "=";
        int start = source.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        int valueStart = start + prefix.length();
        int end = source.indexOf(',', valueStart);
        return sanitizeLine(end < 0 ? source.substring(valueStart) : source.substring(valueStart, end)).trim();
    }

    private static String emailRuleText(IdentityProviderModel idp) {
        Map<String, String> config = idp == null ? null : idp.getConfig();
        String domain = config == null ? null : DingTalkUserSyncTask.normalizeProvisionedEmailDomain(
                config.get(DingTalkUserSyncTask.PROVISIONED_EMAIL_DOMAIN));
        if (StringUtils.isBlank(domain)) {
            return "钉钉邮箱";
        }
        return "用户名 + @" + sanitizeLine(domain);
    }

    static String loginUserCreatedMarkdown(String realmName, IdentityProviderModel idp, String username,
                                           String dingtalkUser) {
        String title = "Keycloak 钉钉登录创建用户";
        return new StringBuilder()
                .append("## ").append(title).append("\n\n")
                .append("> **领域**：").append(codeSpan(realmName)).append("\n")
                .append("> **身份源**：").append(codeSpan(idp.getAlias())).append("\n\n")
                .append("### 新创建用户\n\n")
                .append("#### 用户名：").append(codeSpan(username)).append("\n")
                .append("- 昵称：").append(codeSpan(displayText(describeField(dingtalkUser, "nickname"), "无"))).append("\n")
                .append("- 钉钉标识：").append(codeSpan(describeDingTalkIdentity(dingtalkUser))).append("\n")
                .append("- 手机：").append(displayFlag(describeField(dingtalkUser, "hasPhone"))).append("\n")
                .append("- 邮箱：").append(displayFlag(describeField(dingtalkUser, "hasEmail"))).append("\n")
                .append("- 邮箱规则：").append(codeSpan(emailRuleText(idp))).append("\n\n")
                .append("### 处理建议\n\n")
                .append("- ").append(CREATED_USERNAME_REVIEW_ACTION)
                .toString();
    }

    static String loginUserCreatedRollbackMarkdown(String realmName, IdentityProviderModel idp, String username,
                                                   String dingtalkUser) {
        StringBuilder text = createdUserProblemMarkdown(
                "Keycloak 钉钉登录创建未落库告警",
                realmName,
                idp,
                username,
                dingtalkUser,
                "事务已回滚，用户没有落库。");
        return text.toString();
    }

    static String loginUserCreatedUnconfirmedMarkdown(String realmName, IdentityProviderModel idp, String username,
                                                      String dingtalkUser, CreatedUserVerification verification) {
        StringBuilder text = createdUserProblemMarkdown(
                "Keycloak 钉钉登录创建未确认告警",
                realmName,
                idp,
                username,
                dingtalkUser,
                "事务提交后未能确认该用户可查询。");
        appendVerificationSection(text, verification);
        return text.toString();
    }

    private static StringBuilder createdUserProblemMarkdown(String title, String realmName, IdentityProviderModel idp,
                                                            String username, String dingtalkUser, String status) {
        return new StringBuilder()
                .append("## ").append(title).append("\n\n")
                .append("> **领域**：").append(codeSpan(realmName)).append("\n")
                .append("> **身份源**：").append(codeSpan(idp.getAlias())).append("\n\n")
                .append("### 事务状态\n\n")
                .append("- 结果：").append(status).append("\n")
                .append("- 建议：检查 Keycloak 日志、AD/LDAP 同步注册和用户存储写入权限。\n\n")
                .append("### 新创建用户\n\n")
                .append("#### 用户名：").append(codeSpan(username)).append("\n")
                .append("- 昵称：").append(codeSpan(displayText(describeField(dingtalkUser, "nickname"), "无"))).append("\n")
                .append("- 钉钉标识：").append(codeSpan(describeDingTalkIdentity(dingtalkUser))).append("\n")
                .append("- 手机：").append(displayFlag(describeField(dingtalkUser, "hasPhone"))).append("\n")
                .append("- 邮箱：").append(displayFlag(describeField(dingtalkUser, "hasEmail"))).append("\n")
                .append("- 邮箱规则：").append(codeSpan(emailRuleText(idp))).append("\n");
    }

    private static void appendVerificationSection(StringBuilder text, CreatedUserVerification verification) {
        if (verification == null || verification.isSuccess()) {
            return;
        }
        text.append("\n### 未确认用户名\n\n");
        for (String username : verification.missingUsernames()) {
            text.append("- ").append(codeSpan(username)).append("\n");
        }
        text.append("- 未确认原因：").append(codeSpan("用户不存在")).append("\n");
        if (StringUtils.isNotBlank(verification.error())) {
            text.append("- 确认失败原因：").append(codeSpan(verification.error())).append("\n");
        }
    }

    static String syncFailureMarkdown(String realmName, String idpAlias, String mode, Throwable error) {
        String errorClass = error == null ? "Unknown" : error.getClass().getSimpleName();
        String reason = error == null ? "" : sanitizeFailureReason(error.getMessage());
        StringBuilder text = new StringBuilder()
                .append("## Keycloak 钉钉同步失败告警\n")
                .append("- 领域：").append(sanitizeLine(realmName)).append("\n")
                .append("- 身份源：").append(sanitizeLine(idpAlias)).append("\n")
                .append("- 执行方式：").append(displayMode(mode)).append("\n")
                .append("- 异常类型：").append(sanitizeLine(errorClass));
        if (StringUtils.isNotBlank(reason)) {
            text.append("\n- 原因：").append(reason);
        }
        return text.toString();
    }

    private static String sanitizeFailureReason(String reason) {
        String sanitized = DingTalkIdentityProvider.sanitizeForLog(sanitizeLine(reason));
        return StringUtils.abbreviate(sanitized, MAX_FAILURE_REASON_LENGTH);
    }

    record CreatedUserVerification(List<String> missingUsernames, String error) {
        CreatedUserVerification {
            missingUsernames = missingUsernames == null ? List.of() : List.copyOf(missingUsernames);
            error = StringUtils.defaultString(error);
        }

        static CreatedUserVerification ok() {
            return new CreatedUserVerification(List.of(), "");
        }

        boolean isSuccess() {
            return missingUsernames.isEmpty() && StringUtils.isBlank(error);
        }
    }

    record SendResult(boolean success, String error, String response) {
        static SendResult success(String response) {
            return new SendResult(true, "", StringUtils.defaultString(response));
        }

        static SendResult failed(String error, String response) {
            return new SendResult(false, StringUtils.defaultString(error), StringUtils.defaultString(response));
        }
    }

    static final class Batch {
        private final KeycloakSession session;
        private final RealmModel realm;
        private final IdentityProviderModel idp;
        private final String mode;
        private final boolean enabled;
        private final List<String> createdUsers = new ArrayList<>();
        private final List<String> createdUsernames = new ArrayList<>();
        private final List<String> skippedCreates = new ArrayList<>();
        private final List<String> disabledUsers = new ArrayList<>();

        private Batch(KeycloakSession session, RealmModel realm, IdentityProviderModel idp, String mode) {
            this.session = session;
            this.realm = realm;
            this.idp = idp;
            this.mode = mode;
            this.enabled = true;
        }

        private Batch() {
            this.session = null;
            this.realm = null;
            this.idp = null;
            this.mode = null;
            this.enabled = false;
        }

        static Batch disabled() {
            return new Batch();
        }

        void addCreatedUser(String username, String dingtalkUser) {
            if (!enabled) {
                return;
            }
            createdUsernames.add(DingTalkWebhookNotifier.sanitizeLine(username));
            createdUsers.add("username=" + DingTalkWebhookNotifier.sanitizeLine(username)
                    + ", dingtalkUser=" + DingTalkWebhookNotifier.sanitizeLine(dingtalkUser));
        }

        void addSkippedCreate(String username, String reason, String dingtalkUser) {
            if (!enabled) {
                return;
            }
            skippedCreates.add("username=" + DingTalkWebhookNotifier.sanitizeLine(username)
                    + ", reason=" + DingTalkWebhookNotifier.sanitizeLine(reason)
                    + ", dingtalkUser=" + DingTalkWebhookNotifier.sanitizeLine(dingtalkUser));
        }

        void addDisabledUser(String username, String reason) {
            if (!enabled) {
                return;
            }
            disabledUsers.add("username=" + DingTalkWebhookNotifier.sanitizeLine(username)
                    + ", reason=" + DingTalkWebhookNotifier.sanitizeLine(reason));
        }

        void flush() {
            if (!enabled || (createdUsers.isEmpty() && skippedCreates.isEmpty() && disabledUsers.isEmpty())) {
                return;
            }

            String title = "Keycloak 钉钉同步用户通知";
            sendCreatedMarkdownAfterCommit(
                    session,
                    realm,
                    idp,
                    title,
                    toMarkdown(),
                    createdUsernames,
                    "Keycloak 钉钉同步事务回滚告警",
                    toRollbackMarkdown(),
                    "Keycloak 钉钉同步创建未确认告警",
                    this::toUnconfirmedMarkdown);
        }

        String toMarkdown() {
            String title = "Keycloak 钉钉同步用户通知";
            StringBuilder text = new StringBuilder()
                    .append("## ").append(title).append("\n\n")
                    .append("> **领域**：").append(codeSpan(realm.getName())).append("\n")
                    .append("> **身份源**：").append(codeSpan(idp.getAlias())).append("\n")
                    .append("> **执行方式**：").append(codeSpan(displayMode(mode))).append("\n\n")
                    .append("### 汇总\n\n")
                    .append("- 新创建：").append(createdUsers.size()).append("\n")
                    .append("- 跳过创建告警：").append(skippedCreates.size()).append("\n")
                    .append("- 禁用离职用户：").append(disabledUsers.size()).append("\n");
            if (!createdUsers.isEmpty()) {
                text.append("- 邮箱规则：").append(codeSpan(emailRuleText(idp))).append("\n")
                        .append("- 处理建议：").append(CREATED_USERNAME_REVIEW_ACTION).append("\n");
            }
            if (!disabledUsers.isEmpty()) {
                text.append("- 禁用原因：").append(codeSpan("未出现在本轮钉钉同步结果中")).append("\n");
            }

            appendCreatedSection(text, createdUsers);
            appendSkippedSection(text, skippedCreates);
            appendDisabledSection(text, disabledUsers);
            return text.toString();
        }

        String toRollbackMarkdown() {
            StringBuilder text = baseProblemMarkdown(
                    "Keycloak 钉钉同步事务回滚告警",
                    "事务已回滚，本轮新创建和禁用动作没有落库。");
            appendCreatedSection(text, createdUsers);
            appendSkippedSection(text, skippedCreates);
            appendDisabledSection(text, disabledUsers);
            return text.toString();
        }

        String toUnconfirmedMarkdown(CreatedUserVerification verification) {
            StringBuilder text = baseProblemMarkdown(
                    "Keycloak 钉钉同步创建未确认告警",
                    "事务提交后，部分新创建用户无法确认可查询。");
            appendVerificationSection(text, verification);
            appendCreatedSection(text, createdUsers);
            appendSkippedSection(text, skippedCreates);
            appendDisabledSection(text, disabledUsers);
            return text.toString();
        }

        private StringBuilder baseProblemMarkdown(String title, String status) {
            StringBuilder text = new StringBuilder()
                    .append("## ").append(title).append("\n\n")
                    .append("> **领域**：").append(codeSpan(realm.getName())).append("\n")
                    .append("> **身份源**：").append(codeSpan(idp.getAlias())).append("\n")
                    .append("> **执行方式**：").append(codeSpan(displayMode(mode))).append("\n\n")
                    .append("### 事务状态\n\n")
                    .append("- 结果：").append(status).append("\n")
                    .append("- 建议：检查 Keycloak 日志、AD/LDAP 同步注册和用户存储写入权限。\n\n")
                    .append("### 汇总\n\n")
                    .append("- 新创建：").append(createdUsers.size()).append("\n")
                    .append("- 跳过创建告警：").append(skippedCreates.size()).append("\n")
                    .append("- 禁用离职用户：").append(disabledUsers.size()).append("\n");
            if (!createdUsers.isEmpty()) {
                text.append("- 邮箱规则：").append(codeSpan(emailRuleText(idp))).append("\n")
                        .append("- 处理建议：").append(CREATED_USERNAME_REVIEW_ACTION).append("\n");
            }
            return text;
        }

        private static void appendCreatedSection(StringBuilder text, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            text.append("\n### 新创建用户\n\n");
            int limit = Math.min(items.size(), MAX_ITEMS_PER_SECTION);
            for (int i = 0; i < limit; i++) {
                String item = items.get(i);
                text.append("#### 用户名：").append(codeSpan(describeField(item, "username"))).append("\n")
                        .append("- 昵称：").append(codeSpan(displayText(describeField(item, "nickname"), "无"))).append("\n")
                        .append("- 钉钉标识：").append(codeSpan(describeDingTalkIdentity(item))).append("\n")
                        .append("- 手机：").append(displayFlag(describeField(item, "hasPhone"))).append("\n")
                        .append("- 邮箱：").append(displayFlag(describeField(item, "hasEmail"))).append("\n\n");
            }
            if (items.size() > limit) {
                text.append("- 还有 ").append(items.size() - limit).append(" 条未展示\n");
            }
        }

        private static void appendSkippedSection(StringBuilder text, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            text.append("\n### 跳过创建告警\n\n");
            int limit = Math.min(items.size(), MAX_ITEMS_PER_SECTION);
            for (int i = 0; i < limit; i++) {
                String item = items.get(i);
                text.append("#### 用户名：").append(codeSpan(displayText(describeField(item, "username"), "未生成"))).append("\n")
                        .append("- 原因：").append(codeSpan(displayCreateReason(describeField(item, "reason")))).append("\n")
                        .append("- 昵称：").append(codeSpan(displayText(describeField(item, "nickname"), "无"))).append("\n")
                        .append("- 钉钉标识：").append(codeSpan(describeDingTalkIdentity(item))).append("\n")
                        .append("- 手机：").append(displayFlag(describeField(item, "hasPhone"))).append("\n")
                        .append("- 邮箱：").append(displayFlag(describeField(item, "hasEmail"))).append("\n\n");
            }
            if (items.size() > limit) {
                text.append("- 还有 ").append(items.size() - limit).append(" 条未展示\n");
            }
        }

        private static void appendDisabledSection(StringBuilder text, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            text.append("\n### 禁用离职用户\n\n");
            int limit = Math.min(items.size(), MAX_ITEMS_PER_SECTION);
            for (int i = 0; i < limit; i++) {
                String item = items.get(i);
                text.append("- ").append(codeSpan(describeField(item, "username")))
                        .append("，原因：").append(codeSpan(describeField(item, "reason"))).append("\n");
            }
            if (items.size() > limit) {
                text.append("- 还有 ").append(items.size() - limit).append(" 条未展示\n");
            }
        }
    }
}
