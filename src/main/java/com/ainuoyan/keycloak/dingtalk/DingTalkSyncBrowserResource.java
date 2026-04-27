package com.ainuoyan.keycloak.dingtalk;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

public class DingTalkSyncBrowserResource {

    private static final Logger logger = Logger.getLogger(DingTalkSyncBrowserResource.class);

    private final KeycloakSession session;

    DingTalkSyncBrowserResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("endpoints")
    public Response endpoints(@QueryParam("realm") String requestedRealm,
                              @QueryParam("alias") String alias,
                              @QueryParam("key") String key,
                              @Context UriInfo uriInfo) {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("Realm not found")
                    .build();
        }
        Response loginRequired = requireEndpointsPageLogin(realm);
        if (loginRequired != null) {
            return loginRequired;
        }
        String requestedRealmName = StringUtils.trimToNull(requestedRealm);
        if (requestedRealmName != null && !requestedRealmName.equals(realm.getName())) {
            return redirectToRealmEndpoint(realm, requestedRealmName, alias, key, uriInfo);
        }

        List<String> aliases = getDingTalkProviderAliases(realm);
        String selectedAlias = StringUtils.defaultString(alias);
        if (StringUtils.isBlank(selectedAlias) && aliases.size() == 1) {
            selectedAlias = aliases.get(0);
        }
        IdentityProviderModel selectedIdp = StringUtils.isNotBlank(selectedAlias)
                ? getDingTalkProvider(realm, selectedAlias)
                : null;

        String html = renderEndpointsPage(realm, aliases, selectedAlias, key, selectedIdp, uriInfo);
        return Response.ok(html)
                .type(MediaType.TEXT_HTML + "; charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                                + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                .header("Pragma", "no-cache")
                .build();
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
        Response loginRequired = requireBrowserApiLogin(realm);
        if (loginRequired != null) {
            return loginRequired;
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
                    : syncTask.syncProviderNow(session, realm, alias, "browser");
            return syncResultResponse(result, dryRun);
        } catch (Exception e) {
            logger.errorf(e, "DingTalk browser sync failed. realm=%s, idp=%s, dryRun=%s",
                    realm.getName(), alias, dryRun);
            return json(Response.Status.INTERNAL_SERVER_ERROR,
                    Map.of("error", "sync_failed", "alias", alias, "dryRun", dryRun,
                            "message", "Sync failed. Check Keycloak server logs for details."));
        } finally {
            restorePreviousRealm(previousRealm);
        }
    }

    @GET
    @Path("cleanup-sync-created-users")
    public Response cleanupSyncCreatedUsers(@QueryParam("alias") String alias,
                                            @QueryParam("key") String key) {
        return previewSyncCreatedUserCleanup(alias, key);
    }

    @GET
    @Path("test-webhook")
    public Response testWebhook(@QueryParam("alias") String alias,
                                @QueryParam("key") String key) {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "realm_not_found"));
        }
        Response loginRequired = requireBrowserApiLogin(realm);
        if (loginRequired != null) {
            return loginRequired;
        }
        if (StringUtils.isBlank(alias)) {
            return json(Response.Status.BAD_REQUEST, Map.of("error", "alias_required"));
        }

        IdentityProviderModel idp = getDingTalkProvider(realm, alias);
        if (idp == null) {
            return json(Response.Status.NOT_FOUND, Map.of("error", "dingtalk_idp_not_found", "alias", alias));
        }
        Response forbidden = validateBrowserAccess(realm, idp, alias, key);
        if (forbidden != null) {
            return forbidden;
        }
        if (!DingTalkWebhookNotifier.isEnabled(idp)) {
            return json(Response.Status.BAD_REQUEST, Map.of(
                    "error", "webhook_disabled_or_url_missing",
                    "alias", alias
            ));
        }

        DingTalkWebhookNotifier.SendResult result = DingTalkWebhookNotifier.sendTest(session, realm, idp);
        Response.Status status = result.success()
                ? Response.Status.OK
                : Response.Status.BAD_GATEWAY;
        return json(status, Map.of(
                "alias", alias,
                "success", result.success(),
                "error", result.error(),
                "response", result.response()
        ));
    }

    @POST
    @Path("cleanup-sync-created-users")
    public Response cleanupSyncCreatedUsersPost(@QueryParam("alias") String alias,
                                                @QueryParam("key") String key) {
        return previewSyncCreatedUserCleanup(alias, key);
    }

    private Response previewSyncCreatedUserCleanup(String alias, String key) {
        BrowserJsonResponse response = previewSyncCreatedUserCleanupJson(alias, key);
        return json(response.status(), response.body());
    }

    BrowserJsonResponse previewSyncCreatedUserCleanupJson(String alias, String key) {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return new BrowserJsonResponse(Response.Status.NOT_FOUND, Map.of("error", "realm_not_found"));
        }
        BrowserJsonResponse loginRequired = browserApiLoginGuard(realm);
        if (loginRequired != null) {
            return loginRequired;
        }
        try {
            IdentityProviderModel idp = getAuthorizedDingTalkProvider(realm, alias, key);
            if (idp == null) {
                return new BrowserJsonResponse(Response.Status.FORBIDDEN,
                        Map.of("error", "unauthorized_or_not_found"));
            }

            DingTalkSyncCreatedUserCleanup.CleanupResult result =
                    DingTalkSyncCreatedUserCleanup.preview(session, realm, idp);
            return new BrowserJsonResponse(Response.Status.OK, Map.of(
                    "alias", result.alias(),
                    "dryRun", true,
                    "candidateCount", result.candidateCount(),
                    "usernames", result.usernames(),
                    "deleteMethod", "POST admin endpoint",
                    "confirm", DingTalkSyncCreatedUserCleanup.CONFIRM,
                    "adminPath", "/admin/realms/" + realm.getName()
                            + "/dingtalk-sync/cleanup-sync-created-users?alias=" + alias
                            + "&confirm=" + DingTalkSyncCreatedUserCleanup.CONFIRM,
                    "message", "Browser cleanup endpoint is preview-only. "
                            + "Use the admin endpoint to delete DingTalk sync-created users."
            ));
        } catch (Exception e) {
            logger.errorf("DingTalk browser cleanup preview failed. realm=%s, idp=%s, error=%s, reason=%s",
                    realm.getName(), StringUtils.defaultString(alias), e.getClass().getName(),
                    DingTalkIdentityProvider.sanitizeForLog(e.getMessage()));
            return new BrowserJsonResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    Map.of("error", "cleanup_preview_failed",
                            "alias", StringUtils.defaultString(alias),
                            "dryRun", true,
                            "message", "Cleanup preview failed. Check Keycloak server logs for details."));
        }
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

    private void restorePreviousRealm(RealmModel previousRealm) {
        session.getContext().setRealm(previousRealm);
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

    private List<String> getDingTalkProviderAliases(RealmModel realm) {
        if (realm == null) {
            return List.of();
        }
        return realm.getIdentityProvidersStream()
                .filter(provider -> DingTalkIdentityProviderFactory.PROVIDER_ID.equals(provider.getProviderId()))
                .filter(IdentityProviderModel::isEnabled)
                .map(IdentityProviderModel::getAlias)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private IdentityProviderModel getAuthorizedDingTalkProvider(RealmModel realm, String alias, String key) {
        IdentityProviderModel idp = getDingTalkProvider(realm, alias);
        return idp != null && validateBrowserAccess(realm, idp, alias, key) == null ? idp : null;
    }

    private Response requireEndpointsPageLogin(RealmModel realm) {
        BrowserHtmlResponse response = endpointsPageLoginGuard(realm);
        if (response == null) {
            return null;
        }
        return Response.status(response.status())
                .type(MediaType.TEXT_HTML + "; charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")
                .header("Pragma", "no-cache")
                .entity(response.body())
                .build();
    }

    BrowserHtmlResponse endpointsPageLoginGuard(RealmModel realm) {
        if (authenticateBrowserRequest(realm) != null) {
            return null;
        }
        logger.warnf("Rejected DingTalk endpoints page request because user is not logged in. realm=%s",
                realm.getName());
        return new BrowserHtmlResponse(Response.Status.UNAUTHORIZED, renderLoginRequiredPage(realm));
    }

    private Response requireBrowserApiLogin(RealmModel realm) {
        BrowserJsonResponse response = browserApiLoginGuard(realm);
        return response == null ? null : json(response.status(), response.body());
    }

    BrowserJsonResponse browserApiLoginGuard(RealmModel realm) {
        if (authenticateBrowserRequest(realm) != null) {
            return null;
        }
        logger.warnf("Rejected DingTalk browser API request because user is not logged in. realm=%s",
                realm.getName());
        return new BrowserJsonResponse(Response.Status.UNAUTHORIZED,
                Map.of("error", "login_required",
                        "message", "Login to Keycloak before using DingTalk browser endpoints."));
    }

    AuthenticationManager.AuthResult authenticateBrowserRequest(RealmModel realm) {
        return new AppAuthManager().authenticateIdentityCookie(session, realm);
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

    private String renderEndpointsPage(RealmModel realm, List<String> aliases, String alias, String key,
                                       IdentityProviderModel selectedIdp, UriInfo uriInfo) {
        String serverRoot = externalServerRoot(uriInfo, realm);
        String encodedRealm = urlEncodePath(realm.getName());
        String browserBase = serverRoot + "/realms/" + encodedRealm + "/"
                + DingTalkSyncBrowserResourceProviderFactory.PROVIDER_ID;
        String adminBase = serverRoot + "/admin/realms/" + encodedRealm + "/"
                + DingTalkSyncAdminResourceProviderFactory.PROVIDER_ID;

        String displayAlias = StringUtils.defaultIfBlank(alias, "{alias}");
        String displayKey = StringUtils.defaultIfBlank(key, "{浏览器同步调试密钥}");
        boolean hasAlias = StringUtils.isNotBlank(alias);
        boolean hasKey = StringUtils.isNotBlank(key);

        List<EndpointRow> browserRows = new ArrayList<>();
        browserRows.add(new EndpointRow("同步预览", "GET",
                endpointUrl(browserBase, "debug", "alias", displayAlias, "key", displayKey),
                "dry-run；需要 GET 调试开关和正确密钥。", hasAlias && hasKey
                        ? endpointUrl(browserBase, "debug", "alias", alias, "key", key)
                        : null, false));
        browserRows.add(new EndpointRow("同步执行", "GET",
                endpointUrl(browserBase, "run", "alias", displayAlias, "key", displayKey,
                        "confirm", DingTalkSyncAdminResource.RUN_CONFIRM),
                "真实同步；需要 GET 调试开关、正确密钥和确认参数。", hasAlias && hasKey
                        ? endpointUrl(browserBase, "run", "alias", alias, "key", key,
                        "confirm", DingTalkSyncAdminResource.RUN_CONFIRM)
                        : null, true));
        browserRows.add(new EndpointRow("清理预览", "GET",
                endpointUrl(browserBase, "cleanup-sync-created-users", "alias", displayAlias, "key", displayKey),
                "dry-run；只预览由当前钉钉同步任务创建的用户，不删除。", hasAlias && hasKey
                        ? endpointUrl(browserBase, "cleanup-sync-created-users", "alias", alias, "key", key)
                        : null, false));
        browserRows.add(new EndpointRow("Webhook 测试", "GET",
                endpointUrl(browserBase, "test-webhook", "alias", displayAlias, "key", displayKey),
                "发送测试消息；需要 GET 调试开关和正确密钥。", hasAlias && hasKey
                        ? endpointUrl(browserBase, "test-webhook", "alias", alias, "key", key)
                        : null, true));

        List<EndpointRow> postRows = new ArrayList<>();
        postRows.add(new EndpointRow("同步执行", "POST",
                endpointUrl(adminBase, "run", "alias", displayAlias),
                "真实同步；需要 Authorization Bearer 管理端 token 和 manage-users 权限。", null, false));
        postRows.add(new EndpointRow("清理执行", "POST",
                endpointUrl(adminBase, "cleanup-sync-created-users", "alias", displayAlias,
                        "confirm", DingTalkSyncCreatedUserCleanup.CONFIRM),
                "真实删除；仅删除由当前钉钉同步任务创建并标记的用户，需要 Bearer token、manage-users 权限和确认参数。", null, true));
        postRows.add(new EndpointRow("Webhook 测试", "POST",
                endpointUrl(adminBase, "test-webhook", "alias", displayAlias),
                "发送测试消息；需要 Bearer token 和 manage-users 权限。", null, false));

        StringBuilder html = new StringBuilder(12_000);
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>钉钉同步接口地址</title>")
                .append("<style>")
                .append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:0;background:#f6f7f9;color:#151515}")
                .append("main{max-width:1120px;margin:0 auto;padding:28px 20px 48px}")
                .append("h1{font-size:24px;margin:0 0 8px}h2{font-size:18px;margin:0 0 8px}p{line-height:1.6}.muted{color:#666}")
                .append(".panel{background:#fff;border:1px solid #d8d8d8;border-radius:6px;padding:18px;margin:18px 0}")
                .append("label{display:block;font-weight:600;margin:0 0 6px}.formgrid{display:grid;grid-template-columns:1fr 1fr 1fr auto;gap:14px;align-items:end}")
                .append("input,select{box-sizing:border-box;width:100%;height:38px;border:1px solid #8a8d90;border-radius:4px;padding:6px 10px;font-size:14px;background:white}")
                .append("button,.open{height:38px;border:1px solid #0066cc;background:#0066cc;color:white;border-radius:4px;padding:0 14px;font-size:14px;text-decoration:none;display:inline-flex;align-items:center;justify-content:center;cursor:pointer}")
                .append("button.secondary{border-color:#8a8d90;background:white;color:#151515}.open.warn{background:#b13800;border-color:#b13800}.disabled{border-color:#d2d2d2!important;background:#f0f0f0!important;color:#777!important;cursor:not-allowed!important}")
                .append(".notice{border-left:4px solid #f0ab00;background:#fff7db;padding:10px 12px;margin:14px 0}")
                .append(".error{border-left-color:#c9190b;background:#faeae8}.ok{border-left-color:#3e8635;background:#eef7ed}")
                .append("table{width:100%;border-collapse:collapse;background:white}th,td{border-bottom:1px solid #d8d8d8;padding:10px;text-align:left;vertical-align:top}")
                .append("th{font-size:13px;color:#4d5258;background:#f0f0f0}.method{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-weight:700}")
                .append(".urlrow{display:flex;gap:8px;align-items:center}.urlrow input{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}")
                .append(".actions{display:flex;gap:8px;flex-wrap:wrap}.note{font-size:13px;color:#555;line-height:1.5;margin-top:6px}")
                .append("@media(max-width:800px){.formgrid{grid-template-columns:1fr}.urlrow{display:block}.urlrow button{margin-top:8px;width:100%}th:nth-child(2),td:nth-child(2){display:none}}")
                .append("</style></head><body><main>");

        html.append("<h1>钉钉同步接口地址</h1>")
                .append("<p class=\"muted\">当前 Realm: <strong>")
                .append(escapeHtml(realm.getName()))
                .append("</strong>。此页面只生成和展示地址，不会自动执行同步、清理或发信。</p>");

        if (aliases.isEmpty()) {
            html.append("<div class=\"notice error\">当前 Realm 没有启用的钉钉 Identity Provider。</div>");
        } else if (StringUtils.isNotBlank(alias) && selectedIdp == null) {
            html.append("<div class=\"notice error\">找不到启用的钉钉 Identity Provider: <strong>")
                    .append(escapeHtml(alias))
                    .append("</strong></div>");
        } else if (selectedIdp != null) {
            html.append("<div class=\"notice ")
                    .append(DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(selectedIdp) ? "ok" : "")
                    .append("\">GET 同步调试入口当前为 <strong>")
                    .append(DingTalkIdentityProviderFactory.isSyncGetDebugEnabled(selectedIdp) ? "开启" : "关闭")
                    .append("</strong>。浏览器 GET 入口还需要填写正确的浏览器同步调试密钥。</div>");
        }

        html.append("<section class=\"panel\"><form method=\"get\"><div class=\"formgrid\">")
                .append("<div><label for=\"realm\">Realm</label><input id=\"realm\" name=\"realm\" autocomplete=\"off\" value=\"")
                .append(escapeHtml(realm.getName()))
                .append("\"></div><div><label for=\"alias\">钉钉 IdP alias</label>");
        if (aliases.isEmpty()) {
            html.append("<input id=\"alias\" name=\"alias\" value=\"").append(escapeHtml(alias)).append("\">");
        } else {
            html.append("<select id=\"alias\" name=\"alias\">");
            if (StringUtils.isBlank(alias) && aliases.size() > 1) {
                html.append("<option value=\"\">请选择</option>");
            }
            for (String candidate : aliases) {
                html.append("<option value=\"").append(escapeHtml(candidate)).append("\"")
                        .append(candidate.equals(alias) ? " selected" : "")
                        .append(">").append(escapeHtml(candidate)).append("</option>");
            }
            html.append("</select>");
        }
        html.append("</div><div><label for=\"key\">浏览器同步调试密钥</label>")
                .append("<input id=\"key\" name=\"key\" type=\"password\" autocomplete=\"off\" value=\"")
                .append(escapeHtml(StringUtils.defaultString(key)))
                .append("\" placeholder=\"不会从服务端读取，只使用你本次输入的值\">")
                .append("</div><button type=\"submit\">生成地址</button></div></form></section>");

        appendEndpointSection(html, "浏览器直接 GET 地址",
                "可以直接在浏览器打开；同步执行和 Webhook 测试会真实产生动作。", browserRows, false);
        appendEndpointSection(html, "管理端 POST API 地址",
                "需要使用 POST 方法、Authorization Bearer token 和当前 realm 的 manage-users 权限；浏览器地址栏直接打开不会执行 POST。", postRows, true);

        html.append("<p class=\"muted\">浏览器执行同步和 Webhook 测试会真实产生动作。调试完成后请关闭“启用 GET 同步调试入口”并清空调试密钥。</p>")
                .append("<script>")
                .append("document.querySelectorAll('[data-copy]').forEach(function(btn){btn.addEventListener('click',async function(){try{await navigator.clipboard.writeText(btn.getAttribute('data-copy'));var old=btn.textContent;btn.textContent='已复制';setTimeout(function(){btn.textContent=old},1200)}catch(e){btn.textContent='复制失败'}})});")
                .append("</script></main></body></html>");
        return html.toString();
    }

    private String renderLoginRequiredPage(RealmModel realm) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>需要登录</title>"
                + "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:0;background:#f6f7f9;color:#151515}"
                + "main{max-width:720px;margin:0 auto;padding:48px 20px}"
                + ".panel{background:#fff;border:1px solid #d8d8d8;border-radius:6px;padding:22px}"
                + "h1{font-size:22px;margin:0 0 10px}p{line-height:1.6;margin:0;color:#555}"
                + "</style></head><body><main><section class=\"panel\"><h1>需要先登录 Keycloak</h1><p>访问 Realm "
                + "<strong>" + escapeHtml(realm.getName()) + "</strong> 的钉钉同步接口地址页面前，请先登录。"
                + "此页面不会向未登录请求展示钉钉 Identity Provider alias 或调试开关状态。</p>"
                + "</section></main></body></html>";
    }

    private Response redirectToRealmEndpoint(RealmModel currentRealm, String requestedRealm, String alias, String key,
                                             UriInfo uriInfo) {
        String base = externalServerRoot(uriInfo, currentRealm) + "/realms/" + urlEncodePath(requestedRealm) + "/"
                + DingTalkSyncBrowserResourceProviderFactory.PROVIDER_ID;
        List<String> queryPairs = new ArrayList<>();
        if (StringUtils.isNotBlank(alias)) {
            queryPairs.add("alias");
            queryPairs.add(alias);
        }
        if (StringUtils.isNotBlank(key)) {
            queryPairs.add("key");
            queryPairs.add(key);
        }
        String target = endpointUrl(base, "endpoints", queryPairs.toArray(new String[0]));
        return Response.seeOther(URI.create(target)).build();
    }

    private void appendEndpointSection(StringBuilder html, String title, String description,
                                       List<EndpointRow> rows, boolean copyEnabled) {
        html.append("<section class=\"panel\"><h2>")
                .append(escapeHtml(title))
                .append("</h2><p class=\"muted\">")
                .append(escapeHtml(description))
                .append("</p><table><thead><tr><th>用途</th><th>方法</th><th>地址</th></tr></thead><tbody>");
        for (EndpointRow row : rows) {
            html.append("<tr><td>").append(escapeHtml(row.name()))
                    .append("<div class=\"note\">").append(escapeHtml(row.note())).append("</div></td>")
                    .append("<td class=\"method\">").append(escapeHtml(row.method())).append("</td><td>")
                    .append("<div class=\"urlrow\"><input readonly value=\"").append(escapeHtml(row.url())).append("\">")
                    .append("<div class=\"actions\">");
            if (copyEnabled) {
                html.append("<button type=\"button\" class=\"secondary\" data-copy=\"")
                        .append(escapeHtml(row.url())).append("\">复制地址</button>");
            } else if (row.openUrl() != null) {
                html.append("<a class=\"open")
                        .append(row.dangerous() ? " warn" : "")
                        .append("\" target=\"_blank\" rel=\"noopener\" href=\"")
                        .append(escapeHtml(row.openUrl())).append("\">访问</a>");
            } else {
                html.append("<button type=\"button\" class=\"secondary disabled\" disabled>生成后访问</button>");
            }
            html.append("</div></div></td></tr>");
        }
        html.append("</tbody></table></section>");
    }

    private String externalServerRoot(UriInfo uriInfo, RealmModel realm) {
        URI requestUri = uriInfo.getRequestUri();
        String rawPath = StringUtils.defaultString(requestUri.getRawPath());
        String marker = "/realms/" + urlEncodePath(realm.getName()) + "/";
        int markerIndex = rawPath.indexOf(marker);
        String contextPath = markerIndex >= 0 ? rawPath.substring(0, markerIndex) : "";
        String authority = StringUtils.defaultIfBlank(requestUri.getRawAuthority(), requestUri.getAuthority());
        if (StringUtils.isBlank(authority)) {
            authority = uriInfo.getBaseUri().getRawAuthority();
        }
        String scheme = StringUtils.defaultIfBlank(requestUri.getScheme(), uriInfo.getBaseUri().getScheme());
        return trimTrailingSlash(scheme + "://" + authority + contextPath);
    }

    private String endpointUrl(String base, String path, String... queryPairs) {
        StringBuilder url = new StringBuilder(base);
        if (!base.endsWith("/")) {
            url.append('/');
        }
        url.append(path);
        if (queryPairs.length > 0) {
            url.append('?');
            for (int i = 0; i < queryPairs.length; i += 2) {
                if (i > 0) {
                    url.append('&');
                }
                url.append(urlEncode(queryPairs[i])).append('=').append(urlEncode(queryPairs[i + 1]));
            }
        }
        return url.toString();
    }

    private String urlEncode(String value) {
        if ("{alias}".equals(value) || "{浏览器同步调试密钥}".equals(value)) {
            return value;
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String urlEncodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimTrailingSlash(String value) {
        return StringUtils.removeEnd(value, "/");
    }

    private String escapeHtml(String value) {
        return StringUtils.defaultString(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    record BrowserJsonResponse(Response.Status status, Map<String, Object> body) {}

    record BrowserHtmlResponse(Response.Status status, String body) {}

    private record EndpointRow(String name, String method, String url, String note, String openUrl, boolean dangerous) {}
}
