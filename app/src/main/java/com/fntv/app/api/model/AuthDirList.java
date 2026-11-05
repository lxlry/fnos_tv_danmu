package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** GET /v/api/v1/server/getAppAuthorizedDir 的 data。 */
public class AuthDirList {
    @SerializedName("authDirList")
    public List<AuthDir> authDirList;

    public static class AuthDir {
        public String path;
        public String uname;
    }
}
