package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;

/** POST /v/api/v1/person/list/{guid} 的单条演职员。 */
public class PersonCredit {
    @SerializedName("person_guid")
    public String personGuid;
    public String role;
    public String job;
    public int order;
    public String name;
    @SerializedName("original_name")
    public String originalName;
    @SerializedName("profile_path")
    public String profilePath;

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (originalName != null) return originalName.trim();
        return "";
    }

    /** 演员显示角色，其他职务显示职务名。 */
    public String roleLabel() {
        String jobName = job == null ? "" : job.trim();
        if ("Actor".equals(jobName)) {
            if (role != null && !role.trim().isEmpty()) return "饰 " + role.trim();
            return "演员";
        }
        if ("Director".equals(jobName)) return "导演";
        if ("Screenplay".equals(jobName) || "Writer".equals(jobName)) return "编剧";
        if ("Producer".equals(jobName)) return "制片";
        if (!jobName.isEmpty()) return jobName;
        if (role != null) return role.trim();
        return "";
    }
}
