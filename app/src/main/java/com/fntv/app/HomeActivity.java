package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.fntv.app.util.SimpleImageLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class HomeActivity extends AppCompatActivity {

    private Button tabMovies, tabLibrary, tabSettings;
    private View panelMovies, panelLibrary, panelSettings;
    private LinearLayout moviesContainer, libraryContainer;
    private TextView tvMoviesLoading, tvLibraryLoading, tvLibraryEmpty;
    private EditText etSearch;
    private TextView tvLibraryPageTitle;
    private boolean isSearching = false;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue, tvDanmuUrl;
    private Button btnLogout, btnFeedback;
    private UpdateManager updateManager;
    private RelativeLayout rlDecoderSetting, rlDanmuSetting, rlSeekStep, rlBufferTime;
    private TextView tvSeekStepValue, tvBufferTimeValue;

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private boolean showingOverview = true;
    private boolean showingEpisodes = false;
    private boolean loadingPreviews = false;
    private boolean overviewLoading = false;
    private boolean continueLoading = false;
    private boolean continueRefreshPending = false;
    private int recordWaitTicks = 0;
    private boolean liveLoading = false;

    // 媒体库浏览排序状态
    private String currentBrowseGuid;
    private String currentBrowseTitle;
    private LinearLayout currentBrowseContainer;
    private TextView currentBrowseLoading;
    private int libSortColumnIndex = 0; // 0=添加日期, 1=发行日期
    private int libSortOrderIndex = 1;  // 0=升序, 1=降序

    private FnApiManager apiManager;
    private String baseUrl = "";
    private SharedPreferences prefs;
    private static final String PREF_DECODER = "decoder_mode";

    private long t0;
    private boolean overviewBuilt = false;
    private List<PlayListItem> cachedContinueWatching;
    private List<PlayListItem> cachedLivePreview;
    private int cachedLiveTotal;
    private long backPressedTime = 0;

    private int color(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // 横竖屏切换时保存的页面状态
    private String savedBrowseGuid, savedBrowseTitle;
    private List<PlayListItem> savedBrowseList;
    private PlayListItem savedDetailItem;
    private PlayInfoResponse savedDetailInfo;
    private boolean browseFromLibrary;
    private String savedLiveChannelTitle;
    private int[] savedMoviesPad;
    private View lastContentFocus;
    private boolean homeEntryFocused;
    private boolean initialFocusPlaced;
    private String lastFocusSectionTag;
    private int lastFocusIndexInSection;
    private Button detailPlayBtn;
    private TextView detailOverview;
    private ViewGroup detailChipRow;
    private ViewGroup detailEpisodeBox;

    @Override

    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (savedDetailItem != null) {
            buildDetailPage(savedDetailItem, savedDetailInfo);
        } else if (savedBrowseList != null) {
            if (browseFromLibrary) { renderBrowseGrid(savedBrowseList, savedBrowseTitle); } else { renderGridInContainer(savedBrowseList, savedBrowseTitle, moviesContainer); }
        } else if (currentTab == 1 && savedBrowseGuid != null) {
            browseItemsInContainer(savedBrowseGuid, savedBrowseTitle,
                    libraryContainer, tvLibraryLoading);
        } else if (currentTab == 0) {
            loadingPreviews = false;
            overviewBuilt = false;
            showOverview();
            loadAllPreviews();
            refreshContinueAfterPlayback();
            loadLiveChannels();
        }
    }

    @Override

    protected void onCreate(Bundle savedInstanceState) {
        t0 = System.currentTimeMillis();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(color(R.color.bg_dark));
        }

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        apiManager = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (apiManager.getApi() == null && !baseUrl.isEmpty()) {
            apiManager.updateBaseUrl(baseUrl);
        }
        // 切换服务器时清空观看记录
        String lastHost = prefs.getString("last_host", "");
        if (!lastHost.equals(baseUrl) && !lastHost.isEmpty()) {
            prefs.edit().remove("watch_history").putString("last_host", baseUrl).apply();
        } else if (lastHost.isEmpty() && !baseUrl.isEmpty()) {
            prefs.edit().putString("last_host", baseUrl).apply();
        }
        initViews();
        setupTabs();
        setupSettings();
        setupLogout();
        updateManager.setup();
        setupFeedback();
        setupSearch();
        setupTvFocus();

        switchTab(0);
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("正在加载媒体库...");
        loadOverview();
    }


    private void initViews() {
        tabMovies = findViewById(R.id.tabMovies);
        tabLibrary = findViewById(R.id.tabLibrary);
        tabSettings = findViewById(R.id.tabSettings);
        panelMovies = findViewById(R.id.panelMovies);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelSettings = findViewById(R.id.panelSettings);
        moviesContainer = findViewById(R.id.moviesGridContainer);
        savedMoviesPad = new int[] {
                moviesContainer.getPaddingLeft(),
                moviesContainer.getPaddingTop(),
                moviesContainer.getPaddingRight(),
                moviesContainer.getPaddingBottom()
        };
        libraryContainer = findViewById(R.id.libraryGridContainer);
        tvLibraryPageTitle = findViewById(R.id.tvLibraryPageTitle);
        tvMoviesLoading = findViewById(R.id.tvMoviesLoading);
        tvLibraryLoading = findViewById(R.id.tvLibraryLoading);
        tvLibraryEmpty = findViewById(R.id.tvLibraryEmpty);
        tvSettingUsername = findViewById(R.id.tvSettingUsername);
        tvSettingServer = findViewById(R.id.tvSettingServer);
        tvDecoderValue = findViewById(R.id.tvDecoderValue);
        btnLogout = findViewById(R.id.btnLogout);
        Button btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        updateManager = new UpdateManager(this, btnCheckUpdate, BuildConfig.VERSION_CODE);
        btnFeedback = findViewById(R.id.btnFeedback);
        rlDecoderSetting = findViewById(R.id.rlDecoderSetting);
        rlDanmuSetting = findViewById(R.id.rlDanmuSetting);
        rlSeekStep = findViewById(R.id.rlSeekStep);
        tvSeekStepValue = findViewById(R.id.tvSeekStepValue);
        rlBufferTime = findViewById(R.id.rlBufferTime);
        tvBufferTimeValue = findViewById(R.id.tvBufferTimeValue);
        tvDanmuUrl = findViewById(R.id.tvDanmuUrl);
        tvSettingServer.setText(prefs.getString("host", ""));

        Button btnHomeSearch = findViewById(R.id.btnHomeSearch);
        if (btnHomeSearch != null) {
            btnHomeSearch.setOnClickListener(v -> {
                switchTab(1);
                loadMediaLibraries();
                if (etSearch != null) etSearch.post(this::focusLibrarySearch);
            });
        }

        TextView tvVersion = findViewById(R.id.tvVersionName);
        try {
            tvVersion.setText("FN TV v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {}
    }


    // ======================== Tab ========================

    @Override

    protected void onResume() {
        super.onResume();
        if (savedDetailItem != null) {
            buildDetailPage(savedDetailItem, savedDetailInfo);
        } else if (currentTab == 0 && !mediaLibraries.isEmpty() && overviewBuilt && showingOverview) {
            loadingPreviews = false;
            if (findContinueWatchingBox() == null) {
                showOverview();
                loadAllPreviews();
                loadLiveChannels();
            }
            refreshContinueAfterPlayback();
        } else if (currentTab == 0 && !overviewBuilt && showingOverview && !overviewLoading) {
            loadOverview();
        }
    }


    private void setupTabs() {
        tabMovies.setOnClickListener(v -> {
            if (currentTab == 0 && !showingOverview) {
                restoreHomeOverview();
                return;
            }
            switchTab(0);
        });
        tabLibrary.setOnClickListener(v -> { switchTab(1); loadMediaLibraries(); });
        tabSettings.setOnClickListener(v -> switchTab(2));
    }


    private void switchTab(int index) {
        int prevTab = currentTab;
        currentTab = index;
        // 从媒体库切换到其他标签时清除搜索状态
        if (prevTab == 1 && isSearching) clearSearch();
        // 切换标签时清除保存的页面状态，防止横竖屏切回时错误恢复
        savedBrowseList = null; savedBrowseGuid = null;
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabMovies.setSelected(index == 0);
        tabLibrary.setSelected(index == 1);
        tabSettings.setSelected(index == 2);
        boolean backToHome = index == 0 && prevTab != 0 && !showingOverview;
        setDetailChrome(index == 0 && savedDetailItem != null && !showingOverview && !backToHome);
        if (index == 0) tabMovies.requestFocus();
        else if (index == 1) tabLibrary.requestFocus();
        else tabSettings.requestFocus();
        if (backToHome) restoreHomeOverview();
        refreshTabFocusTargets();
    }

    private void refreshTabFocusTargets() {
        if (currentTab == 2) {
            wireSettingsFocus();
        } else if (currentTab == 1) {
            if (savedBrowseGuid != null || isSearching) {
                wireBrowseGrid(libraryContainer);
            } else {
                wireLibraryList();
            }
        } else if (showingOverview) {
            wireOverviewFocus();
        } else if (savedDetailItem != null) {
            wireDetailNav();
        } else if (moviesContainer != null) {
            wireBrowseGrid(moviesContainer);
        }
    }

    /** 回到影视首页概览（保留已加载的媒体库，并恢复继续观看） */
    private void restoreHomeOverview() {
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        if (mediaLibraries.isEmpty()) {
            loadOverview();
            return;
        }
        loadingPreviews = false;
        showOverview();
        loadAllPreviews();
        refreshContinueAfterPlayback();
        loadLiveChannels();
    }


    // ==================== 影视概览 ====================


    private void loadOverview() {
        if (overviewLoading) return;
        if (apiManager.getApi() == null) return;
        overviewLoading = true;
        Log.d("Overview", "loadOverview start  t=" + (System.currentTimeMillis() - t0) + "ms");
        showingOverview = true;
        overviewBuilt = false;
        loadingPreviews = false;
        tvMoviesLoading.setVisibility(View.VISIBLE);
        // 不依赖媒体库名单，和名单并行请求，回来时能直接画上
        loadContinueWatching();
        loadLiveChannels();

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                String bodyStr = response.body() != null ? "code=" + response.body().code + " msg=" + response.body().msg + " data=" + (response.body().data != null ? response.body().data.size() + "条" : "null") : "nullBody";
                Log.d("Overview", "getMediaDbList resp code=" + response.code() + " " + bodyStr + " t=" + (System.currentTimeMillis() - t0) + "ms");
                if (response.body() != null && response.body().code != 0) {
                    try { Log.w("Overview", "错误响应: " + new com.google.gson.Gson().toJson(response.body())); } catch (Exception ignored) {}
                }
                // Auth Failed 时重试一次，这次不算结束
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Overview", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                overviewLoading = false;
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) mediaLibraries.add(lib);
                    }
                    Log.d("Overview", "loaded " + mediaLibraries.size() + " libraries");
                    showOverview();
                    loadAllPreviews();
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                overviewLoading = false;
                tvMoviesLoading.setVisibility(View.GONE);
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览 */

    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        savedDetailItem = null; savedDetailInfo = null; savedBrowseList = null; savedBrowseGuid = null;
        setDetailChrome(false);
        savedLiveChannelTitle = null;
        showingEpisodes = false;
        moviesContainer.removeAllViews();
        showingOverview = true;
        overviewBuilt = true;
        homeEntryFocused = false;
        initialFocusPlaced = false;
        detailPlayBtn = null;
        detailOverview = null;
        detailChipRow = null;
        detailEpisodeBox = null;

        LinearLayout continueWatchingBox = new LinearLayout(this);
        continueWatchingBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        continueWatchingBox.setOrientation(LinearLayout.VERTICAL);
        continueWatchingBox.setTag("continue_watching");
        moviesContainer.addView(continueWatchingBox);
        if (cachedContinueWatching != null && !cachedContinueWatching.isEmpty()) {
            addContinueWatchingApi(continueWatchingBox, cachedContinueWatching, 0);
        }

        LinearLayout libRowBox = new LinearLayout(this);
        libRowBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        libRowBox.setOrientation(LinearLayout.VERTICAL);
        libRowBox.setTag("lib_shortcuts");
        libRowBox.addView(makeLibShortcutRow());
        moviesContainer.addView(libRowBox);

        for (MediaDbItem lib : mediaLibraries) {
            LinearLayout headerRow = makeLibHeader(lib.guid, lib.title, 0);
            moviesContainer.addView(headerRow);
            LinearLayout previewBox = new LinearLayout(this);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewBox.setOrientation(LinearLayout.VERTICAL);
            previewBox.setTag("preview_" + lib.guid);
            TextView previewLoading = new TextView(this);
            previewLoading.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            previewLoading.setPadding(dp(8), dp(4), dp(8), dp(8));
            previewLoading.setText("加载中...");
            previewLoading.setTextColor(color(R.color.text_hint));
            previewLoading.setTextSize(13);
            previewBox.addView(previewLoading);
            moviesContainer.addView(previewBox);
            moviesContainer.addView(makeSpacer(dp(8)));
        }

        if (moviesContainer.getChildCount() == 0) {
            TextView e = new TextView(this);
            e.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            e.setGravity(Gravity.CENTER);
            e.setText("暂无影视内容");
            e.setTextColor(color(R.color.text_hint));
            e.setTextSize(14);
            moviesContainer.addView(e);
        }
        if (cachedLivePreview != null && !cachedLivePreview.isEmpty()) {
            fillLiveChannelPreview(cachedLivePreview, cachedLiveTotal);
        }
        wireOverviewFocus();
    }

    /** 加载各媒体库预览 */

    private void loadAllPreviews() {
        if (loadingPreviews) return;
        loadingPreviews = true;
        for (final MediaDbItem lib : mediaLibraries) {
            final String guid = lib.guid;
            apiManager.getApi().getItemList(ItemListRequest.browseLibrary(guid))
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                            || response.body().data == null || response.body().data.list == null
                            || response.body().data.list.isEmpty()) {
                        clearPreview(guid);
                        return;
                    }
                    // 取前6个填到预览容器
                    List<PlayListItem> items = response.body().data.list;
                    if (items.size() > 20) items = items.subList(0, 20);
                    fillPreview(guid, items);
                    updateLibShortcutPoster(guid, items.get(0));
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    clearPreview(guid);
                }
            });
        }
    }

    /** 没有片子时去掉「加载中...」，标题（如「综艺 >」）和下面的间隔一并移除。 */
    private void clearPreview(String libGuid) {
        if (moviesContainer == null) return;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (!(v instanceof LinearLayout) || !("preview_" + libGuid).equals(v.getTag())) continue;
            int start = i;
            int end = i;
            if (i > 0 && "lib_header".equals(moviesContainer.getChildAt(i - 1).getTag())) {
                start = i - 1;
            }
            if (i + 1 < moviesContainer.getChildCount()) {
                View next = moviesContainer.getChildAt(i + 1);
                if (next.getTag() == null && !(next instanceof LinearLayout)) {
                    end = i + 1;
                }
            }
            for (int r = end; r >= start; r--) {
                moviesContainer.removeViewAt(r);
            }
            if (showingOverview) wireOverviewFocus();
            return;
        }
    }

    /** 填充预览卡片（先清空再填充，防止重复） */

    private void fillPreview(String libGuid, List<PlayListItem> items) {
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && ("preview_" + libGuid).equals(v.getTag())) {
                LinearLayout box = (LinearLayout) v;
                box.removeAllViews();
                populateGrid(box, items);
                wireOverviewFocus();
                break;
            }
        }
    }

    /** 构建媒体库标题行（整行可聚焦，点击 = 查看全部） */

    private LinearLayout makeLibHeader(String libGuid, String libTitle, int count) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(dp(4), dp(16), dp(4), dp(8));
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setMinimumHeight(dp(40));
        headerRow.setId(View.generateViewId());
        headerRow.setTag("lib_header");
        headerRow.setFocusable(true);
        headerRow.setBackgroundResource(R.drawable.bg_text_action);
        headerRow.setOnClickListener(v -> browseItems(libGuid, libTitle));

        TextView header = new TextView(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setText(libTitle + "  >");
        header.setTextColor(color(R.color.text_primary));
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);
        headerRow.setOnFocusChangeListener((v, hasFocus) ->
                header.setTextColor(hasFocus ? color(R.color.border_focused) : color(R.color.text_primary)));
        return headerRow;
    }

    private HorizontalScrollView makeHsv() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hsv.setHorizontalScrollBarEnabled(false);
        TvFocus.asScroller(hsv);
        return hsv;
    }

    private HorizontalScrollView makeLibShortcutRow() {
        HorizontalScrollView hsv = makeHsv();
        hsv.setPadding(0, dp(4), 0, dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), 0);

        for (int i = 0; i < mediaLibraries.size(); i++) {
            MediaDbItem lib = mediaLibraries.get(i);
            View tile = makeLibShortcutCard(lib);
            tile.setId(View.generateViewId());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(148), dp(92));
            lp.setMargins(dp(6), 0, dp(6), 0);
            tile.setLayoutParams(lp);
            row.addView(tile);
        }
        hsv.addView(row);
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
        return hsv;
    }

    private View makeLibShortcutCard(MediaDbItem lib) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.bg_lib_tile);
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));
        tile.setFocusable(true);
        tile.setTag("lib_tile_" + lib.guid);
        tile.setOnClickListener(v -> browseItems(lib.guid, lib.title));

        RoundedImageView img = new RoundedImageView(this);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setCornerRadius(14);
        img.setBackgroundColor(color(R.color.bg_poster));
        String poster = makePosterUrl(lib.getFirstPoster());
        if (poster != null) img.setTag(poster);
        tile.addView(img);

        View scrim = new View(this);
        FrameLayout.LayoutParams scrimLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        scrimLp.gravity = Gravity.BOTTOM;
        scrim.setLayoutParams(scrimLp);
        scrim.setBackgroundResource(R.drawable.bg_scrim);
        tile.addView(scrim);

        TextView name = new TextView(this);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.gravity = Gravity.BOTTOM;
        nameLp.setMargins(dp(10), 0, dp(10), dp(8));
        name.setLayoutParams(nameLp);
        name.setText(lib.title != null ? lib.title : "");
        name.setTextColor(color(R.color.text_white));
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(name);
        return tile;
    }

    private void updateLibShortcutPoster(String libGuid, PlayListItem first) {
        if (first == null || first.poster == null || first.poster.isEmpty()) return;
        String url = makePosterUrl(first.poster);
        if (url == null) return;
        String tag = "lib_tile_" + libGuid;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View box = moviesContainer.getChildAt(i);
            if (!(box instanceof ViewGroup) || !"lib_shortcuts".equals(box.getTag())) continue;
            View tile = findViewWithTagDeep((ViewGroup) box, tag);
            if (!(tile instanceof ViewGroup)) return;
            View img = ((ViewGroup) tile).getChildAt(0);
            if (img instanceof ImageView && img.getTag() == null) {
                img.setTag(url);
                SimpleImageLoader.load(url, (ImageView) img, apiManager.getClient());
            }
            return;
        }
    }

    private View findViewWithTagDeep(ViewGroup root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (tag.equals(c.getTag())) return c;
            if (c instanceof ViewGroup) {
                View found = findViewWithTagDeep((ViewGroup) c, tag);
                if (found != null) return found;
            }
        }
        return null;
    }


    private String makePosterUrl(String path) {
        return makeImageUrl(path, 400);
    }

    private String makeImageUrl(String path, int width) {
        if (path == null || path.isEmpty()) {
            Log.d("PosterUrl", "path is null/empty");
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        String fullUrl = baseUrl + "/v/api/v1/sys/img" + p + "?w=" + width;
        Log.d("PosterUrl", "poster=" + path + " -> " + fullUrl);
        return fullUrl;
    }

    private void setDetailChrome(boolean detail) {
        View header = findViewById(R.id.moviesHeader);
        if (header != null) header.setVisibility(detail ? View.GONE : View.VISIBLE);
        if (moviesContainer == null || savedMoviesPad == null) return;
        if (detail) {
            moviesContainer.setPadding(0, 0, 0, 0);
        } else {
            moviesContainer.setPadding(savedMoviesPad[0], savedMoviesPad[1],
                    savedMoviesPad[2], savedMoviesPad[3]);
        }
    }


    // ==================== 继续观看 ====================


    private void addContinueWatchingApi(LinearLayout cont, List<PlayListItem> items, int viewAllId) {
        if (items == null || items.isEmpty()) {
            hideContinueWatching(cont);
            return;
        }
        cont.removeAllViews();
        cont.setVisibility(View.VISIBLE);

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(dp(4), dp(12), dp(4), dp(8));
        h.setText("继续观看");
        h.setTextColor(color(R.color.text_secondary));
        h.setTextSize(14);
        cont.addView(h);

        HorizontalScrollView hsv = makeHsv();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), dp(8));

        for (PlayListItem item : items) {
            View card = makeContinueCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            card.setLayoutParams(lp);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
        if (showingOverview) wireOverviewFocus();
    }

    private String continueMainTitle(PlayListItem item) {
        if (item.tvTitle != null && !item.tvTitle.isEmpty()) return item.tvTitle;
        return item.title != null ? item.title : "";
    }

    private String continueSubTitle(PlayListItem item) {
        if (item.seasonNumber <= 0 && item.episodeNumber <= 0) return "";
        StringBuilder sb = new StringBuilder();
        if (item.seasonNumber > 0) sb.append("第").append(item.seasonNumber).append("季");
        if (item.episodeNumber > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("第").append(item.episodeNumber).append("集");
        }
        return sb.toString();
    }

    private View makeContinueCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        FrameLayout shot = new FrameLayout(this);
        shot.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(10);
        poster.setBackgroundColor(color(R.color.bg_poster));
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) poster.setTag(imgUrl);
        shot.addView(poster);

        int pct = item.duration > 0 ? Math.max(0, Math.min(100, (int) (item.ts * 100 / item.duration))) : 0;
        if (pct > 0) {
            LinearLayout pBar = new LinearLayout(this);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            barLp.gravity = Gravity.BOTTOM;
            pBar.setLayoutParams(barLp);
            pBar.setOrientation(LinearLayout.HORIZONTAL);
            pBar.setWeightSum(100);
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            fill.setBackgroundColor(color(R.color.colorPrimary));
            pBar.addView(fill);
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            rest.setBackgroundColor(color(R.color.progress_track));
            pBar.addView(rest);
            shot.addView(pBar);
        }
        card.addView(shot);

        final TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(14);
        title.setTextColor(color(R.color.text_primary));
        title.setText(continueMainTitle(item));
        card.addView(title);

        String sub = continueSubTitle(item);
        if (!sub.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            subTv.setSingleLine(true);
            subTv.setTextSize(12);
            subTv.setTextColor(color(R.color.text_hint));
            subTv.setPadding(0, dp(2), 0, 0);
            subTv.setText(sub);
            card.addView(subTv);
        }

        final PlayListItem fi = item;
        card.setOnClickListener(v -> launchPlayer(fi.guid, fi.title, fi.tvTitle != null ? fi.tvTitle : "",
                fi.episodeNumber, fi.poster, fi.getCategoryLabel(),
                fi.ts, fi.duration, fi.parentGuid));
        return card;
    }

    private LinearLayout findContinueWatchingBox() {
        if (moviesContainer == null) return null;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && "continue_watching".equals(v.getTag())) {
                return (LinearLayout) v;
            }
        }
        return null;
    }

    private void hideContinueWatching(LinearLayout box) {
        if (box == null) return;
        box.removeAllViews();
        box.setVisibility(View.GONE);
    }

    private int firstLibHeaderId() {
        for (int j = 0; j < moviesContainer.getChildCount(); j++) {
            View cv = moviesContainer.getChildAt(j);
            if ("lib_header".equals(cv.getTag()) && cv.getId() > 0) return cv.getId();
        }
        return -1;
    }

    /** 播放记录还在上报时先等它完成，再拉继续观看，避免列表早于记录返回。 */
    private void refreshContinueAfterPlayback() {
        if (PlayerActivity.hasPendingRecord() && recordWaitTicks < 20) {
            recordWaitTicks++;
            moviesContainer.postDelayed(this::refreshContinueAfterPlayback, 400);
            return;
        }
        recordWaitTicks = 0;
        loadContinueWatching();
    }

    private void loadContinueWatching() {
        if (apiManager.getApi() == null) return;
        if (continueLoading) {
            continueRefreshPending = true;
            return;
        }
        continueLoading = true;
        apiManager.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                continueLoading = false;
                List<PlayListItem> list = (response.isSuccessful() && response.body() != null
                        && response.body().code == 0) ? response.body().data : null;
                if (list != null && !list.isEmpty()) {
                    cachedContinueWatching = new ArrayList<>(list);
                } else if (response.isSuccessful() && response.body() != null && response.body().code == 0) {
                    cachedContinueWatching = null;
                }
                if (showingOverview) {
                    LinearLayout box = findContinueWatchingBox();
                    if (box != null) {
                        if (cachedContinueWatching == null || cachedContinueWatching.isEmpty()) {
                            hideContinueWatching(box);
                        } else {
                            addContinueWatchingApi(box, cachedContinueWatching, firstLibHeaderId());
                        }
                    }
                }
                if (continueRefreshPending) {
                    continueRefreshPending = false;
                    loadContinueWatching();
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                continueLoading = false;
                if (showingOverview) {
                    LinearLayout box = findContinueWatchingBox();
                    if (box != null && cachedContinueWatching != null && !cachedContinueWatching.isEmpty()
                            && box.getChildCount() == 0) {
                        addContinueWatchingApi(box, cachedContinueWatching, firstLibHeaderId());
                    }
                }
                if (continueRefreshPending) {
                    continueRefreshPending = false;
                    loadContinueWatching();
                }
            }
        });
    }


    private void setFocusDownInContainer(ViewGroup group, int targetId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            if (v.isFocusable()) {
                v.setNextFocusDownId(targetId);
            }
            if (v instanceof ViewGroup) {
                setFocusDownInContainer((ViewGroup) v, targetId);
            }
        }
    }


    // ==================== 横向滚动卡片 ====================


    private void populateGrid(LinearLayout cont, List<PlayListItem> items) {
        HorizontalScrollView hsv = makeHsv();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), dp(8));

        for (int i = 0; i < items.size(); i++) {
            View card = makeItemCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            card.setLayoutParams(lp);
            row.addView(card);
        }

        hsv.addView(row);
        cont.addView(hsv);

        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
    }

    private String itemCardSub(PlayListItem item) {
        if (item != null && ("TV".equals(item.type) || "Episode".equals(item.type))) {
            int seasons = item.localNumberOfSeasons > 0 ? item.localNumberOfSeasons : item.numberOfSeasons;
            int episodes = item.localNumberOfEpisodes > 0 ? item.localNumberOfEpisodes : item.numberOfEpisodes;
            if (seasons > 1) return "共" + seasons + "季";
            if (episodes > 0) return "共" + episodes + "集";
            if (seasons == 1) return "共1季";
        }
        return itemYear(item);
    }

    private String itemYear(PlayListItem item) {
        String d = item.airDate;
        if (d != null && d.length() >= 4 && Character.isDigit(d.charAt(0))) return d.substring(0, 4);
        return "";
    }

    private String itemResolution(PlayListItem item) {
        if (item.mediaStream == null || item.mediaStream.resolutions == null
                || item.mediaStream.resolutions.isEmpty()) return "";
        String raw = item.mediaStream.resolutions.get(0);
        if (raw == null) return "";
        String u = raw.toUpperCase();
        if (u.contains("2160") || u.contains("4K") || u.contains("UHD")) return "4K";
        if (u.contains("1440") || u.contains("2K")) return "2K";
        if (u.contains("1080")) return "1080";
        if (u.contains("720")) return "720";
        return raw.length() > 6 ? "" : raw;
    }

    private String itemRating(PlayListItem item) {
        if (item.voteAverage == null || item.voteAverage.isEmpty()) return "";
        try {
            float v = Float.parseFloat(item.voteAverage);
            if (v <= 0) return "";
            return String.format(java.util.Locale.US, "%.1f", v);
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private TextView makeOverlayBadge(String text, int gravity) {
        TextView tv = new TextView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(color(R.color.text_white));
        tv.setTextSize(10);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        tv.setBackgroundResource(R.drawable.bg_badge);
        return tv;
    }

    /** 竖版海报卡片：图 + 角标 + 标题/年份 */

    private View makeItemCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        FrameLayout posterBox = new FrameLayout(this);
        posterBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(186)));

        RoundedImageView iv = new RoundedImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(color(R.color.bg_poster));
        iv.setCornerRadius(10);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) iv.setTag(imgUrl);
        posterBox.addView(iv);

        String rating = itemRating(item);
        if (!rating.isEmpty()) {
            posterBox.addView(makeOverlayBadge(rating, Gravity.TOP | Gravity.RIGHT));
        }
        String res = itemResolution(item);
        if (!res.isEmpty()) {
            posterBox.addView(makeOverlayBadge(res, Gravity.BOTTOM | Gravity.RIGHT));
        }
        card.addView(posterBox);

        final TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(13);
        title.setTextColor(color(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        card.addView(title);

        String sub = itemCardSub(item);
        if (!sub.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            subTv.setTextSize(12);
            subTv.setTextColor(color(R.color.text_hint));
            subTv.setPadding(0, dp(2), 0, 0);
            subTv.setText(sub);
            card.addView(subTv);
        }

        card.setOnFocusChangeListener((v, hasFocus) -> title.setSelected(hasFocus));
        card.setTag(item);
        card.setOnClickListener(v -> onItemClick((PlayListItem) card.getTag()));
        return card;
    }


    private void onItemClick(PlayListItem item) {
        showDetail(item);  // 全部走 getPlayInfo
    }


    // ==================== 查看全部 ====================


    private void browseItems(String ancestorGuid, String title) {
        browseItemsInContainer(ancestorGuid, title, moviesContainer, tvMoviesLoading);
    }

    private void browseItemsInContainer(String ancestorGuid, String title,
                                        LinearLayout container, TextView loadingView) {
        Log.d("Overview", "browseItems: guid=" + ancestorGuid + " title=" + title);
        savedDetailItem = null; savedDetailInfo = null;
        showingEpisodes = false;
        setDetailChrome(false);
        savedBrowseGuid = ancestorGuid; savedBrowseTitle = title;
        browseFromLibrary = (container == libraryContainer);
        if (browseFromLibrary) {
            if (etSearch != null) etSearch.setVisibility(View.GONE);
            if (tvLibraryPageTitle != null) tvLibraryPageTitle.setVisibility(View.GONE);
        }
        if (container == moviesContainer) showingOverview = false;

        // 存储当前浏览上下文，排序变化时用于重新加载
        currentBrowseGuid = ancestorGuid;
        currentBrowseTitle = title;
        currentBrowseContainer = container;
        currentBrowseLoading = loadingView;

        container.removeAllViews();
        loadingView.setVisibility(View.VISIBLE);

        String sortColumn = libSortColumnIndex == 0 ? "create_time" : "release_date";
        String sortType = libSortOrderIndex == 0 ? "ASC" : "DESC";
        ItemListRequest request = new ItemListRequest(ancestorGuid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true, sortColumn, sortType, 50);
        apiManager.getApi().getItemList(request)
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                loadingView.setVisibility(View.GONE);
                Log.d("Overview", "browseItems response code=" + response.code());

                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && response.body().data.list != null
                        && !response.body().data.list.isEmpty()) {
                    List<PlayListItem> list = response.body().data.list;
                    savedBrowseList = list;
                    int total = response.body().data.total;
                    Log.d("Overview", "browseItems: got " + list.size() + " items, total=" + total);

                    TextView h = new TextView(HomeActivity.this);
                    h.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    h.setPadding(6, 8, 6, 4);
                    h.setText(title + "  (" + total + "项)");
                    h.setTextColor(color(R.color.text_primary));
                    h.setTextSize(14);
                    container.addView(h);

                    // 排序筛选栏
                    LinearLayout sortFilterBar = makeLibSortFilterBar();
                    sortFilterBar.setTag("lib_sort_bar");
                    container.addView(sortFilterBar);
                    container.addView(makeSpacer(6));

                    // 自适应列数网格（最小卡片宽200dp）
                    float density = getResources().getDisplayMetrics().density;
                    int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
                    for (int idx = 0; idx < list.size(); idx += cols) {
                        LinearLayout row = new LinearLayout(HomeActivity.this);
                        row.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        int inRow = Math.min(cols, list.size() - idx);
                        for (int c = 0; c < cols && idx + c < list.size(); c++) {
                            PlayListItem pli = list.get(idx + c);
                            View card = makeItemCard(pli);
                            // 图片按9:16竖版比例
                            if (card instanceof ViewGroup) {
                                View ch = ((ViewGroup) card).getChildAt(0);
                                if (ch != null) {
                                    int posterH = Math.min(dp(280), (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                                    ch.setLayoutParams(new LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                                }
                            }
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                            lp.rightMargin = 6;
                            lp.leftMargin = 6;
                            card.setLayoutParams(lp);
                            row.addView(card);
                        }
                        // 补齐空位
                        for (int e = inRow; e < cols; e++) {
                            View spacer = new View(HomeActivity.this);
                            spacer.setLayoutParams(new LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                            row.addView(spacer);
                        }
                        container.addView(row);
                        container.addView(makeSpacer(12));
                    }
                    new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
                    wireBrowseGrid(container);
                } else {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无内容");
                    e.setTextColor(color(R.color.text_hint));
                    e.setTextSize(14);
                    container.addView(e);
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingView.setVisibility(View.GONE);
            }
        });
    }

    /** 从缓存数据重绘浏览网格（横竖屏切换时调用） */

    private void renderBrowseGrid(List<PlayListItem> list, String title) {
        renderGridInContainer(list, title, libraryContainer);
    }

    /** 在指定容器中绘制缓存网格 */

    private void renderGridInContainer(List<PlayListItem> list, String title, LinearLayout container) {
        container.removeAllViews();
        int total = list.size();

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText(title + "  (" + total + "项)");
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(14);
        container.addView(h);

        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeItemCard(pli);
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    if (ch != null) {
                        int posterH = Math.min(dp(280), (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                        ch.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                    }
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6; lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            container.addView(row);
            container.addView(makeSpacer(12));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(container, 0));
        wireBrowseGrid(container);
    }

    // ==================== 媒体库排序筛选 ====================

    /** 使用当前排序重新加载媒体库内容 */
    private void reFetchLibraryItems() {
        if (currentBrowseGuid == null || currentBrowseContainer == null) return;
        browseItemsInContainer(currentBrowseGuid, currentBrowseTitle,
                currentBrowseContainer, currentBrowseLoading);
    }

    /** 构建排序筛选栏 */
    private LinearLayout makeLibSortFilterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_input);
        bar.setPadding(16, 18, 16, 18);

        // 排序方式标签
        TextView sortLabel = new TextView(this);
        sortLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sortLabel.setGravity(Gravity.CENTER_VERTICAL);
        sortLabel.setText("排序方式: ");
        sortLabel.setTextColor(color(R.color.text_secondary));
        sortLabel.setTextSize(13);
        bar.addView(sortLabel);

        // 排序列选择按钮
        Button sortColumnBtn = new Button(this);
        sortColumnBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortColumnBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] colLabels = {"添加日期", "发行日期"};
        sortColumnBtn.setText(colLabels[libSortColumnIndex] + " ▾");
        sortColumnBtn.setTextColor(color(R.color.text_primary));
        sortColumnBtn.setTextSize(12);
        sortColumnBtn.setFocusable(true);
        sortColumnBtn.setPadding(14, 0, 14, 0);
        sortColumnBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
        });
        final Button colBtnRef = sortColumnBtn;
        sortColumnBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序方式")
                        .setSingleChoiceItems(colLabels, libSortColumnIndex,
                                (dialog, which) -> {
                                    libSortColumnIndex = which;
                                    colBtnRef.setText(colLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortColumnBtn);

        // 弹性间隔
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bar.addView(spacer);

        // 顺序标签
        TextView orderLabel = new TextView(this);
        orderLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        orderLabel.setGravity(Gravity.CENTER_VERTICAL);
        orderLabel.setText("顺序: ");
        orderLabel.setTextColor(color(R.color.text_secondary));
        orderLabel.setTextSize(13);
        bar.addView(orderLabel);

        // 排序顺序选择按钮
        Button sortOrderBtn = new Button(this);
        sortOrderBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 78));
        sortOrderBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        String[] orderLabels = {"升序", "降序"};
        sortOrderBtn.setText(orderLabels[libSortOrderIndex] + " ▾");
        sortOrderBtn.setTextColor(color(R.color.text_primary));
        sortOrderBtn.setTextSize(12);
        sortOrderBtn.setFocusable(true);
        sortOrderBtn.setPadding(14, 0, 14, 0);
        sortOrderBtn.setOnFocusChangeListener((v, hasFocus) -> {
            v.setScaleX(hasFocus ? 1.08f : 1.0f);
            v.setScaleY(hasFocus ? 1.08f : 1.0f);
        });
        final Button orderBtnRef = sortOrderBtn;
        sortOrderBtn.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("排序顺序")
                        .setSingleChoiceItems(orderLabels, libSortOrderIndex,
                                (dialog, which) -> {
                                    libSortOrderIndex = which;
                                    orderBtnRef.setText(orderLabels[which] + " ▾");
                                    dialog.dismiss();
                                    reFetchLibraryItems();
                                })
                        .setNegativeButton("取消", null)
                        .show()
        );
        bar.addView(sortOrderBtn);

        return bar;
    }

    /** 启动播放器 */
    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur) {
        launchPlayer(guid, title, tvTitle, epNum, poster, cat, ts, dur, null);
    }

    private void launchPlayer(String guid, String title, String tvTitle, int epNum,
                              String poster, String cat, long ts, long dur, String parentGuid) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", guid);
        intent.putExtra("title", title);
        intent.putExtra("tv_title", tvTitle);
        intent.putExtra("episode_number", epNum);
        intent.putExtra("poster", poster);
        intent.putExtra("category", cat);
        intent.putExtra("ts", ts);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        startActivity(intent);
    }


    private TextView makeMetaChip(String text) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(color(R.color.text_primary));
        tv.setTextSize(12);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setBackgroundResource(R.drawable.bg_meta_chip);
        tv.setSingleLine(true);
        return tv;
    }

    private TextView makeSectionLabel(String text) {
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, dp(4), 0, dp(8));
        h.setText(text);
        h.setTextColor(color(R.color.text_secondary));
        h.setTextSize(13);
        return h;
    }

    private String formatRuntimeLabel(long sec) {
        if (sec <= 0) return "";
        long m = (sec + 30) / 60;
        if (m < 60) return m + "分钟";
        long h = m / 60;
        long rm = m % 60;
        return rm > 0 ? h + "小时" + rm + "分钟" : h + "小时";
    }

    private String detailYear(PlayInfoResponse info, PlayListItem item) {
        String date = null;
        if (info.item != null && info.item.releaseDate != null && !info.item.releaseDate.isEmpty()) {
            date = info.item.releaseDate;
        } else if (info.item != null && info.item.airDate != null && !info.item.airDate.isEmpty()) {
            date = info.item.airDate;
        } else {
            date = item.airDate;
        }
        if (date != null && date.length() >= 4 && Character.isDigit(date.charAt(0))) {
            return date.substring(0, 4);
        }
        return "";
    }

    private String detailResolution(PlayInfoResponse info, PlayListItem item) {
        List<String> res = null;
        if (info.item != null && info.item.mediaStream != null) res = info.item.mediaStream.resolutions;
        if ((res == null || res.isEmpty()) && item.mediaStream != null) res = item.mediaStream.resolutions;
        if (res == null || res.isEmpty() || res.get(0) == null) return "";
        String raw = res.get(0);
        String u = raw.toUpperCase();
        if (u.contains("2160") || u.contains("4K") || u.contains("UHD")) return "4K";
        if (u.contains("1440") || u.contains("2K")) return "2K";
        if (u.contains("1080")) return "1080P";
        if (u.contains("720")) return "720P";
        return raw.length() > 8 ? "" : raw;
    }

    private String detailAudio(PlayInfoResponse info) {
        if (info.item == null || info.item.mediaStream == null
                || info.item.mediaStream.audioType == null
                || info.item.mediaStream.audioType.isEmpty()) return "";
        String a = info.item.mediaStream.audioType.get(0);
        if (a == null) return "";
        return a.length() > 18 ? a.substring(0, 18) : a;
    }

    // ==================== 详情页（getPlayInfo → 按类型展示） ====================



    private void showDetail(PlayListItem item) {
        switchTab(0);
        savedBrowseList = null; savedBrowseGuid = null;
        showingOverview = false;
        showingEpisodes = false;
        setDetailChrome(true);
        moviesContainer.removeAllViews();
        TextView loading = new TextView(this);
        loading.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(140)));
        loading.setGravity(Gravity.CENTER);
        loading.setText("加载中...");
        loading.setTextColor(color(R.color.text_hint));
        loading.setTextSize(14);
        moviesContainer.addView(loading);

        Map<String, String> body = new HashMap<>();
        body.put("item_guid", item.guid);
        apiManager.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null) {
                    Toast.makeText(HomeActivity.this, "获取详情失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 构建详情页（飞牛影视风格：背景图 + 海报叠字 + 标签 + 剧集横滑） */
    private void buildDetailPage(PlayListItem item, PlayInfoResponse info) {
        moviesContainer.removeAllViews();
        savedDetailItem = item;
        savedDetailInfo = info;
        showingEpisodes = false;
        detailPlayBtn = null;
        detailOverview = null;
        detailChipRow = null;
        detailEpisodeBox = null;
        setDetailChrome(true);

        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        String epTitle = info.item != null && info.item.title != null ? info.item.title : item.title;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        if (series.isEmpty() && item.tvTitle != null) series = item.tvTitle;
        int epNum = info.item != null ? info.item.episodeNumber : item.episodeNumber;
        int seasonNum = info.item != null ? info.item.seasonNumber : item.seasonNumber;

        String typeStr = info.type != null ? info.type : item.type;
        String typeLabel = "电影";
        if ("Episode".equals(typeStr) || "TV".equals(typeStr)) typeLabel = "剧集";
        else if ("Video".equals(typeStr)) typeLabel = "视频";
        boolean isSeries = "TV".equals(typeStr) || "Episode".equals(typeStr);

        String mainTitle = !series.isEmpty() ? series : (epTitle != null ? epTitle : "");
        String subTitle = "";
        if (isSeries) {
            StringBuilder sub = new StringBuilder();
            if (seasonNum > 0) sub.append("第").append(seasonNum).append("季");
            if (epNum > 0) {
                if (sub.length() > 0) sub.append(" ");
                sub.append("第").append(epNum).append("集");
            }
            if (epTitle != null && !epTitle.isEmpty() && !epTitle.equals(mainTitle)) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(epTitle);
            }
            subTitle = sub.toString();
        }

        String voteRaw = item.voteAverage;
        if ((voteRaw == null || voteRaw.isEmpty()) && info.item != null) voteRaw = info.item.voteAverage;
        String voteLabel = null;
        if (voteRaw != null && !voteRaw.isEmpty() && !voteRaw.equals("0") && !voteRaw.equals("0.0")) {
            try {
                float v = Float.parseFloat(voteRaw);
                if (v > 0) voteLabel = String.format(java.util.Locale.US, "%.1f", v);
            } catch (NumberFormatException ignored) {}
        }

        int runtime = info.item != null ? info.item.runtime : item.runtime;
        long rawDur = info.item != null && info.item.duration > 0 ? info.item.duration : 0;
        if (rawDur <= 0 && runtime > 0) rawDur = runtime * 60L;
        if (rawDur <= 0) rawDur = item.duration;
        final long pDur = rawDur;
        final long pTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        final int progressPct = pDur > 0 ? Math.max(0, Math.min(100, (int) (pTs * 100 / pDur))) : 0;
        final String pGuid = item.guid;
        final String pTitle = item.title;
        final String pTV = !series.isEmpty() ? series : "";
        final String pPoster = item.poster;
        final String pCat = item.getCategoryLabel();
        final int pEp = item.episodeNumber;
        final String pParentGuid = info.parentGuid != null && !info.parentGuid.isEmpty()
                ? info.parentGuid : item.parentGuid;

        String backdropPath = info.getBackdropPath();
        if (backdropPath == null) backdropPath = item.poster;
        String posterPath = info.getPosterPath();
        if (posterPath == null) posterPath = item.poster;
        String backdropUrl = makeImageUrl(backdropPath, 800);
        String posterUrl = makeImageUrl(posterPath, 400);

        int posterW = dp(land ? 168 : 120);
        int posterH = posterW * 3 / 2;
        int heroH = posterH + dp(56);

        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH));
        hero.setBackgroundColor(color(R.color.bg_poster));
        hero.setClipChildren(false);
        hero.setClipToPadding(false);

        ImageView backdrop = new ImageView(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setBackgroundColor(color(R.color.bg_poster));
        hero.addView(backdrop);
        if (backdropUrl != null) {
            SimpleImageLoader.load(backdropUrl, backdrop, apiManager.getClient());
        }

        View dim = new View(this);
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setBackgroundColor(0x66000000);
        hero.addView(dim);

        View fade = new View(this);
        fade.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fade.setBackgroundResource(R.drawable.bg_detail_fade);
        hero.addView(fade);

        LinearLayout heroRow = new LinearLayout(this);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = Gravity.BOTTOM;
        heroRow.setLayoutParams(rowLp);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.BOTTOM);
        heroRow.setPadding(dp(16), dp(12), dp(16), dp(16));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(posterW, posterH));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(color(R.color.bg_card));
        poster.setCornerRadius(10);
        if (posterUrl != null) {
            SimpleImageLoader.load(posterUrl, poster, apiManager.getClient());
        }
        heroRow.addView(poster);

        LinearLayout infoCol = new LinearLayout(this);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.leftMargin = dp(16);
        infoCol.setLayoutParams(infoLp);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleBig = new TextView(this);
        titleBig.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        titleBig.setText(mainTitle.trim());
        titleBig.setTextColor(color(R.color.text_white));
        titleBig.setTextSize(land ? 26 : 20);
        titleBig.setTypeface(Typeface.DEFAULT_BOLD);
        titleBig.setMaxLines(2);
        titleBig.setEllipsize(TextUtils.TruncateAt.END);
        titleBig.setShadowLayer(6, 0, 1, 0xCC000000);
        infoCol.addView(titleBig);

        String original = info.item != null ? info.item.originalTitle : null;
        if (original != null && !original.isEmpty() && !original.equals(mainTitle)) {
            TextView origTv = new TextView(this);
            origTv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            origTv.setPadding(0, dp(2), 0, 0);
            origTv.setText(original);
            origTv.setTextColor(color(R.color.text_hint));
            origTv.setTextSize(13);
            origTv.setSingleLine(true);
            origTv.setEllipsize(TextUtils.TruncateAt.END);
            infoCol.addView(origTv);
        }

        if (!subTitle.isEmpty()) {
            TextView subTv = new TextView(this);
            subTv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            subTv.setPadding(0, dp(4), 0, 0);
            subTv.setText(subTitle);
            subTv.setTextColor(color(R.color.text_secondary));
            subTv.setTextSize(13);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.END);
            infoCol.addView(subTv);
        }

        HorizontalScrollView chipScroll = makeHsv();
        LinearLayout.LayoutParams chipScrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipScrollLp.topMargin = dp(10);
        chipScroll.setLayoutParams(chipScrollLp);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.addView(makeMetaChip(typeLabel));
        String year = detailYear(info, item);
        if (!year.isEmpty()) chips.addView(makeMetaChip(year));
        if (voteLabel != null) {
            TextView score = makeMetaChip(voteLabel);
            score.setTextColor(color(R.color.rating_gold));
            chips.addView(score);
        }
        String runtimeLabel = formatRuntimeLabel(pDur);
        if (!runtimeLabel.isEmpty()) chips.addView(makeMetaChip(runtimeLabel));
        String resLabel = detailResolution(info, item);
        if (!resLabel.isEmpty()) chips.addView(makeMetaChip(resLabel));
        String audioLabel = detailAudio(info);
        if (!audioLabel.isEmpty()) chips.addView(makeMetaChip(audioLabel));
        chipScroll.addView(chips);
        infoCol.addView(chipScroll);

        Button playBtn = new Button(this);
        playBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        playLp.topMargin = dp(14);
        playBtn.setLayoutParams(playLp);
        playBtn.setMinWidth(dp(148));
        playBtn.setPadding(dp(22), 0, dp(22), 0);
        playBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        playBtn.setFocusable(true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setAllCaps(false);
        playBtn.setTextColor(color(R.color.text_white));
        playBtn.setTextSize(16);
        playBtn.setTypeface(Typeface.DEFAULT_BOLD);
        playBtn.setText(pTs > 0 ? "继续播放" : "播放");
        playBtn.setOnClickListener(v -> {
            Object tag = playBtn.getTag();
            long finalDur = tag instanceof Long ? (Long) tag : pDur;
            launchPlayer(pGuid, pTitle, pTV, pEp, pPoster, pCat, pTs, finalDur, pParentGuid);
        });
        infoCol.addView(playBtn);

        if (pTs > 0) {
            LinearLayout progRow = new LinearLayout(this);
            LinearLayout.LayoutParams progRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            progRowLp.topMargin = dp(8);
            progRow.setLayoutParams(progRowLp);
            progRow.setOrientation(LinearLayout.VERTICAL);

            LinearLayout pBar = new LinearLayout(this);
            pBar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            pBar.setOrientation(LinearLayout.HORIZONTAL);
            pBar.setWeightSum(100);
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, progressPct));
            fill.setBackgroundColor(color(R.color.colorPrimary));
            pBar.addView(fill);
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - progressPct));
            rest.setBackgroundColor(color(R.color.progress_track));
            pBar.addView(rest);
            progRow.addView(pBar);

            TextView progTv = new TextView(this);
            progTv.setPadding(0, dp(4), 0, 0);
            progTv.setTextSize(12);
            progTv.setTextColor(color(R.color.text_hint));
            progTv.setText(formatDuration(pTs) + " / " + formatDuration(pDur));
            progRow.addView(progTv);
            infoCol.addView(progRow);
        }

        heroRow.addView(infoCol);
        hero.addView(heroRow);
        moviesContainer.addView(hero);

        LinearLayout below = new LinearLayout(this);
        below.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        below.setOrientation(LinearLayout.VERTICAL);
        below.setPadding(dp(16), dp(8), dp(16), dp(28));
        below.setBackgroundColor(color(R.color.bg_dark));

        String overview = info.item != null && info.item.overview != null
                ? info.item.overview : item.overview;
        if (overview != null && !overview.isEmpty()) {
            below.addView(makeSectionLabel("简介"));
            final TextView ov = new TextView(this);
            ov.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ov.setText(overview);
            ov.setTextColor(color(R.color.text_secondary));
            ov.setTextSize(14);
            ov.setLineSpacing(6, 1);
            ov.setMaxLines(4);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setFocusable(true);
            ov.setOnClickListener(v -> {
                boolean collapsed = ov.getMaxLines() == 4;
                ov.setMaxLines(collapsed ? Integer.MAX_VALUE : 4);
                ov.setEllipsize(collapsed ? null : TextUtils.TruncateAt.END);
            });
            ov.setOnFocusChangeListener((v, hasFocus) ->
                    ov.setTextColor(hasFocus ? color(R.color.text_primary) : color(R.color.text_secondary)));
            detailOverview = ov;
            below.addView(ov);
            below.addView(makeSpacer(dp(12)));
        }

        if (isSeries && item.guid != null && !item.guid.isEmpty()) {
            LinearLayout seasonsBox = new LinearLayout(this);
            seasonsBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            seasonsBox.setOrientation(LinearLayout.VERTICAL);
            below.addView(seasonsBox);
            loadSeasons(seasonsBox, item.guid, item, playBtn, pTs, seasonNum, pParentGuid);
        }

        moviesContainer.addView(below);
        detailPlayBtn = playBtn;
        detailChipRow = null;
        detailEpisodeBox = null;
        wireDetailNav();
        playBtn.post(playBtn::requestFocus);
    }

    /** 加载剧集列表并按季分组 */
    private void loadEpisodes(final LinearLayout content, final String parentGuid, final PlayListItem item,
                              final Button playBtn, final long pTs, final String historyEpGuid) {
        apiManager.getApi().getEpisodeList(parentGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                List<PlayListItem> episodes = response.body().data;
                showSeasons(content, episodes, item);
                // 从剧集列表找到历史记录对应的那一集，用它的精确时长覆盖按钮
                if (historyEpGuid != null) {
                    for (PlayListItem ep : episodes) {
                        if (ep.guid.equals(historyEpGuid) && ep.duration > 0) {
                            long epDur = ep.duration;
                            Log.d("Detail", "剧集列表匹配到历史剧集, 时长: " + formatDuration(epDur));
                            playBtn.setText("▶  继续播放\n" + formatDuration(pTs) + " / " + formatDuration(epDur));
                            // 通过 setTag 把修正后的时长传给点击监听
                            playBtn.setTag(epDur);
                            break;
                        }
                    }
                }
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 加载季列表，并在详情页内横滑展示剧集封面 */
    private void loadSeasons(final LinearLayout content, final String itemGuid, final PlayListItem item,
                              final Button playBtn, final long pTs, final int preferSeason,
                              final String fallbackSeasonGuid) {
        apiManager.getApi().getSeasonList(itemGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (savedDetailItem == null || item.guid == null || !item.guid.equals(savedDetailItem.guid)) return;
                List<PlayListItem> seasons = (response.isSuccessful() && response.body() != null
                        && response.body().code == 0) ? response.body().data : null;
                if (seasons == null || seasons.isEmpty()) {
                    if (fallbackSeasonGuid != null && !fallbackSeasonGuid.isEmpty()) {
                        content.addView(makeSectionLabel("剧集"));
                        LinearLayout epBox = newEpisodeBox();
                        content.addView(epBox);
                        fillSeasonEpisodes(epBox, fallbackSeasonGuid, item, playBtn, pTs);
                    }
                    return;
                }

                content.addView(makeSectionLabel("剧集"));

                HorizontalScrollView hsv = makeHsv();
                LinearLayout chipRow = new LinearLayout(HomeActivity.this);
                chipRow.setOrientation(LinearLayout.HORIZONTAL);
                chipRow.setPadding(0, 0, 0, dp(4));
                hsv.addView(chipRow);
                content.addView(hsv);
                content.addView(makeSpacer(dp(8)));

                final LinearLayout epBox = newEpisodeBox();
                content.addView(epBox);
                detailChipRow = chipRow;
                detailEpisodeBox = epBox;

                PlayListItem selected = seasons.get(0);
                for (PlayListItem s : seasons) {
                    if (preferSeason > 0 && s.seasonNumber == preferSeason) {
                        selected = s;
                        break;
                    }
                }

                final List<TextView> chips = new ArrayList<>();
                for (final PlayListItem season : seasons) {
                    TextView chip = makeSeasonChip(season);
                    chip.setId(View.generateViewId());
                    chip.setOnClickListener(v -> {
                        for (TextView c : chips) c.setSelected(false);
                        chip.setSelected(true);
                        fillSeasonEpisodes(epBox, season.guid, item, playBtn, pTs);
                    });
                    if (season.guid != null && season.guid.equals(selected.guid)) chip.setSelected(true);
                    chips.add(chip);
                    chipRow.addView(chip);
                }
                wireDetailNav();
                fillSeasonEpisodes(epBox, selected.guid, item, playBtn, pTs);
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                if (fallbackSeasonGuid != null && !fallbackSeasonGuid.isEmpty()
                        && savedDetailItem != null && item.guid != null
                        && item.guid.equals(savedDetailItem.guid)) {
                    content.addView(makeSectionLabel("剧集"));
                    LinearLayout epBox = newEpisodeBox();
                    content.addView(epBox);
                    fillSeasonEpisodes(epBox, fallbackSeasonGuid, item, playBtn, pTs);
                }
            }
        });
    }

    private LinearLayout newEpisodeBox() {
        LinearLayout epBox = new LinearLayout(this);
        epBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        epBox.setOrientation(LinearLayout.VERTICAL);
        return epBox;
    }

    private TextView makeSeasonChip(PlayListItem season) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setTextSize(13);
        chip.setTextColor(color(R.color.text_primary));
        int sn = season.seasonNumber > 0 ? season.seasonNumber : 1;
        String label = "第" + sn + "季";
        if (season.localNumberOfEpisodes > 0) label += " · " + season.localNumberOfEpisodes + "集";
        chip.setText(label);
        chip.setBackgroundResource(R.drawable.bg_season_chip);
        chip.setFocusable(true);
        chip.setClickable(true);
        return chip;
    }

    private void fillSeasonEpisodes(final LinearLayout epBox, final String seasonGuid,
                                    final PlayListItem item, final Button playBtn, final long pTs) {
        epBox.removeAllViews();
        TextView loading = new TextView(this);
        loading.setTextColor(color(R.color.text_hint));
        loading.setTextSize(13);
        loading.setText("加载剧集...");
        epBox.addView(loading);

        apiManager.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (savedDetailItem == null || item.guid == null || !item.guid.equals(savedDetailItem.guid)) return;
                epBox.removeAllViews();
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) {
                    TextView empty = new TextView(HomeActivity.this);
                    empty.setText("暂无剧集");
                    empty.setTextColor(color(R.color.text_hint));
                    empty.setTextSize(13);
                    epBox.addView(empty);
                    return;
                }
                HorizontalScrollView hsv = makeHsv();
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, dp(4));
                for (PlayListItem ep : response.body().data) {
                    boolean current = ep.guid != null && ep.guid.equals(item.guid);
                    View card = makeDetailEpisodeCard(ep, current);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            dp(176), ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = dp(10);
                    card.setLayoutParams(lp);
                    row.addView(card);
                    if (current && ep.duration > 0) playBtn.setTag(ep.duration);
                }
                hsv.addView(row);
                epBox.addView(hsv);
                new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
                wireDetailNav();
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                if (savedDetailItem == null || item.guid == null || !item.guid.equals(savedDetailItem.guid)) return;
                epBox.removeAllViews();
                TextView err = new TextView(HomeActivity.this);
                err.setText("剧集加载失败");
                err.setTextColor(color(R.color.text_hint));
                err.setTextSize(13);
                epBox.addView(err);
            }
        });
    }

    private View makeDetailEpisodeCard(PlayListItem ep, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        FrameLayout shot = new FrameLayout(this);
        shot.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(10);
        poster.setBackgroundColor(color(R.color.bg_poster));
        String imgUrl = makePosterUrl(ep.poster);
        if (imgUrl != null) poster.setTag(imgUrl);
        shot.addView(poster);

        String epLabel = ep.episodeNumber > 0 ? "第" + ep.episodeNumber + "集" : "剧集";
        shot.addView(makeOverlayBadge(epLabel, Gravity.TOP | Gravity.LEFT));
        if (isCurrent) {
            shot.addView(makeOverlayBadge("当前", Gravity.TOP | Gravity.RIGHT));
        } else if (ep.watched == 1) {
            shot.addView(makeOverlayBadge("已看", Gravity.TOP | Gravity.RIGHT));
        }

        int pct = ep.duration > 0 ? Math.max(0, Math.min(100, (int) (ep.ts * 100 / ep.duration))) : 0;
        if (pct > 0) {
            LinearLayout pBar = new LinearLayout(this);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            barLp.gravity = Gravity.BOTTOM;
            pBar.setLayoutParams(barLp);
            pBar.setOrientation(LinearLayout.HORIZONTAL);
            pBar.setWeightSum(100);
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            fill.setBackgroundColor(color(R.color.colorPrimary));
            pBar.addView(fill);
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            rest.setBackgroundColor(color(R.color.progress_track));
            pBar.addView(rest);
            shot.addView(pBar);
        }
        card.addView(shot);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(13);
        title.setTextColor(isCurrent ? color(R.color.colorAccent) : color(R.color.text_primary));
        title.setText(ep.title != null ? ep.title : epLabel);
        card.addView(title);

        if (ep.duration > 0) {
            TextView dur = new TextView(this);
            dur.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dur.setTextSize(12);
            dur.setTextColor(color(R.color.text_hint));
            dur.setPadding(0, dp(2), 0, 0);
            dur.setText(formatRuntimeLabel(ep.duration));
            card.addView(dur);
        }

        final String eg = ep.guid;
        final String et = ep.title;
        final String eTV = ep.tvTitle != null ? ep.tvTitle : "";
        final int eEp = ep.episodeNumber;
        final String epPo = ep.poster;
        final String epCa = ep.getCategoryLabel();
        final long epTs = ep.ts > 0 ? ep.ts : 0;
        final long epDu = ep.duration;
        final String epPG = ep.parentGuid;
        card.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));
        return card;
    }

    /** 加载某季的剧集列表（点击季后调用，直接显示剧集） */
    private void loadEpisodesForSeason(String seasonGuid, int seasonNumber, PlayListItem original) {
        showingEpisodes = true;
        setDetailChrome(true);
        moviesContainer.setPadding(dp(16), dp(8), dp(16), dp(16));
        moviesContainer.removeAllViews();
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(dp(4), dp(12), dp(4), dp(12));
        h.setText("第 " + seasonNumber + " 季");
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        moviesContainer.addView(h);

        apiManager.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.isEmpty()) return;
                for (PlayListItem ep : response.body().data) {
                    moviesContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
                    moviesContainer.addView(makeSpacer(dp(8)));
                }
                new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(moviesContainer, 0));
            }
            @Override public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 显示季列表 */

    private void showSeasons(LinearLayout content, List<PlayListItem> episodes, PlayListItem item) {
        Map<Integer, List<PlayListItem>> map = new HashMap<>();
        for (PlayListItem ep : episodes) {
            int sn = ep.seasonNumber > 0 ? ep.seasonNumber : 1;
            if (!map.containsKey(sn)) map.put(sn, new ArrayList<PlayListItem>());
            map.get(sn).add(ep);
        }

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, 14, 0, 14);
        h.setText("选择剧集");
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(22);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(h);

        List<Integer> nums = new ArrayList<>(map.keySet());
        java.util.Collections.sort(nums);
        for (final int sn : nums) {
            final List<PlayListItem> eps = map.get(sn);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.bg_media_card);
            card.setPadding(16, 18, 16, 18);
            card.setFocusable(true);
            card.setMinimumHeight(56);

            LinearLayout tc = new LinearLayout(this);
            tc.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tc.setOrientation(LinearLayout.VERTICAL);
            tc.setGravity(Gravity.CENTER_VERTICAL);
            TextView st = new TextView(this); st.setTextSize(16); st.setTextColor(color(R.color.text_primary));
            st.setText("第 " + sn + " 季"); tc.addView(st);
            TextView ss = new TextView(this); ss.setTextSize(12); ss.setTextColor(color(R.color.text_hint));
            ss.setText(eps.size() + " 集"); tc.addView(ss);
            card.addView(tc);

            TextView ar = new TextView(this);
            ar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ar.setText(">"); ar.setTextColor(color(R.color.text_hint)); ar.setTextSize(20);
            ar.setGravity(Gravity.CENTER); ar.setPadding(8, 0, 0, 0);
            card.addView(ar);

            card.setOnClickListener(v -> showEpisodes(eps, sn, sn == 1 ? item : item));
            content.addView(card);
            content.addView(makeSpacer(6));
        }
    }

    /** 显示某季剧集 */

    private void showEpisodes(List<PlayListItem> eps, int sn, PlayListItem original) {
        showingEpisodes = true;
        setDetailChrome(true);
        moviesContainer.setPadding(dp(16), dp(8), dp(16), dp(16));
        moviesContainer.removeAllViews();

        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(dp(4), dp(12), dp(4), dp(12));
        h.setText("第 " + sn + " 季");
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(18);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        moviesContainer.addView(h);

        for (PlayListItem ep : eps) {
            moviesContainer.addView(makeEpisodeItem(ep, ep.guid.equals(original.guid)));
            moviesContainer.addView(makeSpacer(dp(8)));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(moviesContainer, 0));
        List<View> cards = TvFocus.collectVisibleFocusables(moviesContainer);
        TvFocus.bindChain(cards);
        if (!cards.isEmpty()) {
            bindTabBar(TvFocus.listOf(cards.get(0)), TvFocus.listOf(cards.get(cards.size() - 1)));
        }
        TvFocus.sealAll(cards);
    }

    /** 剧集条目卡片 */

    private View makeEpisodeItem(PlayListItem ep, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);
        card.setGravity(Gravity.CENTER_VERTICAL);

        final String eg = ep.guid;
        final String et = ep.title;
        final String eTV = ep.tvTitle != null ? ep.tvTitle : "";
        final int eEp = ep.episodeNumber;
        final String epPo = ep.poster;
        final String epCa = ep.getCategoryLabel();
        final long epTs = ep.ts > 0 ? ep.ts : 0;
        final long epDu = ep.duration;
        final String epPG = ep.parentGuid;
        card.setOnClickListener(v -> launchPlayer(eg, et, eTV, eEp, epPo, epCa, epTs, epDu, epPG));

        FrameLayout shot = new FrameLayout(this);
        shot.setLayoutParams(new LinearLayout.LayoutParams(dp(128), dp(72)));
        RoundedImageView still = new RoundedImageView(this);
        still.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setCornerRadius(8);
        still.setBackgroundColor(color(R.color.bg_poster));
        String imgUrl = makePosterUrl(ep.poster);
        if (imgUrl != null) still.setTag(imgUrl);
        shot.addView(still);
        int pct = ep.duration > 0 ? Math.max(0, Math.min(100, (int) (ep.ts * 100 / ep.duration))) : 0;
        if (pct > 0) {
            LinearLayout pBar = new LinearLayout(this);
            FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            barLp.gravity = Gravity.BOTTOM;
            pBar.setLayoutParams(barLp);
            pBar.setOrientation(LinearLayout.HORIZONTAL);
            pBar.setWeightSum(100);
            View fill = new View(this);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
            fill.setBackgroundColor(color(R.color.colorPrimary));
            pBar.addView(fill);
            View rest = new View(this);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100 - pct));
            rest.setBackgroundColor(color(R.color.progress_track));
            pBar.addView(rest);
            shot.addView(pBar);
        }
        card.addView(shot);

        LinearLayout infoCol = new LinearLayout(this);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.leftMargin = dp(12);
        infoCol.setLayoutParams(infoLp);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        TextView epTitle = new TextView(this);
        epTitle.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        epTitle.setTextSize(15);
        epTitle.setTextColor(isCurrent ? color(R.color.colorAccent) : color(R.color.text_primary));
        epTitle.setSingleLine(true);
        epTitle.setEllipsize(TextUtils.TruncateAt.END);
        epTitle.setText(ep.title != null ? ep.title : "未知");
        infoCol.addView(epTitle);

        StringBuilder sub = new StringBuilder();
        if (ep.episodeNumber > 0) sub.append("第").append(ep.episodeNumber).append("集");
        if (ep.duration > 0) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append(formatRuntimeLabel(ep.duration));
        }
        if (isCurrent) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append("当前");
        } else if (ep.watched == 1) {
            if (sub.length() > 0) sub.append("  ·  ");
            sub.append("已看");
        }
        if (sub.length() > 0) {
            TextView subTv = new TextView(this);
            subTv.setPadding(0, dp(4), 0, 0);
            subTv.setTextSize(12);
            subTv.setTextColor(color(R.color.text_hint));
            subTv.setText(sub.toString());
            infoCol.addView(subTv);
        }
        card.addView(infoCol);
        return card;
    }


    // ==================== 媒体库 Tab ====================


    private void loadMediaLibraries() {
        isSearching = false;
        if (etSearch != null) etSearch.setVisibility(View.VISIBLE);
        if (tvLibraryPageTitle != null) tvLibraryPageTitle.setVisibility(View.VISIBLE);
        clearContainer(libraryContainer, tvLibraryLoading, tvLibraryEmpty);
        tvLibraryLoading.setVisibility(View.VISIBLE);

        final int[] retryCount = {1};
        apiManager.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                tvLibraryLoading.setVisibility(View.GONE);
                // Auth Failed 时重试一次
                if (response.body() != null && response.body().code == -2 && retryCount[0] > 0) {
                    retryCount[0]--;
                    Log.d("Home", "Auth Failed，重试中...");
                    call.clone().enqueue(this);
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null && !response.body().data.isEmpty()) {
                    mediaLibraries.clear();
                    List<MediaDbItem> filteredLibs = new ArrayList<>();
                    for (MediaDbItem lib : response.body().data) {
                        if (!lib.refreshDisabled) filteredLibs.add(lib);
                    }
                    mediaLibraries.addAll(filteredLibs);
                    populateLibGrid(libraryContainer, filteredLibs);
                    return;
                }
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    // ==================== 搜索 ====================

    private void setupSearch() {
        if (etSearch == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etSearch.setShowSoftInputOnFocus(false);
        }
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitLibrarySearch();
                return true;
            }
            return false;
        });
        etSearch.setOnClickListener(v -> submitLibrarySearch());
        etSearch.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                submitLibrarySearch();
                return true;
            }
            return false;
        });
    }

    private long lastSearchImeAt;

    private void focusLibrarySearch() {
        if (etSearch == null || etSearch.getVisibility() != View.VISIBLE) return;
        if (!etSearch.isFocused()) etSearch.requestFocus();
        showLibraryKeyboard();
    }

    private void showLibraryKeyboard() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSearchImeAt < 800) return;
        lastSearchImeAt = now;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void submitLibrarySearch() {
        String q = etSearch.getText().toString().trim();
        if (q.isEmpty()) {
            focusLibrarySearch();
            return;
        }
        performSearch(q);
        hideKeyboard();
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            clearSearch();
            return;
        }
        isSearching = true;
        libraryContainer.removeAllViews();
        tvLibraryLoading.setVisibility(View.VISIBLE);
        tvLibraryLoading.setText("搜索中...");

        SearchHelper.search(apiManager, query, new SearchHelper.SearchCallback() {
            @Override
            public void onResults(List<PlayListItem> results) {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchResults(results);
            }

            @Override
            public void onEmpty() {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchEmpty();
            }

            @Override
            public void onError(String msg) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText(msg);
                tvLibraryEmpty.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showSearchResults(List<PlayListItem> results) {
        libraryContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));
        for (int idx = 0; idx < results.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, results.size() - idx);
            for (int c = 0; c < cols && idx + c < results.size(); c++) {
                PlayListItem item = results.get(idx + c);
                View card = makeItemCard(item);
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    if (ch != null) {
                        int posterH = Math.min(dp(280), (int) (getResources().getDisplayMetrics().widthPixels / cols * 1.5));
                        ch.setLayoutParams(new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                    }
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            libraryContainer.addView(row);
            libraryContainer.addView(makeSpacer(12));
        }
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(libraryContainer, 0));
        wireBrowseGrid(libraryContainer);
    }

    private void showSearchEmpty() {
        libraryContainer.removeAllViews();
        TextView empty = new TextView(this);
        empty.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 120));
        empty.setGravity(Gravity.CENTER);
        empty.setText("搜索无结果");
        empty.setTextColor(color(R.color.text_hint));
        empty.setTextSize(14);
        libraryContainer.addView(empty);
    }

    private void clearSearch() {
        if (isSearching) {
            isSearching = false;
            if (etSearch != null) {
                etSearch.setText("");
                etSearch.setVisibility(View.VISIBLE);
            }
            loadMediaLibraries();
        }
    }

    private void populateLibGrid(LinearLayout cont, List<MediaDbItem> libs) {
        clearContainer(cont, tvLibraryLoading, tvLibraryEmpty);
        cont.addView(makeLibSectionTitle("媒体库"));
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_lib_group);
        group.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < libs.size(); i++) {
            if (i > 0) group.addView(makeLibDivider());
            boolean first = i == 0;
            boolean last = i == libs.size() - 1;
            group.addView(makeLibRow(libs.get(i), first, last));
        }
        cont.addView(group);
        wireLibraryList();
        loadLibraryCounts(libs);
    }

    private TextView makeLibSectionTitle(String text) {
        TextView title = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(4);
        title.setLayoutParams(lp);
        title.setText(text);
        title.setTextColor(color(R.color.text_secondary));
        title.setTextSize(13);
        return title;
    }

    private View makeLibDivider() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        line.setLayoutParams(lp);
        line.setBackgroundColor(color(R.color.lib_divider));
        return line;
    }

    /** 电影用胶片图标，其余（影视剧 / 动漫 / 综艺等）用电视图标。 */
    private boolean isMovieLibrary(MediaDbItem lib) {
        String category = lib.category == null ? "" : lib.category.trim();
        if ("movie".equalsIgnoreCase(category) || "movies".equalsIgnoreCase(category)
                || "电影".equals(category)) return true;
        String title = lib.title == null ? "" : lib.title;
        return title.contains("电影");
    }

    private int libRowBackground(boolean first, boolean last) {
        if (first && last) return R.drawable.bg_lib_row_single;
        if (first) return R.drawable.bg_lib_row_top;
        if (last) return R.drawable.bg_lib_row_bottom;
        return R.drawable.bg_lib_row_mid;
    }

    private View makeLibRow(MediaDbItem lib, boolean first, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(18), dp(8), dp(16), dp(8));
        row.setFocusable(true);
        row.setBackgroundResource(libRowBackground(first, last));

        AppCompatImageView icon = new AppCompatImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        icon.setImageResource(isMovieLibrary(lib) ? R.drawable.ic_lib_movie : R.drawable.ic_lib_tv);
        row.addView(icon);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.leftMargin = dp(14);
        titleLp.rightMargin = dp(12);
        title.setLayoutParams(titleLp);
        title.setText(lib.title != null ? lib.title : "");
        title.setTextColor(color(R.color.text_primary));
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title);

        TextView count = new TextView(this);
        count.setTag(libCountTag(lib.guid));
        count.setTextColor(color(R.color.lib_count));
        count.setTextSize(16);
        count.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(count);

        AppCompatImageView chevron = new AppCompatImageView(this);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevronLp.leftMargin = dp(10);
        chevron.setLayoutParams(chevronLp);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        row.addView(chevron);

        row.setTag(lib);
        row.setOnClickListener(v -> {
            MediaDbItem m = (MediaDbItem) row.getTag();
            browseItemsInContainer(m.guid, m.title, libraryContainer, tvLibraryLoading);
        });
        return row;
    }

    private static String libCountTag(String guid) {
        return "lib_count_" + guid;
    }

    /** 右侧数字：优先媒体库汇总，缺的再按列表 total 补。 */
    private void loadLibraryCounts(List<MediaDbItem> libs) {
        final List<MediaDbItem> snapshot = new ArrayList<>(libs);
        apiManager.getApi().getMediaDbSum().enqueue(new Callback<ApiResponse<Map<String, Integer>>>() {
            @Override
            public void onResponse(Call<ApiResponse<Map<String, Integer>>> call,
                                   Response<ApiResponse<Map<String, Integer>>> response) {
                Map<String, Integer> sums = null;
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
                    sums = response.body().data;
                }
                for (MediaDbItem lib : snapshot) {
                    Integer n = lookupLibraryCount(sums, lib);
                    if (n != null) applyLibraryCount(lib.guid, n);
                    else fetchLibraryTotal(lib.guid);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<Map<String, Integer>>> call, Throwable t) {
                for (MediaDbItem lib : snapshot) fetchLibraryTotal(lib.guid);
            }
        });
    }

    private Integer lookupLibraryCount(Map<String, Integer> sums, MediaDbItem lib) {
        if (sums == null || sums.isEmpty() || lib == null || lib.guid == null) return null;
        if (sums.containsKey(lib.guid)) return sums.get(lib.guid);
        if (lib.category != null && sums.containsKey(lib.category)) return sums.get(lib.category);
        return null;
    }

    private void fetchLibraryTotal(String guid) {
        if (guid == null) return;
        ItemListRequest request = new ItemListRequest(guid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true, "create_time", "DESC", 1);
        apiManager.getApi().getItemList(request)
                .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                           Response<ApiResponse<ItemListResponse>> response) {
                        if (!response.isSuccessful() || response.body() == null
                                || response.body().code != 0 || response.body().data == null) {
                            return;
                        }
                        applyLibraryCount(guid, response.body().data.total);
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
                });
    }

    private void applyLibraryCount(String guid, int total) {
        if (isFinishing() || libraryContainer == null || guid == null) return;
        View v = libraryContainer.findViewWithTag(libCountTag(guid));
        if (v instanceof TextView) ((TextView) v).setText(String.valueOf(total));
    }


    // ==================== 设置 ====================


    private void setupSettings() {
        tvSettingUsername.setText("用户名: " + prefs.getString("user", ""));
        String d = prefs.getString(PREF_DECODER, "hardware");
        tvDecoderValue.setText("hardware".equals(d) ? "硬解" : "软解");
        rlDecoderSetting.setOnClickListener(v -> toggleDecoder());

        // 弹幕服务器
        String danmuUrl = prefs.getString("danmu_url", "");
        if (danmuUrl.isEmpty()) {
            String host = prefs.getString("host", "");
            host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            danmuUrl = "http://" + host + ":9321";
        }
        tvDanmuUrl.setText(danmuUrl);
        rlDanmuSetting.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("弹幕服务器地址");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setText(tvDanmuUrl.getText());
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                String val = input.getText().toString().trim();
                if (!val.isEmpty()) {
                    prefs.edit().putString("danmu_url", val).apply();
                    tvDanmuUrl.setText(val);
                }
            });
            b.setNegativeButton("重置", (dialog, which) -> {
                prefs.edit().remove("danmu_url").apply();
                String host = prefs.getString("host", "");
                host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
                tvDanmuUrl.setText("http://" + host + ":9321");
            });
            b.show();
        });

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("快进退步长（秒）");
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input.setText(String.valueOf(savedStep[0]));
            input.setSelection(input.getText().length());
            b.setView(input);
            b.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    if (val < 1) val = 1;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("seek_step", val).apply();
                    tvSeekStepValue.setText(val + "s");
                    savedStep[0] = val;
                } catch (Exception ignored) {}
            });
            b.setNegativeButton("取消", null);
            b.show();
        });

        // 缓冲时间
        final int[] savedBuffer = {prefs.getInt("buffer_time", 30)};
        tvBufferTimeValue.setText(savedBuffer[0] + "s");
        rlBufferTime.setOnClickListener(v -> {
            android.app.AlertDialog.Builder b2 = new android.app.AlertDialog.Builder(this);
            b2.setTitle("缓冲时间（秒）");
            final android.widget.EditText input2 = new android.widget.EditText(this);
            input2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            input2.setText(String.valueOf(savedBuffer[0]));
            input2.setSelection(input2.getText().length());
            b2.setView(input2);
            b2.setPositiveButton("保存", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input2.getText().toString().trim());
                    if (val < 5) val = 5;
                    if (val > 300) val = 300;
                    prefs.edit().putInt("buffer_time", val).apply();
                    tvBufferTimeValue.setText(val + "s");
                    savedBuffer[0] = val;
                } catch (Exception ignored) {}
            });
            b2.setNegativeButton("取消", null);
            b2.show();
        });

        apiManager.getApi().getUserInfo().enqueue(new Callback<ApiResponse<UserInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserInfoResponse>> call,
                                   Response<ApiResponse<UserInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().code == 0
                        && response.body().data != null) {
                    tvSettingUsername.setText("用户名: " + response.body().data.getDisplayName());
                }
            }
            @Override public void onFailure(Call<ApiResponse<UserInfoResponse>> call, Throwable t) {}
        });
    }


    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            Toast.makeText(this, "解码: 软解 (CPU)", Toast.LENGTH_SHORT).show();
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            Toast.makeText(this, "解码: 硬解 (GPU)", Toast.LENGTH_SHORT).show();
        }
    }


    // ==================== 问题反馈 ====================


    private void setupFeedback() {
        btnFeedback.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("问题反馈")
                    .setMessage("如有问题或建议，请加 QQ群：\n693516430")
                    .setPositiveButton("复制群号", (dialog, which) -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setText("693516430");
                        Toast.makeText(this, "群号已复制", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        });
    }


    // ==================== 登出 ====================


    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }


    private void logout() {
        apiManager.setToken(null);
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        startActivity(intent);
        finish();
    }


    // ==================== 工具 ====================


    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        l.setVisibility(View.GONE);
        e.setVisibility(View.GONE);
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v != l && v != e) c.removeView(v);
        }
    }


    private View makeSpacer(int h) {
        View v = new View(HomeActivity.this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }


    private String formatDuration(long sec) {
        if (sec <= 0) return "";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        return m + "分" + s + "秒";
    }

    /** 逐张加载图片 */

    private void loadImagesLazily(ViewGroup container, int index) {
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty() || index >= targets.size()) return;

        ImageView iv = targets.get(index);
        Object tag = iv.getTag();
        if (tag instanceof String) {
            String url = (String) tag;
            if (url.startsWith("http")) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
        }
        final int next = index + 1;
        if (next < targets.size()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() { loadImagesLazily(container, next); }
            }, 100);
        }
    }


    private void collectImageViews(ViewGroup parent, List<ImageView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView) {
                out.add((ImageView) child);
            } else if (child instanceof ViewGroup) {
                collectImageViews((ViewGroup) child, out);
            }
        }
    }


    // ==================== 直播频道 ====================


    /** 加载直播频道（total>0 则显示预览区） */
    private void loadLiveChannels() {
        if (apiManager.getApi() == null || liveLoading) return;
        liveLoading = true;
        try {
            ItemListRequest liveReq = ItemListRequest.browseLiveChannels();
            Log.d("LiveChannel", "请求体: " + new com.google.gson.Gson().toJson(liveReq));
            Log.d("LiveChannel", "loadLiveChannels 开始请求...");
            apiManager.getApi().getItemList(liveReq).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                @Override
                public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                       Response<ApiResponse<ItemListResponse>> response) {
                    liveLoading = false;
                    try {
                        Log.d("LiveChannel", "响应 code=" + response.code()
                                + " isSuccessful=" + response.isSuccessful());
                        // 打印请求信息
                        okhttp3.Request req = call.request();
                        Log.d("LiveChannel", "请求URL: " + req.url());
                        Log.d("LiveChannel", "请求Method: " + req.method());
                        Log.d("LiveChannel", "请求Headers:");
                        for (int i = 0; i < req.headers().size(); i++) {
                            Log.d("LiveChannel", "  " + req.headers().name(i) + ": " + req.headers().value(i));
                        }
                        if (response.body() != null) {
                            Log.d("LiveChannel", "body code=" + response.body().code
                                    + " msg=" + response.body().msg
                                    + " data=" + new com.google.gson.Gson().toJson(response.body()));
                        } else {
                            Log.w("LiveChannel", "body=null");
                        }
                        if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                                || response.body().data == null || response.body().data.list == null
                                || response.body().data.list.isEmpty()) return;
                        List<PlayListItem> items = response.body().data.list;
                        int total = response.body().data.total;
                        Log.d("LiveChannel", "直播频道: total=" + total + " items=" + items.size());
                        if (total > 0) {
                            List<PlayListItem> preview = items.size() > 20 ? items.subList(0, 20) : items;
                            cachedLivePreview = new ArrayList<>(preview);
                            cachedLiveTotal = total;
                            if (showingOverview && overviewBuilt && moviesContainer != null) {
                                fillLiveChannelPreview(cachedLivePreview, cachedLiveTotal);
                            }
                        }
                    } catch (Exception e) {
                        Log.e("LiveChannel", "onResponse 异常", e);
                    }
                }
                @Override
                public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    liveLoading = false;
                    Log.e("LiveChannel", "请求失败: " + t.getMessage(), t);
                }
            });
        } catch (Exception e) {
            liveLoading = false;
            Log.e("LiveChannel", "loadLiveChannels 异常", e);
        }
    }

    /** 填充直播频道预览区（先移除旧的再添加） */
    private void fillLiveChannelPreview(List<PlayListItem> items, int total) {
        // 移除已有的直播频道区域
        for (int i = moviesContainer.getChildCount() - 1; i >= 0; i--) {
            View v = moviesContainer.getChildAt(i);
            if (v instanceof LinearLayout && "live_channel".equals(v.getTag())) {
                moviesContainer.removeView(v);
            }
        }

        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setTag("live_channel");
        section.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headerRow = makeLibHeader(null, "直播频道", 0);
        headerRow.setOnClickListener(v -> browseLiveChannels());
        section.addView(headerRow);

        HorizontalScrollView hsv = makeHsv();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), dp(8));

        for (int i = 0; i < items.size(); i++) {
            View card = makeLiveChannelCard(items.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            card.setLayoutParams(lp);
            row.addView(card);
        }

        hsv.addView(row);
        section.addView(hsv);
        section.addView(makeSpacer(dp(8)));

        moviesContainer.addView(section);
        if (showingOverview) wireOverviewFocus();
    }

    /** 直播频道卡片（和其他卡片样式一致，图片区域空白） */
    private View makeLiveChannelCard(PlayListItem item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        String shortName = item.title != null && !item.title.isEmpty() ? item.title : "?";
        int[] colors = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00,
                        0xFF8E24AA, 0xFF00ACC1, 0xFF6D4C41, 0xFF546E7A};
        int colorIdx = item.guid != null ? Math.abs(item.guid.hashCode() % colors.length) : 0;
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dp(10));
        badgeBg.setColor(colors[colorIdx]);
        TextView channelBadge = new TextView(this);
        channelBadge.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(186)));
        channelBadge.setGravity(Gravity.CENTER);
        channelBadge.setText(shortName);
        channelBadge.setTextColor(color(R.color.text_white));
        int len = shortName.length();
        channelBadge.setTextSize(len <= 2 ? 28 : len <= 4 ? 20 : 16);
        channelBadge.setTypeface(Typeface.DEFAULT_BOLD);
        channelBadge.setBackgroundDrawable(badgeBg);
        card.addView(channelBadge);

        final TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(13);
        title.setTextColor(color(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        card.addView(title);

        TextView tag = new TextView(this);
        tag.setTextSize(12);
        tag.setTextColor(color(R.color.text_hint));
        tag.setPadding(0, dp(2), 0, 0);
        tag.setText("直播");
        card.addView(tag);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                title.setSelected(hasFocus);
            }
        });

        card.setTag(item);
        card.setOnClickListener(v -> {
            PlayListItem it = (PlayListItem) card.getTag();
            launchPlayer(it.guid, it.title, "", 0, null, "LiveChannel", 0, 0, it.parentGuid);
        });
        return card;
    }

    /** 直播频道查看全部 */
    private void browseLiveChannels() {
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingOverview = false;
        savedLiveChannelTitle = "直播频道";
        moviesContainer.removeAllViews();
        tvMoviesLoading.setVisibility(View.VISIBLE);
        tvMoviesLoading.setText("加载直播频道...");

        apiManager.getApi().getItemList(ItemListRequest.browseLiveChannels()).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                tvMoviesLoading.setVisibility(View.GONE);
                if (!response.isSuccessful() || response.body() == null || response.body().code != 0
                        || response.body().data == null || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    TextView e = new TextView(HomeActivity.this);
                    e.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 120));
                    e.setGravity(Gravity.CENTER);
                    e.setText("暂无直播频道");
                    e.setTextColor(color(R.color.text_hint));
                    e.setTextSize(14);
                    moviesContainer.addView(e);
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                int total = response.body().data.total;
                Log.d("LiveChannel", "直播频道查看全部: total=" + total + " items=" + list.size()
                        + " resp=" + new com.google.gson.Gson().toJson(response.body()));
                renderLiveChannelGrid(list, total);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                Toast.makeText(HomeActivity.this, "加载直播频道失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 渲染直播频道网格（自适应列数） */
    private void renderLiveChannelGrid(List<PlayListItem> list, int total) {
        // 标题
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(6, 8, 6, 4);
        h.setText("直播频道  (" + total + "项)");
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(14);
        moviesContainer.addView(h);

        // 自适应列数
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (130 * density)));

        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                PlayListItem pli = list.get(idx + c);
                View card = makeLiveChannelCard(pli);
                // 图片占位区域高度与其他卡片一致：基于列数自适应
                if (card instanceof ViewGroup) {
                    View ch = ((ViewGroup) card).getChildAt(0);
                    int posterH = Math.min(550, (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2);
                    ch.setLayoutParams(new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, posterH));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.rightMargin = 6;
                lp.leftMargin = 6;
                card.setLayoutParams(lp);
                row.addView(card);
            }
            // 补齐空位
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
                row.addView(spacer);
            }
            moviesContainer.addView(row);
            moviesContainer.addView(makeSpacer(8));
        }
        wireBrowseGrid(moviesContainer);
    }


    // ==================== 电视焦点 ====================

    private void setupTvFocus() {
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null) TvFocus.remember(newF);
            if (newF != null && !isTabBar(newF) && newF.isShown()) {
                lastContentFocus = newF;
                rememberFocusSection(newF);
            }
        });
        TvFocus.bindRow(Arrays.asList(tabMovies, tabLibrary, tabSettings));
        TvFocus.point(tabMovies, View.FOCUS_DOWN, tabMovies);
        TvFocus.point(tabLibrary, View.FOCUS_DOWN, tabLibrary);
        TvFocus.point(tabSettings, View.FOCUS_DOWN, tabSettings);
        wireSettingsFocus();
    }

    private boolean isTabBar(View v) {
        return v == tabMovies || v == tabLibrary || v == tabSettings;
    }

    private View currentTabView() {
        if (currentTab == 1) return tabLibrary;
        if (currentTab == 2) return tabSettings;
        return tabMovies;
    }

    private boolean isUsableFocus(View v) {
        return v != null && v.isShown() && v.isFocusable() && v.getVisibility() == View.VISIBLE;
    }

    private boolean isInCurrentPanel(View v) {
        View panel = currentTab == 1 ? panelLibrary : currentTab == 2 ? panelSettings : panelMovies;
        View p = v;
        while (p != null) {
            if (p == panel) return panel.getVisibility() == View.VISIBLE;
            Object parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void bindTabBar(List<View> enterLane, List<View> exitLane) {
        List<View> tabs = Arrays.asList(tabMovies, tabLibrary, tabSettings);
        if (enterLane != null && !enterLane.isEmpty()) {
            TvFocus.bindUpToNearest(tabs, enterLane);
        }
        if (exitLane != null && !exitLane.isEmpty()) {
            TvFocus.bindDownToRow(exitLane, tabs);
        }
    }

    private boolean moveExplicitFocus(View focused, int keyCode) {
        if (focused == null) return false;
        View next = TvFocus.resolveEntry(focused, TvFocus.dirFromKey(keyCode));
        if (next == null) return false;
        if (next == focused) return true;
        if (!isUsableFocus(next)) return true;
        if (!isTabBar(next) && !isInCurrentPanel(next) && next != findViewById(R.id.btnHomeSearch)) {
            return true;
        }
        focusOn(next);
        return true;
    }

    private void wireSettingsFocus() {
        List<View> items = new ArrayList<>();
        if (rlDecoderSetting != null) items.add(rlDecoderSetting);
        if (rlSeekStep != null) items.add(rlSeekStep);
        if (rlBufferTime != null) items.add(rlBufferTime);
        if (rlDanmuSetting != null) items.add(rlDanmuSetting);
        View checkUpdate = findViewById(R.id.btnCheckUpdate);
        if (checkUpdate != null) items.add(checkUpdate);
        if (btnFeedback != null) items.add(btnFeedback);
        if (btnLogout != null) items.add(btnLogout);
        TvFocus.bindChain(items);
        if (!items.isEmpty()) {
            bindTabBar(Collections.singletonList(items.get(0)),
                    Collections.singletonList(items.get(items.size() - 1)));
        }
        TvFocus.sealAll(items);
    }

    private void wireLibraryList() {
        List<View> cards = TvFocus.collectVisibleFocusables(libraryContainer);
        TvFocus.bindChain(cards);
        if (etSearch != null && etSearch.getVisibility() == View.VISIBLE) {
            TvFocus.stay(etSearch);
            if (!cards.isEmpty()) {
                TvFocus.point(etSearch, View.FOCUS_DOWN, cards.get(0));
                TvFocus.point(cards.get(0), View.FOCUS_UP, etSearch);
            } else {
                TvFocus.point(etSearch, View.FOCUS_DOWN, tabLibrary);
            }
        }
        List<View> enter = etSearch != null && etSearch.getVisibility() == View.VISIBLE
                ? TvFocus.listOf(etSearch)
                : (!cards.isEmpty() ? TvFocus.listOf(cards.get(0)) : null);
        List<View> exit = !cards.isEmpty()
                ? TvFocus.listOf(cards.get(cards.size() - 1)) : enter;
        bindTabBar(enter, exit);
        TvFocus.sealAll(cards);
        TvFocus.seal(etSearch);
    }

    private void wireBrowseGrid(LinearLayout container) {
        if (container == null) return;
        container.post(() -> {
            bindBrowseGridNow(container);
            container.post(() -> {
                bindBrowseGridNow(container);
                placeBrowseFocus(container);
            });
        });
    }

    /** 进入媒体库网格后，焦点落到第一张海报，而不是停在搜索或底栏。 */
    private void placeBrowseFocus(LinearLayout container) {
        if (container == null) return;
        View focused = getCurrentFocus();
        if (isDescendantOf(focused, container)) return;
        List<List<View>> rows = TvFocus.collectGridRows(container);
        View target = null;
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            target = rows.get(0).get(0);
        } else {
            View sortBar = container.findViewWithTag("lib_sort_bar");
            if (sortBar instanceof ViewGroup) {
                List<View> sortBtns = TvFocus.collectVisibleFocusables(sortBar);
                if (!sortBtns.isEmpty()) target = sortBtns.get(0);
            }
        }
        if (isUsableFocus(target)) focusOn(target);
    }

    private boolean isDescendantOf(View child, View parent) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private void bindBrowseGridNow(LinearLayout container) {
        if (container == null) return;
        if (currentTab == 1 && container != libraryContainer) return;
        if (currentTab == 0 && container != moviesContainer) return;
        List<List<View>> rows = TvFocus.collectGridRows(container);
        View sortBar = container.findViewWithTag("lib_sort_bar");
        List<View> sortBtns = sortBar instanceof ViewGroup
                ? TvFocus.collectVisibleFocusables(sortBar) : Collections.emptyList();
        View upTarget = null;
        if (!sortBtns.isEmpty()) {
            TvFocus.bindRow(sortBtns);
            upTarget = sortBtns.get(0);
        } else if (container == libraryContainer && etSearch != null
                && etSearch.getVisibility() == View.VISIBLE) {
            upTarget = etSearch;
        }
        TvFocus.bindGrid(rows, upTarget, currentTabView());
        if (!sortBtns.isEmpty() && !rows.isEmpty()) {
            TvFocus.bindVertical(sortBtns, rows.get(0));
        }
        Button homeSearch = container == moviesContainer
                ? findViewById(R.id.btnHomeSearch) : null;
        if (!sortBtns.isEmpty()) {
            if (container == libraryContainer && etSearch != null
                    && etSearch.getVisibility() == View.VISIBLE) {
                TvFocus.stay(etSearch);
                TvFocus.bindVertical(TvFocus.listOf(etSearch), sortBtns);
            } else if (homeSearch != null && homeSearch.getVisibility() == View.VISIBLE) {
                TvFocus.stay(homeSearch);
                TvFocus.bindAbove(homeSearch, sortBtns);
            } else {
                for (View v : sortBtns) TvFocus.point(v, View.FOCUS_UP, v);
            }
        } else if (homeSearch != null && homeSearch.getVisibility() == View.VISIBLE && !rows.isEmpty()) {
            TvFocus.stay(homeSearch);
            TvFocus.bindAbove(homeSearch, rows.get(0));
        } else if (upTarget == etSearch && etSearch != null) {
            TvFocus.stay(etSearch);
            if (!rows.isEmpty()) {
                TvFocus.bindVertical(TvFocus.listOf(etSearch), rows.get(0));
            } else {
                TvFocus.point(etSearch, View.FOCUS_DOWN, tabLibrary);
            }
        }
        List<View> enter = null;
        if (container == libraryContainer && etSearch != null
                && etSearch.getVisibility() == View.VISIBLE) {
            enter = TvFocus.listOf(etSearch);
        } else if (!sortBtns.isEmpty()) {
            enter = sortBtns;
        } else if (!rows.isEmpty()) {
            enter = rows.get(0);
        }
        List<View> exit = !rows.isEmpty() ? rows.get(rows.size() - 1)
                : (!sortBtns.isEmpty() ? sortBtns : enter);
        bindTabBar(enter, exit);
        for (List<View> row : rows) TvFocus.sealAll(row);
        TvFocus.sealAll(sortBtns);
        TvFocus.seal(etSearch);
    }

    private void wireOverviewFocus() {
        if (!showingOverview || moviesContainer == null) return;
        moviesContainer.post(() -> {
            bindOverviewFocusNow();
            moviesContainer.post(this::bindOverviewFocusNow);
        });
    }

    private void bindOverviewFocusNow() {
        if (!showingOverview || moviesContainer == null || currentTab != 0) return;
        List<List<View>> lanes = new ArrayList<>();
        Button searchBtn = findViewById(R.id.btnHomeSearch);
        if (searchBtn != null && searchBtn.getVisibility() == View.VISIBLE) {
            TvFocus.stay(searchBtn);
            lanes.add(TvFocus.listOf(searchBtn));
        }

        View pendingHeader = null;
        List<View> headerViews = new ArrayList<>();
        List<List<View>> headerCardLanes = new ArrayList<>();
        List<List<View>> headerPrevLanes = new ArrayList<>();
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View child = moviesContainer.getChildAt(i);
            Object tag = child.getTag();
            if ("lib_shortcuts".equals(tag)) {
                List<View> tiles = TvFocus.collectVisibleFocusables(child);
                if (!tiles.isEmpty()) {
                    TvFocus.bindRow(tiles);
                    lanes.add(tiles);
                }
            } else if ("continue_watching".equals(tag) && child.getVisibility() == View.VISIBLE) {
                List<View> cards = TvFocus.collectVisibleFocusables(child);
                if (!cards.isEmpty()) {
                    TvFocus.bindRow(cards);
                    lanes.add(cards);
                }
            } else if ("lib_header".equals(tag)) {
                pendingHeader = child;
            } else if (tag instanceof String && ((String) tag).startsWith("preview_")) {
                List<View> cards = TvFocus.collectVisibleFocusables(child);
                if (!cards.isEmpty()) {
                    TvFocus.bindRow(cards);
                    List<View> prev = contentLaneBefore(lanes, searchBtn);
                    if (pendingHeader != null) {
                        headerViews.add(pendingHeader);
                        headerCardLanes.add(cards);
                        headerPrevLanes.add(prev);
                    }
                    lanes.add(cards);
                }
                pendingHeader = null;
            } else if ("live_channel".equals(tag) && child instanceof ViewGroup) {
                List<View> cards = new ArrayList<>();
                collectLiveLane((ViewGroup) child, cards);
                View liveHeader = findHeaderIn((ViewGroup) child);
                if (!cards.isEmpty()) {
                    TvFocus.bindRow(cards);
                    List<View> prev = contentLaneBefore(lanes, searchBtn);
                    if (liveHeader != null) {
                        headerViews.add(liveHeader);
                        headerCardLanes.add(cards);
                        headerPrevLanes.add(prev);
                    }
                    lanes.add(cards);
                }
            }
        }

        int start = 0;
        if (searchBtn != null && !lanes.isEmpty() && lanes.get(0).get(0) == searchBtn && lanes.size() > 1) {
            TvFocus.bindAbove(searchBtn, lanes.get(1));
            start = 1;
        }
        for (int i = start; i < lanes.size() - 1; i++) {
            TvFocus.bindVertical(lanes.get(i), lanes.get(i + 1));
        }
        if (!lanes.isEmpty()) {
            bindTabBar(lanes.get(0), lanes.get(lanes.size() - 1));
            for (View v : lanes.get(0)) TvFocus.point(v, View.FOCUS_UP, v);
        }
        for (int i = 0; i < headerViews.size(); i++) {
            TvFocus.bindSectionHeader(headerViews.get(i), headerCardLanes.get(i), headerPrevLanes.get(i));
        }
        for (List<View> lane : lanes) TvFocus.sealAll(lane);
        TvFocus.sealAll(TvFocus.collectAllFocusables(moviesContainer));
        TvFocus.seal(searchBtn);
        restoreOverviewFocus();
    }

    private List<View> contentLaneBefore(List<List<View>> lanes, View searchBtn) {
        if (lanes == null || lanes.isEmpty()) return null;
        List<View> last = lanes.get(lanes.size() - 1);
        if (searchBtn != null && !last.isEmpty() && last.get(0) == searchBtn) return null;
        return last;
    }

    private View findHeaderIn(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if ("lib_header".equals(c.getTag())) return c;
        }
        return null;
    }

    private void collectLiveLane(ViewGroup group, List<View> cards) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if ("lib_header".equals(c.getTag())) continue;
            if (c instanceof HorizontalScrollView || c instanceof ScrollView) {
                collectLiveLane((ViewGroup) c, cards);
            } else if (c.isFocusable() && c.getVisibility() == View.VISIBLE) {
                cards.add(c);
            } else if (c instanceof ViewGroup) {
                collectLiveLane((ViewGroup) c, cards);
            }
        }
    }

    private void rememberFocusSection(View v) {
        lastFocusSectionTag = null;
        lastFocusIndexInSection = 0;
        View p = v;
        while (p != null) {
            Object tag = p.getTag();
            if (tag instanceof String) {
                String s = (String) tag;
                if ("lib_shortcuts".equals(s) || "continue_watching".equals(s)
                        || "live_channel".equals(s) || s.startsWith("preview_")) {
                    lastFocusSectionTag = s;
                    List<View> items = TvFocus.collectAllFocusables(p);
                    int idx = items.indexOf(v);
                    lastFocusIndexInSection = Math.max(0, idx);
                    return;
                }
                if ("lib_header".equals(s)) {
                    lastFocusSectionTag = headerSectionKey(p);
                    lastFocusIndexInSection = 0;
                    return;
                }
            }
            Object parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
    }

    private String headerSectionKey(View header) {
        Object parent = header.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) parent;
            int i = g.indexOfChild(header);
            if (i >= 0 && i + 1 < g.getChildCount()) {
                Object nt = g.getChildAt(i + 1).getTag();
                if (nt instanceof String) return "header:" + nt;
            }
            if ("live_channel".equals(g.getTag())) return "header:live_channel";
        }
        return "lib_header";
    }

    private View restoreInRememberedSection() {
        if (lastFocusSectionTag == null || moviesContainer == null) return null;
        if (lastFocusSectionTag.startsWith("header:")) {
            String rest = lastFocusSectionTag.substring("header:".length());
            if ("live_channel".equals(rest)) {
                View live = moviesContainer.findViewWithTag("live_channel");
                return live instanceof ViewGroup ? findHeaderIn((ViewGroup) live) : null;
            }
            View preview = moviesContainer.findViewWithTag(rest);
            if (preview != null && preview.getParent() instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) preview.getParent();
                int i = g.indexOfChild(preview);
                if (i > 0) {
                    View prev = g.getChildAt(i - 1);
                    if ("lib_header".equals(prev.getTag())) return prev;
                }
            }
            return null;
        }
        View section = findTaggedSection(lastFocusSectionTag);
        if (section == null) return null;
        List<View> items = TvFocus.collectAllFocusables(section);
        if (items.isEmpty()) return null;
        View pick = items.get(Math.min(lastFocusIndexInSection, items.size() - 1));
        return isUsableFocus(pick) ? pick : null;
    }

    private View findTaggedSection(String tag) {
        return moviesContainer != null ? moviesContainer.findViewWithTag(tag) : null;
    }

    private List<View> continueCards() {
        LinearLayout box = findContinueWatchingBox();
        if (box == null || box.getVisibility() != View.VISIBLE) return Collections.emptyList();
        return TvFocus.collectVisibleFocusables(box);
    }

    private List<View> shortcutCards() {
        if (moviesContainer == null) return Collections.emptyList();
        View shortcuts = moviesContainer.findViewWithTag("lib_shortcuts");
        if (!(shortcuts instanceof ViewGroup) || shortcuts.getVisibility() != View.VISIBLE) {
            return Collections.emptyList();
        }
        return TvFocus.collectVisibleFocusables(shortcuts);
    }

    private void restoreOverviewFocus() {
        if (currentTab != 0 || !showingOverview) return;
        View focused = getCurrentFocus();
        Button searchBtn = findViewById(R.id.btnHomeSearch);
        List<View> continueCards = continueCards();
        List<View> shortcuts = shortcutCards();
        boolean onSearch = focused == searchBtn;
        boolean onRealContent = isUsableFocus(focused) && !onSearch && !isTabBar(focused)
                && isInCurrentPanel(focused);

        if (!initialFocusPlaced && !continueCards.isEmpty()) {
            boolean stillAtStart = !onRealContent
                    || (!shortcuts.isEmpty() && focused == shortcuts.get(0));
            if (stillAtStart) {
                focusOn(continueCards.get(0));
                initialFocusPlaced = true;
                homeEntryFocused = true;
                return;
            }
            initialFocusPlaced = true;
        }

        if (onRealContent) {
            homeEntryFocused = true;
            return;
        }
        if (isTabBar(focused) && homeEntryFocused) return;

        View restore = restoreInRememberedSection();
        if (isUsableFocus(restore) && isInCurrentPanel(restore) && restore != searchBtn) {
            focusOn(restore);
            homeEntryFocused = true;
            return;
        }
        if (isUsableFocus(lastContentFocus) && isInCurrentPanel(lastContentFocus)
                && lastContentFocus != searchBtn) {
            focusOn(lastContentFocus);
            homeEntryFocused = true;
            return;
        }
        if (homeEntryFocused) return;

        View target = !continueCards.isEmpty() ? continueCards.get(0)
                : (!shortcuts.isEmpty() ? shortcuts.get(0) : searchBtn);
        if (isUsableFocus(target)) {
            focusOn(target);
            homeEntryFocused = true;
            if (!continueCards.isEmpty()) initialFocusPlaced = true;
        }
    }

    private void wireDetailNav() {
        if (moviesContainer == null) return;
        moviesContainer.post(() -> {
            bindDetailNavNow();
            moviesContainer.post(this::bindDetailNavNow);
        });
    }

    private void bindDetailNavNow() {
        if (showingOverview || showingEpisodes || currentTab != 0) return;
        List<View> play = detailPlayBtn != null ? TvFocus.listOf(detailPlayBtn) : Collections.emptyList();
        List<View> ov = detailOverview != null ? TvFocus.listOf(detailOverview) : Collections.emptyList();
        List<View> chips = detailChipRow != null
                ? TvFocus.collectVisibleFocusables(detailChipRow) : Collections.emptyList();
        List<View> eps = detailEpisodeBox != null
                ? TvFocus.collectVisibleFocusables(detailEpisodeBox) : Collections.emptyList();

        List<List<View>> rows = new ArrayList<>();
        if (!play.isEmpty()) rows.add(play);
        if (!ov.isEmpty()) rows.add(ov);
        if (!chips.isEmpty()) rows.add(chips);
        if (!eps.isEmpty()) rows.add(eps);

        for (List<View> row : rows) TvFocus.bindRow(row);
        for (int i = 0; i < rows.size() - 1; i++) {
            TvFocus.bindVertical(rows.get(i), rows.get(i + 1));
        }
        if (!rows.isEmpty()) {
            for (View v : rows.get(0)) TvFocus.point(v, View.FOCUS_UP, v);
            bindTabBar(rows.get(0), rows.get(rows.size() - 1));
        }
        for (List<View> row : rows) TvFocus.sealAll(row);
    }

    // ==================== 按键 ====================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean dpad = keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (dpad && event.getAction() == KeyEvent.ACTION_DOWN && handleDpad(keyCode)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 在滚动容器吃掉方向键之前移动焦点，底部 Tab 才能被按到。 */
    private boolean handleDpad(int keyCode) {
        View focused = getCurrentFocus();
        if (isTabBar(focused)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                View back = isUsableFocus(lastContentFocus) && isInCurrentPanel(lastContentFocus)
                        ? lastContentFocus : null;
                if (back == null) {
                    View next = TvFocus.resolveEntry(focused, View.FOCUS_UP);
                    if (isUsableFocus(next) && next != focused) back = next;
                }
                if (back != null) {
                    focusOn(back);
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        }
        if (focused instanceof EditText
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return false;
        }
        return moveExplicitFocus(focused, keyCode);
    }

    private void focusOn(View target) {
        target.requestFocus();
        target.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0,
                        Math.max(target.getWidth(), 1),
                        Math.max(target.getHeight(), 1)),
                true);
        target.post(() -> revealInVerticalScroll(target));
    }

    /** 滚回上方时把整行拉进可视区域，避免焦点落在屏幕外看起来像这一栏消失了。 */
    private void revealInVerticalScroll(View target) {
        ScrollView scroller = findVerticalScroller(target);
        if (scroller == null || scroller.getChildCount() == 0) return;
        View block = ancestorTagged(target, "continue_watching");
        View shown = block != null ? block : target;
        int top = topWithinContent(shown, scroller);
        int height = Math.max(shown.getHeight(), 1);
        int scrollY = scroller.getScrollY();
        int viewport = scroller.getHeight();
        if (viewport <= 0) return;
        if (top >= scrollY && top + height <= scrollY + viewport) return;
        int dest = height >= viewport || top < scrollY ? top : top + height - viewport;
        scroller.scrollTo(0, Math.max(0, dest));
    }

    private ScrollView findVerticalScroller(View target) {
        ViewParent parent = target.getParent();
        while (parent instanceof View) {
            if (parent instanceof ScrollView && !(parent instanceof HorizontalScrollView)) {
                return (ScrollView) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private View ancestorTagged(View target, String tag) {
        View v = target;
        while (v != null) {
            if (tag.equals(v.getTag())) return v;
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) return null;
            v = (View) parent;
        }
        return null;
    }

    private int topWithinContent(View target, ScrollView scroller) {
        int y = 0;
        View v = target;
        View content = scroller.getChildAt(0);
        while (v != null && v != content && v != scroller) {
            y += v.getTop();
            ViewParent parent = v.getParent();
            if (!(parent instanceof View)) break;
            v = (View) parent;
        }
        return y;
    }

    @Override

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            // 搜索框有焦点 → 隐藏键盘并清除搜索
            if (etSearch != null && etSearch.isFocused()) {
                hideKeyboard();
                etSearch.clearFocus();
            }
            // 搜索模式 → 清除搜索
            if (isSearching) {
                clearSearch();
                return true;
            }
            // 媒体库浏览中 → 返回媒体库首页
            if (currentTab == 1 && savedBrowseGuid != null) {
                savedBrowseGuid = null; savedBrowseList = null;
                loadMediaLibraries();
                return true;
            }
            // 剧集选择页 → 返回详情页
            if (showingEpisodes && savedDetailItem != null && savedDetailInfo != null) {
                showingEpisodes = false;
                buildDetailPage(savedDetailItem, savedDetailInfo);
                return true;
            }
            if (!showingOverview) {
                restoreHomeOverview();
                return true;
            }
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                finish();
            } else {
                backPressedTime = System.currentTimeMillis();
                Toast.makeText(this, "再按一次返回桌面", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
