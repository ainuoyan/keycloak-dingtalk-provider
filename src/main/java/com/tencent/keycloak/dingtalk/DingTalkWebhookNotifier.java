package com.tencent.keycloak.dingtalk;

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

    static boolean isEnabled(IdentityProviderModel idp) {
        return idp != null && isEnabled(idp.getConfig());
    }

    static boolean isEnabled(Map<String, String> config) {
        return config != null
                && Boolean.parseBoolean(config.getOrDefault(NOTIFICATION_WEBHOOK_ENABLED, "false"))
                && StringUtils.isNotBlank(config.get(NOTIFICATION_WEBHOOK_URL));
    }

    private static void sendMarkdown(KeycloakSession session, IdentityProviderModel idp, String title, String text) {
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
            logger.debugf("DingTalk webhook notification response: %s",
                    DingTalkIdentityProvider.sanitizeForLog(response));
        } catch (Exception e) {
            logger.warnf(e, "Failed to send DingTalk webhook notification. idp=%s, webhook=%s",
                    idp.getAlias(), DingTalkIdentityProvider.sanitizeUriForLog(targetUrl));
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
            createdUsers.add("username=" + sanitizeLine(username) + ", dingtalkUser=" + sanitizeLine(dingtalkUser));
        }

        void addSkippedCreate(String username, String reason, String dingtalkUser) {
            if (!enabled) {
                return;
            }
            skippedCreates.add("username=" + sanitizeLine(username)
                    + ", reason=" + sanitizeLine(reason)
                    + ", dingtalkUser=" + sanitizeLine(dingtalkUser));
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

        private static String sanitizeLine(String value) {
            return StringUtils.defaultString(value).replace('\n', ' ').replace('\r', ' ');
        }
    }
}
