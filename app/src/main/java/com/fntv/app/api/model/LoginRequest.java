package com.fntv.app.api.model;

import com.fntv.app.api.FnAuthUtils;
import com.google.gson.annotations.SerializedName;

public class LoginRequest {
    @SerializedName("app_name")
    public String appName;
    public String username;
    public String password;
    public String nonce;

    public LoginRequest(String username, String password) {
        this.appName = "trimemedia-web";
        this.username = username;
        this.password = password;
        this.nonce = FnAuthUtils.generateNonce();
    }
}
