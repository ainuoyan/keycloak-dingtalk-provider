package com.tencent.keycloak.dingtalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

public class DingTalkSyncBrowserResource {

    private static final Logger logger = Logger.getLogger(DingTalkSyncBrowserResource.class);

    private final KeycloakSession session;

    DingTalkSyncBrowserResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("debug")
    public Response debugSync(@QueryParam("alias") String alias, @QueryParam("key") String key) {
        return sync(alias, key, null, true);
    }

    @GET
    @Path("run")
    public Response runSync(@QueryParam("alias") String alias,
                            @QueryParam("key") String key,
                            @QueryParam("confirm") String confirm) {
        return sync(alias, key, confirm, false);
    }

    private Response sync(String alias, String key, String confirm, boolean dryRun) {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "realm_not_found"));
        }
        if (StringUtils.isBlank(alias)) {
            return json(Response.Status.BAD_REQUEST, Map.of("error", "alias_required"));
        }
        if (!dryRun && !DingTalkSyncAdminResource.RUN_CONFIRM.equals(confirm)) {
            return json(Response.Status.BAD_REQUEST, Map.of(
                    "error", "confirm_required",
                    "confirm", DingTalkSyncAdminResource.RUN_CONFIRM
            ));
        }

        IdentityProviderModel idp = getDingTalkProvider(realm, alias);
        if (idp == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "dingtalk_idp_not_found", "alias", alias));
        }
        Response forbidden = validateBrowserAccess(realm, idp, alias, key);
        if (forbidden != null) {
            return forbidden;
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            DingTalkUserSyncTask syncTask = new DingTalkUserSyncTask();
            DingTalkUserSyncTask.SyncResult result = dryRun
                    ? syncTask.previewProviderNow(session, realm, alias)
                    : syncTask.syncProviderNow(session, realm, alias);
            return syncResultResponse(result, dryRun);
        } catch (Exception e) {
            logger.errorf(e, "DingTalk browser sync failed. realm=%s, idp=%s, dryRun=%s",
                    realm.getName(), alias, dryRun);
            return json(Response.Status.INTERNAL_SERVER_ERROR,
                    Map.of("error", "sync_failed", "alias", alias, "dryRun", dryRun,
                            "message", StringUtils.defaultString(e.getMessage())));
        } finally {
            session.getContext().setRealm(previousRealm);
        }
    }

    @GET
    @Path("cleanup-numeric-users")
    public Response cleanupNumericUsers(@QueryParam("alias") String alias,
                                        @QueryParam("key") String key) {
        return previewNumericUserCleanup(alias, key);
    }

    @POST
    @Path("cleanup-numeric-users")
    public Response cleanupNumericUsersPost(@QueryParam("alias") String alias,
                                            @QueryParam("key") String key) {
        return previewNumericUserCleanup(alias, key);
    }

    private Response previewNumericUserCleanup(String alias, String key) {
        RealmModel realm = session.getContext().getRealm();
        IdentityProviderModel idp = getAuthorizedDingTalkProvider(realm, alias, key);
        if (idp == null) {
            return json(Response.Status.FORBIDDEN, Map.of("error", "unauthorized_or_not_found"));
        }

        DingTalkNumericUserCleanup.CleanupResult result =
                DingTalkNumericUserCleanup.preview(session, realm, idp);
        return Response.ok(Map.of(
                "alias", result.alias(),
                "dryRun", true,
                "candidateCount", result.candidateCount(),
                "usernames", result.usernames(),
                "deleteMethod", "POST admin endpoint",
                "confirm", DingTalkNumericUserCleanup.CONFIRM,
                "adminPath", "/admin/realms/" + realm.getName()
                        + "/dingtalk-sync/cleanup-numeric-users?alias=" + alias
                        + "&confirm=" + DingTalkNumericUserCleanup.CONFIRM,
                "message", "Browser cleanup endpoint is preview-only. Use the admin endpoint to delete users."
        ), MediaType.APPLICATION_JSON_TYPE).build();
    }

    private Response json(Response.Status status, Map<String, Object> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }

    private Response syncResultResponse(DingTalkUserSyncTask.SyncResult result, boolean dryRun) {
        return Response.ok(Map.ofEntries(
                Map.entry("alias", result.alias()),
                Map.entry("dryRun", dryRun),
                Map.entry("listed", result.listed()),
                Map.entry("matched", result.matched()),
                Map.entry("created", result.created()),
                Map.entry("linked", result.linked()),
                Map.entry("updated", result.updated()),
                Map.entry("reenabled", result.reenabled()),
                Map.entry("disabled", result.disabled()),
                Map.entry("skipped", result.skipped()),
                Map.entry("reason", result.reason() == null ? "" : result.reason())
        ), MediaType.APPLICATION_JSON_TYPE).build();
    }

    private IdentityProviderModel getDingTalkProvider(RealmModel realm, String alias) {
        if (realm == null || StringUtils.isBlank(alias)) {
            return null;
        }
        return realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(provider -> alias.equals(provider.getAlias()))
                .findFirst()
                .orElse(null);
    }

    private IdentityProviderModel getAuthorizedDingTalkProvider(RealmModel realm, String alias, String key) {
        IdentityProviderModel idp = getDingTalkProvider(realm, alias);
        return idp != null && validateBrowserAccess(realm, idp, alias, key) == null ? idp : null;
    }

    private Response validateBrowserAccess(RealmModel realm, IdentityProviderModel idp, String alias, String key) {
        if (!DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(idp)) {
            logger.warnf("Rejected DingTalk browser sync request because GET debug is disabled. realm=%s, idp=%s",
                    realm.getName(), alias);
            return json(Response.Status.FORBIDDEN, Map.of("error", "get_debug_disabled", "alias", alias));
        }
        String configuredKey = idp.getConfig().get(DingTalkIdentityProviderFactory.BROWSER_SYNC_DEBUG_KEY);
        if (StringUtils.isBlank(configuredKey)) {
            return json(Response.Status.FORBIDDEN, Map.of("error", "browser_debug_disabled", "alias", alias));
        }
        if (!constantTimeEquals(configuredKey, key)) {
            logger.warnf("Rejected DingTalk browser sync request. realm=%s, idp=%s",
                    realm.getName(), alias);
            return json(Response.Status.FORBIDDEN, Map.of("error", "invalid_debug_key", "alias", alias));
        }
        return null;
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
