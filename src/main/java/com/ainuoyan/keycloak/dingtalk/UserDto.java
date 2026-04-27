package com.ainuoyan.keycloak.dingtalk;

/**
 * 钉钉用户信息DTO
 *
 * @author: generated
 * @date: 2025-11-26
 **/
public class UserDto {

    /**
     * 用户昵称
     */
    private String nick;

    /**
     * 用户头像
     */
    private String avatarUrl;

    /**
     * 手机号
     */
    private String mobile;

    /**
     * 用户OpenID
     */
    private String openId;

    /**
     * 用户UnionID
     */
    private String unionId;

    /**
     * 钉钉企业通讯录 userId
     */
    private String userId;

    /**
     * 邮箱
     */
    private String email;

    public String getNick() {
        return nick;
    }

    public void setNick(String nick) {
        this.nick = nick;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
