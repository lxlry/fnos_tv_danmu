package com.fntv.app.api.model;

import com.fntv.app.api.FnAuthUtils;
import com.google.gson.annotations.SerializedName;

public class ApiResponse<T> {
    public int code; // 从 boolean success 改为 int code
    public String msg; // 新增 msg 字段
    public T data;
}
