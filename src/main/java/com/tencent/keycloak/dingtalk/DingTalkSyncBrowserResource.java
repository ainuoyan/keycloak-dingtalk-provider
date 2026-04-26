package com.tencent.keycloak.dingtalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

public class DingTalkSyncBrowserResource {

    private static final Logger logger = Logger.getLogger(DingTalkSyncBrowserResource.class);

    private final KeycloakSession session;

    DingTalkSyncBrowserResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("debug")
    public Response debugSync(@QueryParam("alias") String alias, @QueryParam("key") String key) {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "realm_not_found"));
        }
        if (StringUtils.isBlank(alias)) {
            return json(Response.Status.BAD_REQUEST, Map.of("error", "alias_required"));
        }

        IdentityProviderModel idp = realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(provider -> alias.equals(provider.getAlias()))
                .findFirst()
                .orElse(null);
        if (idp == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "dingtalk_idp_not_found", "alias", alias));
        }

        String configuredKey = idp.getConfig().get(DingTalkIdentityProviderFactory.BROWSER_SYNC_DEBUG_KEY);
        if (StringUtils.isBlank(configuredKey)) {
            return json(Response.Status.FORBIDDEN, Map.of("error", "browser_debug_disabled", "alias", alias));
        }
        if (!constantTimeEquals(configuredKey, key)) {
            logger.warnf("Rejected DingTalk browser sync debug request. realm=%s, idp=%s",
                    realm.getName(), alias);
            return json(Response.Status.FORBIDDEN, Map.of("error", "invalid_debug_key", "alias", alias));
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            DingTalkUserSyncTask.SyncResult result = new DingTalkUserSyncTask()
                    .syncProviderNow(session, realm, alias);
            return Response.ok(Map.of(
                    "alias", result.alias(),
                    "listed", result.listed(),
                    "matched", result.matched(),
                    "created", result.created(),
                    "linked", result.linked(),
                    "updated", result.updated(),
                    "reenabled", result.reenabled(),
                    "disabled", result.disabled(),
                    "skipped", result.skipped(),
                    "reason", result.reason() == null ? "" : result.reason()
            ), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (Exception e) {
            logger.errorf(e, "DingTalk browser sync debug failed. realm=%s, idp=%s",
                    realm.getName(), alias);
            return json(Response.Status.INTERNAL_SERVER_ERROR,
                    Map.of("error", "sync_failed", "alias", alias, "message", StringUtils.defaultString(e.getMessage())));
        } finally {
            session.getContext().setRealm(previousRealm);
        }
    }

    @GET
    @Path("cleanup-numeric-users")
    public Response cleanupNumericUsers(@QueryParam("alias") String alias,
                                        @QueryParam("key") String key) {
        return cleanupNumericUsers(alias, key, null, false);
    }

    @POST
    @Path("cleanup-numeric-users")
    public Response cleanupNumericUsersPost(@QueryParam("alias") String alias,
                                            @QueryParam("key") String key,
                                            @QueryParam("confirm") String confirm) {
        return cleanupNumericUsers(alias, key, confirm, true);
    }

    private Response cleanupNumericUsers(String alias, String key, String confirm, boolean allowExecute) {
        RealmModel realm = session.getContext().getRealm();
        IdentityProviderModel idp = getAuthorizedDingTalkProvider(realm, alias, key);
        if (idp == null) {
            return json(Response.Status.FORBIDDEN, Map.of("error", "unauthorized_or_not_found"));
        }

        boolean execute = allowExecute && "DELETE_NUMERIC_DINGTALK_USERS".equals(confirm);
        List<UserModel> candidates = findNumericDingTalkUsers(realm, idp);
        List<String> usernames = candidates.stream()
                .map(UserModel::getUsername)
                .sorted()
                .toList();

        if (!execute) {
            return Response.ok(Map.of(
                    "alias", alias,
                    "dryRun", true,
                    "candidateCount", usernames.size(),
                    "usernames", usernames,
                    "deleteMethod", "POST",
                    "confirm", "DELETE_NUMERIC_DINGTALK_USERS"
            ), MediaType.APPLICATION_JSON_TYPE).build();
        }

        int deleted = 0;
        for (UserModel user : candidates) {
            logger.warnf("Deleting numeric DingTalk-managed user. realm=%s, idp=%s, username=%s",
                    realm.getName(), alias, user.getUsername());
            if (session.users().removeUser(realm, user)) {
                deleted++;
            }
        }

        return Response.ok(Map.of(
                "alias", alias,
                "dryRun", false,
                "candidateCount", usernames.size(),
                "deleted", deleted,
                "usernames", usernames
        ), MediaType.APPLICATION_JSON_TYPE).build();
    }

    private Response json(Response.Status status, Map<String, Object> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }

    private IdentityProviderModel getAuthorizedDingTalkProvider(RealmModel realm, String alias, String key) {
        if (realm == null || StringUtils.isBlank(alias)) {
            return null;
        }

        IdentityProviderModel idp = realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(provider -> alias.equals(provider.getAlias()))
                .findFirst()
                .orElse(null);
        if (idp == null) {
            return null;
        }

        String configuredKey = idp.getConfig().get(DingTalkIdentityProviderFactory.BROWSER_SYNC_DEBUG_KEY);
        if (StringUtils.isBlank(configuredKey) || !constantTimeEquals(configuredKey, key)) {
            logger.warnf("Rejected DingTalk browser debug request. realm=%s, idp=%s",
                    realm.getName(), alias);
            return null;
        }

        return idp;
    }

    private List<UserModel> findNumericDingTalkUsers(RealmModel realm, IdentityProviderModel idp) {
        return session.users()
                .searchForUserByUserAttributeStream(realm, "dingtalk_idp_alias", idp.getAlias())
                .filter(user -> user.getUsername() != null && user.getUsername().matches("\\d+"))
                .filter(user -> "true".equals(user.getFirstAttribute("dingtalk_managed")))
                .filter(this::isSyncCreatedOrLegacyNumericUser)
                .filter(user -> hasFederatedIdentity(realm, user, idp.getAlias()))
                .toList();
    }

    private boolean isSyncCreatedOrLegacyNumericUser(UserModel user) {
        if ("true".equals(user.getFirstAttribute(DingTalkUserSyncTask.DINGTALK_CREATED_BY_SYNC))) {
            return true;
        }

        String username = user.getUsername();
        String dingtalkUserId = user.getFirstAttribute("dingtalk_userid");
        return StringUtils.isNotBlank(username) && username.equals(dingtalkUserId);
    }

    private boolean hasFederatedIdentity(RealmModel realm, UserModel user, String alias) {
        FederatedIdentityModel identity = session.users().getFederatedIdentity(realm, user, alias);
        return identity != null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
