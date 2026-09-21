package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * 用户信息响应
 * 对应 Electron types.ts 中的 UserInfo
 */
public class UserInfoResponse {

    public String username;

    public String nickname;

    @SerializedName("avatar")
    public String avatar;

    @SerializedName("user_group")
    public String userGroup;

    @SerializedName("group_id")
    public int groupId;

    @SerializedName("is_guest")
    public int isGuest;

    /**
     * 云存储信息
     */
    @SerializedName("cloud_storage_info")
    public CloudStorageInfo cloudStorageInfo;

    public static class CloudStorageInfo {
        @SerializedName("dav_username")
        public String davUsername;

        public boolean valid;
        public boolean disabled;

        @SerializedName("cloud_storage_type")
        public int cloudStorageType;

        @SerializedName("cloud_nick_name")
        public String cloudNickName;

        @SerializedName("fssize")
        public long fsSize;

        @SerializedName("frsize")
        public long frSize;

        @SerializedName("fusize")
        public long fuSize;

        @SerializedName("is_vip")
        public boolean isVip;
    }

    /** 获取显示名称 */
    public String getDisplayName() {
        if (nickname != null && !nickname.isEmpty()) return nickname;
        return username != null ? username : "未知用户";
    }
}
