package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * 项目列表响应
 * 对应 Electron types.ts 中的 ItemListResponse
 */
public class ItemListResponse {

    @SerializedName("mdb_name")
    public String mdbName;

    @SerializedName("mdb_category")
    public String mdbCategory;

    @SerializedName("top_dir")
    public String topDir;

    public String dir;

    public int total;

    public List<PlayListItem> list;
}
