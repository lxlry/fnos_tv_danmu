package com.fntv.app;

import android.app.Activity;

import com.fntv.app.api.AccessCodes;

/**
 * 用户名密码提交后探测访问码。未开启就直接登录；开启了再弹窗。
 * 记住过的码会先试一次，不对再弹窗。
 */
final class AccessGate {
    interface Callback {
        void onReady(String code);
        void onCancel();
    }

    private AccessGate() {}

    static void prepare(Activity activity, String baseUrl, String typed, Callback callback) {
        if (activity == null || activity.isFinishing()) {
            if (callback != null) callback.onCancel();
            return;
        }
        final String existing = typed == null ? "" : typed.trim();
        new Thread(() -> {
            int probe = AccessCodes.probe(baseUrl);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    if (callback != null) callback.onCancel();
                    return;
                }
                if (probe == AccessCodes.OPEN) {
                    if (callback != null) callback.onReady("");
                    return;
                }
                if (probe == AccessCodes.LIMITED) {
                    AppToast.show(activity, "访问码尝试过多，请稍后再试", true);
                    if (callback != null) callback.onCancel();
                    return;
                }
                if (probe == AccessCodes.REQUIRED) {
                    if (existing.isEmpty()) {
                        AccessCodePrompt.show(activity, baseUrl, new AccessCodePrompt.Listener() {
                            @Override public void onCode(String code) {
                                if (callback != null) callback.onReady(code);
                            }
                            @Override public void onCancel() {
                                if (callback != null) callback.onCancel();
                            }
                        });
                        return;
                    }
                    verifyTyped(activity, baseUrl, existing, callback);
                    return;
                }
                if (callback != null) callback.onReady(existing);
            });
        }, "access-code").start();
    }

    private static void verifyTyped(Activity activity, String baseUrl, String code, Callback callback) {
        new Thread(() -> {
            int result = AccessCodes.verify(baseUrl, code);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    if (callback != null) callback.onCancel();
                    return;
                }
                if (result == AccessCodes.OPEN) {
                    if (callback != null) callback.onReady(code);
                    return;
                }
                if (result == AccessCodes.LIMITED) {
                    AppToast.show(activity, "访问码尝试过多，请稍后再试", true);
                    if (callback != null) callback.onCancel();
                    return;
                }
                if (result == AccessCodes.OFFLINE) {
                    AppToast.show(activity, "网络连接失败", true);
                    if (callback != null) callback.onCancel();
                    return;
                }
                if (result == AccessCodes.UNKNOWN) {
                    if (callback != null) callback.onReady(code);
                    return;
                }
                AccessCodePrompt.show(activity, baseUrl, new AccessCodePrompt.Listener() {
                    @Override public void onCode(String entered) {
                        if (callback != null) callback.onReady(entered);
                    }
                    @Override public void onCancel() {
                        if (callback != null) callback.onCancel();
                    }
                });
            });
        }, "access-code").start();
    }
}
