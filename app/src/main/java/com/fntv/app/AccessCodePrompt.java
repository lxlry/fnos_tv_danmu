package com.fntv.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fntv.app.api.AccessCodes;

/** 服务器要求访问码时弹出。校验通过才把码交回去。 */
final class AccessCodePrompt {
    interface Listener {
        void onCode(String code);
        void onCancel();
    }

    private AccessCodePrompt() {}

    static void show(Activity activity, String baseUrl, Listener listener) {
        if (activity == null || activity.isFinishing()) {
            if (listener != null) listener.onCancel();
            return;
        }
        final int[] generation = {0};
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_card);
        int pad = Math.round(20 * density);
        root.setPadding(pad, Math.round(18 * density), pad, Math.round(16 * density));

        TextView title = new TextView(activity);
        title.setText("请输入访问码");
        title.setTextColor(color(activity, R.color.text_primary));
        title.setTextSize(18);
        root.addView(title);

        TextView message = new TextView(activity);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageLp.topMargin = Math.round(8 * density);
        message.setLayoutParams(messageLp);
        message.setText("这台飞牛开启了访问码。通过后才能登录。");
        message.setTextColor(color(activity, R.color.text_secondary));
        message.setTextSize(14);
        root.addView(message);

        EditText input = new EditText(activity);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(48 * density));
        inputLp.topMargin = Math.round(14 * density);
        input.setLayoutParams(inputLp);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setHint("访问码");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setPadding(Math.round(12 * density), 0, Math.round(12 * density), 0);
        input.setTextColor(color(activity, R.color.text_primary));
        input.setHintTextColor(color(activity, R.color.text_hint));
        input.setTextSize(15);
        input.setId(View.generateViewId());
        root.addView(input);

        TextView error = new TextView(activity);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorLp.topMargin = Math.round(8 * density);
        error.setLayoutParams(errorLp);
        error.setTextColor(color(activity, R.color.error));
        error.setTextSize(13);
        error.setVisibility(View.GONE);
        root.addView(error);

        Button scan = null;
        if (QrInput.isTelevision(activity)) {
            scan = QrInput.scanButton(activity, input, "访问码");
            LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(44 * density));
            scanLp.topMargin = Math.round(10 * density);
            scan.setLayoutParams(scanLp);
            scan.setText("扫码输入");
            scan.setTextSize(15);
            root.addView(scan);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Math.round(16 * density);
        actions.setLayoutParams(actionsLp);

        Button cancel = button(activity, "取消", false);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, Math.round(44 * density), 1);
        cancelLp.rightMargin = Math.round(8 * density);
        cancel.setLayoutParams(cancelLp);
        Button confirm = button(activity, "确定", true);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, Math.round(44 * density), 1);
        confirmLp.leftMargin = Math.round(8 * density);
        confirm.setLayoutParams(confirmLp);
        actions.addView(cancel);
        actions.addView(confirm);
        root.addView(actions);

        final Button scanButton = scan;
        TvFocus.point(input, View.FOCUS_DOWN, scanButton != null ? scanButton : confirm);
        TvFocus.point(input, View.FOCUS_UP, input);
        TvFocus.point(input, View.FOCUS_LEFT, input);
        TvFocus.point(input, View.FOCUS_RIGHT, input);
        if (scanButton != null) {
            TvFocus.point(scanButton, View.FOCUS_UP, input);
            TvFocus.point(scanButton, View.FOCUS_DOWN, confirm);
            TvFocus.point(scanButton, View.FOCUS_LEFT, scanButton);
            TvFocus.point(scanButton, View.FOCUS_RIGHT, scanButton);
        }
        TvFocus.point(cancel, View.FOCUS_UP, scanButton != null ? scanButton : input);
        TvFocus.point(cancel, View.FOCUS_RIGHT, confirm);
        TvFocus.point(cancel, View.FOCUS_LEFT, cancel);
        TvFocus.point(confirm, View.FOCUS_UP, scanButton != null ? scanButton : input);
        TvFocus.point(confirm, View.FOCUS_LEFT, cancel);
        TvFocus.point(confirm, View.FOCUS_RIGHT, confirm);

        final boolean[] finished = {false};
        Runnable cancelRun = () -> {
            if (finished[0]) return;
            finished[0] = true;
            generation[0]++;
            dialog.dismiss();
            if (listener != null) listener.onCancel();
        };
        cancel.setOnClickListener(v -> cancelRun.run());
        dialog.setOnCancelListener(d -> cancelRun.run());
        confirm.setOnClickListener(v -> {
            String code = input.getText() == null ? "" : input.getText().toString().trim();
            if (code.isEmpty()) {
                error.setText("请输入访问码");
                error.setVisibility(View.VISIBLE);
                return;
            }
            int ticket = ++generation[0];
            confirm.setEnabled(false);
            confirm.setText("验证中...");
            error.setVisibility(View.GONE);
            new Thread(() -> {
                int result = AccessCodes.verify(baseUrl, code);
                activity.runOnUiThread(() -> {
                    if (finished[0] || ticket != generation[0] || activity.isFinishing()) return;
                    confirm.setEnabled(true);
                    confirm.setText("确定");
                    if (result == AccessCodes.OPEN || result == AccessCodes.UNKNOWN) {
                        finished[0] = true;
                        generation[0]++;
                        dialog.dismiss();
                        if (listener != null) listener.onCode(code);
                        return;
                    }
                    if (result == AccessCodes.LIMITED) {
                        error.setText("尝试次数过多，请稍后再试");
                    } else if (result == AccessCodes.OFFLINE) {
                        error.setText("网络异常，请稍后重试");
                    } else {
                        error.setText("访问码错误");
                    }
                    error.setVisibility(View.VISIBLE);
                });
            }, "access-code").start();
        });

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.86f);
            int max = Math.round(420 * density);
            if (width > max) width = max;
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        input.requestFocus();
    }

    private static Button button(Activity activity, String text, boolean primary) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setFocusable(true);
        button.setTextColor(color(activity, primary ? R.color.text_white : R.color.text_secondary));
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_input);
        return button;
    }

    private static int color(Activity activity, int resId) {
        return ContextCompat.getColor(activity, resId);
    }
}
