package com.fntv.app;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** 播放器设置从右侧滑出，不再用居中弹窗。 */
final class SideSheet {

    private static Dialog open;
    private static final java.util.Map<Dialog, Runnable> dismissHooks = new java.util.WeakHashMap<>();

    private SideSheet() {}

    /** 播放器仍持有按键时，把方向键交给正在显示的侧边栏。 */
    static boolean onActivityKey(KeyEvent event) {
        return open != null && open.isShowing() && onKey(open, event, false);
    }

    static void place(Dialog dialog) {
        place(dialog, 320);
    }

    static void place(Dialog dialog, int widthDp) {
        placeSized(dialog, widthDp);
    }

    static void showList(Context context, String title, String[] items, DialogInterface.OnClickListener listener) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setCanceledOnTouchOutside(true);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121212);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setIncludeFontPadding(false);
        heading.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(heading);

        View line = new View(context);
        line.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        lineLp.bottomMargin = (int) (4 * density);
        line.setLayoutParams(lineLp);
        root.addView(line);

        ScrollView scroll = sheetScroll(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll);

        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                Button row = new Button(context);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * density)));
                row.setBackgroundResource(R.drawable.bg_player_action);
                row.setMinWidth(0);
                row.setMinHeight(0);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                row.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
                row.setAllCaps(false);
                row.setText(items[i]);
                row.setTextColor(Color.WHITE);
                row.setTextSize(15);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (listener != null) listener.onClick(dialog, index);
                });
                list.addView(row);
            }
        }

        dialog.setContentView(root);
        place(dialog);
        track(dialog);
        dialog.show();
        focusSheet(dialog, list);
    }

    /** 画质、音轨、字幕卡片。detail 和 badge 可空。 */
    static final class Choice {
        final String title;
        final String detail;
        final String badge;

        Choice(String title, String detail, String badge) {
            this.title = title == null ? "" : title;
            this.detail = detail;
            this.badge = badge;
        }
    }

    /** 画质格子：一行三个，选中项蓝框。 */
    static void showGrid(Context context, String title, List<Choice> choices, int selected,
                         DialogInterface.OnClickListener listener) {
        showPanel(context, title, 420, buildGrid(context, choices, selected, listener));
    }

    /** 音轨/字幕卡片。没有条目时显示 emptyText。 */
    static void showCards(Context context, String title, String section, List<Choice> choices, int selected,
                          String emptyText, DialogInterface.OnClickListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        if (section != null && !section.isEmpty()) {
            TextView sec = new TextView(context);
            sec.setText(section);
            sec.setTextColor(Color.WHITE);
            sec.setTextSize(16);
            sec.setPadding(0, (int) (8 * density), 0, (int) (12 * density));
            body.addView(sec);
        }
        if (choices == null || choices.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(emptyText == null ? "" : emptyText);
            empty.setTextColor(0xFFB0B0B0);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, (int) (48 * density), 0, 0);
            body.addView(empty);
        } else {
            for (int i = 0; i < choices.size(); i++) {
                body.addView(trackCard(context, choices.get(i), i == selected, i, listener));
            }
        }
        showPanel(context, title, 360, body);
    }

    private static void showPanel(Context context, String title, int widthDp, View body) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setCanceledOnTouchOutside(true);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121212);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setIncludeFontPadding(false);
        heading.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(heading);

        View line = new View(context);
        line.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        lineLp.bottomMargin = (int) (4 * density);
        line.setLayoutParams(lineLp);
        root.addView(line);

        ScrollView scroll = sheetScroll(context);
        scroll.addView(body);
        root.addView(scroll);
        dialog.setContentView(root);
        placeSized(dialog, widthDp);
        wireDismiss(body, dialog);
        track(dialog);
        dialog.show();
        focusSheet(dialog, body);
    }

    private static final class Click {
        final DialogInterface.OnClickListener listener;
        final int index;

        Click(DialogInterface.OnClickListener listener, int index) {
            this.listener = listener;
            this.index = index;
        }
    }

    private static void wireDismiss(View view, Dialog dialog) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) wireDismiss(group.getChildAt(i), dialog);
        }
        Object tag = view.getTag();
        if (tag instanceof Click) {
            Click click = (Click) tag;
            view.setOnClickListener(v -> {
                dialog.dismiss();
                if (click.listener != null) click.listener.onClick(dialog, click.index);
            });
        }
    }

    private static LinearLayout buildGrid(Context context, List<Choice> choices, int selected,
                                          DialogInterface.OnClickListener listener) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        if (choices == null) return grid;
        LinearLayout row = null;
        for (int i = 0; i < choices.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            row.addView(qualityChip(context, choices.get(i), i == selected, i, listener));
        }
        if (row != null) {
            int remain = choices.size() % 3;
            if (remain != 0) {
                for (int i = remain; i < 3; i++) {
                    View pad = new View(context);
                    row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1));
                }
            }
        }
        return grid;
    }

    private static View qualityChip(Context context, Choice choice, boolean selected, int index,
                                    DialogInterface.OnClickListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        FrameLayout chip = new FrameLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (78 * density), 1);
        int gap = (int) (6 * density);
        lp.setMargins(gap, gap, gap, gap);
        chip.setLayoutParams(lp);
        chip.setBackground(stroke(density, selected));
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setClickable(true);
        chip.setTag(new Click(listener, index));
        chip.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackground(stroke(density, selected, hasFocus)));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        text.setLayoutParams(textLp);
        TextView title = new TextView(context);
        title.setText(choice.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        text.addView(title);
        if (choice.detail != null && !choice.detail.isEmpty()) {
            TextView detail = new TextView(context);
            detail.setText(choice.detail);
            detail.setTextColor(0xFFB0B0B0);
            detail.setTextSize(12);
            detail.setGravity(Gravity.CENTER);
            detail.setPadding(0, (int) (2 * density), 0, 0);
            text.addView(detail);
        }
        chip.addView(text);
        if (choice.badge != null && !choice.badge.isEmpty()) {
            TextView badge = new TextView(context);
            badge.setText(choice.badge);
            badge.setTextColor(0xFFB0B0B0);
            badge.setTextSize(10);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.gravity = Gravity.TOP | Gravity.RIGHT;
            badgeLp.topMargin = (int) (6 * density);
            badgeLp.rightMargin = (int) (8 * density);
            badge.setLayoutParams(badgeLp);
            chip.addView(badge);
        }
        return chip;
    }

    private static View trackCard(Context context, Choice choice, boolean selected, int index,
                                  DialogInterface.OnClickListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * density);
        card.setLayoutParams(lp);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight((int) (64 * density));
        card.setPadding((int) (14 * density), (int) (10 * density), (int) (14 * density), (int) (10 * density));
        card.setBackground(stroke(density, selected));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setTag(new Click(listener, index));
        card.setOnFocusChangeListener((v, hasFocus) ->
                v.setBackground(stroke(density, selected, hasFocus)));
        if (selected) {
            TextView mark = new TextView(context);
            mark.setText("✓");
            mark.setTextColor(0xFF4C8DFF);
            mark.setTextSize(18);
            mark.setPadding(0, 0, (int) (10 * density), 0);
            card.addView(mark);
        }
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(choice.title);
        title.setTextColor(selected ? 0xFF4C8DFF : Color.WHITE);
        title.setTextSize(15);
        text.addView(title);
        if (choice.detail != null && !choice.detail.isEmpty()) {
            TextView detail = new TextView(context);
            detail.setText(choice.detail);
            detail.setTextColor(0xFFB0B0B0);
            detail.setTextSize(12);
            detail.setPadding(0, (int) (2 * density), 0, 0);
            text.addView(detail);
        }
        card.addView(text);
        return card;
    }

    private static GradientDrawable stroke(float density, boolean selected) {
        return stroke(density, selected, false);
    }

    private static GradientDrawable stroke(float density, boolean selected, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0x33FFFFFF : 0x00000000);
        bg.setCornerRadius(10 * density);
        int color = focused ? 0xFF81C784 : (selected ? 0xFF4C8DFF : 0x66FFFFFF);
        bg.setStroke((int) ((focused || selected ? 2 : 1) * density), color);
        return bg;
    }

    private static void placeSized(Dialog dialog, int widthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        window.getDecorView().setPadding(0, 0, 0, 0);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.gravity = Gravity.END | Gravity.TOP;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.x = 0;
        lp.y = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(lp);
        dialog.setOnShowListener(d -> ensurePinned(dialog, widthDp));
    }

    /** 换页后重新把面板钉回右侧，避免内容再次铺满窗口。 */
    static void ensurePinned(Dialog dialog, int widthDp) {
        pinPanel(dialog, widthDp);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.getDecorView().post(() -> pinPanel(dialog, widthDp));
    }

    /** 浮窗在电视上只会包住内容。把面板拉到屏幕右侧，从顶到底铺满。 */
    private static void pinPanel(Dialog dialog, int widthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.getDecorView().setPadding(0, 0, 0, 0);
        View content = window.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) return;
        ViewGroup host = (ViewGroup) content;
        View panel = host.getChildAt(host.getChildCount() - 1);
        boolean first = !"side_panel".equals(panel.getTag());
        int width = (int) (widthDp * dialog.getContext().getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        panel.setLayoutParams(panelLp);
        panel.setClickable(true);
        panel.setMinimumHeight(dialog.getContext().getResources().getDisplayMetrics().heightPixels);
        if (first) {
            panel.setTag("side_panel");
            host.setClickable(true);
            host.setOnClickListener(v -> {
                if (dialog.isShowing()) dialog.dismiss();
            });
            int inset = statusBarInset(dialog.getContext());
            if (inset > 0) {
                panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop() + inset,
                        panel.getPaddingRight(), panel.getPaddingBottom());
            }
        }
    }

    private static ScrollView sheetScroll(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        return scroll;
    }

    /** 打开后把焦点放进第一条，并串起上下左右，避免停在滚动容器上。 */
    static void focus(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        track(dialog);
        View decor = dialog.getWindow().getDecorView();
        boolean tv = television(dialog.getContext());
        decor.setFocusable(tv);
        decor.setFocusableInTouchMode(tv);
        if (tv) decor.requestFocus();
        decor.post(() -> bindSheet(decor, true));
    }

    private static void track(Dialog dialog) {
        open = dialog;
        dialog.setOnKeyListener((d, keyCode, event) -> onKey(dialog, event, true));
        dialog.setOnDismissListener(d -> {
            if (open == dialog) open = null;
            Runnable extra = dismissHooks.remove(dialog);
            if (extra != null) extra.run();
        });
    }

    static void afterDismiss(Dialog dialog, Runnable action) {
        if (dialog == null || action == null) return;
        dismissHooks.put(dialog, action);
    }

    private static boolean onKey(Dialog dialog, KeyEvent event, boolean inDialogWindow) {
        if (dialog == null || !dialog.isShowing() || event == null) return false;
        int key = event.getKeyCode();
        boolean dpad = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE;
        if (!dpad && !ok && !back) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (back) {
            dialog.dismiss();
            return true;
        }
        View focus = dialog.getCurrentFocus();
        View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if ((focus == null || focus == decor) && decor != null) {
            if (!inDialogWindow) decor.requestFocus();
            bindSheet(decor, false);
            focus = dialog.getCurrentFocus();
        }
        if (focus == null || focus == decor) return true;
        if (focus instanceof android.widget.NumberPicker
                && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (inDialogWindow) return false;
            android.widget.NumberPicker picker = (android.widget.NumberPicker) focus;
            int delta = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            int next = picker.getValue() + delta;
            if (next > picker.getMaxValue()) next = picker.getMinValue();
            if (next < picker.getMinValue()) next = picker.getMaxValue();
            picker.setValue(next);
            return true;
        }
        if (focus instanceof android.widget.SeekBar
                && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (inDialogWindow) return false;
            android.widget.SeekBar bar = (android.widget.SeekBar) focus;
            int step = Math.max(1, bar.getKeyProgressIncrement());
            int delta = key == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step;
            bar.setProgress(Math.max(0, Math.min(bar.getMax(), bar.getProgress() + delta)));
            return true;
        }
        if (ok) {
            if (focus.isClickable()) focus.performClick();
            return true;
        }
        if (!TvFocus.move(focus, key)) {
            int dir = TvFocus.dirFromKey(key);
            View next = dir == 0 ? null : focus.focusSearch(dir);
            if (next != null && next != focus) next.requestFocus();
        }
        return true;
    }

    private static void focusSheet(Dialog dialog, View body) {
        if (dialog.getWindow() != null) {
            View decor = dialog.getWindow().getDecorView();
            boolean tv = television(dialog.getContext());
            decor.setFocusable(tv);
            decor.setFocusableInTouchMode(tv);
            if (tv) decor.requestFocus();
        }
        body.post(() -> bindSheet(body, true));
    }

    private static void bindSheet(View body, boolean retry) {
            java.util.ArrayList<View> items = new java.util.ArrayList<>();
            collectFocusables(body, items);
            if (items.isEmpty()) return;
            boolean tv = television(body.getContext());
            for (View item : items) {
                item.setFocusable(true);
                item.setFocusableInTouchMode(tv);
            }
            boolean sameTop = items.size() > 1;
            if (sameTop) {
                int top = items.get(0).getTop();
                for (View item : items) {
                    if (item.getTop() != top) {
                        sameTop = false;
                        break;
                    }
                }
            }
            if (sameTop && retry) {
                body.post(() -> bindSheet(body, false));
                return;
            }
            java.util.ArrayList<java.util.ArrayList<View>> rows = new java.util.ArrayList<>();
            if (sameTop) {
                for (View item : items) {
                    java.util.ArrayList<View> row = new java.util.ArrayList<>();
                    row.add(item);
                    rows.add(row);
                }
            } else {
                for (View item : items) {
                    java.util.ArrayList<View> row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                    if (row == null || Math.abs(item.getTop() - row.get(0).getTop()) > Math.max(1, item.getHeight() / 2)) {
                        row = new java.util.ArrayList<>();
                        rows.add(row);
                    }
                    row.add(item);
                }
            }
            for (int r = 0; r < rows.size(); r++) {
                java.util.ArrayList<View> row = rows.get(r);
                TvFocus.bindRow(row);
                if (r + 1 < rows.size()) TvFocus.bindVertical(row, rows.get(r + 1));
            }
            if (!rows.isEmpty()) {
                for (View v : rows.get(0)) TvFocus.point(v, View.FOCUS_UP, v);
                java.util.ArrayList<View> last = rows.get(rows.size() - 1);
                for (View v : last) TvFocus.point(v, View.FOCUS_DOWN, v);
            }
            for (java.util.ArrayList<View> row : rows) TvFocus.sealAll(row);
            if (!tv) return;
            View first = items.get(0);
            if (!first.requestFocus()) first.post(first::requestFocus);
    }

    private static boolean television(Context context) {
        if (context == null) return false;
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private static void collectFocusables(View view, java.util.List<View> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        boolean leaf = !(view instanceof ViewGroup);
        if (view.isFocusable() && (view.isClickable() || leaf)) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectFocusables(group.getChildAt(i), out);
        }
    }

    /** 菜单栏露出状态栏时，侧边栏背景仍铺满，文字下移避开状态栏。 */
    private static int statusBarInset(Context context) {
        if (!(context instanceof android.app.Activity)) return 0;
        android.app.Activity activity = (android.app.Activity) context;
        View decor = activity.getWindow().getDecorView();
        if ((decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
        }
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? context.getResources().getDimensionPixelSize(resId) : 0;
    }
}
