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
                    .previewProviderNow(session, realm, alias);
            return Response.ok(Map.ofEntries(
                    Map.entry("alias", result.alias()),
                    Map.entry("dryRun", true),
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
        } catch (Exception e) {
            logger.errorf(e, "DingTalk browser sync preview failed. realm=%s, idp=%s",
                    realm.getName(), alias);
            return json(Response.Status.INTERNAL_SERVER_ERROR,
                    Map.of("error", "sync_preview_failed", "alias", alias, "message", StringUtils.defaultString(e.getMessage())));
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

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
