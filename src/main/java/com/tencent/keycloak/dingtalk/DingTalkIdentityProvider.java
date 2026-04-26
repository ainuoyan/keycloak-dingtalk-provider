/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.keycloak.dingtalk;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.http.simple.SimpleHttp;
import org.keycloak.http.simple.SimpleHttpRequest;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * 钉钉登录集成Provider
 *
 * @author: generated
 * @date: 2025-11-26
 */
public class DingTalkIdentityProvider extends AbstractOAuth2IdentityProvider<OAuth2IdentityProviderConfig>
        implements SocialIdentityProvider<OAuth2IdentityProviderConfig> {

    private static final Logger logger = Logger.getLogger(DingTalkIdentityProvider.class);

    // 钉钉新版 OAuth2.0 接口URL（企业内部应用）
    private static final String AUTH_URL = "https://login.dingtalk.com/oauth2/auth";
    private static final String TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";
    private static final String USER_INFO_URL = "https://api.dingtalk.com/v1.0/contact/users/me";
    
    // 企业内部应用 - 获取企业 access_token
    private static final String CORP_TOKEN_URL = "https://oapi.dingtalk.com/gettoken";
    // 企业内部应用 - 通过 unionId 获取用户 userId
    private static final String GET_USERID_BY_UNIONID_URL = "https://oapi.dingtalk.com/topapi/user/getbyunionid";
    // 企业内部应用 - 获取用户详情
    private static final String GET_USER_DETAIL_URL = "https://oapi.dingtalk.com/topapi/v2/user/get";

    // 字段常量
    private static final String OPEN_ID = "dingtalk_openid";
    private static final String UNION_ID = "dingtalk_unionid";
    private static final String DINGTALK_USER_ID = "dingtalk_userid";
    private static final String CORP_ID = "dingtalk_corpid";
    private static final String ENTERPRISE_VERIFIED = "dingtalk_enterprise_verified";
    private static final String NICK_NAME = "nickname";
    private static final String PHONE_NUMBER = "phoneNumber";
    private static final String IS_UPDATE_USER_INFO = "isUpdateUserInfo";
    private static final String SYNC_PHONE_NUMBER_IF_MISSING = "syncPhoneNumberIfMissing";
    private static final String ENTERPRISE_ID = "enterpriseId";
    private static final String REQUIRE_ENTERPRISE_USER = "requireEnterpriseUser";
    private static final String ALLOWED_CORP_IDS = "allowedCorpIds";
    static final String ENABLE_ENTERPRISE_ROLE_GRANT = "enableEnterpriseRoleGrant";
    private static final String MATCH_ACTION = "matchAction";
    private static final String MATCH_RULES = "matchRules";
    private static final String MATCHED_USER_ID = "dingtalk_matched_user_id";
    
    // 角色和属性常量（与 keycloak-copilot-provider 保持一致）
    private static final String ROLE_MEMBER = "ent-member:";
    private static final String ROLE_PLUGIN_ENABLE = "ent-plugin-enabled:";
    private static final String ENT_USER_RESOURCE = "ent-user-source:";
    private static final String ENT_USER_NAME = "ent-user-name:";
    private static final String ENT_JOIN_TIMESTAMP = "ent-join-timestamp:";

    private static final List<String> DEFAULT_MATCH_RULES = List.of("phone", "email");
    private static final List<String> PHONE_ATTRIBUTE_NAMES = List.of("phoneNumber", "mobile", "telephoneNumber");
    private static final List<String> SENSITIVE_LOG_KEYS = List.of(
            "accessToken", "refreshToken", "clientSecret", "access_token",
            "appsecret", "mobile", "email", "phoneNumber", "userid", "userId",
            "unionid", "unionId", "openid", "openId", "name", "nick",
            "avatarUrl", "staffId");

    public DingTalkIdentityProvider(KeycloakSession session, OAuth2IdentityProviderConfig config) {
        super(session, config);
        config.setAuthorizationUrl(AUTH_URL);
        config.setTokenUrl(TOKEN_URL);
        config.setUserInfoUrl(USER_INFO_URL);
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(callback, realm, event, this);
    }

    @Override
    protected boolean supportsExternalExchange() {
        return true;
    }

    @Override
    protected String getDefaultScopes() {
        // 需要 openid 和 corpid 来获取用户信息
        return "openid corpid";
    }
    
    @Override
    protected String getProfileEndpointForValidation(EventBuilder event) {
        return USER_INFO_URL;
    }
    
    /**
     * 重写授权 URL 构建，添加 prompt=consent 强制用户重新授权
     * 这样可以确保用户授权时包含最新的权限
     */
    @Override
    public Response performLogin(org.keycloak.broker.provider.AuthenticationRequest request) {
        try {
            String redirectUri = request.getRedirectUri();
            String state = request.getState().getEncoded();
            String nonce = request.getAuthenticationSession().getClientNote("nonce");

            UriBuilder authUrl = UriBuilder.fromUri(AUTH_URL)
                    .queryParam("client_id", getConfig().getClientId())
                    .queryParam("redirect_uri", redirectUri)
                    .queryParam("response_type", "code")
                    .queryParam("scope", getDefaultScopes())
                    .queryParam("state", state)
                    .queryParam("prompt", "consent");

            if (nonce != null) {
                authUrl.queryParam("nonce", nonce);
            }

            URI loginUri = authUrl.build();
            logger.debugf("Redirecting to DingTalk OAuth endpoint, redirectUri=%s, scope=%s",
                    sanitizeUriForLog(redirectUri), getDefaultScopes());

            return Response.seeOther(loginUri).build();
        } catch (Exception e) {
            throw new IdentityBrokerException("Failed to create authorization URL", e);
        }
    }

    // 用于在 Endpoint 和 Provider 之间传递 token 响应信息
    private static final ThreadLocal<UserTokenDto> tokenResponseHolder = new ThreadLocal<>();

    @Override
    protected BrokeredIdentityContext doGetFederatedIdentity(String accessToken) {
        try {
            return doGetFederatedIdentityInternal(accessToken);
        } finally {
            tokenResponseHolder.remove();
        }
    }

    private BrokeredIdentityContext doGetFederatedIdentityInternal(String accessToken) {
        UserDto userDto = null;
        String response = null;
        
        // 获取保存的 token 响应信息
        UserTokenDto tokenDto = tokenResponseHolder.get();
        String corpId = tokenDto != null ? tokenDto.getCorpId() : null;
        boolean enterpriseVerifiedByCorpApi = false;
        
        logger.debugf("Calling DingTalk user info API with accessToken=%s, corpId=%s",
                mask(accessToken), mask(corpId));
        
        // 首先尝试新版 API
        try {
            SimpleHttpRequest simpleHttp = SimpleHttp.create(session)
                    .doGet(USER_INFO_URL)
                    .header("x-acs-dingtalk-access-token", accessToken);

            response = simpleHttp.asString();
            logger.debugf("DingTalk user info response: %s", sanitizeForLog(response));

            if (response.contains("\"code\"") && (response.contains("Forbidden") || response.contains("error"))) {
                logger.warnf("DingTalk user info API returned error, will try corp API");
                userDto = null;
            } else {
                userDto = JSON.parseObject(response, UserDto.class);
                if (userDto != null) {
                    logger.debugf("Parsed DingTalk user - openId=%s, unionId=%s, nick=%s",
                            mask(userDto.getOpenId()), mask(userDto.getUnionId()), mask(userDto.getNick()));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to get DingTalk user info from API", e);
        }

        // 新版 OAuth 接口有时只返回 openId/unionId，不返回手机号、邮箱或通讯录 userId。
        // 企业登录校验开启时，即使基础资料已足够，也要用企业通讯录接口反查 unionId 是否属于当前 app 所在企业。
        if (shouldQueryCorpUserInfo(tokenDto, userDto)) {
            logger.debug("Trying corp internal API to complete DingTalk user info");
            UserDto corpUserInfo = getUserInfoByCorpApi(corpId, userDto);
            enterpriseVerifiedByCorpApi = corpUserInfo != null && StringUtils.isNotBlank(corpUserInfo.getUserId());
            userDto = mergeMissingUserInfo(userDto, corpUserInfo);
        }

        // 最后尝试从 token 响应中获取信息
        if (userDto == null || StringUtils.isBlank(userDto.getOpenId())) {
            if (tokenDto != null && StringUtils.isNotBlank(tokenDto.getOpenId())) {
                logger.infof("Using token response info as fallback. openId=%s, unionId=%s",
                        mask(tokenDto.getOpenId()), mask(tokenDto.getUnionId()));
                UserDto tokenFallback = new UserDto();
                tokenFallback.setOpenId(tokenDto.getOpenId());
                tokenFallback.setUnionId(tokenDto.getUnionId());
                tokenFallback.setNick("dingtalk_user");
                userDto = mergeMissingUserInfo(userDto, tokenFallback);
            }
        }

        if (userDto == null || StringUtils.isBlank(userDto.getOpenId())) {
            throw new IdentityBrokerException("Failed to get DingTalk user info: no openId available");
        }

        // 使用unionId作为唯一标识,如果没有则使用openId
        String uniqueId = StringUtils.isNotBlank(userDto.getUnionId())
                ? userDto.getUnionId()
                : userDto.getOpenId();

        BrokeredIdentityContext context = new BrokeredIdentityContext(uniqueId, getConfig());

        // Keycloak 本地用户名保持和定时同步一致：优先姓名拼音，仅把钉钉 userId 写入用户属性。
        String username = resolveUsername(userDto);
        context.setModelUsername(username);
        context.setUsername(resolveBrokerUsername(userDto, username));
        if (StringUtils.isNotBlank(userDto.getNick())) {
            context.setName(userDto.getNick());
            context.setFirstName(userDto.getNick());
        }

        // 设置用户属性
        context.setUserAttribute(OPEN_ID, userDto.getOpenId());
        if (StringUtils.isNotBlank(userDto.getUnionId())) {
            context.setUserAttribute(UNION_ID, userDto.getUnionId());
        }
        if (StringUtils.isNotBlank(userDto.getUserId())) {
            context.setUserAttribute(DINGTALK_USER_ID, userDto.getUserId());
        }
        if (StringUtils.isNotBlank(corpId)) {
            context.setUserAttribute(CORP_ID, corpId);
        }
        if (enterpriseVerifiedByCorpApi) {
            context.setUserAttribute(ENTERPRISE_VERIFIED, "true");
        }
        if (StringUtils.isNotBlank(userDto.getNick())) {
            context.setUserAttribute(NICK_NAME, userDto.getNick());
        }

        // 设置手机号(去除+86前缀)
        if (StringUtils.isNotBlank(userDto.getMobile())) {
            String mobile = formatMobile(userDto.getMobile());
            context.setUserAttribute(PHONE_NUMBER, mobile);
            context.setUserAttribute("mobile", mobile);
        }

        // 设置邮箱
        if (StringUtils.isNotBlank(userDto.getEmail())) {
            context.setEmail(userDto.getEmail());
        }

        // 存储原始profile用于mapper
        try {
            if (response != null && !response.contains("\"code\"")) {
                JsonNode profile = mapper.readTree(response);
                AbstractJsonUserAttributeMapper.storeUserProfileForMapper(context, profile, getConfig().getAlias());
            }
        } catch (Exception e) {
            logger.debug("Failed to store user profile for mapper", e);
        }

        logger.infof("Successfully created context for DingTalk user: %s", username);
        return context;
    }

    @Override
    protected String extractTokenFromResponse(String response, String tokenName) {
        try {
            logger.debugf("DingTalk token response: %s", sanitizeForLog(response));
            UserTokenDto tokenDto = JSON.parseObject(response, UserTokenDto.class);
            if (tokenDto == null || StringUtils.isBlank(tokenDto.getAccessToken())) {
                tokenResponseHolder.remove();
                throw new IdentityBrokerException("Failed to extract access token from DingTalk response");
            }
            // 保存 token 响应信息，供后续 doGetFederatedIdentity 使用
            tokenResponseHolder.set(tokenDto);
            logger.debugf("Extracted DingTalk token info - openId=%s, unionId=%s, corpId=%s",
                    mask(tokenDto.getOpenId()), mask(tokenDto.getUnionId()), mask(tokenDto.getCorpId()));
            return tokenDto.getAccessToken();
        } catch (RuntimeException e) {
            tokenResponseHolder.remove();
            throw e;
        }
    }

    @Override
    public void preprocessFederatedIdentity(KeycloakSession session, RealmModel realm,
                                            BrokeredIdentityContext context) {
        super.preprocessFederatedIdentity(session, realm, context);

        Map<String, String> config = context.getIdpConfig().getConfig();
        if (!isEnterpriseLoginAllowed(config, context.getUserAttribute(CORP_ID),
                Boolean.parseBoolean(context.getUserAttribute(ENTERPRISE_VERIFIED)))) {
            throw new IdentityBrokerException("DingTalk login rejected: user is not from allowed enterprise");
        }

        Optional<UserModel> linkedUser = findLinkedUser(session, realm, context);
        if (linkedUser.isPresent()) {
            UserModel user = linkedUser.get();
            context.setModelUsername(user.getUsername());
            if (StringUtils.isNotBlank(user.getEmail())) {
                context.setEmail(user.getEmail());
            }
            logger.infof("Resolved DingTalk login by existing federated identity. user=%s",
                    user.getUsername());
            return;
        }

        List<String> matchRules = parseMatchRules(config != null ? config.get(MATCH_RULES) : null);
        Optional<UserModel> matchedUser = findMatchingUser(session, realm, context, matchRules);

        if (matchedUser.isPresent()) {
            UserModel user = matchedUser.get();
            context.setModelUsername(user.getUsername());
            if (StringUtils.isNotBlank(user.getEmail())) {
                context.setEmail(user.getEmail());
            }
            context.setUserAttribute(MATCHED_USER_ID, user.getId());
            logger.infof("Matched DingTalk login to existing user by configured rules. user=%s",
                    user.getUsername());
            return;
        }

        if (!isCreateOnNoMatchAllowed(config)) {
            throw new IdentityBrokerException("DingTalk login rejected: no existing user matched configured rules");
        }
        if (StringUtils.isBlank(context.getModelUsername())) {
            throw new IdentityBrokerException("DingTalk login rejected: username cannot be resolved from nickname or email");
        }
    }

    private Optional<UserModel> findLinkedUser(KeycloakSession session, RealmModel realm,
                                               BrokeredIdentityContext context) {
        if (context == null || context.getIdpConfig() == null
                || StringUtils.isBlank(context.getIdpConfig().getAlias())
                || StringUtils.isBlank(context.getId())) {
            return Optional.empty();
        }

        UserModel user = session.users().getUserByFederatedIdentity(
                realm,
                new FederatedIdentityModel(
                        context.getIdpConfig().getAlias(),
                        context.getId(),
                        context.getUsername()));
        return Optional.ofNullable(user);
    }

    /**
     * 重写 importNewUser 方法，确保新用户首次登录时写入钉钉身份属性，并按配置授予企业角色。
     */
    @Override
    public void importNewUser(KeycloakSession session, RealmModel realm, UserModel user,
                               BrokeredIdentityContext context) {
        // 调用父类方法完成基本的用户导入
        super.importNewUser(session, realm, user, context);
        
        logger.infof("Importing new DingTalk user: %s", user.getUsername());
        
        syncDingTalkIdentityAttributes(user, context);

        // 设置用户属性
        String nickname = context.getUserAttribute(NICK_NAME);
        if (StringUtils.isNotBlank(nickname)) {
            user.setSingleAttribute(NICK_NAME, nickname);
        }
        
        String phoneNumber = context.getUserAttribute(PHONE_NUMBER);
        if (StringUtils.isNotBlank(phoneNumber)) {
            user.setSingleAttribute(PHONE_NUMBER, phoneNumber);
        }
        
        // 按配置分配当前企业对应的 ent-member 和 ent-plugin-enabled 角色
        grantEnterpriseRoles(realm, user, context);

        DingTalkWebhookNotifier.notifyLoginUserCreated(
                session,
                realm,
                context.getIdpConfig(),
                user,
                context.getId(),
                context.getUserAttribute(DINGTALK_USER_ID),
                StringUtils.isNotBlank(context.getUserAttribute(PHONE_NUMBER)),
                StringUtils.isNotBlank(context.getEmail()));
        
        logger.infof("Successfully imported new DingTalk user: %s", user.getUsername());
    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user,
                                     BrokeredIdentityContext context) {
        IdentityProviderModel idpConfig = context.getIdpConfig();
        Map<String, String> config = idpConfig.getConfig();

        syncDingTalkIdentityAttributes(user, context);

        // 检查是否需要更新用户信息
        if (config == null || !Boolean.parseBoolean(config.getOrDefault(IS_UPDATE_USER_INFO, "true"))) {
            logger.debugf("Skip updating user info for: %s", user.getUsername());
            syncMissingPhoneNumber(user, context, config);
            grantEnterpriseRoles(realm, user, context);
            return;
        }

        // 更新邮箱
        if (StringUtils.isNotBlank(context.getEmail())) {
            user.setEmail(context.getEmail());
        }

        // 更新昵称
        String nickname = context.getUserAttribute(NICK_NAME);
        if (StringUtils.isNotBlank(nickname)) {
            user.setSingleAttribute(NICK_NAME, nickname);
        }

        // 更新手机号
        String phoneNumber = context.getUserAttribute(PHONE_NUMBER);
        if (StringUtils.isNotBlank(phoneNumber)) {
            user.setSingleAttribute(PHONE_NUMBER, phoneNumber);
        }

        // 分配当前企业对应的 ent-member 角色和设置用户属性（与飞书/企业微信保持一致）
        grantEnterpriseRoles(realm, user, context);

        logger.infof("Updated user info for: %s", user.getUsername());
    }

    private void syncDingTalkIdentityAttributes(UserModel user, BrokeredIdentityContext context) {
        setAttributeIfPresent(user, DINGTALK_USER_ID, context.getUserAttribute(DINGTALK_USER_ID));
        setAttributeIfPresent(user, UNION_ID, context.getUserAttribute(UNION_ID));
        setAttributeIfPresent(user, OPEN_ID, context.getUserAttribute(OPEN_ID));
        setAttributeIfPresent(user, CORP_ID, context.getUserAttribute(CORP_ID));
    }

    private void setAttributeIfPresent(UserModel user, String attributeName, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        if (!value.equals(user.getFirstAttribute(attributeName))) {
            user.setSingleAttribute(attributeName, value);
        }
    }

    private void syncMissingPhoneNumber(UserModel user, BrokeredIdentityContext context, Map<String, String> config) {
        if (!isMissingPhoneNumberSyncEnabled(config)) {
            return;
        }

        String phoneNumber = context.getUserAttribute(PHONE_NUMBER);
        if (StringUtils.isBlank(phoneNumber)) {
            logger.debugf("Skip phone number sync for %s: DingTalk did not return mobile", user.getUsername());
            return;
        }

        if (StringUtils.isNotBlank(user.getFirstAttribute(PHONE_NUMBER))) {
            logger.debugf("Skip phone number sync for %s: phoneNumber already exists", user.getUsername());
            return;
        }

        user.setSingleAttribute(PHONE_NUMBER, phoneNumber);
        logger.infof("Filled missing phoneNumber for user: %s", user.getUsername());
    }
    
    /**
     * 分配当前企业对应的 ent-member 和 ent-plugin-enabled 角色，并设置企业用户属性
     * 这个逻辑与 Utils.updateOauthUserInfo 保持一致
     */
    private void grantEnterpriseRoles(RealmModel realm, UserModel user, BrokeredIdentityContext context) {
        if (!isEnterpriseRoleGrantEnabled(context.getIdpConfig().getConfig())) {
            logger.debugf("Skip enterprise role grant for %s: disabled by provider config", user.getUsername());
            return;
        }

        String enterpriseId = resolveEnterpriseId(
                context.getIdpConfig().getConfig(),
                context.getUserAttribute(CORP_ID));

        if (StringUtils.isBlank(enterpriseId)) {
            logger.warnf("Skip granting enterprise roles to %s: enterpriseId/corpId is not configured",
                    user.getUsername());
            return;
        }

        String providerId = context.getIdpConfig().getProviderId();
        user.setSingleAttribute(ENT_USER_RESOURCE + enterpriseId, providerId);

        String nickName = context.getUserAttribute(NICK_NAME);
        if (StringUtils.isNotBlank(nickName)) {
            user.setSingleAttribute(ENT_USER_NAME + enterpriseId, nickName);
        }

        if (StringUtils.isBlank(user.getFirstAttribute(ENT_JOIN_TIMESTAMP + enterpriseId))) {
            user.setSingleAttribute(ENT_JOIN_TIMESTAMP + enterpriseId,
                    String.valueOf(System.currentTimeMillis() / 1000));
        }

        grantRoleIfPresent(realm, user, ROLE_MEMBER + enterpriseId);
        grantRoleIfPresent(realm, user, ROLE_PLUGIN_ENABLE + enterpriseId);
    }

    private void grantRoleIfPresent(RealmModel realm, UserModel user, String roleName) {
        RoleModel role = realm.getRole(roleName);
        if (role == null) {
            logger.warnf("Role %s does not exist, skip granting it to user %s", roleName, user.getUsername());
            return;
        }

        if (!user.hasRole(role)) {
            user.grantRole(role);
            logger.infof("Granted role %s to user %s", role.getName(), user.getUsername());
        }
    }

    static String resolveUsername(UserDto userDto) {
        return DingTalkUserSyncTask.resolveProvisionedUsername(userDto);
    }

    static String resolveBrokerUsername(UserDto userDto, String fallbackUsername) {
        if (userDto == null) {
            return fallbackUsername;
        }

        return Arrays.asList(
                        userDto.getUserId(),
                        userDto.getEmail(),
                        userDto.getNick(),
                        userDto.getUnionId(),
                        userDto.getOpenId(),
                        fallbackUsername)
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .findFirst()
                .orElse(fallbackUsername);
    }

    /**
     * 格式化手机号(去除+86前缀)
     */
    static String formatMobile(String mobile) {
        if (StringUtils.isBlank(mobile)) {
            return mobile;
        }
        return mobile.startsWith("+86") ? mobile.substring(3) : mobile;
    }

    static boolean needsCorpUserInfo(UserDto userDto) {
        return userDto == null
                || StringUtils.isBlank(userDto.getOpenId())
                || StringUtils.isBlank(userDto.getUserId())
                || StringUtils.isBlank(userDto.getMobile())
                || StringUtils.isBlank(userDto.getEmail());
    }

    private boolean shouldQueryCorpUserInfo(UserTokenDto tokenDto, UserDto userDto) {
        return hasUnionId(tokenDto, userDto)
                && (needsCorpUserInfo(userDto) || isEnterpriseLoginRequired(getConfig().getConfig()));
    }

    static boolean hasUnionId(UserTokenDto tokenDto, UserDto userDto) {
        return (tokenDto != null && StringUtils.isNotBlank(tokenDto.getUnionId()))
                || (userDto != null && StringUtils.isNotBlank(userDto.getUnionId()));
    }

    static UserDto mergeMissingUserInfo(UserDto primary, UserDto fallback) {
        if (primary == null) {
            return fallback;
        }
        if (fallback == null) {
            return primary;
        }

        if (StringUtils.isBlank(primary.getNick())) {
            primary.setNick(fallback.getNick());
        }
        if (StringUtils.isBlank(primary.getAvatarUrl())) {
            primary.setAvatarUrl(fallback.getAvatarUrl());
        }
        if (StringUtils.isBlank(primary.getMobile())) {
            primary.setMobile(fallback.getMobile());
        }
        if (StringUtils.isBlank(primary.getOpenId())) {
            primary.setOpenId(fallback.getOpenId());
        }
        if (StringUtils.isBlank(primary.getUnionId())) {
            primary.setUnionId(fallback.getUnionId());
        }
        if (StringUtils.isBlank(primary.getUserId())) {
            primary.setUserId(fallback.getUserId());
        }
        if (StringUtils.isBlank(primary.getEmail())) {
            primary.setEmail(fallback.getEmail());
        }
        return primary;
    }

    /**
     * 通过企业内部应用 API 获取用户信息
     * 
     * 使用 OAuth token 或用户信息响应中的 unionId → 企业接口获取 userId → 获取用户详情。
     */
    private UserDto getUserInfoByCorpApi(String corpId, UserDto oauthUserInfo) {
        try {
            // 1. 获取企业 access_token
            String corpAccessToken = getCorpAccessToken();
            if (StringUtils.isBlank(corpAccessToken)) {
                logger.warn("Failed to get corp access token");
                return null;
            }
            logger.debugf("Got corp access token: %s", mask(corpAccessToken));

            UserTokenDto tokenDto = tokenResponseHolder.get();
            String unionId = tokenDto != null ? tokenDto.getUnionId() : null;
            if (StringUtils.isBlank(unionId) && oauthUserInfo != null) {
                unionId = oauthUserInfo.getUnionId();
            }
            if (StringUtils.isBlank(unionId)) {
                logger.debugf("Skip DingTalk corp API for corpId=%s: unionId is missing", mask(corpId));
                return null;
            }

            logger.debugf("Trying to get userId by unionId: %s", mask(unionId));
            String userId = getUserIdByUnionId(corpAccessToken, unionId);
            if (StringUtils.isBlank(userId)) {
                logger.warn("Failed to get userId by any method");
                return null;
            }
            logger.debugf("Got userId: %s", mask(userId));

            // 2. 通过 userId 获取用户详情
            UserDto userDetail = getUserDetailByUserId(corpAccessToken, userId);
            if (userDetail != null) {
                return userDetail;
            }

            UserDto verifiedUser = new UserDto();
            verifiedUser.setUserId(userId);
            return verifiedUser;

        } catch (Exception e) {
            logger.warn("Failed to get user info by corp API", e);
            return null;
        }
    }

    /**
     * 获取企业 access_token
     */
    private String getCorpAccessToken() {
        try {
            String url = UriBuilder.fromUri(CORP_TOKEN_URL)
                    .queryParam("appkey", getConfig().getClientId())
                    .queryParam("appsecret", getConfig().getClientSecret())
                    .build()
                    .toString();

            String response = SimpleHttp.create(session).doGet(url).asString();
            logger.debugf("Corp token response: %s", sanitizeForLog(response));
            
            JsonNode json = mapper.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                logger.errorf("Failed to get corp token: %s", json.get("errmsg").asText());
                return null;
            }
            return json.get("access_token").asText();
        } catch (Exception e) {
            logger.error("Failed to get corp access token", e);
            return null;
        }
    }

    /**
     * 通过 unionId 获取用户 userId
     */
    private String getUserIdByUnionId(String corpAccessToken, String unionId) {
        try {
            String url = UriBuilder.fromUri(GET_USERID_BY_UNIONID_URL)
                    .queryParam("access_token", corpAccessToken)
                    .build()
                    .toString();
            Map<String, String> body = Map.of("unionid", unionId);
            
            String response = SimpleHttp.create(session).doPost(url)
                    .header("Content-Type", "application/json")
                    .json(body)
                    .asString();
            logger.debugf("Get userId by unionId response: %s", sanitizeForLog(response));
            
            JsonNode json = mapper.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                logger.errorf("Failed to get userId: %s", json.get("errmsg").asText());
                return null;
            }
            return json.path("result").path("userid").asText();
        } catch (Exception e) {
            logger.error("Failed to get userId by unionId", e);
            return null;
        }
    }

    /**
     * 通过 userId 获取用户详情
     */
    private UserDto getUserDetailByUserId(String corpAccessToken, String userId) {
        try {
            String url = UriBuilder.fromUri(GET_USER_DETAIL_URL)
                    .queryParam("access_token", corpAccessToken)
                    .build()
                    .toString();
            Map<String, String> body = Map.of("userid", userId);
            
            String response = SimpleHttp.create(session).doPost(url)
                    .header("Content-Type", "application/json")
                    .json(body)
                    .asString();
            logger.debugf("Get user detail response: %s", sanitizeForLog(response));
            
            JsonNode json = mapper.readTree(response);
            if (json.has("errcode") && json.get("errcode").asInt() != 0) {
                logger.errorf("Failed to get user detail: %s", json.get("errmsg").asText());
                return null;
            }
            
            JsonNode result = json.path("result");
            UserDto userDto = new UserDto();
            userDto.setUserId(userId);
            userDto.setOpenId(result.path("open_id").asText(null));
            userDto.setUnionId(result.path("unionid").asText(null));
            userDto.setNick(result.path("name").asText(null));
            userDto.setMobile(result.path("mobile").asText(null));
            userDto.setEmail(result.path("email").asText(null));
            
            // 如果没有 openId，使用 unionId 或 userId 作为替代
            if (StringUtils.isBlank(userDto.getOpenId())) {
                if (StringUtils.isNotBlank(userDto.getUnionId())) {
                    userDto.setOpenId(userDto.getUnionId());
                } else {
                    userDto.setOpenId(userId);
                }
            }
            
            logger.debugf("Got user detail - openId=%s, unionId=%s, nick=%s",
                    mask(userDto.getOpenId()), mask(userDto.getUnionId()), mask(userDto.getNick()));
            return userDto;
        } catch (Exception e) {
            logger.error("Failed to get user detail", e);
            return null;
        }
    }

    private Optional<UserModel> findMatchingUser(KeycloakSession session, RealmModel realm,
                                                 BrokeredIdentityContext context,
                                                 List<String> matchRules) {
        for (String rule : matchRules) {
            Optional<UserModel> matched = findMatchingUserByRule(session, realm, context, rule);
            if (matched.isPresent()) {
                logger.debugf("DingTalk matched existing user by rule=%s, user=%s",
                        rule, matched.get().getUsername());
                return matched;
            }
        }
        return Optional.empty();
    }

    private Optional<UserModel> findMatchingUserByRule(KeycloakSession session, RealmModel realm,
                                                       BrokeredIdentityContext context,
                                                       String rule) {
        switch (rule) {
            case "email":
                if (StringUtils.isBlank(context.getEmail())) {
                    return Optional.empty();
                }
                return findUniqueByEmail(session, realm, context.getEmail(), "login");
            case "phone":
                String phoneNumber = context.getUserAttribute(PHONE_NUMBER);
                for (String attributeName : PHONE_ATTRIBUTE_NAMES) {
                    Optional<UserModel> matched = findUniqueByAttribute(
                            session, realm, attributeName, phoneNumber, "login phone");
                    if (matched.isPresent()) {
                        return matched;
                    }
                }
                return Optional.empty();
            case "unionid":
                return findUniqueByAttribute(session, realm, UNION_ID, context.getUserAttribute(UNION_ID), "login unionid");
            case "openid":
                return findUniqueByAttribute(session, realm, OPEN_ID, context.getUserAttribute(OPEN_ID), "login openid");
            case "username":
                return findUniqueByUsernameCandidates(session, realm, getUsernameCandidates(context), "login username");
            default:
                logger.warnf("Unsupported DingTalk match rule: %s", rule);
                return Optional.empty();
        }
    }

    static List<String> getUsernameCandidates(BrokeredIdentityContext context) {
        String email = StringUtils.trimToNull(context.getEmail());
        String emailPrefix = null;
        if (email != null) {
            int atIndex = email.indexOf('@');
            if (atIndex > 0) {
                emailPrefix = email.substring(0, atIndex);
            }
        }

        return Arrays.asList(
                        emailPrefix,
                        context.getModelUsername())
                .stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Optional<UserModel> findUniqueByEmail(KeycloakSession session, RealmModel realm,
                                                  String email, String source) {
        if (StringUtils.isBlank(email)) {
            return Optional.empty();
        }

        List<UserModel> matches = session.users()
                .searchForUserStream(realm, Map.of(UserModel.EMAIL, email), 0, 2)
                .filter(user -> email.equalsIgnoreCase(user.getEmail()))
                .limit(2)
                .toList();
        if (matches.isEmpty()) {
            UserModel matched = session.users().getUserByEmail(realm, email);
            return Optional.ofNullable(matched);
        }
        return uniqueMatch(matches, source + " email", email);
    }

    private Optional<UserModel> findUniqueByAttribute(KeycloakSession session, RealmModel realm,
                                                     String attributeName, String attributeValue,
                                                     String source) {
        if (StringUtils.isBlank(attributeValue)) {
            return Optional.empty();
        }
        List<UserModel> matches = session.users()
                .searchForUserByUserAttributeStream(realm, attributeName, attributeValue)
                .limit(2)
                .toList();
        return uniqueMatch(matches, source + " attribute=" + attributeName, attributeValue);
    }

    private Optional<UserModel> findUniqueByUsernameCandidates(KeycloakSession session, RealmModel realm,
                                                               List<String> usernames, String source) {
        List<UserModel> matches = new ArrayList<>();
        for (String username : usernames) {
            UserModel matched = session.users().getUserByUsername(realm, username);
            if (matched != null && matches.stream().noneMatch(existing -> existing.getId().equals(matched.getId()))) {
                matches.add(matched);
                if (matches.size() > 1) {
                    break;
                }
            }
        }
        return uniqueMatch(matches, source, usernames);
    }

    private Optional<UserModel> uniqueMatch(List<UserModel> matches, String source, Object valueForLog) {
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            logger.warnf("Skip DingTalk user match by %s: multiple Keycloak users matched value=%s",
                    source, valueForLog);
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }

    static List<String> parseMatchRules(String rawRules) {
        if (StringUtils.isBlank(rawRules)) {
            return DEFAULT_MATCH_RULES;
        }

        return Arrays.stream(rawRules.split("[,，;；\\s]+"))
                .map(StringUtils::trimToEmpty)
                .map(rule -> rule.toLowerCase(Locale.ROOT))
                .map(DingTalkIdentityProvider::normalizeMatchRule)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private static String normalizeMatchRule(String rule) {
        switch (rule) {
            case "mail":
            case "邮箱":
                return "email";
            case "mobile":
            case "phone_number":
            case "phonenumber":
            case "手机号":
            case "电话":
                return "phone";
            case "union_id":
            case "union":
                return "unionid";
            case "open_id":
            case "open":
                return "openid";
            case "user":
            case "name":
            case "用户名":
                return "username";
            default:
                return rule;
        }
    }

    static boolean isCreateOnNoMatchAllowed(Map<String, String> config) {
        return config == null || Boolean.parseBoolean(config.getOrDefault(MATCH_ACTION, "true"));
    }

    static boolean isEnterpriseLoginRequired(Map<String, String> config) {
        return config == null || Boolean.parseBoolean(config.getOrDefault(REQUIRE_ENTERPRISE_USER, "true"));
    }

    static boolean isEnterpriseLoginAllowed(Map<String, String> config, String corpId,
                                            boolean verifiedByCorpApi) {
        if (!isEnterpriseLoginRequired(config)) {
            return true;
        }
        if (verifiedByCorpApi) {
            return true;
        }

        String normalizedCorpId = StringUtils.trimToNull(corpId);
        if (normalizedCorpId == null) {
            return false;
        }

        List<String> allowedCorpIds = parseAllowedCorpIds(config);
        return !allowedCorpIds.isEmpty()
                && allowedCorpIds.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(normalizedCorpId));
    }

    static List<String> parseAllowedCorpIds(Map<String, String> config) {
        if (config == null) {
            return List.of();
        }

        String value = config.get(ALLOWED_CORP_IDS);
        if (StringUtils.isBlank(value)) {
            return List.of();
        }

        return Arrays.stream(value.split("[,，;；\\s]+"))
                .map(StringUtils::trimToNull)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    static boolean isEnterpriseRoleGrantEnabled(Map<String, String> config) {
        return config != null && Boolean.parseBoolean(config.getOrDefault(ENABLE_ENTERPRISE_ROLE_GRANT, "false"));
    }

    static boolean isMissingPhoneNumberSyncEnabled(Map<String, String> config) {
        return config == null
                || Boolean.parseBoolean(config.getOrDefault(SYNC_PHONE_NUMBER_IF_MISSING, "true"));
    }

    static String resolveEnterpriseId(Map<String, String> config, String corpId) {
        String configuredEnterpriseId = config != null ? config.get(ENTERPRISE_ID) : null;
        if (StringUtils.isNotBlank(configuredEnterpriseId)) {
            return configuredEnterpriseId.trim();
        }
        return StringUtils.trimToNull(corpId);
    }

    static String sanitizeForLog(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }

        String sanitized = value;
        for (String key : SENSITIVE_LOG_KEYS) {
            sanitized = sanitized.replaceAll(
                    "(?i)(\"" + Pattern.quote(key) + "\"\\s*:\\s*\")[^\"]*(\")",
                    "$1***$2");
            sanitized = sanitized.replaceAll(
                    "(?i)(" + Pattern.quote(key) + "=)[^&\\s,}]+",
                    "$1***");
        }
        return sanitized;
    }

    static String sanitizeUriForLog(String uriValue) {
        if (StringUtils.isBlank(uriValue)) {
            return uriValue;
        }
        try {
            URI parsed = URI.create(uriValue.trim());
            StringBuilder builder = new StringBuilder();
            if (StringUtils.isNotBlank(parsed.getScheme())) {
                builder.append(parsed.getScheme()).append("://");
            }
            if (StringUtils.isNotBlank(parsed.getHost())) {
                builder.append(parsed.getHost());
            }
            if (parsed.getPort() > 0) {
                builder.append(":").append(parsed.getPort());
            }
            if (StringUtils.isNotBlank(parsed.getPath())) {
                builder.append(parsed.getPath());
            }
            if (StringUtils.isNotBlank(parsed.getQuery())) {
                builder.append("?***");
            }
            return builder.isEmpty() ? "***" : builder.toString();
        } catch (Exception ignored) {
            return "***";
        }
    }

    static String mask(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }

        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        if (trimmed.length() <= 10) {
            return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 2);
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * OAuth2.0回调处理Endpoint
     */
    protected static class Endpoint extends AbstractOAuth2IdentityProvider.Endpoint {

        private final DingTalkIdentityProvider provider;

        public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event,
                        DingTalkIdentityProvider provider) {
            super(callback, realm, event, provider);
            this.provider = provider;
        }

        @Override
        public SimpleHttpRequest generateTokenRequest(String authorizationCode) {
            try {
                // 钉钉使用JSON格式的POST请求，直接传入Map对象让SimpleHttp序列化
                Map<String, String> requestBody = Map.of(
                        "clientId", provider.getConfig().getClientId(),
                        "clientSecret", provider.getConfig().getClientSecret(),
                        "code", authorizationCode,
                        "grantType", "authorization_code"
                );

                logger.debugf("Generating DingTalk token request. clientId=%s, code=%s",
                        mask(provider.getConfig().getClientId()), mask(authorizationCode));

                return SimpleHttp.create(session).doPost(TOKEN_URL)
                        .header("Content-Type", "application/json")
                        .json(requestBody);

            } catch (Exception e) {
                logger.error("Failed to generate DingTalk token request", e);
                throw new IdentityBrokerException("Failed to generate token request: " + e.getMessage());
            }
        }
    }
}
