package com.fntv.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.util.SimpleImageLoader;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/** 剧集列表管理 — 加载、切换，以及飞牛风格选集面板 */
public class EpisodeManager {

    public interface Callback {
        String getBaseUrl();
        String getParentGuid();
        String getItemGuid();
        int getEpisodeNumber();
        String getSeriesTitle();
        int getSeasonNumber();
        long getPlayPositionSec();
        long getDurationSec();
        FnApiManager getApiManager();
        Context getContext();
        void onSwitchEpisode(String guid, String title);
        void onPickerChanged(boolean open);
    }

    private static final int PAGE = 30;
    private static final int COLOR_WATCHED = 0xFF5B8CFF;
    private static final int COLOR_IDLE = 0xFF8E96A3;
    private static final int COLOR_PLAYING = 0xFF5B8CFF;

    private List<PlayListItem> episodeList;
    private int currentEpIndex = -1;
    private int pageIndex;
    private boolean loadingEpisodes = false;
    private boolean tvLayout;
    private View pickerRoot;
    private LinearLayout rangeBar;
    private LinearLayout listBox;
    private View listScroller;
    private final List<TextView> rangeChips = new ArrayList<>();
    private final Callback cb;
    private final Button btnEpisodeList;
    private final View btnNextEp;
    private String currentGuid;
    private String currentTitle;

    private static final String TAG = "Player";

    public EpisodeManager(Callback cb, Button btnEpisodeList, View btnNextEp) {
        this.cb = cb;
        this.btnEpisodeList = btnEpisodeList;
        this.btnNextEp = btnNextEp;
    }

    public boolean hasNext() {
        return episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1;
    }

    public boolean isLoading() { return loadingEpisodes; }

    public boolean isLoaded() { return episodeList != null && !episodeList.isEmpty(); }

    public boolean isPickerShowing() {
        return pickerRoot != null && pickerRoot.getVisibility() == View.VISIBLE;
    }

    public void loadList(String parentGuid) {
        if (parentGuid == null || parentGuid.isEmpty()) return;
        loadingEpisodes = true;
        Log.d(TAG, "getEpisodeList 请求: " + cb.getBaseUrl() + "/v/api/v1/episode/list/" + parentGuid);
        cb.getApiManager().getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                           Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        Log.d(TAG, "getEpisodeList 响应 code=" + resp.code()
                                + " body=" + (resp.body() != null ? "code=" + resp.body().code + " size="
                                + (resp.body().data != null ? resp.body().data.size() : "null") : "null"));
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            currentEpIndex = -1;
                            int epNum = cb.getEpisodeNumber();
                            String itemGuid = cb.getItemGuid();
                            for (int i = 0; i < episodeList.size(); i++) {
                                PlayListItem ep = episodeList.get(i);
                                if (ep.guid != null && ep.guid.equals(itemGuid)) {
                                    currentEpIndex = i;
                                    break;
                                }
                                if (currentEpIndex < 0 && epNum > 0 && ep.episodeNumber == epNum) {
                                    currentEpIndex = i;
                                }
                            }
                            Log.d(TAG, "getEpisodeList 成功: " + episodeList.size() + " 集, currentIdx=" + currentEpIndex
                                    + " epNum=" + epNum + " itemGuid=" + itemGuid);
                            btnEpisodeList.setVisibility(View.VISIBLE);
                            updateNextBtn();
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
                        Log.e(TAG, "getEpisodeList 失败: " + t.getMessage());
                    }
                });
    }

    public void playNext() {
        if (!hasNext()) return;
        PlayListItem next = episodeList.get(currentEpIndex + 1);
        currentEpIndex++;
        currentGuid = next.guid;
        currentTitle = next.title;
        updateNextBtn();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    public void showPicker() {
        if (episodeList == null || episodeList.isEmpty()) return;
        if (!(cb.getContext() instanceof Activity)) return;
        Activity act = (Activity) cb.getContext();
        FrameLayout host = act.findViewById(R.id.episodePanel);
        if (host == null) return;
        if (isPickerShowing()) {
            dismissPicker();
            return;
        }
        rememberCurrentProgress();
        tvLayout = isTelevision(act);
        int current = Math.max(0, currentEpIndex);
        pageIndex = pageOf(displayNumber(episodeList.get(current), current));

        pickerRoot = LayoutInflater.from(act).inflate(
                tvLayout ? R.layout.panel_episode_tv : R.layout.panel_episode_side, host, false);
        if (!tvLayout) fitSideWidth(act, pickerRoot.findViewById(R.id.epSheet));
        TextView title = pickerRoot.findViewById(R.id.tvEpPanelTitle);
        if (title != null) title.setText(seriesHeading());
        View scrim = pickerRoot.findViewById(R.id.epScrim);
        if (scrim != null) scrim.setOnClickListener(v -> dismissPicker());
        rangeBar = pickerRoot.findViewById(R.id.epRangeBar);
        listBox = pickerRoot.findViewById(R.id.epList);
        listScroller = pickerRoot.findViewById(R.id.epListScroll);
        buildRangeChips(act);
        fillPage(act);

        host.removeAllViews();
        host.addView(pickerRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setVisibility(View.VISIBLE);
        cb.onPickerChanged(true);
    }

    public void dismissPicker() {
        if (!isPickerShowing()) return;
        Activity act = cb.getContext() instanceof Activity ? (Activity) cb.getContext() : null;
        if (act != null) {
            FrameLayout host = act.findViewById(R.id.episodePanel);
            if (host != null) {
                host.setVisibility(View.GONE);
                host.removeAllViews();
            }
        }
        pickerRoot = null;
        rangeBar = null;
        listBox = null;
        listScroller = null;
        rangeChips.clear();
        cb.onPickerChanged(false);
    }

    public void reset() {
        dismissPicker();
        episodeList = null;
        currentEpIndex = -1;
        loadingEpisodes = false;
    }

    private void rememberCurrentProgress() {
        if (currentEpIndex < 0 || currentEpIndex >= episodeList.size()) return;
        PlayListItem cur = episodeList.get(currentEpIndex);
        long pos = cb.getPlayPositionSec();
        long dur = cb.getDurationSec();
        if (pos > 0) cur.ts = pos;
        if (dur > 0 && cur.duration <= 0) cur.duration = dur;
    }

    private void select(int index) {
        if (index < 0 || index >= episodeList.size()) return;
        PlayListItem s = episodeList.get(index);
        currentEpIndex = index;
        currentGuid = s.guid;
        currentTitle = s.title;
        updateNextBtn();
        dismissPicker();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    private void buildRangeChips(Activity act) {
        rangeChips.clear();
        int pages = pageCount();
        View scroll = pickerRoot.findViewById(R.id.epRangeScroll);
        if (pages <= 1) {
            if (scroll != null) scroll.setVisibility(View.GONE);
            return;
        }
        if (scroll != null) scroll.setVisibility(View.VISIBLE);
        rangeBar.removeAllViews();
        int maxNum = maxDisplayNumber();
        for (int p = 0; p < pages; p++) {
            int start = p * PAGE + 1;
            int end = Math.min((p + 1) * PAGE, maxNum);
            TextView chip = new TextView(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            chip.setLayoutParams(lp);
            chip.setMinWidth(dp(act, 72));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(act, 12), 0, dp(act, 12), 0);
            chip.setText(start + "-" + end);
            chip.setTextSize(13);
            chip.setFocusable(true);
            chip.setBackgroundResource(R.drawable.bg_episode_chip);
            final int page = p;
            chip.setOnClickListener(v -> switchRangePage(act, page, true));
            if (tvLayout) {
                chip.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) switchRangePage(act, page, false);
                });
            }
            rangeBar.addView(chip);
            rangeChips.add(chip);
        }
        styleRangeChips();
    }

    private void styleRangeChips() {
        for (int i = 0; i < rangeChips.size(); i++) {
            TextView chip = rangeChips.get(i);
            boolean on = i == pageIndex;
            chip.setSelected(on);
            chip.setTextColor(on ? 0xFFF4F6F8 : 0x99F4F6F8);
        }
    }

    private void switchRangePage(Activity act, int page, boolean takeFocus) {
        if (pageIndex == page) return;
        pageIndex = page;
        styleRangeChips();
        fillPage(act, takeFocus);
    }

    private void fillPage(Activity act) {
        fillPage(act, true);
    }

    private void fillPage(Activity act, boolean takeFocus) {
        listBox.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(act);
        int layout = tvLayout ? R.layout.item_episode_tv : R.layout.item_episode_side;
        List<View> rows = new ArrayList<>();
        View currentView = null;
        for (int i = 0; i < episodeList.size(); i++) {
            PlayListItem ep = episodeList.get(i);
            if (pageOf(displayNumber(ep, i)) != pageIndex) continue;
            View row = inflater.inflate(layout, listBox, false);
            bindRow(act, row, ep, i);
            listBox.addView(row);
            rows.add(row);
            if (i == currentEpIndex) currentView = row;
        }
        wirePickerFocus(rows);
        if (!takeFocus) return;
        View focus = currentView != null ? currentView : (rows.isEmpty() ? null : rows.get(0));
        if (focus != null) focusRow(focus);
    }

    private void focusRow(View target) {
        target.post(() -> target.post(() -> {
            if (pickerRoot == null) return;
            target.requestFocus();
            int pad = dp(target.getContext(), tvLayout ? 28 : 8);
            if (listScroller instanceof ScrollView) {
                ((ScrollView) listScroller).scrollTo(0, Math.max(0, target.getTop() - pad));
            } else if (listScroller instanceof HorizontalScrollView) {
                ((HorizontalScrollView) listScroller).scrollTo(Math.max(0, target.getLeft() - pad), 0);
            }
        }));
    }

    private void bindRow(Activity act, View row, PlayListItem ep, int index) {
        boolean current = index == currentEpIndex;
        int num = displayNumber(ep, index);
        TextView title = row.findViewById(R.id.epTitle);
        TextView duration = row.findViewById(R.id.epDuration);
        title.setText(episodeLabel(num, ep.title));
        String dur = tvLayout ? durationWords(episodeDuration(ep)) : clock(episodeDuration(ep));
        duration.setText(dur);
        duration.setVisibility(dur.isEmpty() ? View.GONE : View.VISIBLE);

        TextView status = row.findViewById(R.id.epStatus);
        if (status != null) {
            if (current) {
                status.setText("播放中");
                status.setTextColor(COLOR_PLAYING);
            } else {
                int pct = watchPercent(ep);
                if (pct < 0) {
                    status.setText("未观看");
                    status.setTextColor(COLOR_IDLE);
                } else {
                    status.setText("已观看" + pct + "%");
                    status.setTextColor(COLOR_WATCHED);
                }
            }
        }
        row.setSelected(current);
        row.setFocusable(true);
        row.setOnClickListener(v -> select(index));

        RoundedImageView thumb = row.findViewById(R.id.epThumb);
        if (thumb != null) {
            thumb.setCornerRadius(tvLayout ? 6 : 8);
            String url = ep.getPosterUrl(cb.getBaseUrl());
            SimpleImageLoader.load(url, thumb, cb.getApiManager().getClient());
        }
    }

    private void wirePickerFocus(List<View> rows) {
        TvFocus.bindRow(rows);
        if (!rangeChips.isEmpty()) {
            List<View> chips = new ArrayList<>(rangeChips);
            TvFocus.bindRow(chips);
            View down = null;
            for (View row : rows) {
                if (row.isSelected()) { down = row; break; }
            }
            if (down == null && !rows.isEmpty()) down = rows.get(0);
            View up = pageIndex >= 0 && pageIndex < rangeChips.size()
                    ? rangeChips.get(pageIndex) : rangeChips.get(0);
            if (down != null) {
                for (View chip : chips) TvFocus.point(chip, View.FOCUS_DOWN, down);
                for (View row : rows) TvFocus.point(row, View.FOCUS_UP, up);
            }
        }
        TvFocus.sealAll(rows);
        TvFocus.sealAll(new ArrayList<>(rangeChips));
    }

    private void fitSideWidth(Activity act, View sheet) {
        if (sheet == null) return;
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        boolean portrait = screenH > screenW;
        int width = portrait
                ? (int) (screenW * 0.92f)
                : Math.min((int) (screenW * 0.46f), dp(act, 440));
        ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.width = Math.max(width, dp(act, 260));
        sheet.setLayoutParams(lp);
    }

    private String seriesHeading() {
        String name = cb.getSeriesTitle();
        if (name == null || name.isEmpty()) name = "选集";
        int season = cb.getSeasonNumber();
        if (season > 0) return name + " · 第" + season + "季";
        return name;
    }

    private int displayNumber(PlayListItem ep, int index) {
        return ep.episodeNumber > 0 ? ep.episodeNumber : (index + 1);
    }

    private int pageOf(int number) {
        return Math.max(0, (number - 1) / PAGE);
    }

    private int maxDisplayNumber() {
        int max = episodeList.size();
        for (int i = 0; i < episodeList.size(); i++) {
            max = Math.max(max, displayNumber(episodeList.get(i), i));
        }
        return max;
    }

    private int pageCount() {
        return Math.max(1, (maxDisplayNumber() + PAGE - 1) / PAGE);
    }

    private long episodeDuration(PlayListItem ep) {
        if (ep.duration > 0) return ep.duration;
        if (ep.runtime > 0) return ep.runtime * 60L;
        return 0;
    }

    /** 未看返回 -1，否则返回 1–99。 */
    private int watchPercent(PlayListItem ep) {
        long dur = episodeDuration(ep);
        if (dur > 0 && ep.ts > 0) {
            int pct = (int) Math.round(ep.ts * 100f / dur);
            if (pct <= 0) return ep.watched == 1 ? 99 : -1;
            return Math.min(99, Math.max(1, pct));
        }
        if (ep.watched == 1) return 99;
        return -1;
    }

    private String clock(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private String durationWords(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return h + "小时" + m + "分钟" + s + "秒";
        if (m > 0) return m + "分钟" + s + "秒";
        return s + "秒";
    }

    private String episodeLabel(int num, String title) {
        String n = num < 100 ? String.format("%02d", num) : String.valueOf(num);
        if (title == null || title.isEmpty()) return n;
        return n + ". " + title;
    }

    private boolean isTelevision(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private int dp(Context context, int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private void updateNextBtn() {
        btnNextEp.setVisibility(hasNext() ? View.VISIBLE : View.GONE);
    }
}
