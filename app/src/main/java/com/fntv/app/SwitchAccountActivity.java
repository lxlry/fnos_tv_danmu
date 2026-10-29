package com.fntv.app;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.LoginRequest;
import com.fntv.app.api.model.LoginResponseData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 已保存账号列表。点一张卡片就登录，右上角加号去登录页添加。 */
public class SwitchAccountActivity extends AppCompatActivity {
    private static final String TAG = "SwitchAccount";
    private static final int[] AVATAR_COLORS = {
            0xFF8B7CFF, 0xFFE892A8, 0xFF8EA4C8, 0xFF6E8FE8,
            0xFF7EC8C3, 0xFFD4A574, 0xFFB08BD4, 0xFF8FB8E8
    };

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    private SharedPreferences prefs;
    private LinearLayout accountList;
    private View btnBack;
    private View btnRefresh;
    private View btnAdd;
    private float density;
    private boolean loggingIn;
    private boolean protectSession;
    private boolean sessionCaptured;
    private boolean sessionReplaced;
    private String sessionHost = "";
    private String sessionToken = "";
    private final Bundle sessionSnapshot = new Bundle();
    private final List<View> cards = new ArrayList<>();
    private final List<View> menus = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionGuard.attach(this);
        setContentView(R.layout.activity_switch_account);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.bg_dark));
        }
        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        protectSession = getIntent().getBooleanExtra("switch_account", false);
        int screen = getResources().getDisplayMetrics().widthPixels;
        int max = dp(640);
        if (screen > max) {
            int side = (screen - max) / 2;
            findViewById(R.id.switchColumn).setPadding(side, 0, side, 0);
        }
        accountList = findViewById(R.id.accountList);
        btnBack = findViewById(R.id.btnSwitchBack);
        btnRefresh = findViewById(R.id.btnSwitchRefresh);
        btnAdd = findViewById(R.id.btnSwitchAdd);
        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> {
            render();
            AppToast.show(this, "已刷新");
        });
        btnAdd.setOnClickListener(v -> openLogin(null));
        if (getIntent().getBooleanExtra("auth_expired", false)) {
            AppToast.show(this, "登录已失效，请重新登录", true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loggingIn) render();
    }

    private void render() {
        accountList.removeAllViews();
        cards.clear();
        menus.clear();
        List<AccountStore.SavedAccount> accounts = AccountStore.get(prefs).list();
        if (accounts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有账号，点右上角添加");
            empty.setTextColor(color(R.color.text_hint));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            accountList.addView(empty);
            wireFocus();
            btnAdd.requestFocus();
            return;
        }
        String activeId = AccountStore.get(prefs).activeId();
        boolean signedIn = !prefs.getString("auth_token", "").isEmpty();
        Map<String, List<AccountStore.SavedAccount>> groups = new LinkedHashMap<>();
        for (AccountStore.SavedAccount account : accounts) {
            String key = groupKey(account);
            List<AccountStore.SavedAccount> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(account);
        }
        boolean firstGroup = true;
        for (Map.Entry<String, List<AccountStore.SavedAccount>> entry : groups.entrySet()) {
            TextView header = new TextView(this);
            header.setText(groupTitle(entry.getValue().get(0)));
            header.setTextColor(color(R.color.text_primary));
            header.setTextSize(15);
            header.setPadding(dp(4), firstGroup ? dp(4) : dp(16), dp(4), dp(8));
            accountList.addView(header);
            firstGroup = false;
            for (AccountStore.SavedAccount account : entry.getValue()) {
                boolean current = signedIn && account.id != null && account.id.equals(activeId);
                addCard(account, current);
            }
        }
        wireFocus();
        if (!cards.isEmpty()) cards.get(0).requestFocus();
    }

    private void addCard(final AccountStore.SavedAccount account, boolean current) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_account_card);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> useAccount(account));

        TextView avatar = new TextView(this);
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(letter(account.user));
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(18);
        avatar.setTypeface(null, Typeface.BOLD);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(AVATAR_COLORS[Math.abs(account.user == null ? 0 : account.user.hashCode()) % AVATAR_COLORS.length]);
        avatar.setBackground(circle);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        avatar.setLayoutParams(avatarLp);
        card.addView(avatar);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textLp.leftMargin = dp(12);
        textLp.rightMargin = dp(8);
        text.setLayoutParams(textLp);
        TextView name = new TextView(this);
        name.setText(account.user == null ? "" : account.user);
        name.setTextColor(color(R.color.text_primary));
        name.setTextSize(16);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        text.addView(name);
        TextView host = new TextView(this);
        host.setText(hostLine(account));
        host.setTextColor(color(R.color.text_hint));
        host.setTextSize(12);
        host.setSingleLine(true);
        host.setPadding(0, dp(2), 0, 0);
        text.addView(host);
        card.addView(text);

        View side;
        if (current) {
            TextView badge = new TextView(this);
            badge.setText("当前使用");
            badge.setTextColor(color(R.color.text_white));
            badge.setTextSize(11);
            badge.setBackgroundResource(R.drawable.bg_account_badge);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            card.addView(badge);
            side = null;
        } else {
            AppCompatImageView more = new AppCompatImageView(this);
            more.setImageResource(R.drawable.ic_player_more);
            more.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            more.setContentDescription("更多");
            more.setFocusable(true);
            more.setClickable(true);
            more.setBackgroundResource(R.drawable.bg_text_action);
            more.setColorFilter(color(R.color.text_secondary));
            more.setPadding(dp(8), dp(8), dp(8), dp(8));
            more.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
            more.setOnClickListener(v -> confirmDelete(account));
            card.addView(more);
            side = more;
            menus.add(more);
        }
        accountList.addView(card);
        cards.add(card);
        if (side == null) menus.add(card);
    }

    private void useAccount(AccountStore.SavedAccount account) {
        if (loggingIn || account == null) return;
        boolean current = !prefs.getString("auth_token", "").isEmpty()
                && account.id != null && account.id.equals(AccountStore.get(prefs).activeId());
        if (current) {
            sessionReplaced = true;
            if (protectSession) {
                finish();
            } else {
                openHome();
            }
            return;
        }
        if (account.password == null || account.password.isEmpty()) {
            AppToast.show(this, "这个账号没有保存密码，请重新登录");
            openLogin(account);
            return;
        }
        if (protectSession && !sessionCaptured) captureSession();
        loggingIn = true;
        AppToast.show(this, "正在登录 " + account.user);
        if ("fnid".equals(account.mode)) loginFnId(account);
        else loginHttp(account);
    }

    private void loginHttp(final AccountStore.SavedAccount account) {
        final String server = AccountStore.normalizeHttp(account.endpoint);
        FnApiManager.getInstance().updateBaseUrl(server);
        FnApiManager.getInstance().getApi().login(new LoginRequest(account.user, account.password))
                .enqueue(new Callback<ApiResponse<LoginResponseData>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<LoginResponseData>> call,
                                           Response<ApiResponse<LoginResponseData>> response) {
                        if (isFinishing()) {
                            restoreLiveSession();
                            return;
                        }
                        if (response.isSuccessful() && response.body() != null
                                && response.body().code == 0 && response.body().data != null
                                && response.body().data.token != null) {
                            finishLogin("http", server, server, account.user,
                                    account.password, response.body().data.token);
                            return;
                        }
                        String msg = response.body() != null ? response.body().msg : "服务器响应异常";
                        failLogin(msg == null || msg.isEmpty() ? "登录失败" : msg);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<LoginResponseData>> call, Throwable t) {
                        Log.e(TAG, "登录失败", t);
                        if (isFinishing()) {
                            restoreLiveSession();
                            return;
                        }
                        failLogin(t.getMessage() == null ? "网络连接失败" : t.getMessage());
                    }
                });
    }

    private void loginFnId(final AccountStore.SavedAccount account) {
        new FnIdLoginHelper(this, account.endpoint, account.user, account.password,
                new FnIdLoginHelper.FnIdCallback() {
                    @Override
                    public void onSuccess(String token, String domain) {
                        runOnUiThread(() -> {
                            if (isFinishing()) {
                                restoreLiveSession();
                                return;
                            }
                            finishLogin("fnid", account.endpoint, domain, account.user, account.password, token);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (isFinishing()) {
                                restoreLiveSession();
                                return;
                            }
                            failLogin(message == null ? "FN ID 登录失败" : message);
                        });
                    }
                }).start();
    }

    private void finishLogin(String mode, String endpoint, String server, String user, String pass, String token) {
        sessionReplaced = true;
        FnApiManager.getInstance().setToken(token);
        SessionGuard.onLogin();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", server == null ? "" : server);
        editor.putString("user", user);
        editor.putString("auth_token", token);
        editor.putString("login_mode", mode);
        if ("fnid".equals(mode)) {
            editor.putString("fnid_host", endpoint);
            editor.putString("fnid_user", user);
            editor.putString("fnid_pass", pass);
            editor.putBoolean("fnid_remember", true);
            editor.putBoolean("_was_fnid", true);
        } else {
            editor.putString("pass", pass);
            editor.putBoolean("remember", true);
            editor.putBoolean("_was_fnid", false);
            // 避免设置页仍按旧的 FN ID 模式读账号
            editor.remove("fnid_host");
            editor.remove("fnid_user");
            editor.remove("fnid_pass");
            editor.putBoolean("fnid_remember", false);
        }
        // 同步写入，避免立刻打开首页时还读到上一个账号
        editor.commit();
        AccountStore.get(prefs).remember(mode, endpoint, server, user, pass, true);
        AppToast.show(this, "登录成功");
        openHome();
    }

    private void failLogin(String message) {
        loggingIn = false;
        restoreSessionSnapshot();
        AppToast.show(this, message, true);
    }

    private void confirmDelete(final AccountStore.SavedAccount account) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_card);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView message = new TextView(this);
        message.setText("删除 " + account.user + " 的登录记录？");
        message.setTextColor(color(R.color.text_primary));
        message.setTextSize(16);
        root.addView(message);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(16);
        actions.setLayoutParams(actionsLp);
        Button cancel = actionButton("取消", false);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.rightMargin = dp(8);
        cancel.setLayoutParams(cancelLp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button delete = actionButton("删除", true);
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        deleteLp.leftMargin = dp(8);
        delete.setLayoutParams(deleteLp);
        delete.setOnClickListener(v -> {
            dialog.dismiss();
            AccountStore.get(prefs).delete(account.id);
            render();
        });
        actions.addView(cancel);
        actions.addView(delete);
        root.addView(actions);
        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            int max = dp(420);
            if (width > max) width = max;
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        cancel.requestFocus();
    }

    private Button actionButton(String text, boolean danger) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setFocusable(true);
        button.setTextColor(color(danger ? R.color.text_white : R.color.text_secondary));
        button.setBackgroundResource(danger ? R.drawable.bg_btn_danger : R.drawable.bg_input);
        return button;
    }

    private void openLogin(AccountStore.SavedAccount prefill) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("add_account", true);
        intent.putExtra("skip_auto_login", true);
        if (protectSession) intent.putExtra("switch_account", true);
        if (prefill != null) {
            intent.putExtra("prefill_mode", prefill.mode);
            intent.putExtra("prefill_host", prefill.endpoint);
            intent.putExtra("prefill_user", prefill.user);
        }
        startActivity(intent);
    }

    private void openHome() {
        sessionReplaced = true;
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void wireFocus() {
        TvFocus.point(btnBack, View.FOCUS_RIGHT, btnRefresh);
        TvFocus.point(btnRefresh, View.FOCUS_LEFT, btnBack);
        TvFocus.point(btnRefresh, View.FOCUS_RIGHT, btnAdd);
        TvFocus.point(btnAdd, View.FOCUS_LEFT, btnRefresh);
        TvFocus.point(btnBack, View.FOCUS_LEFT, btnBack);
        TvFocus.point(btnAdd, View.FOCUS_RIGHT, btnAdd);
        TvFocus.point(btnBack, View.FOCUS_UP, btnBack);
        TvFocus.point(btnRefresh, View.FOCUS_UP, btnRefresh);
        TvFocus.point(btnAdd, View.FOCUS_UP, btnAdd);
        View first = cards.isEmpty() ? btnAdd : cards.get(0);
        TvFocus.point(btnBack, View.FOCUS_DOWN, first);
        TvFocus.point(btnRefresh, View.FOCUS_DOWN, first);
        TvFocus.point(btnAdd, View.FOCUS_DOWN, first);
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            View menu = i < menus.size() ? menus.get(i) : card;
            View up = i == 0 ? btnAdd : cards.get(i - 1);
            View down = i == cards.size() - 1 ? card : cards.get(i + 1);
            TvFocus.point(card, View.FOCUS_UP, up);
            TvFocus.point(card, View.FOCUS_DOWN, down);
            TvFocus.point(card, View.FOCUS_LEFT, card);
            if (menu == card) {
                TvFocus.point(card, View.FOCUS_RIGHT, card);
                continue;
            }
            View menuUp = i == 0 ? btnAdd : menus.get(i - 1);
            View menuDown = i == menus.size() - 1 ? menu : menus.get(i + 1);
            TvFocus.point(card, View.FOCUS_RIGHT, menu);
            TvFocus.point(menu, View.FOCUS_LEFT, card);
            TvFocus.point(menu, View.FOCUS_RIGHT, menu);
            TvFocus.point(menu, View.FOCUS_UP, menuUp);
            TvFocus.point(menu, View.FOCUS_DOWN, menuDown);
        }
    }

    private void captureSession() {
        sessionCaptured = true;
        sessionHost = prefs.getString("host", "");
        sessionToken = prefs.getString("auth_token", "");
        sessionSnapshot.putString("host", sessionHost);
        sessionSnapshot.putString("user", prefs.getString("user", ""));
        sessionSnapshot.putString("pass", prefs.getString("pass", ""));
        sessionSnapshot.putBoolean("remember", prefs.getBoolean("remember", false));
        sessionSnapshot.putBoolean("has_pass", prefs.contains("pass"));
        sessionSnapshot.putString("login_mode", prefs.getString("login_mode", ""));
        sessionSnapshot.putString("auth_token", sessionToken);
        sessionSnapshot.putString("fnid_host", prefs.getString("fnid_host", ""));
        sessionSnapshot.putString("fnid_user", prefs.getString("fnid_user", ""));
        sessionSnapshot.putString("fnid_pass", prefs.getString("fnid_pass", ""));
        sessionSnapshot.putBoolean("fnid_remember", prefs.getBoolean("fnid_remember", false));
        sessionSnapshot.putBoolean("has_fnid_pass", prefs.contains("fnid_pass"));
        sessionSnapshot.putBoolean("_was_fnid", prefs.getBoolean("_was_fnid", false));
    }

    private void restoreSessionSnapshot() {
        if (!protectSession || !sessionCaptured || sessionReplaced) return;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", sessionSnapshot.getString("host", ""));
        editor.putString("user", sessionSnapshot.getString("user", ""));
        editor.putBoolean("remember", sessionSnapshot.getBoolean("remember"));
        if (sessionSnapshot.getBoolean("has_pass")) editor.putString("pass", sessionSnapshot.getString("pass", ""));
        else editor.remove("pass");
        editor.putString("login_mode", sessionSnapshot.getString("login_mode", ""));
        editor.putString("auth_token", sessionSnapshot.getString("auth_token", ""));
        editor.putString("fnid_host", sessionSnapshot.getString("fnid_host", ""));
        editor.putString("fnid_user", sessionSnapshot.getString("fnid_user", ""));
        editor.putBoolean("fnid_remember", sessionSnapshot.getBoolean("fnid_remember"));
        if (sessionSnapshot.getBoolean("has_fnid_pass")) {
            editor.putString("fnid_pass", sessionSnapshot.getString("fnid_pass", ""));
        } else {
            editor.remove("fnid_pass");
        }
        editor.putBoolean("_was_fnid", sessionSnapshot.getBoolean("_was_fnid"));
        editor.apply();
        restoreLiveSession();
    }

    private void restoreLiveSession() {
        if (!protectSession || !sessionCaptured || sessionReplaced) return;
        if (sessionHost != null && !sessionHost.isEmpty()) {
            FnApiManager.getInstance().updateBaseUrl(sessionHost);
        }
        FnApiManager.getInstance().setToken(sessionToken == null || sessionToken.isEmpty() ? null : sessionToken);
    }

    private static String groupKey(AccountStore.SavedAccount account) {
        String bare = bareHost(account);
        return bare.isEmpty() ? hostLine(account) : bare;
    }

    private static String groupTitle(AccountStore.SavedAccount account) {
        return groupKey(account);
    }

    /** 去掉协议和端口，同一台机器上的不同端口归到一组。 */
    private static String bareHost(AccountStore.SavedAccount account) {
        if (account == null) return "";
        String raw;
        if ("fnid".equals(account.mode)) {
            String server = account.server == null ? "" : account.server;
            raw = server.isEmpty() ? (account.endpoint == null ? "" : account.endpoint) : server;
        } else {
            raw = account.endpoint == null ? "" : account.endpoint;
        }
        raw = raw.replaceFirst("^https?://", "");
        int slash = raw.indexOf('/');
        if (slash >= 0) raw = raw.substring(0, slash);
        int colon = raw.indexOf(':');
        if (colon > 0 && raw.indexOf(':', colon + 1) < 0) raw = raw.substring(0, colon);
        return raw;
    }

    private static String hostLine(AccountStore.SavedAccount account) {
        if (account == null) return "";
        if ("fnid".equals(account.mode)) {
            String server = account.server == null ? "" : account.server.replaceFirst("^https?://", "");
            if (!server.isEmpty() && !server.equals(account.endpoint)) return server;
            return account.endpoint == null ? "" : account.endpoint;
        }
        String host = account.endpoint == null ? "" : account.endpoint;
        return host.replaceFirst("^https?://", "");
    }

    private static String letter(String user) {
        if (user == null || user.isEmpty()) return "?";
        String first = user.substring(0, 1);
        if (first.charAt(0) < 128) return first.toLowerCase();
        return first;
    }

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    void onSessionExpired() {
        AppToast.show(this, "登录已失效，请重新登录", true);
        loggingIn = false;
        render();
    }

    @Override
    protected void onDestroy() {
        if (protectSession && sessionCaptured && !sessionReplaced) restoreSessionSnapshot();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
