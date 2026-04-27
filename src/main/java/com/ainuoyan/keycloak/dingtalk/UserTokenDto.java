package com.ainuoyan.keycloak.dingtalk;

/**
 * 钉钉Token响应DTO
 * 
 * 钉钉 userAccessToken 接口返回的完整响应包含用户基本信息
 *
 * @author: generated
 * @date: 2025-11-26
 **/
public class UserTokenDto {

    /**
     * Access Token
     */
    private String accessToken;

    /**
     * 过期时间(秒)
     */
    private Long expireIn;

    /**
     * Refresh Token
     */
    private String refreshToken;

    /**
     * 用户的openId（token响应中包含）
     */
    private String openId;

    /**
     * 用户的unionId（token响应中包含）
     */
    private String unionId;

    /**
     * 用户的corpId（企业ID，token响应中可能包含）
     */
    private String corpId;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Long getExpireIn() {
        return expireIn;
    }

    public void setExpireIn(Long expireIn) {
        this.expireIn = expireIn;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getOpenId() {
        return openId;
    }

    public void setOpenId(String openId) {
        this.openId = openId;
    }

    public String getUnionId() {
        return unionId;
    }

    public void setUnionId(String unionId) {
        this.unionId = unionId;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }
}
