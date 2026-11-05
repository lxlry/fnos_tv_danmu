package com.fntv.app.api.model;

import java.util.List;

/** POST /v/api/v1/person/item/list 的 data。 */
public class PersonWorksData {
    public List<PlayListItem> list;
    public int total;
}
