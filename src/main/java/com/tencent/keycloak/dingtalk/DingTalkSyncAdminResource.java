package com.tencent.keycloak.dingtalk;

import java.util.Map;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

public class DingTalkSyncAdminResource {

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
        return runSync(alias);
    }

    private Response runSync(String alias) throws Exception {
        auth.users().requireManage();

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
            )).build();
        } finally {
            session.getContext().setRealm(previousRealm);
        }
    }
}
