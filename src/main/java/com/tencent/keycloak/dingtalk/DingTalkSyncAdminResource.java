package com.tencent.keycloak.dingtalk;

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
        return runSync(alias, false, null);
    }

    @GET
    @Path("run")
    public Response runFromBrowser(@QueryParam("alias") String alias,
                                   @QueryParam("confirm") String confirm) throws Exception {
        return runSync(alias, true, confirm);
    }

    @GET
    @Path("cleanup-numeric-users")
    public Response previewNumericCleanup(@QueryParam("alias") String alias) {
        return cleanupNumericUsers(alias, null, false);
    }

    @POST
    @Path("cleanup-numeric-users")
    public Response cleanupNumericUsers(@QueryParam("alias") String alias,
                                        @QueryParam("confirm") String confirm) {
        return cleanupNumericUsers(alias, confirm, true);
    }

    private Response runSync(String alias, boolean requireConfirm, String confirm) throws Exception {
        auth.users().requireManage();
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
        } finally {
            session.getContext().setRealm(previousRealm);
        }
    }

    private Response cleanupNumericUsers(String alias, String confirm, boolean execute) {
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
            if (execute && !DingTalkNumericUserCleanup.CONFIRM.equals(confirm)) {
                return json(Response.Status.BAD_REQUEST, Map.of(
                        "error", "confirm_required",
                        "confirm", DingTalkNumericUserCleanup.CONFIRM
                ));
            }

            DingTalkNumericUserCleanup.CleanupResult result = execute
                    ? DingTalkNumericUserCleanup.execute(session, realm, idp)
                    : DingTalkNumericUserCleanup.preview(session, realm, idp);

            adminEvent.operation(OperationType.ACTION)
                    .resource(DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID + "/cleanup-numeric-users")
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
            session.getContext().setRealm(previousRealm);
        }
    }

    private IdentityProviderModel getDingTalkProvider(String alias) {
        return realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .filter(provider -> alias.equals(provider.getAlias()))
                .findFirst()
                .orElse(null);
    }

    private Response json(Response.Status status, Map<String, Object> body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
