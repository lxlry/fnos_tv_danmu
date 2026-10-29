package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.fntv.app.api.FnApiManager;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** 登录态失效后清掉本地 token，并回到登录页。同一轮只跳一次。 */
final class SessionGuard {
    private static final AtomicBoolean redirecting = new AtomicBoolean(false);
    private static Context appContext;
    private static WeakReference<Activity> foreground = new WeakReference<>(null);

    private SessionGuard() {}

    static void attach(Activity activity) {
        if (activity == null) return;
        appContext = activity.getApplicationContext();
        foreground = new WeakReference<>(activity);
    }

    /** 重新登录成功后允许再次处理失效。 */
    static void onLogin() {
        redirecting.set(false);
    }

    static boolean isAuthFailure(int httpCode, Integer apiCode) {
        if (httpCode == 401) return true;
        return apiCode != null && apiCode == -2;
    }

    static void expire() {
        if (!redirecting.compareAndSet(false, true)) return;
        FnApiManager.getInstance().setToken(null);
        Context stored = appContext;
        Activity activity = foreground.get();
        if (stored == null && activity != null) stored = activity.getApplicationContext();
        if (stored != null) {
            stored.getSharedPreferences("fntv_prefs", Context.MODE_PRIVATE)
                    .edit().remove("auth_token").apply();
        }
        if (activity instanceof SwitchAccountActivity) {
            redirecting.set(false);
            if (!activity.isFinishing()) {
                ((SwitchAccountActivity) activity).onSessionExpired();
            }
            return;
        }
        if (activity instanceof MainActivity) {
            redirecting.set(false);
            if (!activity.isFinishing()) {
                AppToast.show(activity, "登录已失效，请重新登录", true);
            }
            return;
        }
        Context launch = activity != null ? activity : stored;
        if (launch == null) {
            redirecting.set(false);
            return;
        }
        Intent intent = new Intent(launch, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        intent.putExtra("auth_expired", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        launch.startActivity(intent);
    }
}
