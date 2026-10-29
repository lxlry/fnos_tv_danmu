package com.fntv.app;

import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 已保存的登录账户。登录成功后写入，登录页可点选切换。
 * 同一服务器（或 FN ID）加用户名只保留一条，最近使用的排在前面，最多 8 个。
 */
final class AccountStore {
    static final int LIMIT = 8;
    private static final String KEY_LIST = "saved_accounts";
    private static final String KEY_ACTIVE = "active_account_id";
    private static final String KEY_MIGRATED = "accounts_migrated";
    private static final Type LIST_TYPE = new TypeToken<ArrayList<SavedAccount>>() {}.getType();
    private static final Gson GSON = new Gson();

    private final SharedPreferences prefs;

    private AccountStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    static AccountStore get(SharedPreferences prefs) {
        return new AccountStore(prefs);
    }

    static final class SavedAccount {
        String id;
        String mode;
        String endpoint;
        String server;
        String user;
        String password;
        long usedAt;
    }

    List<SavedAccount> list() {
        migrateIfNeeded();
        return read();
    }

    String activeId() {
        return prefs.getString(KEY_ACTIVE, "");
    }

    /** 勾选记住密码时写入列表；取消勾选时从列表里去掉，避免继续留着密码。 */
    void remember(String mode, String endpoint, String server, String user, String password, boolean keep) {
        if (keep && password != null && !password.isEmpty()) {
            upsert(mode, endpoint, server, user, password);
        } else {
            delete(build(mode, endpoint, server, user, password, 0L).id);
        }
    }

    void upsert(String mode, String endpoint, String server, String user, String password) {
        migrateIfNeeded();
        SavedAccount account = build(mode, endpoint, server, user, password, System.currentTimeMillis());
        if (account.endpoint.isEmpty() || account.user.isEmpty()) return;

        List<SavedAccount> all = read();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (account.id.equals(all.get(i).id)) all.remove(i);
        }
        all.add(0, account);
        if (all.size() > LIMIT) {
            all = new ArrayList<>(all.subList(0, LIMIT));
        }
        write(all);
        prefs.edit().putString(KEY_ACTIVE, account.id).apply();
    }

    void delete(String id) {
        if (id == null || id.isEmpty()) return;
        List<SavedAccount> all = read();
        boolean removed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            if (id.equals(all.get(i).id)) {
                all.remove(i);
                removed = true;
            }
        }
        if (!removed) return;
        write(all);
        if (id.equals(prefs.getString(KEY_ACTIVE, ""))) {
            prefs.edit().remove(KEY_ACTIVE).apply();
        }
    }

    void clear() {
        prefs.edit().remove(KEY_LIST).remove(KEY_ACTIVE).apply();
    }

    static String normalizeHttp(String host) {
        String value = safe(host);
        if (value.isEmpty()) return "";
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static String label(SavedAccount account) {
        if (account == null) return "";
        if ("fnid".equals(account.mode)) return "FN ID · " + account.endpoint;
        return account.endpoint == null ? "" : account.endpoint;
    }

    private void migrateIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return;
        List<SavedAccount> all = read();
        if (all.isEmpty()) {
            String fnId = safe(prefs.getString("fnid_host", ""));
            String fnUser = safe(prefs.getString("fnid_user", ""));
            String fnPass = prefs.getString("fnid_pass", "");
            if (!fnId.isEmpty() && !fnUser.isEmpty() && fnPass != null && !fnPass.isEmpty()) {
                all.add(build("fnid", fnId, prefs.getString("host", ""), fnUser, fnPass, 1L));
            }
            String host = safe(prefs.getString("host", ""));
            String user = safe(prefs.getString("user", ""));
            String pass = prefs.getString("pass", "");
            if (!host.isEmpty() && !user.isEmpty() && pass != null && !pass.isEmpty()
                    && (host.startsWith("http://") || host.startsWith("https://"))) {
                all.add(build("http", host, host, user, pass, 2L));
            }
            String mode = prefs.getString("login_mode", "http");
            List<SavedAccount> ordered = new ArrayList<>();
            for (SavedAccount item : all) {
                if (mode.equals(item.mode)) ordered.add(item);
            }
            for (SavedAccount item : all) {
                if (!mode.equals(item.mode)) ordered.add(item);
            }
            all = ordered;
            write(all);
            if (!all.isEmpty()) {
                prefs.edit().putString(KEY_ACTIVE, all.get(0).id).apply();
            }
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply();
    }

    private static SavedAccount build(String mode, String endpoint, String server,
                                      String user, String password, long usedAt) {
        SavedAccount account = new SavedAccount();
        account.mode = "fnid".equals(mode) ? "fnid" : "http";
        account.endpoint = "fnid".equals(account.mode) ? safe(endpoint) : normalizeHttp(endpoint);
        account.server = safe(server);
        if (account.server.isEmpty()) account.server = account.endpoint;
        if (!"fnid".equals(account.mode)) account.server = normalizeHttp(account.server);
        account.user = safe(user);
        account.password = password == null ? "" : password;
        account.usedAt = usedAt;
        account.id = account.mode + "\u0001" + account.endpoint + "\u0001" + account.user;
        return account;
    }

    private List<SavedAccount> read() {
        String raw = prefs.getString(KEY_LIST, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            List<SavedAccount> parsed = GSON.fromJson(raw, LIST_TYPE);
            if (parsed == null) return new ArrayList<>();
            List<SavedAccount> clean = new ArrayList<>();
            for (SavedAccount item : parsed) {
                if (item == null || item.user == null || item.endpoint == null) continue;
                if (item.mode == null) item.mode = "http";
                if (item.id == null || item.id.isEmpty()) {
                    item.id = item.mode + "\u0001" + item.endpoint + "\u0001" + item.user;
                }
                if (item.password == null) item.password = "";
                if (item.server == null || item.server.isEmpty()) item.server = item.endpoint;
                clean.add(item);
            }
            return clean;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void write(List<SavedAccount> all) {
        prefs.edit().putString(KEY_LIST, GSON.toJson(all)).apply();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
