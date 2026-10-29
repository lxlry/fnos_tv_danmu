package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.LoginRequest;
import com.fntv.app.api.model.LoginResponseData;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    // HTTP 登录卡片
    private LinearLayout httpCard;
    private EditText httpHostEt, httpUserEt, httpPassEt;
    private CheckBox httpRememberCb;
    private Button httpHostScan, httpUserScan, httpPassScan;

    // FN ID 登录卡片
    private LinearLayout fnidCard;
    private EditText fnidHostEt, fnidUserEt, fnidPassEt;
    private CheckBox fnidRememberCb;
    private Button fnidHostScan, fnidUserScan, fnidPassScan;

    private FrameLayout cardContainer;
    private Button btnLogin;
    private Button btnSavedAccounts;
    private Button btnHttpMode, btnFnIdMode;
    private SharedPreferences prefs;
    private boolean isLoggingIn = false;
    private boolean isFnIdMode = false;
    private long loginStartTime;
    private float density;
    private View httpRememberWrap;
    private View fnidRememberWrap;
    /** 从首页过来切换账户时，旧页面还在。登录失败要把接口地址和 token 还原。 */
    private boolean switching;
    private boolean sessionReplaced;
    private String sessionHost = "";
    private String sessionToken = "";
    private final Bundle sessionSnapshot = new Bundle();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionGuard.attach(this);
        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        boolean skipAuto = getIntent().getBooleanExtra("skip_auto_login", false);
        switching = getIntent().getBooleanExtra("switch_account", false);
        if (!skipAuto && restoreSession()) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        if (switching) captureSession();
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(color(R.color.bg_dark));
        }
        if (getIntent().getBooleanExtra("auth_expired", false)) {
            AppToast.show(this, "登录已失效，请重新登录", true);
        }
        density = getResources().getDisplayMetrics().density;
        btnLogin = findViewById(R.id.btnLogin);
        LinearLayout loginCard = findViewById(R.id.loginCard);

        // 移除旧布局中的输入字段（从"服务器地址"标签到记住密码）
        int[] removeIds = {R.id.etHost, R.id.etUser, R.id.etPass, R.id.cbRemember};
        for (int i = loginCard.getChildCount() - 1; i >= 0; i--) {
            View v = loginCard.getChildAt(i);
            boolean shouldRemove = false;
            if (v instanceof EditText) {
                for (int rid : removeIds) {
                    if (v.getId() == rid) { shouldRemove = true; break; }
                }
            } else if (v instanceof RelativeLayout && v.getId() == R.id.cbRemember) {
                shouldRemove = true;
            } else if (v instanceof TextView) {
                String t = ((TextView) v).getText().toString();
                if ("服务器地址".equals(t) || "用户名".equals(t) || "密码".equals(t)) shouldRemove = true;
            }
            if (shouldRemove) loginCard.removeViewAt(i);
        }

        // ====== 创建模式切换按钮 ======
        LinearLayout modeRow = new LinearLayout(this);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.leftMargin = 0;
        modeLp.rightMargin = 0;
        modeRow.setLayoutParams(modeLp);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(android.view.Gravity.CENTER);
        modeRow.setPadding(0, 0, 0, (int)(8 * density));

        btnHttpMode = makeToggleBtn("HTTP 登录");
        btnFnIdMode = makeToggleBtn("FN ID 登录");
        modeRow.addView(btnHttpMode);
        View btnSpacer = new View(this);
        btnSpacer.setLayoutParams(new LinearLayout.LayoutParams((int)(12 * density), 1));
        modeRow.addView(btnSpacer);
        modeRow.addView(btnFnIdMode);

        // 把横线移到模式行上面
        View divider = null;
        for (int i = 0; i < loginCard.getChildCount(); i++) {
            View v = loginCard.getChildAt(i);
            if (v instanceof View && !(v instanceof TextView) && !(v instanceof EditText)
                    && !(v instanceof RelativeLayout) && !(v instanceof Button)
                    && v.getId() != android.R.id.content) {
                divider = v;
                break;
            }
        }
        if (divider != null) {
            loginCard.removeView(divider);
            // 去掉横线的上边距，紧贴 FN TV
            android.view.ViewGroup.MarginLayoutParams dlp = (android.view.ViewGroup.MarginLayoutParams) divider.getLayoutParams();
            dlp.topMargin = 0;
            divider.setLayoutParams(dlp);
            // 在 app name 后插入横线（index 2）
            loginCard.addView(divider, 2);
        }
        // 在登录按钮前插入模式行
        int btnIdx = loginCard.indexOfChild(btnLogin);
        if (btnIdx > 0) {
            loginCard.addView(modeRow, btnIdx);
        }

        // ====== 创建卡片容器 ======
        cardContainer = new FrameLayout(this);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.bottomMargin = (int)(16 * density);
        cardContainer.setLayoutParams(ccLp);

        // HTTP 登录卡片
        httpCard = buildInputCard(false);
        cardContainer.addView(httpCard);

        // FN ID 登录卡片（初始隐藏）
        fnidCard = buildInputCard(true);
        fnidCard.setVisibility(View.GONE);
        cardContainer.addView(fnidCard);

        // 模式按钮 ↓ 第一个输入框
        if (httpHostEt != null) btnHttpMode.setNextFocusDownId(httpHostEt.getId());
        if (fnidHostEt != null) btnFnIdMode.setNextFocusDownId(fnidHostEt.getId());

        // 把卡片容器插入到 modeRow 后面
        int modeIdx = loginCard.indexOfChild(modeRow);
        if (modeIdx >= 0) {
            loginCard.addView(cardContainer, modeIdx + 1);
        }

        // ====== 模式切换 ======
        View.OnClickListener modeSwitch = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newMode = (v == btnFnIdMode);
                if (newMode == isFnIdMode) return;
                saveCardInput();
                isFnIdMode = newMode;
                httpCard.setVisibility(isFnIdMode ? View.GONE : View.VISIBLE);
                fnidCard.setVisibility(isFnIdMode ? View.VISIBLE : View.GONE);
                loadCardInput();
                updateModeColor();
                wireLoginFocus();
            }
        };
        btnHttpMode.setOnClickListener(modeSwitch);
        btnFnIdMode.setOnClickListener(modeSwitch);

        // ====== 恢复数据 ======
        isFnIdMode = "fnid".equals(prefs.getString("login_mode", ""));
        loadCardInput();
        updateModeColor();
        if (isFnIdMode) {
            httpCard.setVisibility(View.GONE);
            fnidCard.setVisibility(View.VISIBLE);
        }
        addSavedAccountsEntry(loginCard);
        applyPrefill();
        wireLoginFocus();
        focusLoginField();

        // 登录按钮
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { doLogin(); }
        });

        // 自动登录（仅冷启动时触发，手动退出登录不触发）
        if (!skipAuto) {
            btnLogin.postDelayed(() -> {
                if (isFnIdMode) {
                    String id = prefs.getString("fnid_host", "");
                    String u = prefs.getString("fnid_user", "");
                    String p = prefs.getString("fnid_pass", "");
                    if (!id.isEmpty() && !u.isEmpty() && !p.isEmpty() && prefs.getBoolean("fnid_remember", false)) {
                        btnLogin.setText("自动登录中...");
                        doLogin();
                    }
                } else {
                    String h = prefs.getString("host", "");
                    String u = prefs.getString("user", "");
                    String p = prefs.getString("pass", "");
                    if (!h.isEmpty() && !u.isEmpty() && !p.isEmpty() && prefs.getBoolean("remember", false)) {
                        btnLogin.setText("自动登录中...");
                        doLoginHttp(h, u, p);
                    }
                }
            }, 300);
        }
    }

    // ==================== 卡片构建 ====================

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private Button makeToggleBtn(String text) {
        Button btn = new Button(this);
        btn.setLayoutParams(new LinearLayout.LayoutParams(0, (int)(44 * density), 1));
        btn.setBackgroundResource(R.drawable.bg_mode_toggle);
        btn.setText(text);
        btn.setTextColor(color(R.color.text_primary));
        btn.setTextSize(13);
        btn.setFocusable(true);
        btn.setPadding(8, 0, 8, 0);
        return btn;
    }

    private View makeSpacer(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    /** 构建一个输入卡片（含标签、输入框、记住密码） */
    private LinearLayout buildInputCard(boolean isFnId) {
        LinearLayout card = new LinearLayout(this);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 4, 0, 0);

        String hostHint = isFnId ? "输入 FN ID（如 a123456789）" : "例如 http://192.168.1.1:5666";
        int hostInputType = isFnId
                ? android.text.InputType.TYPE_CLASS_TEXT
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI;

        Button[] hostScan = new Button[1];
        Button[] userScan = new Button[1];
        Button[] passScan = new Button[1];
        EditText etHost = addInputRow(card, isFnId ? "FN ID" : "服务器地址", hostHint, hostInputType, dp(16), hostScan);
        EditText etUser = addInputRow(card, "用户名", "输入用户名",
                android.text.InputType.TYPE_CLASS_TEXT, dp(16), userScan);
        EditText etPass = addInputRow(card, "密码", "输入密码",
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                dp(16), passScan);

        // 记住密码
        RelativeLayout cbWrap = new RelativeLayout(this);
        cbWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density)));
        cbWrap.setBackgroundResource(R.drawable.bg_input);
        cbWrap.setFocusable(true);
        cbWrap.setNextFocusDownId(R.id.btnLogin);
        cbWrap.setPadding((int)(12 * density), 0, (int)(12 * density), 0);

        // AppCompatCheckBox：支持 API 19 上的 buttonTint 兼容着色
        CheckBox cb = new androidx.appcompat.widget.AppCompatCheckBox(this);
        RelativeLayout.LayoutParams cbLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.addRule(RelativeLayout.CENTER_VERTICAL);
        cb.setLayoutParams(cbLp);
        cb.setFocusable(false);
        cb.setText("记住密码");
        cb.setTextColor(color(R.color.text_primary));
        cb.setTextSize(14);
        cb.setId(View.generateViewId());
        // 兼容旧版本的勾选图标染蓝（替代 API 22 的 getButtonDrawable + setTint）
        androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                cb, android.content.res.ColorStateList.valueOf(color(R.color.colorAccent)));
        cbWrap.addView(cb);
        cbWrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { cb.toggle(); }
        });
        card.addView(cbWrap);

        // 保存引用
        if (isFnId) {
            fnidCard = card;
            fnidHostEt = etHost;
            fnidUserEt = etUser;
            fnidPassEt = etPass;
            fnidHostScan = hostScan[0];
            fnidUserScan = userScan[0];
            fnidPassScan = passScan[0];
            fnidRememberCb = cb;
            fnidRememberWrap = cbWrap;
        } else {
            httpCard = card;
            httpHostEt = etHost;
            httpUserEt = etUser;
            httpPassEt = etPass;
            httpHostScan = hostScan[0];
            httpUserScan = userScan[0];
            httpPassScan = passScan[0];
            httpRememberCb = cb;
            httpRememberWrap = cbWrap;
        }

        return card;
    }

    private void linkScan(View field, View scan, View up, View down) {
        if (field == null || scan == null || scan.getVisibility() != View.VISIBLE) return;
        TvFocus.point(field, View.FOCUS_RIGHT, scan);
        TvFocus.point(scan, View.FOCUS_LEFT, field);
        TvFocus.point(scan, View.FOCUS_RIGHT, scan);
        TvFocus.point(scan, View.FOCUS_UP, up);
        TvFocus.point(scan, View.FOCUS_DOWN, down);
    }

    /** 标签加输入框。电视端右侧多一个扫码按钮。 */
    private EditText addInputRow(LinearLayout card, String label, String hint, int inputType,
                                 int bottomMargin, Button[] scanOut) {
        card.addView(makeLabel(label));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = bottomMargin;
        row.setLayoutParams(rowLp);

        EditText field = new EditText(this);
        field.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1));
        field.setBackgroundResource(R.drawable.bg_input);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setSingleLine(true);
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setTextColor(color(R.color.text_primary));
        field.setHintTextColor(color(R.color.text_hint));
        field.setTextSize(15);
        field.setId(View.generateViewId());
        row.addView(field);

        if (QrInput.isTelevision(this)) {
            Button scan = QrInput.scanButton(this, field, label);
            LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(dp(72), dp(48));
            scanLp.leftMargin = dp(8);
            scan.setLayoutParams(scanLp);
            row.addView(scan);
            if (scanOut != null && scanOut.length > 0) scanOut[0] = scan;
        }
        card.addView(row);
        return field;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tv.setPadding(0, 0, 0, (int)(4 * density));
        tv.setText(text);
        tv.setTextColor(color(R.color.text_secondary));
        tv.setTextSize(12);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** 登录按钮下方：有已保存账号时显示入口，进入切换账号页。 */
    private void addSavedAccountsEntry(LinearLayout loginCard) {
        btnSavedAccounts = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(12);
        btnSavedAccounts.setLayoutParams(lp);
        btnSavedAccounts.setBackgroundResource(R.drawable.bg_input);
        btnSavedAccounts.setTextColor(color(R.color.text_secondary));
        btnSavedAccounts.setTextSize(15);
        btnSavedAccounts.setAllCaps(false);
        btnSavedAccounts.setFocusable(true);
        btnSavedAccounts.setOnClickListener(v -> {
            Intent intent = new Intent(this, SwitchAccountActivity.class);
            if (switching) intent.putExtra("switch_account", true);
            // 结束登录页，避免切换成功后底层登录页生命周期把旧账号写回 prefs
            intent.putExtra("from_login", true);
            startActivity(intent);
            finish();
        });
        int loginIdx = loginCard.indexOfChild(btnLogin);
        if (loginIdx >= 0) loginCard.addView(btnSavedAccounts, loginIdx + 1);
        else loginCard.addView(btnSavedAccounts);
        refreshSavedAccountsEntry();
    }

    private void refreshSavedAccountsEntry() {
        if (btnSavedAccounts == null) return;
        int count = AccountStore.get(prefs).list().size();
        if (count <= 0) {
            btnSavedAccounts.setVisibility(View.GONE);
            return;
        }
        btnSavedAccounts.setVisibility(View.VISIBLE);
        btnSavedAccounts.setText(count == 1 ? "使用已有账号" : "使用已有账号（" + count + "）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedAccountsEntry();
        if (btnSavedAccounts != null) wireLoginFocus();
    }

    private void wireLoginFocus() {
        EditText host = isFnIdMode ? fnidHostEt : httpHostEt;
        EditText user = isFnIdMode ? fnidUserEt : httpUserEt;
        EditText pass = isFnIdMode ? fnidPassEt : httpPassEt;
        View remember = isFnIdMode ? fnidRememberWrap : httpRememberWrap;
        Button mode = isFnIdMode ? btnFnIdMode : btnHttpMode;
        if (host == null || user == null || pass == null || remember == null || mode == null) return;

        TvFocus.point(btnHttpMode, View.FOCUS_RIGHT, btnFnIdMode);
        TvFocus.point(btnFnIdMode, View.FOCUS_LEFT, btnHttpMode);
        TvFocus.point(btnHttpMode, View.FOCUS_LEFT, btnHttpMode);
        TvFocus.point(btnFnIdMode, View.FOCUS_RIGHT, btnFnIdMode);

        View aboveMode = btnSavedAccounts != null && btnSavedAccounts.getVisibility() == View.VISIBLE
                ? btnSavedAccounts : btnLogin;
        TvFocus.point(btnHttpMode, View.FOCUS_UP, aboveMode);
        TvFocus.point(btnFnIdMode, View.FOCUS_UP, aboveMode);
        TvFocus.point(btnHttpMode, View.FOCUS_DOWN, host);
        TvFocus.point(btnFnIdMode, View.FOCUS_DOWN, host);

        TvFocus.point(host, View.FOCUS_UP, mode);
        TvFocus.point(host, View.FOCUS_DOWN, user);
        TvFocus.point(user, View.FOCUS_UP, host);
        TvFocus.point(user, View.FOCUS_DOWN, pass);
        TvFocus.point(pass, View.FOCUS_UP, user);
        TvFocus.point(pass, View.FOCUS_DOWN, remember);
        Button hostScan = isFnIdMode ? fnidHostScan : httpHostScan;
        Button userScan = isFnIdMode ? fnidUserScan : httpUserScan;
        Button passScan = isFnIdMode ? fnidPassScan : httpPassScan;
        linkScan(host, hostScan, mode, userScan != null ? userScan : user);
        linkScan(user, userScan, hostScan != null ? hostScan : host, passScan != null ? passScan : pass);
        linkScan(pass, passScan, userScan != null ? userScan : user, remember);
        TvFocus.point(remember, View.FOCUS_UP, pass);
        TvFocus.point(remember, View.FOCUS_DOWN, btnLogin);
        TvFocus.point(remember, View.FOCUS_LEFT, remember);
        TvFocus.point(remember, View.FOCUS_RIGHT, remember);

        TvFocus.point(btnLogin, View.FOCUS_UP, remember);
        TvFocus.point(btnLogin, View.FOCUS_LEFT, btnLogin);
        TvFocus.point(btnLogin, View.FOCUS_RIGHT, btnLogin);
        if (btnSavedAccounts != null && btnSavedAccounts.getVisibility() == View.VISIBLE) {
            TvFocus.point(btnLogin, View.FOCUS_DOWN, btnSavedAccounts);
            TvFocus.point(btnSavedAccounts, View.FOCUS_UP, btnLogin);
            TvFocus.point(btnSavedAccounts, View.FOCUS_DOWN, mode);
            TvFocus.point(btnSavedAccounts, View.FOCUS_LEFT, btnSavedAccounts);
            TvFocus.point(btnSavedAccounts, View.FOCUS_RIGHT, btnSavedAccounts);
        } else {
            TvFocus.point(btnLogin, View.FOCUS_DOWN, mode);
        }
    }

    /** 添加账号时密码留空。补登某个已保存账号时，带上服务器和用户名。 */
    private void applyPrefill() {
        if (!getIntent().getBooleanExtra("add_account", false)) return;
        String modeName = getIntent().getStringExtra("prefill_mode");
        if (modeName != null && !modeName.isEmpty()) {
            isFnIdMode = "fnid".equals(modeName);
        }
        httpCard.setVisibility(isFnIdMode ? View.GONE : View.VISIBLE);
        fnidCard.setVisibility(isFnIdMode ? View.VISIBLE : View.GONE);
        EditText hostEt = isFnIdMode ? fnidHostEt : httpHostEt;
        EditText userEt = isFnIdMode ? fnidUserEt : httpUserEt;
        EditText passEt = isFnIdMode ? fnidPassEt : httpPassEt;
        if (modeName != null && !modeName.isEmpty()) {
            String host = getIntent().getStringExtra("prefill_host");
            String user = getIntent().getStringExtra("prefill_user");
            if (hostEt != null && host != null) hostEt.setText(host);
            if (userEt != null && user != null) userEt.setText(user);
        } else if (userEt != null) {
            userEt.setText("");
        }
        if (passEt != null) passEt.setText("");
        updateModeColor();
    }

    private void focusLoginField() {
        EditText host = isFnIdMode ? fnidHostEt : httpHostEt;
        EditText user = isFnIdMode ? fnidUserEt : httpUserEt;
        EditText pass = isFnIdMode ? fnidPassEt : httpPassEt;
        EditText target = host;
        if (user != null && user.getText() != null && user.getText().length() > 0) target = pass;
        else if (host != null && host.getText() != null && host.getText().length() > 0) target = user;
        if (target != null) target.requestFocus();
    }

    private void captureSession() {
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
        if (!switching || sessionReplaced) return;
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", sessionSnapshot.getString("host", ""));
        editor.putString("user", sessionSnapshot.getString("user", ""));
        editor.putBoolean("remember", sessionSnapshot.getBoolean("remember"));
        if (sessionSnapshot.getBoolean("has_pass")) {
            editor.putString("pass", sessionSnapshot.getString("pass", ""));
        } else {
            editor.remove("pass");
        }
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

    /** 切换账户失败或返回时，首页仍使用原来的服务器和 token。 */
    private void restoreLiveSession() {
        if (!switching || sessionReplaced) return;
        if (sessionHost != null && !sessionHost.isEmpty()) {
            FnApiManager.getInstance().updateBaseUrl(sessionHost);
        }
        if (sessionToken == null || sessionToken.isEmpty()) {
            FnApiManager.getInstance().setToken(null);
        } else {
            FnApiManager.getInstance().setToken(sessionToken);
        }
    }

    private void openHome() {
        sessionReplaced = true;
        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ==================== 数据保存/加载 ====================

    private void saveCardInput() {
        SharedPreferences.Editor e = prefs.edit();
        if (isFnIdMode) {
            e.putString("fnid_host", fnidHostEt.getText().toString().trim());
            e.putString("fnid_user", fnidUserEt.getText().toString().trim());
            e.putBoolean("fnid_remember", fnidRememberCb.isChecked());
            String pass = fnidPassEt.getText().toString().trim();
            if (fnidRememberCb.isChecked()) e.putString("fnid_pass", pass);
            else e.remove("fnid_pass");
        } else {
            e.putString("host", httpHostEt.getText().toString().trim());
            e.putString("user", httpUserEt.getText().toString().trim());
            e.putBoolean("remember", httpRememberCb.isChecked());
            String pass = httpPassEt.getText().toString().trim();
            if (httpRememberCb.isChecked()) e.putString("pass", pass);
            else e.remove("pass");
        }
        e.apply();
    }

    private void loadCardInput() {
        if (isFnIdMode) {
            fnidHostEt.setText(prefs.getString("fnid_host", ""));
            fnidUserEt.setText(prefs.getString("fnid_user", ""));
            boolean rem = prefs.getBoolean("fnid_remember", false);
            fnidRememberCb.setChecked(rem);
            fnidPassEt.setText(rem ? prefs.getString("fnid_pass", "") : "");
        } else {
            httpHostEt.setText(prefs.getString("host", "http://192.168.10.158:5666"));
            httpUserEt.setText(prefs.getString("user", ""));
            boolean rem = prefs.getBoolean("remember", false);
            httpRememberCb.setChecked(rem);
            httpPassEt.setText(rem ? prefs.getString("pass", "") : "");
        }
    }

    private void updateModeColor() {
        btnHttpMode.setSelected(!isFnIdMode);
        btnFnIdMode.setSelected(isFnIdMode);
        btnHttpMode.setTextColor(color(isFnIdMode ? R.color.text_hint : R.color.text_primary));
        btnFnIdMode.setTextColor(color(isFnIdMode ? R.color.text_primary : R.color.text_hint));
    }

    // ==================== 登录逻辑 ====================

    private void doLogin() {
        if (isLoggingIn) return;

        String host, user, pass;
        if (isFnIdMode) {
            host = fnidHostEt.getText().toString().trim();
            user = fnidUserEt.getText().toString().trim();
            pass = fnidPassEt.getText().toString().trim();
        } else {
            host = httpHostEt.getText().toString().trim();
            user = httpUserEt.getText().toString().trim();
            pass = httpPassEt.getText().toString().trim();
        }

        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            AppToast.show(this, "所有字段都不能为空");
            return;
        }

        isLoggingIn = true;
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");

        if (isFnIdMode) {
            if (!FnIdLoginHelper.isFnId(host)) {
                AppToast.show(this, "FN ID 格式不正确（6-30 位字符，不含点号和斜杠）", true);
                resetLoginState();
                return;
            }
            if (!switching) saveCardInput();
            new FnIdLoginHelper(this, host, user, pass, new FnIdLoginHelper.FnIdCallback() {
                @Override
                public void onSuccess(String token, String domain) {
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            restoreLiveSession();
                            return;
                        }
                        onFnIdLoginSuccess(token, domain, user, pass);
                    });
                }
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        if (isFinishing()) {
                            restoreLiveSession();
                            return;
                        }
                        AppToast.show(MainActivity.this, "FN ID 登录失败: " + message, true);
                        resetLoginState();
                    });
                }
            }).start();
        } else {
            btnLogin.post(() -> doLoginHttp(host, user, pass));
        }
    }

    private void onFnIdLoginSuccess(String token, String domain, String user, String pass) {
        sessionReplaced = true;
        FnApiManager.getInstance().setToken(token);
        SessionGuard.onLogin();
        // 保存 FN ID（用户输入的原值）到 fnid_host，domain 真实地址只存 host
        String rawFnId = fnidHostEt.getText().toString().trim();
        SharedPreferences.Editor e = prefs.edit();
        e.putString("fnid_host", rawFnId);
        e.putString("fnid_user", user);
        e.putString("user", user);
        boolean rem = fnidRememberCb.isChecked();
        e.putBoolean("fnid_remember", rem);
        if (rem) e.putString("fnid_pass", pass);
        else e.remove("fnid_pass");
        e.putString("host", domain);
        e.putString("login_mode", "fnid");
        e.putBoolean("_was_fnid", true);
        e.putString("auth_token", token);
        e.commit();
        AccountStore.get(prefs).remember("fnid", rawFnId, domain, user, pass, rem);
        Log.i(TAG, "FN ID 登录成功！fnId=" + rawFnId + " domain=" + domain);
        AppToast.show(this, "FN ID 登录成功！");
        openHome();
    }

    private void doLoginHttp(String host, String user, String pass) {
        loginStartTime = System.currentTimeMillis();
        final String server = AccountStore.normalizeHttp(host);
        if (!switching) persistHttp(server, user, pass, null, false);

        FnApiManager.getInstance().updateBaseUrl(server);
        Log.d(TAG, "尝试登录到: " + server + ", 用户: " + user);

        FnApiManager.getInstance().getApi().login(new LoginRequest(user, pass)).enqueue(new Callback<ApiResponse<LoginResponseData>>() {
            @Override
            public void onResponse(Call<ApiResponse<LoginResponseData>> call, Response<ApiResponse<LoginResponseData>> response) {
                if (isFinishing()) {
                    restoreLiveSession();
                    return;
                }
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().code == 0 && response.body().data != null) {
                        String token = response.body().data.token;
                        sessionReplaced = true;
                        FnApiManager.getInstance().setToken(token);
                        SessionGuard.onLogin();
                        persistHttp(server, user, pass, token, true);
                        boolean keep = httpRememberCb != null && httpRememberCb.isChecked();
                        AccountStore.get(prefs).remember("http", server, server, user, pass, keep);
                        Log.i(TAG, "HTTP 登录成功！耗时 " + (System.currentTimeMillis() - loginStartTime) + "ms");
                        AppToast.show(MainActivity.this, "登录成功！");
                        openHome();
                        return;
                    } else {
                        AppToast.show(MainActivity.this, "登录失败: " + response.body().msg, true);
                    }
                } else {
                    AppToast.show(MainActivity.this, "服务器响应异常，请重试", true);
                }
                resetLoginState();
            }
            @Override
            public void onFailure(Call<ApiResponse<LoginResponseData>> call, Throwable t) {
                Log.e(TAG, "网络请求失败: " + t.getMessage(), t);
                if (isFinishing()) {
                    restoreLiveSession();
                    return;
                }
                AppToast.show(MainActivity.this, "网络连接失败: " + t.getMessage(), true);
                resetLoginState();
            }
        });
    }

    private void persistHttp(String host, String user, String pass, String token, boolean sync) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("host", host);
        e.putString("user", user);
        boolean rem = httpRememberCb != null && httpRememberCb.isChecked();
        e.putBoolean("remember", rem);
        if (rem) e.putString("pass", pass);
        else e.remove("pass");
        e.putString("login_mode", "http");
        e.putBoolean("_was_fnid", false);
        if (token != null) e.putString("auth_token", token);
        if (sync) e.commit();
        else e.apply();
    }

    /** 进程被系统回收后，用上次的登录态直接进主页，不再停在登录页。 */
    private boolean restoreSession() {
        String token = prefs.getString("auth_token", "");
        String host = prefs.getString("host", "");
        if (token.isEmpty() || host.isEmpty()) return false;
        FnApiManager.getInstance().updateBaseUrl(host);
        FnApiManager.getInstance().setToken(token);
        SessionGuard.onLogin();
        return true;
    }

    private void resetLoginState() {
        isLoggingIn = false;
        btnLogin.setEnabled(true);
        btnLogin.setText("登 录");
        restoreSessionSnapshot();
    }

    @Override
    protected void onDestroy() {
        if (switching && !sessionReplaced) restoreSessionSnapshot();
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
