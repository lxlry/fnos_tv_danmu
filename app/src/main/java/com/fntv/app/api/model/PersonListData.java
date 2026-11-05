package com.fntv.app.api.model;

import java.util.List;

/** POST /v/api/v1/person/list/{guid} 的 data。 */
public class PersonListData {
    public List<PersonCredit> list;
    public int total;
}
