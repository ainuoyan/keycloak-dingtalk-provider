package com.tencent.keycloak.dingtalk;

import java.util.List;
import java.util.Map;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

public class DingTalkSyncAdminResource {

    static final String RUN_CONFIRM = "RUN_DINGTALK_SYNC";

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;
    private final AdminEventBuilder adminEvent;

    DingTalkSyncAdminResource(KeycloakSession session, RealmModel realm,
                              AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.session = session;
        this.realm = realm;
        this.auth = auth;
        this.adminEvent = adminEvent;
    }

    @POST
    @Path("run")
    public Response run(@QueryParam("alias") String alias) throws Exception {
        return runSync(alias, false, null, false);
    }

    @GET
    @Path("run")
    public Response runFromBrowser(@QueryParam("alias") String alias,
                                   @QueryParam("confirm") String confirm) throws Exception {
        return runSync(alias, true, confirm, true);
    }

    @GET
    @Path("debug")
    public Response previewSync(@QueryParam("alias") String alias) throws Exception {
        auth.users().requireManage();
        Response disabled = requireGetDebugEnabled(alias);
        if (disabled != null) {
            return disabled;
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            DingTalkUserSyncTask.SyncResult result = new DingTalkUserSyncTask()
                    .previewProviderNow(session, realm, alias);

            adminEvent.operation(OperationType.ACTION)
                    .resource(DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID + "/debug")
                    .detail("alias", alias)
                    .detail("dryRun", "true")
                    .success();

            return syncResultResponse(result, true);
        } finally {
            restorePreviousRealm(previousRealm);
        }
    }

    @GET
    @Path("cleanup-sync-created-users")
    public Response previewSyncCreatedUserCleanup(@QueryParam("alias") String alias) {
        return cleanupSyncCreatedUsers(alias, null, false);
    }

    @POST
    @Path("cleanup-sync-created-users")
    public Response cleanupSyncCreatedUsers(@QueryParam("alias") String alias,
                                            @QueryParam("confirm") String confirm) {
        return cleanupSyncCreatedUsers(alias, confirm, true);
    }

    @POST
    @Path("test-webhook")
    public Response testWebhook(@QueryParam("alias") String alias) {
        auth.users().requireManage();
        if (StringUtils.isBlank(alias)) {
            return json(Response.Status.BAD_REQUEST, Map.of("error", "alias_required"));
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            IdentityProviderModel idp = getDingTalkProvider(alias);
            if (idp == null) {
                return json(Response.Status.NOT_FOUND, Map.of("error", "dingtalk_idp_not_found", "alias", alias));
            }
            if (!DingTalkWebhookNotifier.isEnabled(idp)) {
                return json(Response.Status.BAD_REQUEST, Map.of(
                        "error", "webhook_disabled_or_url_missing",
                        "alias", alias
                ));
            }

            DingTalkWebhookNotifier.SendResult result =
                    DingTalkWebhookNotifier.sendTest(session, realm, idp);

            adminEvent.operation(OperationType.ACTION)
                    .resource(DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID + "/test-webhook")
                    .detail("alias", alias)
                    .detail("success", String.valueOf(result.success()))
                    .success();

            Response.Status status = result.success()
                    ? Response.Status.OK
                    : Response.Status.BAD_GATEWAY;
            return json(status, Map.of(
                    "alias", alias,
                    "success", result.success(),
                    "error", result.error(),
                    "response", result.response()
            ));
        } finally {
            restorePreviousRealm(previousRealm);
        }
    }

    private Response runSync(String alias, boolean requireConfirm, String confirm, boolean getRequest) throws Exception {
        auth.users().requireManage();
        if (getRequest) {
            Response disabled = requireGetDebugEnabled(alias);
            if (disabled != null) {
                return disabled;
            }
        }
        if (requireConfirm && !RUN_CONFIRM.equals(confirm)) {
            return json(Response.Status.BAD_REQUEST, Map.of(
                    "error", "confirm_required",
                    "confirm", RUN_CONFIRM
            ));
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            DingTalkUserSyncTask.SyncResult result = new DingTalkUserSyncTask()
                    .syncProviderNow(session, realm, alias);

            adminEvent.operation(OperationType.ACTION)
                    .resource(DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID)
                    .detail("alias", alias)
                    .success();

            return syncResultResponse(result, false);
        } finally {
            restorePreviousRealm(previousRealm);
        }
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

    private Response cleanupSyncCreatedUsers(String alias, String confirm, boolean execute) {
        auth.users().requireManage();
        if (StringUtils.isBlank(alias)) {
            return json(Response.Status.BAD_REQUEST, Map.of("error", "alias_required"));
        }

        RealmModel previousRealm = session.getContext().getRealm();
        try {
            session.getContext().setRealm(realm);
            IdentityProviderModel idp = getDingTalkProvider(alias);
            if (idp == null) {
                return json(Response.Status.NOT_FOUND, Map.of("error", "dingtalk_idp_not_found", "alias", alias));
            }
            if (!execute && !DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(idp)) {
                return getDebugDisabledResponse(List.of(idp.getAlias()));
            }
            if (execute && !DingTalkSyncCreatedUserCleanup.CONFIRM.equals(confirm)) {
                return json(Response.Status.BAD_REQUEST, Map.of(
                        "error", "confirm_required",
                        "confirm", DingTalkSyncCreatedUserCleanup.CONFIRM
                ));
            }

            DingTalkSyncCreatedUserCleanup.CleanupResult result = execute
                    ? DingTalkSyncCreatedUserCleanup.execute(session, realm, idp)
                    : DingTalkSyncCreatedUserCleanup.preview(session, realm, idp);

            adminEvent.operation(OperationType.ACTION)
                    .resource(DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID + "/cleanup-sync-created-users")
                    .detail("alias", alias)
                    .detail("dryRun", String.valueOf(result.dryRun()))
                    .detail("candidateCount", String.valueOf(result.candidateCount()))
                    .detail("deleted", String.valueOf(result.deleted()))
                    .success();

            return Response.ok(Map.of(
                    "alias", result.alias(),
                    "dryRun", result.dryRun(),
                    "candidateCount", result.candidateCount(),
                    "deleted", result.deleted(),
                    "usernames", result.usernames()
            ), MediaType.APPLICATION_JSON_TYPE).build();
        } finally {
            restorePreviousRealm(previousRealm);
        }
    }

    private void restorePreviousRealm(RealmModel previousRealm) {
        if (previousRealm != null) {
            session.getContext().setRealm(previousRealm);
        }
    }

    private IdentityProviderModel getDingTalkProvider(String alias) {
        return getDingTalkProviders(alias).stream()
                .findFirst()
                .orElse(null);
    }

    private List<IdentityProviderModel> getDingTalkProviders(String alias) {
        return realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(provider -> StringUtils.isBlank(alias) || alias.equals(provider.getAlias()))
                .toList();
    }

    private Response requireGetDebugEnabled(String alias) {
        List<IdentityProviderModel> providers = getDingTalkProviders(alias);
        if (providers.isEmpty()) {
            return json(Response.Status.NOT_FOUND, Map.of(
                    "error", "dingtalk_idp_not_found",
                    "alias", StringUtils.defaultString(alias)
            ));
        }

        List<String> disabledAliases = providers.stream()
                .filter(provider -> !DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(provider))
                .map(IdentityProviderModel::getAlias)
                .toList();
        if (!disabledAliases.isEmpty()) {
            return getDebugDisabledResponse(disabledAliases);
        }
        return null;
    }

    private Response getDebugDisabledResponse(List<String> aliases) {
        return json(Response.Status.FORBIDDEN, Map.of(
                "error", "get_debug_disabled",
                "aliases", aliases
        ));
    }

    private Response json(Response.Status status, Map<String, Object> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
