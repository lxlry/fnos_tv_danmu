package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;

/** GET /v/api/v1/person/{guid} */
public class PersonProfile {
    public String guid;
    public String name;
    @SerializedName("original_name")
    public String originalName;
    public String profile;
    public String biography;
}
