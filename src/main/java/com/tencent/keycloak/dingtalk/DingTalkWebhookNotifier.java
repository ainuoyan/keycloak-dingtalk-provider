package com.tencent.keycloak.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

final class DingTalkWebhookNotifier {

    static final String NOTIFICATION_WEBHOOK_ENABLED = "notificationWebhookEnabled";
    static final String NOTIFICATION_WEBHOOK_URL = "notificationWebhookUrl";
    static final String NOTIFICATION_WEBHOOK_SECRET = "notificationWebhookSecret";

    private static final Logger logger = Logger.getLogger(DingTalkWebhookNotifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int MAX_ITEMS_PER_SECTION = 20;

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
                                       boolean hasPhone, boolean hasEmail) {
        if (!isEnabled(idp)) {
            return;
        }

        String title = "Keycloak 钉钉登录创建用户";
        String text = new StringBuilder()
                .append("## ").append(title).append("\n")
                .append("- Realm: ").append(realm.getName()).append("\n")
                .append("- IdP: ").append(idp.getAlias()).append("\n")
                .append("- Username: ").append(user.getUsername()).append("\n")
                .append("- DingTalk: ")
                .append("userId=").append(DingTalkIdentityProvider.mask(dingtalkUserId))
                .append(", externalId=").append(DingTalkIdentityProvider.mask(externalId))
                .append(", hasPhone=").append(hasPhone)
                .append(", hasEmail=").append(hasEmail)
                .toString();
        sendMarkdown(session, idp, title, text);
    }

    static SendResult sendTest(KeycloakSession session, RealmModel realm, IdentityProviderModel idp) {
        if (!isEnabled(idp)) {
            return SendResult.failed("webhook_disabled_or_url_missing", "");
        }

        String title = "Keycloak 钉钉机器人测试";
        String text = new StringBuilder()
                .append("## ").append(title).append("\n")
                .append("- Realm: ").append(realm.getName()).append("\n")
                .append("- IdP: ").append(idp.getAlias()).append("\n")
                .append("- Time: ")
                .append(DingTalkUserSyncTask.formatBeijingTime(System.currentTimeMillis() / 1000))
                .append("\n")
                .append("- Result: Webhook 配置可用")
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
        private final List<String> skippedCreates = new ArrayList<>();

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

        void flush() {
            if (!enabled || (createdUsers.isEmpty() && skippedCreates.isEmpty())) {
                return;
            }

            String title = "Keycloak 钉钉同步用户通知";
            StringBuilder text = new StringBuilder()
                    .append("## ").append(title).append("\n")
                    .append("- Realm: ").append(realm.getName()).append("\n")
                    .append("- IdP: ").append(idp.getAlias()).append("\n")
                    .append("- Mode: ").append(mode).append("\n")
                    .append("- Created: ").append(createdUsers.size()).append("\n")
                    .append("- Skipped create WARN: ").append(skippedCreates.size()).append("\n");

            appendSection(text, "新创建用户", createdUsers);
            appendSection(text, "跳过创建 WARN", skippedCreates);
            sendMarkdown(session, idp, title, text.toString());
        }

        private static void appendSection(StringBuilder text, String title, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            text.append("\n### ").append(title).append("\n");
            int limit = Math.min(items.size(), MAX_ITEMS_PER_SECTION);
            for (int i = 0; i < limit; i++) {
                text.append("- ").append(items.get(i)).append("\n");
            }
            if (items.size() > limit) {
                text.append("- ... omitted ").append(items.size() - limit).append(" more\n");
            }
        }

    }
}
