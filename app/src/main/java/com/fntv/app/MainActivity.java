package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    // HTTP 登录卡片
    private LinearLayout httpCard;
    private EditText httpHostEt, httpUserEt, httpPassEt;
    private CheckBox httpRememberCb;

    // FN ID 登录卡片
    private LinearLayout fnidCard;
    private EditText fnidHostEt, fnidUserEt, fnidPassEt;
    private CheckBox fnidRememberCb;

    private FrameLayout cardContainer;
    private Button btnLogin;
    private Button btnHttpMode, btnFnIdMode;
    private SharedPreferences prefs;
    private boolean isLoggingIn = false;
    private boolean isFnIdMode = false;
    private long loginStartTime;
    private float density;
    private LinearLayout accountSection;
    private LinearLayout accountRows;
    private Button btnClearAccounts;
    private final List<View> accountBodies = new ArrayList<>();
    private final List<View> accountDeletes = new ArrayList<>();
    private View httpRememberWrap;
    private View fnidRememberWrap;
    /** 从首页过来切换账户时，旧页面还在。登录失败要把接口地址和 token 还原。 */
    private boolean switching;
    private boolean sessionReplaced;
    private boolean initialFocusPlaced;
    private String sessionHost = "";
    private String sessionToken = "";
    private final Bundle sessionSnapshot = new Bundle();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionGuard.attach(this);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(color(R.color.bg_dark));
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        boolean skipAuto = getIntent().getBooleanExtra("skip_auto_login", false);
        switching = getIntent().getBooleanExtra("switch_account", false);
        if (switching) captureSession();
        if (!skipAuto && restoreSession()) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
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

        buildAccountSection();
        int modeIdxForAccounts = loginCard.indexOfChild(modeRow);
        if (modeIdxForAccounts >= 0) loginCard.addView(accountSection, modeIdxForAccounts);

        // ====== 恢复数据 ======
        isFnIdMode = "fnid".equals(prefs.getString("login_mode", ""));
        loadCardInput();
        updateModeColor();
        if (isFnIdMode) {
            httpCard.setVisibility(View.GONE);
            fnidCard.setVisibility(View.VISIBLE);
        }
        renderAccounts();

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

        int nextId = View.generateViewId();

        // FN ID / 服务器地址
        card.addView(makeLabel(isFnId ? "FN ID" : "服务器地址"));
        EditText etHost = new EditText(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density));
        hostLp.bottomMargin = 16;
        etHost.setLayoutParams(hostLp);
        etHost.setBackgroundResource(R.drawable.bg_input);
        etHost.setHint(hostHint);
        etHost.setInputType(hostInputType);
        etHost.setPadding((int)(12 * density), 0, (int)(12 * density), 0);
        etHost.setTextColor(color(R.color.text_primary));
        etHost.setHintTextColor(color(R.color.text_hint));
        etHost.setTextSize(15);
        int hostId = View.generateViewId();
        etHost.setId(hostId);
        card.addView(etHost);

        // 用户名
        card.addView(makeLabel("用户名"));
        EditText etUser = new EditText(this);
        LinearLayout.LayoutParams userLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density));
        userLp.bottomMargin = (int)(16 * density);
        etUser.setLayoutParams(userLp);
        etUser.setBackgroundResource(R.drawable.bg_input);
        etUser.setHint("输入用户名");
        etUser.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etUser.setPadding((int)(12 * density), 0, (int)(12 * density), 0);
        etUser.setTextColor(color(R.color.text_primary));
        etUser.setHintTextColor(color(R.color.text_hint));
        etUser.setTextSize(15);
        int userId = View.generateViewId();
        etUser.setId(userId);
        etUser.setNextFocusUpId(hostId);
        card.addView(etUser);

        // 密码
        card.addView(makeLabel("密码"));
        EditText etPass = new EditText(this);
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density));
        passLp.bottomMargin = (int)(16 * density);
        etPass.setLayoutParams(passLp);
        etPass.setBackgroundResource(R.drawable.bg_input);
        etPass.setHint("输入密码");
        etPass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPass.setPadding((int)(12 * density), 0, (int)(12 * density), 0);
        etPass.setTextColor(color(R.color.text_primary));
        etPass.setHintTextColor(color(R.color.text_hint));
        etPass.setTextSize(15);
        int passId = View.generateViewId();
        etPass.setId(passId);
        etPass.setNextFocusUpId(userId);
        card.addView(etPass);

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
            fnidRememberCb = cb;
            fnidRememberWrap = cbWrap;
        } else {
            httpCard = card;
            httpHostEt = etHost;
            httpUserEt = etUser;
            httpPassEt = etPass;
            httpRememberCb = cb;
            httpRememberWrap = cbWrap;
        }

        return card;
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

    // ==================== 已保存账户 ====================

    private int dp(int value) {
        return Math.round(value * density);
    }

    private void buildAccountSection() {
        accountSection = new LinearLayout(this);
        accountSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = dp(12);
        accountSection.setLayoutParams(sectionLp);
        accountSection.setVisibility(View.GONE);

        TextView title = makeLabel("已保存账户");
        accountSection.addView(title);

        TextView hint = new TextView(this);
        hint.setText("勾选记住密码后可在这里切换，最多保留 " + AccountStore.LIMIT + " 个");
        hint.setTextColor(color(R.color.text_hint));
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.bottomMargin = dp(8);
        hint.setLayoutParams(hintLp);
        accountSection.addView(hint);

        accountRows = new LinearLayout(this);
        accountRows.setOrientation(LinearLayout.VERTICAL);
        accountRows.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        accountSection.addView(accountRows);

        btnClearAccounts = new Button(this);
        btnClearAccounts.setText("清除全部记录");
        btnClearAccounts.setAllCaps(false);
        btnClearAccounts.setTextSize(13);
        btnClearAccounts.setTextColor(color(R.color.text_secondary));
        btnClearAccounts.setBackgroundResource(R.drawable.bg_input);
        btnClearAccounts.setFocusable(true);
        btnClearAccounts.setMinHeight(0);
        btnClearAccounts.setMinimumHeight(0);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        clearLp.topMargin = dp(4);
        btnClearAccounts.setLayoutParams(clearLp);
        btnClearAccounts.setOnClickListener(v -> confirm(
                "确定清除全部已保存账户？清除后需要重新输入密码登录。",
                "清除",
                () -> {
                    AccountStore.get(prefs).clear();
                    renderAccounts();
                }));
        accountSection.addView(btnClearAccounts);
    }

    private void renderAccounts() {
        accountRows.removeAllViews();
        accountBodies.clear();
        accountDeletes.clear();
        List<AccountStore.SavedAccount> accounts = AccountStore.get(prefs).list();
        String activeId = AccountStore.get(prefs).activeId();
        accountSection.setVisibility(accounts.isEmpty() ? View.GONE : View.VISIBLE);
        for (AccountStore.SavedAccount account : accounts) {
            addAccountRow(account, activeId != null && activeId.equals(account.id));
        }
        wireLoginFocus();
        if (initialFocusPlaced) return;
        initialFocusPlaced = true;
        if (!accounts.isEmpty()) {
            View target = accountBodies.get(0);
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).id != null && accounts.get(i).id.equals(activeId)) {
                    target = accountBodies.get(i);
                    break;
                }
            }
            final View focus = target;
            focus.post(() -> {
                if (!isFinishing()) focus.requestFocus();
            });
            return;
        }
        EditText host = isFnIdMode ? fnidHostEt : httpHostEt;
        if (host != null) host.post(() -> {
            if (!isFinishing()) host.requestFocus();
        });
    }

    private void addAccountRow(final AccountStore.SavedAccount account, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(0, dp(64), 1));
        body.setBackgroundResource(R.drawable.bg_input);
        body.setPadding(dp(12), dp(8), dp(12), dp(8));
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setFocusable(true);
        body.setClickable(true);
        body.setOnClickListener(v -> loginWithAccount(account));

        TextView name = new TextView(this);
        name.setText(account.user);
        name.setTextColor(color(R.color.text_primary));
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        body.addView(name);

        TextView detail = new TextView(this);
        String detailText = AccountStore.label(account);
        if (current) detailText = "当前  ·  " + detailText;
        detail.setText(detailText);
        detail.setTextColor(color(current ? R.color.colorAccent : R.color.text_hint));
        detail.setTextSize(12);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(detail);

        Button delete = new Button(this);
        delete.setText("删除");
        delete.setAllCaps(false);
        delete.setTextSize(13);
        delete.setTextColor(color(R.color.error));
        delete.setBackgroundResource(R.drawable.bg_input);
        delete.setFocusable(true);
        delete.setMinHeight(0);
        delete.setMinimumHeight(0);
        delete.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(dp(72), dp(64));
        deleteLp.leftMargin = dp(8);
        delete.setLayoutParams(deleteLp);
        delete.setOnClickListener(v -> confirm(
                "确定删除 " + account.user + " @ " + account.endpoint + " 的登录记录？",
                "删除",
                () -> {
                    AccountStore.get(prefs).delete(account.id);
                    renderAccounts();
                    if (!accountBodies.isEmpty()) accountBodies.get(0).requestFocus();
                    else if (isFnIdMode) btnFnIdMode.requestFocus();
                    else btnHttpMode.requestFocus();
                }));

        row.addView(body);
        row.addView(delete);
        accountRows.addView(row);
        accountBodies.add(body);
        accountDeletes.add(delete);
    }

    private void loginWithAccount(AccountStore.SavedAccount account) {
        if (isLoggingIn || account == null) return;
        boolean fnid = "fnid".equals(account.mode);
        if (fnid != isFnIdMode) {
            isFnIdMode = fnid;
            httpCard.setVisibility(fnid ? View.GONE : View.VISIBLE);
            fnidCard.setVisibility(fnid ? View.VISIBLE : View.GONE);
            updateModeColor();
        }
        if (fnid) {
            fnidHostEt.setText(account.endpoint);
            fnidUserEt.setText(account.user);
            fnidPassEt.setText(account.password == null ? "" : account.password);
            fnidRememberCb.setChecked(true);
        } else {
            httpHostEt.setText(account.endpoint);
            httpUserEt.setText(account.user);
            httpPassEt.setText(account.password == null ? "" : account.password);
            httpRememberCb.setChecked(true);
        }
        wireLoginFocus();
        if (account.password == null || account.password.isEmpty()) {
            AppToast.show(this, "该账户没有保存密码，请输入后再登录");
            EditText pass = fnid ? fnidPassEt : httpPassEt;
            pass.requestFocus();
            return;
        }
        doLogin();
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

        View aboveMode = accountSection.getVisibility() == View.VISIBLE ? btnClearAccounts : btnLogin;
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
        TvFocus.point(remember, View.FOCUS_UP, pass);
        TvFocus.point(remember, View.FOCUS_DOWN, btnLogin);
        TvFocus.point(remember, View.FOCUS_LEFT, remember);
        TvFocus.point(remember, View.FOCUS_RIGHT, remember);

        View top = accountSection.getVisibility() == View.VISIBLE && !accountBodies.isEmpty()
                ? accountBodies.get(0) : mode;
        TvFocus.point(btnLogin, View.FOCUS_UP, remember);
        TvFocus.point(btnLogin, View.FOCUS_DOWN, top);
        TvFocus.point(btnLogin, View.FOCUS_LEFT, btnLogin);
        TvFocus.point(btnLogin, View.FOCUS_RIGHT, btnLogin);

        for (int i = 0; i < accountBodies.size(); i++) {
            View body = accountBodies.get(i);
            View delete = accountDeletes.get(i);
            View bodyUp = i == 0 ? btnLogin : accountBodies.get(i - 1);
            View bodyDown = i == accountBodies.size() - 1 ? btnClearAccounts : accountBodies.get(i + 1);
            View deleteUp = i == 0 ? btnLogin : accountDeletes.get(i - 1);
            View deleteDown = i == accountDeletes.size() - 1 ? btnClearAccounts : accountDeletes.get(i + 1);
            TvFocus.point(body, View.FOCUS_LEFT, body);
            TvFocus.point(body, View.FOCUS_RIGHT, delete);
            TvFocus.point(body, View.FOCUS_UP, bodyUp);
            TvFocus.point(body, View.FOCUS_DOWN, bodyDown);
            TvFocus.point(delete, View.FOCUS_LEFT, body);
            TvFocus.point(delete, View.FOCUS_RIGHT, delete);
            TvFocus.point(delete, View.FOCUS_UP, deleteUp);
            TvFocus.point(delete, View.FOCUS_DOWN, deleteDown);
        }
        if (accountSection.getVisibility() == View.VISIBLE) {
            View lastBody = accountBodies.isEmpty() ? btnLogin : accountBodies.get(accountBodies.size() - 1);
            TvFocus.point(btnClearAccounts, View.FOCUS_UP, lastBody);
            TvFocus.point(btnClearAccounts, View.FOCUS_DOWN, mode);
            TvFocus.point(btnClearAccounts, View.FOCUS_LEFT, btnClearAccounts);
            TvFocus.point(btnClearAccounts, View.FOCUS_RIGHT, btnClearAccounts);
        }
    }

    private void confirm(String message, String yesText, final Runnable onYes) {
        final android.app.Dialog dialog = new android.app.Dialog(
                this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_card);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));

        TextView msg = new TextView(this);
        msg.setText(message);
        msg.setTextColor(color(R.color.text_primary));
        msg.setTextSize(15);
        root.addView(msg);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(16);
        actions.setLayoutParams(actionsLp);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextSize(15);
        cancel.setTextColor(color(R.color.text_secondary));
        cancel.setBackgroundResource(R.drawable.bg_input);
        cancel.setFocusable(true);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        cancelLp.rightMargin = dp(8);
        cancel.setLayoutParams(cancelLp);
        cancel.setOnClickListener(v -> dialog.dismiss());

        Button yes = new Button(this);
        yes.setText(yesText);
        yes.setAllCaps(false);
        yes.setTextSize(15);
        yes.setTextColor(color(R.color.text_white));
        yes.setBackgroundResource(R.drawable.bg_btn_danger);
        yes.setFocusable(true);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        yesLp.leftMargin = dp(8);
        yes.setLayoutParams(yesLp);
        yes.setOnClickListener(v -> {
            dialog.dismiss();
            onYes.run();
        });

        TvFocus.point(cancel, View.FOCUS_RIGHT, yes);
        TvFocus.point(yes, View.FOCUS_LEFT, cancel);
        TvFocus.point(cancel, View.FOCUS_LEFT, cancel);
        TvFocus.point(yes, View.FOCUS_RIGHT, yes);
        actions.addView(cancel);
        actions.addView(yes);
        root.addView(actions);
        dialog.setContentView(root);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            int max = dp(440);
            if (width > max) width = max;
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        cancel.requestFocus();
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
        e.apply();
        AccountStore.get(prefs).remember("fnid", rawFnId, domain, user, pass, rem);
        Log.i(TAG, "FN ID 登录成功！fnId=" + rawFnId + " domain=" + domain);
        AppToast.show(this, "FN ID 登录成功！");
        openHome();
    }

    private void doLoginHttp(String host, String user, String pass) {
        loginStartTime = System.currentTimeMillis();
        final String server = AccountStore.normalizeHttp(host);
        if (!switching) persistHttp(server, user, pass);

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
                        persistHttp(server, user, pass);
                        prefs.edit().putString("auth_token", token).apply();
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

    private void persistHttp(String host, String user, String pass) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("host", host);
        e.putString("user", user);
        boolean rem = httpRememberCb != null && httpRememberCb.isChecked();
        e.putBoolean("remember", rem);
        if (rem) e.putString("pass", pass);
        else e.remove("pass");
        e.putString("login_mode", "http");
        e.apply();
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
