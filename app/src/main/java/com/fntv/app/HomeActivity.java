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
    private TextView tvHomeTitle;
    private View panelMovies, panelLibrary, panelSettings;
    private LinearLayout moviesContainer, libraryContainer;
    private TextView tvMoviesLoading, tvLibraryLoading, tvLibraryEmpty;
    private EditText etSearch;
    private TextView tvLibraryPageTitle;
    private boolean isSearching = false;
    /** 点开搜索时所在的标签。返回时回到这一页，而不是媒体库。 */
    private int searchEntryTab = -1;
    private TextView tvSettingUsername, tvSettingServer, tvDecoderValue, tvDanmuUrl;
    private Button btnLogout, btnFeedback;
    private UpdateManager updateManager;
    private RelativeLayout rlDecoderSetting, rlDanmuSetting, rlSeekStep, rlBufferTime;
    private TextView tvSeekStepValue, tvBufferTimeValue;

    private int currentTab = 0;
    private final List<MediaDbItem> mediaLibraries = new ArrayList<>();
    private boolean showingOverview = true;
    /** 正在切栏目或清掉当前页内容。这段时间焦点乱跳不能再触发切页。 */
    private boolean tabSwitching;
    private boolean showingEpisodes = false;
    private boolean loadingPreviews = false;
    private boolean overviewLoading = false;
    private boolean homeRefreshRequested = false;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout homeSwipeRefresh;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout librarySwipeRefresh;
    private boolean pageRefreshRequested = false;
    private boolean continueLoading = false;
    private boolean continueRefreshPending = false;
    private int recordWaitTicks = 0;
    private boolean liveLoading = false;

    // 媒体库浏览排序状态
    private String currentBrowseGuid;
    private String currentBrowseTitle;
    private LinearLayout currentBrowseContainer;
    private TextView currentBrowseLoading;
    private int libSortColumnIndex = 0; // 0添加日期 1发行日期 2标题 3评分
    private int libSortOrderIndex = 1;  // 0=升序, 1=降序
    private static final String[] LIB_SORT_LABELS = {"按添加日期", "按发行日期", "按标题", "按评分"};
    private static final String[] LIB_SORT_COLUMNS = {"create_time", "release_date", "sort_title", "vote_average"};

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
    /** 离开首页时，滚动位置落在第几个区块、以及区块顶部之上的偏移。-1 表示不用恢复。 */
    private int pendingHomeAnchor = -1;
    private int pendingHomeAnchorOffset;
    private String lastFocusSectionTag;
    private int lastFocusIndexInSection;
    private boolean holdRememberedFocus;
    private Button detailPlayBtn;
    private TextView detailOverview;
    private ViewGroup detailSeasonRow;
    private ViewGroup detailChipRow;
    private ViewGroup detailRangeRow;
    private View detailRangeScroll;
    private TextView detailEpCount;
    private ViewGroup detailEpisodeBox;
    private LinearLayout detailEpisodeHost;
    private TextView detailSeasonLine;
    private boolean showingSeasonEpisodes;
    private PlayListItem detailOpenedSeason;
    private List<PlayListItem> detailSeasons;
    private PlayListItem detailPlayTarget;
    /** 进详情前收起的那一页。返回时原样放回，焦点仍在点开的那一项。 */
    private ParkedPage detailReturn;
    private View pendingReturnFocus;
    private String pendingReturnGuid;
    private LinearLayout pendingReturnContainer;
    private static final int EPISODE_PAGE = 30;

    /** 离开列表进详情时，把这一页的子视图先摘下来。 */
    private static class ParkedPage {
        LinearLayout container;
        final List<View> children = new ArrayList<>();
        int scrollY;
        int tab;
        boolean overview;
        View focus;
        String focusGuid;
        int width;
        ParkedPage under;
    }

    @Override

    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (savedDetailItem != null) {
            restoreDetailPage();
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
        applyTvHomeChrome();

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
        tvHomeTitle = findViewById(R.id.tvHomeTitle);
        moviesContainer = findViewById(R.id.moviesGridContainer);
        savedMoviesPad = new int[] {
                moviesContainer.getPaddingLeft(),
                moviesContainer.getPaddingTop(),
                moviesContainer.getPaddingRight(),
                moviesContainer.getPaddingBottom()
        };
        libraryContainer = findViewById(R.id.libraryGridContainer);
        etSearch = findViewById(R.id.etSearch);
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

        androidx.swiperefreshlayout.widget.SwipeRefreshLayout homeSwipe = findViewById(R.id.homeSwipeRefresh);
        if (homeSwipe != null) {
            homeSwipeRefresh = homeSwipe;
            homeSwipe.setColorSchemeColors(color(R.color.text_primary));
            homeSwipe.setProgressBackgroundColorSchemeColor(color(R.color.bg_card));
            homeSwipe.setOnRefreshListener(this::refreshCurrentPage);
            syncHomeSwipe();
        }
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout librarySwipe = findViewById(R.id.librarySwipeRefresh);
        if (librarySwipe != null) {
            librarySwipeRefresh = librarySwipe;
            librarySwipe.setColorSchemeColors(color(R.color.text_primary));
            librarySwipe.setProgressBackgroundColorSchemeColor(color(R.color.bg_card));
            librarySwipe.setOnRefreshListener(this::refreshCurrentPage);
            syncHomeSwipe();
        }
        View btnLibBack = findViewById(R.id.btnLibBack);
        if (btnLibBack != null) btnLibBack.setOnClickListener(v -> restoreHomeOverview());
        View btnLibBrowseBack = findViewById(R.id.btnLibBrowseBack);
        if (btnLibBrowseBack != null) btnLibBrowseBack.setOnClickListener(v -> loadMediaLibraries());
        View btnLibBrowseSearch = findViewById(R.id.btnLibBrowseSearch);
        if (btnLibBrowseSearch != null) {
            btnLibBrowseSearch.setOnClickListener(v -> {
                if (etSearch != null) {
                    etSearch.setVisibility(View.VISIBLE);
                    etSearch.post(this::focusLibrarySearch);
                }
            });
        }
        View btnHomeRefresh = findViewById(R.id.btnHomeRefresh);
        if (btnHomeRefresh != null) {
            btnHomeRefresh.setOnClickListener(v -> refreshCurrentPage());
        }
        View btnHomeSearch = findViewById(R.id.btnHomeSearch);
        if (btnHomeSearch != null) {
            btnHomeSearch.setOnClickListener(v -> {
                int fromTab = currentTab;
                switchTab(1);
                searchEntryTab = fromTab;
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
            restoreDetailPage();
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
        tabMovies.setOnClickListener(v -> openTab(0, true));
        tabLibrary.setOnClickListener(v -> openTab(1, true));
        tabSettings.setOnClickListener(v -> openTab(2, true));
        if (!isTelevision()) return;
        View.OnFocusChangeListener onTabFocus = (v, hasFocus) -> {
            if (!hasFocus || tabSwitching) return;
            if (v == tabMovies) openTab(0, false);
            else if (v == tabLibrary) openTab(1, false);
            else if (v == tabSettings) openTab(2, false);
        };
        tabMovies.setOnFocusChangeListener(onTabFocus);
        tabLibrary.setOnFocusChangeListener(onTabFocus);
        tabSettings.setOnFocusChangeListener(onTabFocus);
    }

    /** 底部栏目。电视上焦点移入即切换；再按一次「影视」仍回到首页。 */
    private void openTab(int index, boolean fromClick) {
        if (tabSwitching) return;
        if (index == 0 && currentTab == 0 && !showingOverview) {
            if (fromClick) restoreHomeOverview();
            return;
        }
        if (currentTab == index) {
            if (fromClick && index == 1) loadMediaLibraries();
            return;
        }
        switchTab(index);
        if (index == 1) loadMediaLibraries();
    }


    private void switchTab(int index) {
        if (tabSwitching) return;
        tabSwitching = true;
        try {
        int prevTab = currentTab;
        if (prevTab == 0 && index != 0) captureHomeScroll();
        currentTab = index;
        searchEntryTab = -1;
        // 从媒体库切换到其他标签时清除搜索状态
        if (prevTab == 1 && isSearching) clearSearch();
        // 切换标签时清除保存的页面状态，防止横竖屏切回时错误恢复
        savedBrowseList = null; savedBrowseGuid = null;
        tabMovies.setSelected(index == 0);
        tabLibrary.setSelected(index == 1);
        tabSettings.setSelected(index == 2);
        boolean backToHome = index == 0 && prevTab != 0 && !showingOverview;
        setDetailChrome(index == 0 && savedDetailItem != null && !showingOverview && !backToHome);
        View tab = index == 1 ? tabLibrary : index == 2 ? tabSettings : tabMovies;
        if (!isTelevision() && tab != null) tab.requestFocus();
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (backToHome) restoreHomeOverview();
        refreshTabFocusTargets();
        syncHomeSwipe();
        if (isTelevision()) placeTvTabFocus(index);
        } finally {
            tabSwitching = false;
        }
    }

    /** 电视没有底栏，设置在标题栏右侧；手机保持原来的三个底栏按钮。 */
    private void applyTvHomeChrome() {
        if (!isTelevision()) return;
        View tabBar = findViewById(R.id.tabBar);
        View divider = findViewById(R.id.tabDivider);
        if (tabBar != null) tabBar.setVisibility(View.GONE);
        if (divider != null) divider.setVisibility(View.GONE);
        View settings = findViewById(R.id.btnHomeSettings);
        View search = findViewById(R.id.btnHomeSearch);
        if (settings != null) {
            settings.setVisibility(View.VISIBLE);
            settings.setOnClickListener(v -> openTab(2, true));
        }
        if (search != null && settings != null) {
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) search.getLayoutParams();
            lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0);
            lp.addRule(RelativeLayout.LEFT_OF, R.id.btnHomeSettings);
            lp.rightMargin = dp(8);
            search.setLayoutParams(lp);
        }
        if (tvHomeTitle != null) {
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) tvHomeTitle.getLayoutParams();
            lp.rightMargin = dp(148);
            tvHomeTitle.setLayoutParams(lp);
        }
    }

    private void placeTvTabFocus(int index) {
        if (index == 2) {
            View first = rlDecoderSetting;
            if (first != null) first.post(() -> {
                if (currentTab == 2 && isUsableFocus(first)) first.requestFocus();
            });
            return;
        }
        if (index != 0) return;
        View settingsBtn = findViewById(R.id.btnHomeSettings);
        if (settingsBtn == null) return;
        settingsBtn.post(() -> {
            if (currentTab != 0 || !settingsBtn.isShown()) return;
            View focused = getCurrentFocus();
            if (isUsableFocus(focused) && (isHomeHeader(focused) || isInCurrentPanel(focused))) return;
            settingsBtn.requestFocus();
        });
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

    /** 手机可下拉刷新当前页。详情页关掉，避免把页面刷走。 */
    private void syncHomeSwipe() {
        boolean phone = !isTelevision();
        boolean movies = phone && currentTab == 0 && savedDetailItem == null
                && (showingOverview || savedBrowseGuid != null || savedLiveChannelTitle != null);
        if (homeSwipeRefresh != null) {
            homeSwipeRefresh.setEnabled(movies);
            if (!movies) homeSwipeRefresh.setRefreshing(false);
        }
        boolean library = phone && currentTab == 1 && savedDetailItem == null;
        if (librarySwipeRefresh != null) {
            librarySwipeRefresh.setEnabled(library);
            if (!library) librarySwipeRefresh.setRefreshing(false);
        }
    }

    private void finishHomeSwipe() {
        if (homeSwipeRefresh != null) homeSwipeRefresh.setRefreshing(false);
        if (librarySwipeRefresh != null) librarySwipeRefresh.setRefreshing(false);
        syncHomeSwipe();
    }

    private void noteListRefreshDone(boolean ok) {
        if (!pageRefreshRequested) return;
        pageRefreshRequested = false;
        AppToast.show(this, ok ? "已刷新" : "刷新失败");
        finishHomeSwipe();
    }

    /** 刷新当前这一页：首页、某个媒体库，或直播列表。 */
    private void refreshCurrentPage() {
        if (apiManager.getApi() == null || savedDetailItem != null) {
            finishHomeSwipe();
            return;
        }
        if (currentTab == 1) {
            if (isSearching && etSearch != null && etSearch.getText().length() > 0) {
                pageRefreshRequested = true;
                performSearch(etSearch.getText().toString().trim());
                return;
            }
            if (savedBrowseGuid != null) {
                pageRefreshRequested = true;
                reFetchLibraryItems();
                return;
            }
            pageRefreshRequested = true;
            loadMediaLibraries();
            return;
        }
        if (!showingOverview && savedBrowseGuid != null) {
            pageRefreshRequested = true;
            reFetchLibraryItems();
            return;
        }
        if (!showingOverview && savedLiveChannelTitle != null) {
            pageRefreshRequested = true;
            browseLiveChannels();
            return;
        }
        refreshHome();
    }

    /** 重新拉取首页：继续观看、媒体库和各库预览。 */
    private void refreshHome() {
        if (apiManager.getApi() == null || overviewLoading) {
            finishHomeSwipe();
            return;
        }
        homeRefreshRequested = true;
        loadingPreviews = false;
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingSeasonEpisodes = false;
        loadOverview();
    }

    /** 回到影视首页概览（保留已加载的媒体库，并恢复继续观看） */
    private void restoreHomeOverview() {
        detailReturn = null;
        pendingReturnFocus = null;
        pendingReturnGuid = null;
        pendingReturnContainer = null;
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingSeasonEpisodes = false;
        detailOpenedSeason = null;
        detailSeasons = null;
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
                    if (homeRefreshRequested) {
                        homeRefreshRequested = false;
                        AppToast.show(HomeActivity.this, "已刷新");
                    }
                    finishHomeSwipe();
                } else {
                    tvMoviesLoading.setVisibility(View.GONE);
                    if (homeRefreshRequested) {
                        homeRefreshRequested = false;
                        AppToast.show(HomeActivity.this, "刷新失败");
                    }
                    finishHomeSwipe();
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                overviewLoading = false;
                tvMoviesLoading.setVisibility(View.GONE);
                if (homeRefreshRequested) {
                    homeRefreshRequested = false;
                    AppToast.show(HomeActivity.this, "刷新失败");
                }
                finishHomeSwipe();
                Log.e("Overview", "getMediaDbList onFailure: " + t.getMessage() + " t=" + (System.currentTimeMillis() - t0) + "ms");
            }
        });
    }

    /** 构建概览 */

    private void showOverview() {
        tvMoviesLoading.setVisibility(View.GONE);
        savedDetailItem = null; savedDetailInfo = null; savedBrowseList = null; savedBrowseGuid = null;
        setDetailChrome(false);
        clearLibraryBrowseHeader();
        savedLiveChannelTitle = null;
        showingEpisodes = false;
        parkFocusOutside(moviesContainer);
        moviesContainer.removeAllViews();
        showingOverview = true;
        overviewBuilt = true;
        homeEntryFocused = false;
        initialFocusPlaced = false;
        detailPlayBtn = null;
        detailOverview = null;
        detailSeasonRow = null;
        detailChipRow = null;
        detailRangeRow = null;
        detailRangeScroll = null;
        detailEpCount = null;
        detailEpisodeBox = null;
        detailEpisodeHost = null;

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

        if (pendingHomeAnchor >= 0) {
            initialFocusPlaced = true;
            homeEntryFocused = true;
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
        syncHomeSwipe();
        wireOverviewFocus();
        applyPendingHomeScroll();
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
                    applyPendingHomeScroll();
                }
                @Override public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                    clearPreview(guid);
                }
            });
        }
    }

    /** 没有片子时去掉「加载中...」，栏目标题和下面的间隔一并移除。 */
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
            applyPendingHomeScroll();
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
                if (pendingReturnGuid != null) box.post(this::applyReturnFocus);
                break;
            }
        }
    }

    /** 媒体库标题。高亮只包住名字，点击进入该库。 */

    private LinearLayout makeLibHeader(String libGuid, String libTitle, int count) {
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams headerLp = (LinearLayout.LayoutParams) headerRow.getLayoutParams();
        headerLp.topMargin = dp(10);
        headerLp.bottomMargin = dp(2);
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
        header.setText(libTitle);
        header.setTextColor(color(R.color.text_primary));
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(header);

        ImageView arrow = new ImageView(this);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        arrowLp.leftMargin = dp(1);
        arrow.setLayoutParams(arrowLp);
        arrow.setImageResource(R.drawable.ic_chevron_right_bold);
        arrow.setColorFilter(color(R.color.text_primary));
        headerRow.addView(arrow);
        headerRow.setOnFocusChangeListener((v, hasFocus) -> {
            int c = hasFocus ? color(R.color.border_focused) : color(R.color.text_primary);
            header.setTextColor(c);
            arrow.setColorFilter(c);
        });
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(100));
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
        if (item.seasonNumber < 0 && item.episodeNumber <= 0) return "";
        if (item.seasonNumber == 0 && item.episodeNumber <= 0) return "";
        StringBuilder sb = new StringBuilder();
        if (item.seasonNumber == 0 && item.episodeNumber > 0) {
            sb.append("特别篇");
        } else if (item.seasonNumber > 0) {
            sb.append("第").append(item.seasonNumber).append("季");
        }
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
        if (container == moviesContainer) {
            captureHomeScroll();
            showingOverview = false;
            syncHomeSwipe();
        }
        parkFocusOutside(container);

        // 存储当前浏览上下文，排序变化时用于重新加载
        currentBrowseGuid = ancestorGuid;
        currentBrowseTitle = title;
        currentBrowseContainer = container;
        currentBrowseLoading = loadingView;

        container.removeAllViews();
        loadingView.setVisibility(View.VISIBLE);

        int sortIdx = libSortColumnIndex;
        if (sortIdx < 0 || sortIdx >= LIB_SORT_COLUMNS.length) sortIdx = 0;
        String sortColumn = LIB_SORT_COLUMNS[sortIdx];
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
                    showLibraryBrowseHeader(container, title);
                    renderLibraryGrid(container, list, total);
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
                noteListRefreshDone(response.isSuccessful() && response.body() != null
                        && response.body().code == 0);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                loadingView.setVisibility(View.GONE);
                noteListRefreshDone(false);
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
        showLibraryBrowseHeader(container, title);
        renderLibraryGrid(container, list, list == null ? 0 : list.size());
    }

    private void showLibraryBrowseHeader(LinearLayout container, String title) {
        String name = title == null ? "" : title;
        if (container == moviesContainer) {
            View back = findViewById(R.id.btnLibBack);
            View refresh = findViewById(R.id.btnHomeRefresh);
            if (back != null) back.setVisibility(View.VISIBLE);
            if (refresh != null) refresh.setVisibility(View.GONE);
            View logo = findViewById(R.id.ivHomeLogo);
            if (logo != null) logo.setVisibility(View.GONE);
            if (tvHomeTitle != null) {
                tvHomeTitle.setText(name);
                tvHomeTitle.setGravity(Gravity.CENTER);
                android.widget.RelativeLayout.LayoutParams lp =
                        (android.widget.RelativeLayout.LayoutParams) tvHomeTitle.getLayoutParams();
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.leftMargin = dp(48);
                lp.addRule(android.widget.RelativeLayout.RIGHT_OF, 0);
                lp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
                tvHomeTitle.setLayoutParams(lp);
            }
            return;
        }
        View bar = findViewById(R.id.libBrowseBar);
        TextView browseTitle = findViewById(R.id.tvLibBrowseTitle);
        if (bar != null) bar.setVisibility(View.VISIBLE);
        if (browseTitle != null) browseTitle.setText(name);
    }

    private void clearLibraryBrowseHeader() {
        View back = findViewById(R.id.btnLibBack);
        View refresh = findViewById(R.id.btnHomeRefresh);
        if (back != null) back.setVisibility(View.GONE);
        if (refresh != null) refresh.setVisibility(View.VISIBLE);
        View logo = findViewById(R.id.ivHomeLogo);
        if (logo != null) logo.setVisibility(View.VISIBLE);
        if (tvHomeTitle != null) {
            tvHomeTitle.setText("FN TV");
            tvHomeTitle.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.RelativeLayout.LayoutParams lp =
                    (android.widget.RelativeLayout.LayoutParams) tvHomeTitle.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.leftMargin = dp(8);
            lp.addRule(android.widget.RelativeLayout.RIGHT_OF, R.id.ivHomeLogo);
            tvHomeTitle.setLayoutParams(lp);
        }
        View bar = findViewById(R.id.libBrowseBar);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    private void renderLibraryGrid(LinearLayout container, List<PlayListItem> list, int total) {
        container.addView(makeLibraryToolRow(total));
        if (list == null || list.isEmpty()) return;
        float density = getResources().getDisplayMetrics().density;
        int cols = Math.max(3, (int) (getResources().getDisplayMetrics().widthPixels / (120 * density)));
        int posterH = (getResources().getDisplayMetrics().widthPixels / cols) * 3 / 2;
        for (int idx = 0; idx < list.size(); idx += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOrientation(LinearLayout.HORIZONTAL);
            int inRow = Math.min(cols, list.size() - idx);
            for (int c = 0; c < cols && idx + c < list.size(); c++) {
                View card = makeLibraryPoster(list.get(idx + c), posterH);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                card.setLayoutParams(lp);
                row.addView(card);
            }
            for (int e = inRow; e < cols; e++) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
                row.addView(spacer);
            }
            container.addView(row);
            container.addView(makeSpacer(dp(14)));
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

    private View makeLibraryToolRow(int total) {
        LinearLayout bar = new LinearLayout(this);
        bar.setTag("lib_sort_bar");
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(8), dp(10));
        int sortIdx = libSortColumnIndex;
        if (sortIdx < 0 || sortIdx >= LIB_SORT_LABELS.length) sortIdx = 0;
        LinearLayout sort = new LinearLayout(this);
        sort.setOrientation(LinearLayout.HORIZONTAL);
        sort.setGravity(Gravity.CENTER_VERTICAL);
        sort.setFocusable(true);
        sort.setClickable(true);
        sort.setBackgroundResource(R.drawable.bg_text_action);
        sort.setPadding(dp(8), dp(6), dp(4), dp(6));
        TextView sortLabel = new TextView(this);
        sortLabel.setText(LIB_SORT_LABELS[sortIdx]);
        sortLabel.setTextColor(color(R.color.text_secondary));
        sortLabel.setTextSize(14);
        sort.addView(sortLabel);
        AppCompatImageView sortArrow = new AppCompatImageView(this);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        arrowLp.leftMargin = dp(2);
        sortArrow.setLayoutParams(arrowLp);
        sortArrow.setImageResource(R.drawable.ic_chevron_down);
        sortArrow.setContentDescription("排序");
        sort.addView(sortArrow);
        sort.setOnClickListener(v -> showLibrarySortSheet());
        bar.addView(sort);
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(gap);
        TextView count = new TextView(this);
        count.setText(String.valueOf(Math.max(total, 0)));
        count.setTextColor(color(R.color.text_primary));
        count.setTextSize(13);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(dp(36));
        count.setPadding(dp(8), dp(4), dp(8), dp(4));
        count.setBackgroundResource(R.drawable.bg_count_pill);
        bar.addView(count);
        return bar;
    }

    /** 从底部拉出排序。点当前项切换升降序，点其他项切换排序字段。 */
    private void showLibrarySortSheet() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_sort_sheet);
        sheet.setPadding(dp(12), dp(20), dp(12), dp(28));

        TextView title = new TextView(this);
        title.setText("排序");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(14));
        sheet.addView(title);

        int current = libSortColumnIndex;
        if (current < 0 || current >= LIB_SORT_LABELS.length) current = 0;
        View focusTarget = null;
        for (int i = 0; i < LIB_SORT_LABELS.length; i++) {
            final int index = i;
            boolean selected = i == current;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.bg_text_action);

            TextView name = new TextView(this);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            name.setLayoutParams(nameLp);
            name.setText(LIB_SORT_LABELS[i]);
            name.setTextSize(16);
            name.setTextColor(color(selected ? R.color.text_primary : R.color.text_hint));
            row.addView(name);

            if (selected) {
                TextView order = new TextView(this);
                order.setText(libSortOrderIndex == 0 ? "升序排序" : "降序排序");
                order.setTextSize(15);
                order.setTextColor(color(R.color.text_primary));
                row.addView(order);
                AppCompatImageView arrow = new AppCompatImageView(this);
                LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(16), dp(16));
                arrowLp.leftMargin = dp(4);
                arrow.setLayoutParams(arrowLp);
                arrow.setImageResource(R.drawable.ic_chevron_down);
                if (libSortOrderIndex == 0) arrow.setRotation(180f);
                row.addView(arrow);
                focusTarget = row;
            }

            row.setOnClickListener(v -> {
                if (index == libSortColumnIndex) {
                    libSortOrderIndex = libSortOrderIndex == 0 ? 1 : 0;
                } else {
                    libSortColumnIndex = index;
                }
                dialog.dismiss();
                reFetchLibraryItems();
            });
            sheet.addView(row);
        }

        dialog.setContentView(sheet);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        dialog.show();
        if (focusTarget != null) focusTarget.requestFocus();
    }

    private View makeLibraryPoster(PlayListItem item, int posterH) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        FrameLayout posterBox = new FrameLayout(this);
        posterBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, posterH));
        RoundedImageView iv = new RoundedImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(color(R.color.bg_poster));
        iv.setCornerRadius(12);
        String imgUrl = makePosterUrl(item.poster);
        if (imgUrl != null) iv.setTag(imgUrl);
        posterBox.addView(iv);

        String rating = itemRating(item);
        if (!rating.isEmpty()) {
            TextView badge = makeOverlayBadge(rating, Gravity.TOP | Gravity.RIGHT);
            badge.setBackgroundResource(R.drawable.bg_rating_badge);
            badge.setTextColor(0xFF8BE38A);
            posterBox.addView(badge);
        }
        String res = itemResolution(item);
        if (!res.isEmpty()) posterBox.addView(makeOverlayBadge(res, Gravity.BOTTOM | Gravity.RIGHT));
        card.addView(posterBox);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(6);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(13);
        title.setTextColor(color(R.color.text_primary));
        title.setText(item.title != null ? item.title : "未知");
        card.addView(title);

        String year = itemYear(item);
        if (!year.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(year);
            sub.setTextSize(12);
            sub.setTextColor(color(R.color.text_hint));
            sub.setPadding(0, dp(2), 0, 0);
            card.addView(sub);
        }
        card.setOnFocusChangeListener((v, hasFocus) -> {
            title.setSelected(hasFocus);
            title.setTextColor(hasFocus ? color(R.color.border_focused) : color(R.color.text_primary));
        });
        card.setTag(item);
        card.setOnClickListener(v -> onItemClick((PlayListItem) card.getTag()));
        return card;
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
        if (detailReturn == null) {
            LinearLayout from = currentTab == 1 && libraryContainer != null
                    ? libraryContainer : moviesContainer;
            detailReturn = detachPage(from, item);
            if (from != moviesContainer) detailReturn.under = detachPage(moviesContainer, null);
            pendingHomeAnchor = -1;
            holdRememberedFocus = false;
        }
        showingOverview = false;
        showingEpisodes = false;
        showingSeasonEpisodes = false;
        revealTab(0);
        syncHomeSwipe();
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
                    AppToast.show(HomeActivity.this, "获取详情失败");
                    return;
                }
                buildDetailPage(item, response.body().data);
            }
            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                AppToast.show(HomeActivity.this, "网络错误: " + t.getMessage());
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
        detailSeasonRow = null;
        detailChipRow = null;
        detailRangeRow = null;
        detailRangeScroll = null;
        detailEpCount = null;
        detailEpisodeBox = null;
        detailEpisodeHost = null;
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
        if (isSeries) {
            buildSeriesHub(item, info);
            return;
        }

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
        if (isSeries && epNum > 0) playBtn.setText("第" + epNum + "集");
        else playBtn.setText(pTs > 0 ? "继续播放" : "播放");
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
        detailSeasonRow = null;
        detailChipRow = null;
        detailRangeRow = null;
        detailRangeScroll = null;
        detailEpCount = null;
        detailEpisodeBox = null;
        detailEpisodeHost = null;
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

    private void restoreDetailPage() {
        if (showingSeasonEpisodes && detailOpenedSeason != null && detailSeasons != null
                && savedDetailItem != null && savedDetailInfo != null) {
            showSeasonEpisodePage(detailOpenedSeason, detailSeasons);
            return;
        }
        showingSeasonEpisodes = false;
        buildDetailPage(savedDetailItem, savedDetailInfo);
    }

    /** 剧集详情第一级：大标题、播放按钮、季海报。点季卡片再进选集。 */
    private void buildSeriesHub(PlayListItem item, PlayInfoResponse info) {
        showingSeasonEpisodes = false;
        detailOpenedSeason = null;
        detailSeasons = null;
        detailPlayTarget = null;
        detailSeasonLine = null;

        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        if (series.isEmpty() && item.tvTitle != null) series = item.tvTitle;
        String epTitle = info.item != null && info.item.title != null ? info.item.title : item.title;
        String mainTitle = !series.isEmpty() ? series : (epTitle != null ? epTitle : "");
        int epNum = info.item != null ? info.item.episodeNumber : item.episodeNumber;
        int seasonNum = info.item != null ? info.item.seasonNumber : item.seasonNumber;

        String backdropPath = info.getBackdropPath();
        if (backdropPath == null) backdropPath = item.poster;
        String backdropUrl = makeImageUrl(backdropPath, 800);

        int heroH = dp(land ? 240 : 210);
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroH));
        hero.setBackgroundColor(color(R.color.bg_poster));

        ImageView backdrop = new ImageView(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop);
        if (backdropUrl != null) SimpleImageLoader.load(backdropUrl, backdrop, apiManager.getClient());

        View fade = new View(this);
        fade.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fade.setBackgroundResource(R.drawable.bg_detail_fade);
        hero.addView(fade);

        LinearLayout titleBox = new LinearLayout(this);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.BOTTOM;
        titleBox.setLayoutParams(titleLp);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(20), dp(12), dp(20), dp(18));

        TextView titleBig = new TextView(this);
        titleBig.setText(mainTitle.trim());
        titleBig.setTextColor(color(R.color.text_white));
        titleBig.setTextSize(land ? 34 : 30);
        titleBig.setTypeface(Typeface.DEFAULT_BOLD);
        titleBig.setMaxLines(2);
        titleBig.setEllipsize(TextUtils.TruncateAt.END);
        titleBig.setShadowLayer(8, 0, 2, 0xCC000000);
        titleBox.addView(titleBig);

        String meta = seriesMetaLine(info, item);
        if (!meta.isEmpty()) {
            TextView metaTv = new TextView(this);
            metaTv.setPadding(0, dp(8), 0, 0);
            metaTv.setText(meta);
            metaTv.setTextColor(color(R.color.text_secondary));
            metaTv.setTextSize(13);
            metaTv.setSingleLine(true);
            metaTv.setEllipsize(TextUtils.TruncateAt.END);
            titleBox.addView(metaTv);
        }
        hero.addView(titleBox);
        moviesContainer.addView(hero);

        LinearLayout below = newDetailBelow();
        Button playBtn = makeSeriesPlayButton(seasonPlayText(seasonNum, epNum, info.ts > 0 || item.ts > 0));
        below.addView(playBtn);
        addDetailOverview(below, info, item);

        LinearLayout seasonsBox = new LinearLayout(this);
        seasonsBox.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        seasonsBox.setOrientation(LinearLayout.VERTICAL);
        below.addView(seasonsBox);
        moviesContainer.addView(below);

        detailPlayBtn = playBtn;
        String parent = info.parentGuid != null && !info.parentGuid.isEmpty()
                ? info.parentGuid : item.parentGuid;
        long pTs = info.ts > 0 ? info.ts : (item.ts > 0 ? item.ts : 0);
        loadSeasons(seasonsBox, item.guid, item, playBtn, pTs, seasonNum, parent);
        wireDetailNav();
        playBtn.post(playBtn::requestFocus);
    }

    private String seriesMetaLine(PlayInfoResponse info, PlayListItem item) {
        List<String> parts = new ArrayList<>();
        parts.add("影视剧");
        String year = detailYear(info, item);
        if (!year.isEmpty()) parts.add(year);
        if (info.item != null && info.item.productionCountries != null) {
            StringBuilder countries = new StringBuilder();
            for (String c : info.item.productionCountries) {
                if (c == null || c.trim().isEmpty()) continue;
                if (countries.length() > 0) countries.append("·");
                countries.append(c.trim());
            }
            if (countries.length() > 0) parts.add(countries.toString());
        }
        StringBuilder line = new StringBuilder();
        for (String p : parts) {
            if (line.length() > 0) line.append(" / ");
            line.append(p);
        }
        return line.toString();
    }

    private String seasonPlayText(int seasonNum, int epNum, boolean resume) {
        if (seasonNum == 0 && epNum > 0) return "特别篇 集 " + epNum;
        if (seasonNum > 0 && epNum > 0) return "季 " + seasonNum + " 集 " + epNum;
        if (epNum > 0) return "第" + epNum + "集";
        return resume ? "继续播放" : "播放";
    }

    /** 第 0 季和特别篇都显示为特别篇。 */
    private String seasonLabel(PlayListItem season) {
        if (season == null) return "";
        if (season.seasonNumber == 0 || isSpecialSeason(season)) return "特别篇";
        return "第" + season.seasonNumber + "季";
    }

    private boolean isSpecialSeason(PlayListItem season) {
        String title = season.title == null ? "" : season.title.trim();
        String lower = title.toLowerCase();
        return title.contains("特别") || title.contains("特典") || title.contains("番外")
                || lower.contains("special") || lower.equals("sp")
                || lower.contains("ova") || lower.contains("oad");
    }

    private Button makeSeriesPlayButton(String text) {
        Button playBtn = new Button(this);
        playBtn.setId(View.generateViewId());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        playLp.topMargin = dp(4);
        playLp.bottomMargin = dp(12);
        playBtn.setLayoutParams(playLp);
        playBtn.setMinWidth(dp(168));
        playBtn.setPadding(dp(22), 0, dp(22), 0);
        playBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        playBtn.setFocusable(true);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setAllCaps(false);
        playBtn.setTextColor(color(R.color.text_white));
        playBtn.setTextSize(16);
        playBtn.setTypeface(Typeface.DEFAULT_BOLD);
        playBtn.setText(text);
        playBtn.setOnClickListener(v -> launchCurrentDetail());
        return playBtn;
    }

    private void launchCurrentDetail() {
        PlayListItem item = detailPlayTarget != null ? detailPlayTarget : savedDetailItem;
        if (item == null) return;
        long dur = item.duration > 0 ? item.duration : (item.runtime > 0 ? item.runtime * 60L : 0);
        if (detailPlayBtn != null && detailPlayBtn.getTag() instanceof Long) {
            dur = (Long) detailPlayBtn.getTag();
        }
        long ts = 0;
        if (detailPlayTarget == null && savedDetailInfo != null && savedDetailInfo.ts > 0) {
            ts = savedDetailInfo.ts;
        } else if (item.ts > 0) {
            ts = item.ts;
        }
        String tv = item.tvTitle != null && !item.tvTitle.isEmpty() ? item.tvTitle : "";
        launchPlayer(item.guid, item.title, tv, item.episodeNumber, item.poster,
                item.getCategoryLabel(), ts, dur, item.parentGuid);
    }

    private LinearLayout newDetailBelow() {
        LinearLayout below = new LinearLayout(this);
        below.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        below.setOrientation(LinearLayout.VERTICAL);
        below.setPadding(dp(16), dp(8), dp(16), dp(28));
        below.setBackgroundColor(color(R.color.bg_dark));
        return below;
    }

    private void addDetailOverview(LinearLayout below, PlayInfoResponse info, PlayListItem item) {
        String overview = info.item != null && info.item.overview != null
                ? info.item.overview : item.overview;
        if (overview == null || overview.isEmpty()) return;
        final TextView ov = new TextView(this);
        ov.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ov.setText(overview);
        ov.setTextColor(color(R.color.text_secondary));
        ov.setTextSize(14);
        ov.setLineSpacing(6, 1);
        ov.setMaxLines(3);
        ov.setEllipsize(TextUtils.TruncateAt.END);
        ov.setFocusable(true);
        ov.setOnClickListener(v -> {
            boolean collapsed = ov.getMaxLines() == 3;
            ov.setMaxLines(collapsed ? Integer.MAX_VALUE : 3);
            ov.setEllipsize(collapsed ? null : TextUtils.TruncateAt.END);
        });
        ov.setOnFocusChangeListener((v, hasFocus) ->
                ov.setTextColor(hasFocus ? color(R.color.text_primary) : color(R.color.text_secondary)));
        detailOverview = ov;
        below.addView(ov);
        below.addView(makeSpacer(dp(14)));
    }

    /** 剧集详情第二级：某一季的海报、简介和选集。 */
    private void showSeasonEpisodePage(PlayListItem season, List<PlayListItem> seasons) {
        if (savedDetailItem == null || savedDetailInfo == null || season == null) return;
        showingSeasonEpisodes = true;
        detailOpenedSeason = season;
        detailSeasons = seasons;
        detailPlayTarget = null;
        PlayListItem item = savedDetailItem;
        PlayInfoResponse info = savedDetailInfo;

        moviesContainer.removeAllViews();
        detailPlayBtn = null;
        detailOverview = null;
        detailSeasonRow = null;
        detailChipRow = null;
        detailRangeRow = null;
        detailRangeScroll = null;
        detailEpCount = null;
        detailEpisodeBox = null;
        detailEpisodeHost = null;
        detailSeasonLine = null;
        setDetailChrome(true);

        boolean land = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        String series = info.item != null && info.item.tvTitle != null ? info.item.tvTitle : "";
        if (series.isEmpty() && item.tvTitle != null) series = item.tvTitle;
        String mainTitle = !series.isEmpty() ? series
                : (item.title != null ? item.title : "");
        int sn = season.seasonNumber;
        String year = season.airDate != null && season.airDate.length() >= 4
                ? season.airDate.substring(0, 4) : detailYear(info, item);
        String rating = itemRating(season);
        if (rating.isEmpty() && info.item != null) {
            PlayListItem voteHolder = new PlayListItem();
            voteHolder.voteAverage = info.item.voteAverage;
            rating = itemRating(voteHolder);
        }

        String posterPath = season.poster != null && !season.poster.isEmpty()
                ? season.poster : info.getPosterPath();
        if (posterPath == null) posterPath = item.poster;
        String backdropPath = info.getBackdropPath();
        if (backdropPath == null) backdropPath = posterPath;

        int posterW = dp(land ? 132 : 108);
        int posterH = posterW * 3 / 2;
        FrameLayout hero = new FrameLayout(this);
        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, posterH + dp(48)));
        hero.setBackgroundColor(color(R.color.bg_poster));

        ImageView backdrop = new ImageView(this);
        backdrop.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop);
        String backdropUrl = makeImageUrl(backdropPath, 800);
        if (backdropUrl != null) SimpleImageLoader.load(backdropUrl, backdrop, apiManager.getClient());

        View dim = new View(this);
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setBackgroundColor(0x88000000);
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
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        heroRow.setPadding(dp(16), dp(12), dp(16), dp(16));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new LinearLayout.LayoutParams(posterW, posterH));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(8);
        poster.setBackgroundColor(color(R.color.bg_card));
        String posterUrl = makeImageUrl(posterPath, 400);
        if (posterUrl != null) SimpleImageLoader.load(posterUrl, poster, apiManager.getClient());
        heroRow.addView(poster);

        LinearLayout infoCol = new LinearLayout(this);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        infoLp.leftMargin = dp(14);
        infoCol.setLayoutParams(infoLp);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleBig = new TextView(this);
        titleBig.setText(mainTitle.trim());
        titleBig.setTextColor(color(R.color.text_white));
        titleBig.setTextSize(land ? 26 : 22);
        titleBig.setTypeface(Typeface.DEFAULT_BOLD);
        titleBig.setMaxLines(2);
        titleBig.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(titleBig);

        TextView seasonLine = new TextView(this);
        seasonLine.setPadding(0, dp(6), 0, 0);
        seasonLine.setText(seasonLabel(season));
        seasonLine.setTextColor(color(R.color.text_secondary));
        seasonLine.setTextSize(14);
        infoCol.addView(seasonLine);
        detailSeasonLine = seasonLine;

        if (!rating.isEmpty() || !year.isEmpty()) {
            TextView extra = new TextView(this);
            extra.setPadding(0, dp(4), 0, 0);
            extra.setTextColor(color(R.color.rating_gold));
            extra.setTextSize(13);
            extra.setText((rating.isEmpty() ? "" : rating) + (year.isEmpty() ? "" : (rating.isEmpty() ? "" : "   ") + year));
            if (rating.isEmpty()) extra.setTextColor(color(R.color.text_secondary));
            infoCol.addView(extra);
        }
        heroRow.addView(infoCol);
        hero.addView(heroRow);
        moviesContainer.addView(hero);

        LinearLayout below = newDetailBelow();
        int sameSeasonEp = item.seasonNumber == sn ? item.episodeNumber : 0;
        Button playBtn = makeSeriesPlayButton(sameSeasonEp > 0 ? "第" + sameSeasonEp + "集" : "播放");
        below.addView(playBtn);
        addDetailOverview(below, info, item);

        LinearLayout epBox = prepareEpisodeSection(below);
        if (seasons != null && seasons.size() > 1 && detailRangeScroll != null) {
            below.addView(makeSeasonTabRow(seasons, season, epBox, item, playBtn, 0),
                    below.indexOfChild(detailRangeScroll));
        }
        moviesContainer.addView(below);
        detailPlayBtn = playBtn;
        long pTs = info.ts > 0 ? info.ts : item.ts;
        fillSeasonEpisodes(epBox, season.guid, item, playBtn, pTs, 1, sn);
        wireDetailNav();
        playBtn.post(playBtn::requestFocus);
    }

    /** 加载季列表。有季卡片时只展示季，点进后再看集。 */
    private void loadSeasons(final LinearLayout content, final String itemGuid, final PlayListItem item,
                              final Button playBtn, final long pTs, final int preferSeason,
                              final String fallbackSeasonGuid) {
        apiManager.getApi().getSeasonList(itemGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (showingSeasonEpisodes) return;
                if (savedDetailItem == null || item.guid == null || !item.guid.equals(savedDetailItem.guid)) return;
                List<PlayListItem> seasons = (response.isSuccessful() && response.body() != null
                        && response.body().code == 0) ? response.body().data : null;
                if (seasons == null || seasons.isEmpty()) {
                    if (fallbackSeasonGuid != null && !fallbackSeasonGuid.isEmpty()) {
                        LinearLayout epBox = prepareEpisodeSection(content);
                        fillSeasonEpisodes(epBox, fallbackSeasonGuid, item, playBtn, pTs, 1, preferSeason);
                    }
                    return;
                }

                PlayListItem selected = seasons.get(0);
                for (PlayListItem s : seasons) {
                    if (s.seasonNumber == preferSeason) {
                        selected = s;
                        break;
                    }
                }
                detailSeasons = seasons;
                content.addView(makeSeasonPosterRow(seasons, selected));
                wireDetailNav();
            }
            @Override             public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                if (showingSeasonEpisodes) return;
                if (fallbackSeasonGuid != null && !fallbackSeasonGuid.isEmpty()
                        && savedDetailItem != null && item.guid != null
                        && item.guid.equals(savedDetailItem.guid)) {
                    LinearLayout epBox = prepareEpisodeSection(content);
                    fillSeasonEpisodes(epBox, fallbackSeasonGuid, item, playBtn, pTs, 1, preferSeason);
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

    private TextView makePlainLabel(String text) {
        TextView h = new TextView(this);
        h.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        h.setPadding(0, dp(10), 0, dp(8));
        h.setText(text);
        h.setTextColor(color(R.color.text_primary));
        h.setTextSize(15);
        return h;
    }

    private void applySeriesPlayLabel(Button playBtn, int seasonCount, int seasonNum, int epNum) {
        if (playBtn == null || epNum <= 0) return;
        if (seasonCount > 1 && seasonNum > 0) playBtn.setText("季 " + seasonNum + " 集 " + epNum);
        else playBtn.setText("第" + epNum + "集");
    }

    /** 选集标题、分段条和剧集横滑区。分段条插在标题和剧集之间。 */
    private LinearLayout prepareEpisodeSection(LinearLayout content) {
        LinearLayout header = new LinearLayout(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(6));

        TextView title = new TextView(this);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title.setText("选集");
        title.setTextColor(color(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        TextView count = new TextView(this);
        count.setTextColor(color(R.color.text_secondary));
        count.setTextSize(14);
        header.addView(count);
        detailEpCount = count;
        content.addView(header);

        HorizontalScrollView rangeScroll = makeHsv();
        LinearLayout.LayoutParams rangeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rangeLp.bottomMargin = dp(8);
        rangeScroll.setLayoutParams(rangeLp);
        rangeScroll.setVisibility(View.GONE);
        LinearLayout rangeBar = new LinearLayout(this);
        rangeBar.setOrientation(LinearLayout.HORIZONTAL);
        rangeBar.setGravity(Gravity.CENTER_VERTICAL);
        rangeBar.setMinimumHeight(dp(40));
        rangeBar.setBackgroundResource(R.drawable.bg_episode_range_bar);
        rangeBar.setPadding(dp(4), 0, dp(4), 0);
        rangeScroll.addView(rangeBar);
        content.addView(rangeScroll);
        detailRangeScroll = rangeScroll;
        detailRangeRow = rangeBar;

        LinearLayout epBox = newEpisodeBox();
        content.addView(epBox);
        detailEpisodeHost = epBox;
        detailEpisodeBox = epBox;
        return epBox;
    }

    private View makeSeasonPosterRow(List<PlayListItem> seasons, PlayListItem selected) {
        HorizontalScrollView hsv = makeHsv();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 0, 0, dp(4));
        final List<View> cards = new ArrayList<>();
        for (final PlayListItem season : seasons) {
            View card = makeSeasonPosterCard(season);
            card.setOnClickListener(v -> showSeasonEpisodePage(season, seasons));
            cards.add(card);
            row.addView(card);
        }
        hsv.addView(row);
        detailSeasonRow = row;
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
        return hsv;
    }

    private View makeSeasonPosterCard(PlayListItem season) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        card.setLayoutParams(lp);

        FrameLayout shot = new FrameLayout(this);
        shot.setLayoutParams(new LinearLayout.LayoutParams(dp(112), dp(158)));
        shot.setBackgroundResource(R.drawable.bg_season_poster);
        shot.setDuplicateParentStateEnabled(true);
        shot.setPadding(dp(2), dp(2), dp(2), dp(2));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(8);
        poster.setBackgroundColor(color(R.color.bg_poster));
        String img = makePosterUrl(season.poster);
        if (img != null) poster.setTag(img);
        shot.addView(poster);

        String rating = itemRating(season);
        if (!rating.isEmpty()) shot.addView(makeOverlayBadge(rating, Gravity.TOP | Gravity.LEFT));
        String res = shortResolution(season);
        if (!res.isEmpty()) shot.addView(makeOverlayBadge(res, Gravity.BOTTOM | Gravity.RIGHT));
        card.addView(shot);

        TextView name = new TextView(this);
        name.setPadding(0, dp(6), 0, 0);
        name.setText(seasonLabel(season));
        name.setTextColor(color(R.color.text_primary));
        name.setTextSize(14);
        name.setGravity(Gravity.CENTER);
        card.addView(name);

        int eps = season.localNumberOfEpisodes > 0 ? season.localNumberOfEpisodes : season.numberOfEpisodes;
        String year = season.airDate != null && season.airDate.length() >= 4 ? season.airDate.substring(0, 4) : "";
        String sub = "";
        if (eps > 0) sub = "共" + eps + "集";
        if (!year.isEmpty()) sub = sub.isEmpty() ? year : sub + " · " + year;
        if (!sub.isEmpty()) {
            TextView meta = new TextView(this);
            meta.setText(sub);
            meta.setTextColor(color(R.color.text_hint));
            meta.setTextSize(12);
            meta.setGravity(Gravity.CENTER);
            meta.setPadding(0, dp(2), 0, 0);
            card.addView(meta);
        }
        card.setTag(season.guid);
        return card;
    }

    private View makeSeasonTabRow(List<PlayListItem> seasons, PlayListItem selected,
                                  LinearLayout epBox, PlayListItem item, Button playBtn, long pTs) {
        HorizontalScrollView hsv = makeHsv();
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsvLp.bottomMargin = dp(6);
        hsv.setLayoutParams(hsvLp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final List<TextView> tabs = new ArrayList<>();
        for (int i = 0; i < seasons.size(); i++) {
            final PlayListItem season = seasons.get(i);
            if (i > 0) {
                TextView slash = new TextView(this);
                slash.setText("  /  ");
                slash.setTextColor(color(R.color.text_hint));
                slash.setTextSize(14);
                row.addView(slash);
            }
            TextView tab = new TextView(this);
            tab.setText(seasonLabel(season));
            tab.setTextSize(15);
            tab.setPadding(dp(4), dp(6), dp(4), dp(6));
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setTag(season.guid);
            boolean on = season.guid != null && season.guid.equals(selected.guid);
            styleSeasonTab(tab, on);
            tab.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) tab.setTextColor(color(R.color.border_focused));
                else styleSeasonTab(tab, tab.isSelected());
            });
            tab.setOnClickListener(v -> {
                for (TextView t : tabs) styleSeasonTab(t, false);
                styleSeasonTab(tab, true);
                detailOpenedSeason = season;
                int sn = season.seasonNumber;
                if (detailSeasonLine != null) detailSeasonLine.setText(seasonLabel(season));
                applySeriesPlayLabel(playBtn, 1, sn, item.episodeNumber);
                fillSeasonEpisodes(epBox, season.guid, item, playBtn, pTs, 1, sn);
            });
            tabs.add(tab);
            row.addView(tab);
        }
        hsv.addView(row);
        detailChipRow = row;
        return hsv;
    }

    private void styleSeasonTabs(PlayListItem season) {
        if (detailChipRow == null || season == null) return;
        for (int i = 0; i < detailChipRow.getChildCount(); i++) {
            View child = detailChipRow.getChildAt(i);
            if (child instanceof TextView && child.getTag() instanceof String) {
                styleSeasonTab((TextView) child, season.guid != null && season.guid.equals(child.getTag()));
            }
        }
        if (detailSeasonRow != null) {
            for (int i = 0; i < detailSeasonRow.getChildCount(); i++) {
                detailSeasonRow.getChildAt(i).setSelected(false);
            }
        }
    }

    private void styleSeasonTab(TextView tab, boolean selected) {
        tab.setSelected(selected);
        tab.setTextColor(selected ? color(R.color.colorPrimary) : color(R.color.text_secondary));
        tab.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void fillSeasonEpisodes(final LinearLayout epBox, final String seasonGuid,
                                    final PlayListItem item, final Button playBtn, final long pTs,
                                    final int seasonCount, final int seasonNum) {
        if (epBox == null || seasonGuid == null || seasonGuid.isEmpty()) return;
        epBox.removeAllViews();
        TextView loading = new TextView(this);
        loading.setTextColor(color(R.color.text_hint));
        loading.setTextSize(13);
        loading.setText("加载剧集...");
        epBox.addView(loading);
        if (detailRangeScroll != null) detailRangeScroll.setVisibility(View.GONE);
        if (detailRangeRow != null) detailRangeRow.setVisibility(View.GONE);

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
                    if (detailEpCount != null) detailEpCount.setText("");
                    return;
                }
                final List<PlayListItem> episodes = response.body().data;
                if (detailEpCount != null) detailEpCount.setText("共" + episodes.size() + " >");
                int currentPage = 0;
                int currentEp = item.episodeNumber;
                for (int i = 0; i < episodes.size(); i++) {
                    PlayListItem ep = episodes.get(i);
                    if (ep.guid != null && ep.guid.equals(item.guid)) {
                        currentPage = episodePageOf(ep, i);
                        currentEp = episodeDisplayNumber(ep, i);
                        break;
                    }
                }
                PlayListItem pick = episodes.get(0);
                for (PlayListItem ep : episodes) {
                    if (ep.guid != null && item.guid != null && ep.guid.equals(item.guid)) {
                        pick = ep;
                        break;
                    }
                }
                if (showingSeasonEpisodes) {
                    detailPlayTarget = pick;
                    if (pick.episodeNumber > 0) currentEp = pick.episodeNumber;
                }
                applySeriesPlayLabel(playBtn, showingSeasonEpisodes ? 1 : seasonCount, seasonNum, currentEp);
                buildEpisodeRanges(epBox, episodes, currentPage, item, playBtn);
                showEpisodePage(epBox, episodes, currentPage, item, playBtn);
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

    private void buildEpisodeRanges(final LinearLayout epBox, final List<PlayListItem> episodes,
                                    final int currentPage, final PlayListItem item, final Button playBtn) {
        if (detailRangeRow == null || !(detailRangeRow instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) detailRangeRow;
        bar.removeAllViews();
        int pages = Math.max(1, (episodeMaxNumber(episodes) + EPISODE_PAGE - 1) / EPISODE_PAGE);
        if (pages <= 1 || detailRangeScroll == null) {
            if (detailRangeScroll != null) detailRangeScroll.setVisibility(View.GONE);
            bar.setVisibility(View.GONE);
            return;
        }
        detailRangeScroll.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        int maxNum = episodeMaxNumber(episodes);
        final List<TextView> chips = new ArrayList<>();
        final int[] pageHolder = {currentPage};
        for (int p = 0; p < pages; p++) {
            int start = p * EPISODE_PAGE + 1;
            int end = Math.min((p + 1) * EPISODE_PAGE, maxNum);
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            chip.setLayoutParams(lp);
            chip.setMinWidth(dp(72));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setText(start + "-" + end);
            chip.setTextSize(13);
            chip.setFocusable(true);
            chip.setBackgroundResource(R.drawable.bg_episode_chip);
            final int page = p;
            chip.setOnClickListener(v -> selectEpisodeRange(chips, pageHolder, page, epBox, episodes, item, playBtn));
            chip.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && isTelevision()) {
                    selectEpisodeRange(chips, pageHolder, page, epBox, episodes, item, playBtn);
                }
            });
            chips.add(chip);
            bar.addView(chip);
        }
        styleEpisodeRangeChips(chips, currentPage);
    }

    private void selectEpisodeRange(List<TextView> chips, int[] pageHolder, int page,
                                    LinearLayout epBox, List<PlayListItem> episodes,
                                    PlayListItem item, Button playBtn) {
        if (pageHolder[0] == page) return;
        pageHolder[0] = page;
        styleEpisodeRangeChips(chips, page);
        showEpisodePage(epBox, episodes, page, item, playBtn);
    }

    private boolean isTelevision() {
        int mode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        if (mode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private void styleEpisodeRangeChips(List<TextView> chips, int page) {
        for (int i = 0; i < chips.size(); i++) {
            TextView chip = chips.get(i);
            boolean on = i == page;
            chip.setSelected(on);
            chip.setTextColor(on ? color(R.color.text_primary) : color(R.color.text_secondary));
        }
    }

    private void showEpisodePage(LinearLayout epBox, List<PlayListItem> episodes, int page,
                                 PlayListItem item, Button playBtn) {
        epBox.removeAllViews();
        HorizontalScrollView hsv = makeHsv();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(4));
        View currentView = null;
        for (int i = 0; i < episodes.size(); i++) {
            PlayListItem ep = episodes.get(i);
            if (episodePageOf(ep, i) != page) continue;
            boolean current = ep.guid != null && ep.guid.equals(item.guid);
            View card = makeDetailEpisodeCard(ep, i, current);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(12);
            card.setLayoutParams(lp);
            row.addView(card);
            if (current) {
                currentView = card;
                if (ep.duration > 0 && playBtn != null) playBtn.setTag(ep.duration);
            }
        }
        hsv.addView(row);
        epBox.addView(hsv);
        detailEpisodeBox = row;
        new Handler(Looper.getMainLooper()).post(() -> loadImagesLazily(hsv, 0));
        if (currentView != null) {
            View target = currentView;
            target.post(() -> target.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, Math.max(target.getWidth(), 1), Math.max(target.getHeight(), 1)),
                    false));
        }
        wireDetailNav();
    }

    private int episodeDisplayNumber(PlayListItem ep, int index) {
        return ep.episodeNumber > 0 ? ep.episodeNumber : index + 1;
    }

    private int episodePageOf(PlayListItem ep, int index) {
        return Math.max(0, (episodeDisplayNumber(ep, index) - 1) / EPISODE_PAGE);
    }

    private int episodeMaxNumber(List<PlayListItem> episodes) {
        int max = episodes.size();
        for (int i = 0; i < episodes.size(); i++) {
            max = Math.max(max, episodeDisplayNumber(episodes.get(i), i));
        }
        return Math.max(1, max);
    }

    private String shortResolution(PlayListItem item) {
        if (item == null || item.mediaStream == null || item.mediaStream.resolutions == null
                || item.mediaStream.resolutions.isEmpty() || item.mediaStream.resolutions.get(0) == null) {
            return "";
        }
        String u = item.mediaStream.resolutions.get(0).toUpperCase();
        if (u.contains("2160") || u.contains("4K") || u.contains("UHD")) return "4K";
        if (u.contains("1080")) return "1080";
        if (u.contains("720")) return "720";
        return "";
    }

    private String formatDurationWords(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return h + "小时" + m + "分钟" + s + "秒";
        if (m > 0) return m + "分钟" + s + "秒";
        return s + "秒";
    }

    private View makeDetailEpisodeCard(PlayListItem ep, int index, boolean isCurrent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_poster_card);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));
        card.setFocusable(true);

        FrameLayout shot = new FrameLayout(this);
        shot.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(112)));

        RoundedImageView poster = new RoundedImageView(this);
        poster.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setCornerRadius(8);
        poster.setBackgroundColor(color(R.color.bg_poster));
        String imgUrl = makePosterUrl(ep.poster);
        if (imgUrl != null) poster.setTag(imgUrl);
        shot.addView(poster);

        String res = shortResolution(ep);
        if (!res.isEmpty()) shot.addView(makeOverlayBadge(res, Gravity.BOTTOM | Gravity.RIGHT));

        if (ep.duration > 0 && ep.ts > 0) {
            int pct = Math.max(0, Math.min(100, (int) (ep.ts * 100 / ep.duration)));
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
        }
        card.addView(shot);

        int num = episodeDisplayNumber(ep, index);
        String name = ep.title != null ? ep.title : "";
        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(6);
        title.setLayoutParams(titleLp);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setText(name.isEmpty() ? String.valueOf(num) : num + "." + name);
        card.addView(title);

        if (ep.overview != null && !ep.overview.isEmpty()) {
            TextView ov = new TextView(this);
            ov.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ov.setText(ep.overview);
            ov.setTextColor(color(R.color.text_hint));
            ov.setTextSize(12);
            ov.setMaxLines(2);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setPadding(0, dp(2), 0, 0);
            card.addView(ov);
        }

        long durSec = ep.duration > 0 ? ep.duration : (ep.runtime > 0 ? ep.runtime * 60L : 0);
        String durText = formatDurationWords(durSec);
        if (!durText.isEmpty()) {
            TextView dur = new TextView(this);
            dur.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dur.setTextSize(12);
            dur.setTextColor(color(R.color.text_secondary));
            dur.setPadding(0, dp(2), 0, 0);
            dur.setText(durText);
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
        h.setText(seasonNumber == 0 ? "特别篇" : "第 " + seasonNumber + " 季");
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
            int sn = ep.seasonNumber;
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
        View browseBar = findViewById(R.id.libBrowseBar);
        if (browseBar != null) browseBar.setVisibility(View.GONE);
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
                    noteListRefreshDone(true);
                    return;
                }
                tvLibraryEmpty.setVisibility(View.VISIBLE);
                noteListRefreshDone(false);
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText("加载失败: " + t.getMessage());
                tvLibraryEmpty.setVisibility(View.VISIBLE);
                noteListRefreshDone(false);
            }
        });
    }

    // ==================== 搜索 ====================

    private void setupSearch() {
        if (etSearch == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 电视遥控器移到搜索框时不弹键盘；手机第一次点就要能输入
            etSearch.setShowSoftInputOnFocus(!isTelevision());
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
        etSearch.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && searchEntryTab < 0) searchEntryTab = currentTab;
            if (hasFocus && !isTelevision()) v.post(this::showLibraryKeyboard);
        });
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
        if (etSearch == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    /** 离开搜索，回到点开搜索框之前的那一页。 */
    private void leaveSearch() {
        int entry = searchEntryTab;
        searchEntryTab = -1;
        if (etSearch != null && etSearch.isFocused()) {
            hideKeyboard();
            etSearch.clearFocus();
        }
        boolean wasSearching = isSearching;
        if (wasSearching) {
            isSearching = false;
            if (etSearch != null) {
                etSearch.setText("");
                etSearch.setVisibility(View.VISIBLE);
            }
        }
        if (entry >= 0 && entry != currentTab) {
            revealTab(entry);
            if (wasSearching) loadMediaLibraries();
            if (entry == 0) {
                View searchBtn = findViewById(R.id.btnHomeSearch);
                if (searchBtn != null && searchBtn.getVisibility() == View.VISIBLE) {
                    searchBtn.post(searchBtn::requestFocus);
                }
            }
            return;
        }
        if (wasSearching) loadMediaLibraries();
    }

    /** 只切可见页，不重置首页正在看的列表或详情。 */
    private void revealTab(int index) {
        currentTab = index;
        panelMovies.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabMovies.setSelected(index == 0);
        tabLibrary.setSelected(index == 1);
        tabSettings.setSelected(index == 2);
        setDetailChrome(index == 0 && savedDetailItem != null && !showingOverview);
        refreshTabFocusTargets();
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
                noteListRefreshDone(true);
            }

            @Override
            public void onEmpty() {
                tvLibraryLoading.setVisibility(View.GONE);
                showSearchEmpty();
                noteListRefreshDone(true);
            }

            @Override
            public void onError(String msg) {
                tvLibraryLoading.setVisibility(View.GONE);
                tvLibraryEmpty.setText(msg);
                tvLibraryEmpty.setVisibility(View.VISIBLE);
                noteListRefreshDone(false);
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
            group.addView(makeLibRow(libs.get(i)));
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

    private View makeLibRow(MediaDbItem lib) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(18), dp(8), dp(16), dp(8));
        row.setFocusable(true);

        AppCompatImageView icon = new AppCompatImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        icon.setImageResource(isMovieLibrary(lib) ? R.drawable.ic_lib_movie : R.drawable.ic_lib_tv);
        row.addView(icon);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp(10);
        title.setLayoutParams(titleLp);
        title.setText(lib.title != null ? lib.title : "");
        title.setTextColor(color(R.color.text_primary));
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.55f));
        title.setPadding(dp(8), dp(4), dp(8), dp(4));
        title.setBackgroundResource(R.drawable.bg_text_action);
        title.setDuplicateParentStateEnabled(true);
        row.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);

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
        rlDanmuSetting.setOnClickListener(v -> showSettingInput(
                "弹幕服务器地址",
                "例如 http://192.168.1.1:9321",
                tvDanmuUrl.getText().toString(),
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI,
                "重置",
                false,
                value -> {
                    if (!value.isEmpty()) {
                        prefs.edit().putString("danmu_url", value).apply();
                        tvDanmuUrl.setText(value);
                    }
                },
                () -> {
                    prefs.edit().remove("danmu_url").apply();
                    String host = prefs.getString("host", "");
                    host = host.replaceAll("^https?://", "").replaceAll("/.*$", "").replaceAll(":\\d+$", "");
                    tvDanmuUrl.setText("http://" + host + ":9321");
                }));

        // 快进退步长
        final int[] savedStep = {prefs.getInt("seek_step", 10)};
        tvSeekStepValue.setText(savedStep[0] + "s");
        rlSeekStep.setOnClickListener(v -> showSettingInput(
                "快进退步长",
                "单位秒，范围 1–300",
                String.valueOf(savedStep[0]),
                android.text.InputType.TYPE_CLASS_NUMBER,
                "取消",
                true,
                value -> {
                    try {
                        int val = Integer.parseInt(value);
                        if (val < 1) val = 1;
                        if (val > 300) val = 300;
                        prefs.edit().putInt("seek_step", val).apply();
                        tvSeekStepValue.setText(val + "s");
                        savedStep[0] = val;
                    } catch (Exception ignored) {}
                },
                null));

        // 缓冲时间
        final int[] savedBuffer = {prefs.getInt("buffer_time", 30)};
        tvBufferTimeValue.setText(savedBuffer[0] + "s");
        rlBufferTime.setOnClickListener(v -> showSettingInput(
                "缓冲时间",
                "单位秒，范围 5–300",
                String.valueOf(savedBuffer[0]),
                android.text.InputType.TYPE_CLASS_NUMBER,
                "取消",
                true,
                value -> {
                    try {
                        int val = Integer.parseInt(value);
                        if (val < 5) val = 5;
                        if (val > 300) val = 300;
                        prefs.edit().putInt("buffer_time", val).apply();
                        tvBufferTimeValue.setText(val + "s");
                        savedBuffer[0] = val;
                    } catch (Exception ignored) {}
                },
                null));

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


    private void showSettingInput(String title, String hint, String value, int inputType,
                                  String negativeText, boolean selectAll,
                                  final SettingInputCallback onSave, final Runnable onNegative) {
        final android.app.Dialog dialog = new android.app.Dialog(
                this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_setting_input);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
            int max = dp(440);
            if (width > max) width = max;
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        TextView tvTitle = dialog.findViewById(R.id.tv_setting_title);
        TextView tvHint = dialog.findViewById(R.id.tv_setting_hint);
        final EditText input = dialog.findViewById(R.id.et_setting_input);
        Button negative = dialog.findViewById(R.id.btn_setting_negative);
        Button positive = dialog.findViewById(R.id.btn_setting_positive);

        tvTitle.setText(title);
        if (hint == null || hint.isEmpty()) {
            tvHint.setVisibility(View.GONE);
        } else {
            tvHint.setText(hint);
        }
        input.setInputType(inputType);
        input.setText(value == null ? "" : value);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                positive.performClick();
                return true;
            }
            return false;
        });
        negative.setText(negativeText);
        negative.setOnClickListener(v -> {
            dialog.dismiss();
            if (onNegative != null) onNegative.run();
        });
        positive.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            dialog.dismiss();
            if (onSave != null) onSave.onResult(text);
        });
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            if (selectAll) input.selectAll();
            else input.setSelection(input.getText().length());
        });
        dialog.show();
    }


    private interface SettingInputCallback {
        void onResult(String value);
    }


    private void toggleDecoder() {
        String cur = prefs.getString(PREF_DECODER, "hardware");
        if ("hardware".equals(cur)) {
            prefs.edit().putString(PREF_DECODER, "software").apply();
            tvDecoderValue.setText("软解");
            AppToast.show(this, "解码: 软解 (CPU)");
        } else {
            prefs.edit().putString(PREF_DECODER, "hardware").apply();
            tvDecoderValue.setText("硬解");
            AppToast.show(this, "解码: 硬解 (GPU)");
        }
    }


    // ==================== 问题反馈 ====================


    private void setupFeedback() {
        final String issuesUrl = "https://github.com/lxlry/fnos_tv_danmu/issues";
        btnFeedback.setOnClickListener(v -> {
            final android.app.Dialog dialog = new android.app.Dialog(
                    this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.bg_card);
            root.setPadding(dp(20), dp(18), dp(20), dp(16));

            TextView title = new TextView(this);
            title.setText("问题反馈");
            title.setTextColor(color(R.color.text_primary));
            title.setTextSize(18);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            root.addView(title);

            TextView message = new TextView(this);
            message.setText("如有问题或建议，请到 GitHub 提交 Issue：\n" + issuesUrl);
            message.setTextColor(color(R.color.text_secondary));
            message.setTextSize(13);
            LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            messageLp.topMargin = dp(6);
            message.setLayoutParams(messageLp);
            root.addView(message);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionsLp.topMargin = dp(16);
            actions.setLayoutParams(actionsLp);

            Button close = new Button(this);
            close.setText("关闭");
            close.setAllCaps(false);
            close.setTextSize(15);
            close.setTextColor(color(R.color.text_secondary));
            close.setBackgroundResource(R.drawable.bg_chip);
            close.setFocusable(true);
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            closeLp.rightMargin = dp(8);
            close.setLayoutParams(closeLp);
            close.setOnClickListener(view -> dialog.dismiss());

            Button copy = new Button(this);
            copy.setText("复制链接");
            copy.setAllCaps(false);
            copy.setTextSize(15);
            copy.setTextColor(color(R.color.text_white));
            copy.setTypeface(null, android.graphics.Typeface.BOLD);
            copy.setBackgroundResource(R.drawable.bg_btn_primary);
            copy.setFocusable(true);
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            copyLp.leftMargin = dp(8);
            copy.setLayoutParams(copyLp);
            copy.setOnClickListener(view -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setText(issuesUrl);
                AppToast.show(this, "链接已复制");
                dialog.dismiss();
            });

            actions.addView(close);
            actions.addView(copy);
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
            copy.requestFocus();
        });
    }


    // ==================== 登出 ====================


    private void setupLogout() {
        btnLogout.setOnClickListener(v -> logout());
    }


    private void logout() {
        apiManager.setToken(null);
        prefs.edit().remove("auth_token").apply();
        AppToast.show(this, "已退出");
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("skip_auto_login", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }


    // ==================== 工具 ====================


    private void clearContainer(LinearLayout c, TextView l, TextView e) {
        parkFocusOutside(c);
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

    /** 已缓存的海报立刻贴上，没缓存的再去下载。 */
    private void loadImagesLazily(ViewGroup container, int index) {
        List<ImageView> targets = new ArrayList<>();
        collectImageViews(container, targets);
        if (targets.isEmpty() || index >= targets.size()) return;

        for (int i = index; i < targets.size(); i++) {
            ImageView iv = targets.get(i);
            Object tag = iv.getTag();
            if (!(tag instanceof String)) continue;
            String url = (String) tag;
            if (!url.startsWith("http")) continue;
            if (SimpleImageLoader.isMemoryCached(url)) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
        }
        for (int i = index; i < targets.size(); i++) {
            ImageView iv = targets.get(i);
            Object tag = iv.getTag();
            if (!(tag instanceof String)) continue;
            String url = (String) tag;
            if (url.startsWith("http") && !SimpleImageLoader.isMemoryCached(url)) {
                SimpleImageLoader.load(url, iv, apiManager.getClient());
            }
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
        syncHomeSwipe();
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
                    noteListRefreshDone(true);
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                int total = response.body().data.total;
                Log.d("LiveChannel", "直播频道查看全部: total=" + total + " items=" + list.size()
                        + " resp=" + new com.google.gson.Gson().toJson(response.body()));
                renderLiveChannelGrid(list, total);
                noteListRefreshDone(true);
            }
            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                tvMoviesLoading.setVisibility(View.GONE);
                AppToast.show(HomeActivity.this, "加载直播频道失败");
                noteListRefreshDone(false);
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

    private boolean isHomeHeader(View v) {
        if (v == null) return false;
        int id = v.getId();
        return id == R.id.btnHomeRefresh || id == R.id.btnHomeSearch
                || id == R.id.btnHomeSettings || id == R.id.btnLibBack;
    }

    private List<View> homeHeaderButtons() {
        List<View> row = new ArrayList<>();
        View back = findViewById(R.id.btnLibBack);
        View refresh = findViewById(R.id.btnHomeRefresh);
        View search = findViewById(R.id.btnHomeSearch);
        View settings = findViewById(R.id.btnHomeSettings);
        if (back != null && back.getVisibility() == View.VISIBLE) row.add(back);
        if (refresh != null && refresh.getVisibility() == View.VISIBLE) row.add(refresh);
        if (search != null && search.getVisibility() == View.VISIBLE) row.add(search);
        if (settings != null && settings.getVisibility() == View.VISIBLE) row.add(settings);
        return row;
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
        if (!isTabBar(next) && !isInCurrentPanel(next) && !isHomeHeader(next)) {
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
            } else if (!isTelevision()) {
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
        if (pendingReturnFocus != null || pendingReturnGuid != null) return;
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

    /** 清掉一块内容前，先把焦点移出这块，避免焦点掉进其他栏目把页面切走。 */
    private void parkFocusOutside(View container) {
        View focused = getCurrentFocus();
        if (container == null || focused == null || !isDescendantOf(focused, container)) return;
        View park = currentTab == 1 ? tabLibrary : currentTab == 2 ? tabSettings : tabMovies;
        if (isTelevision()) {
            View settingsBtn = findViewById(R.id.btnHomeSettings);
            if (settingsBtn != null && settingsBtn.isShown()) park = settingsBtn;
            else if (park == null || !park.isShown()) return;
        }
        if (park == null) return;
        boolean locked = tabSwitching;
        tabSwitching = true;
        try {
            park.requestFocus();
        } finally {
            if (!locked) tabSwitching = false;
        }
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
        List<View> headerBtns = container == moviesContainer
                ? homeHeaderButtons() : Collections.emptyList();
        if (!headerBtns.isEmpty()) {
            TvFocus.bindRow(headerBtns);
        }
        if (!sortBtns.isEmpty()) {
            if (container == libraryContainer && etSearch != null
                    && etSearch.getVisibility() == View.VISIBLE) {
                TvFocus.stay(etSearch);
                TvFocus.bindVertical(TvFocus.listOf(etSearch), sortBtns);
            } else if (!headerBtns.isEmpty()) {
                TvFocus.bindVertical(headerBtns, sortBtns);
            } else {
                for (View v : sortBtns) TvFocus.point(v, View.FOCUS_UP, v);
            }
        } else if (!headerBtns.isEmpty() && !rows.isEmpty()) {
            TvFocus.bindVertical(headerBtns, rows.get(0));
        } else if (upTarget == etSearch && etSearch != null) {
            TvFocus.stay(etSearch);
            if (!rows.isEmpty()) {
                TvFocus.bindVertical(TvFocus.listOf(etSearch), rows.get(0));
            } else if (!isTelevision()) {
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
        TvFocus.sealAll(headerBtns);
        TvFocus.seal(etSearch);
        applyReturnFocus();
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
        List<View> headerBtns = homeHeaderButtons();
        View searchBtn = headerBtns.isEmpty() ? null : headerBtns.get(0);
        if (!headerBtns.isEmpty()) {
            TvFocus.bindRow(headerBtns);
            lanes.add(headerBtns);
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
        if (!headerBtns.isEmpty() && lanes.size() > 1 && lanes.get(0) == headerBtns) {
            TvFocus.bindVertical(headerBtns, lanes.get(1));
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
        TvFocus.sealAll(headerBtns);
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

    private ScrollView moviesScroller() {
        return verticalScroller(moviesContainer);
    }

    private ScrollView verticalScroller(View container) {
        ViewParent parent = container != null ? container.getParent() : null;
        while (parent != null) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private ParkedPage detachPage(LinearLayout container, PlayListItem focusItem) {
        ParkedPage page = new ParkedPage();
        page.container = container;
        page.tab = currentTab;
        page.overview = showingOverview && container == moviesContainer;
        page.width = getResources().getDisplayMetrics().widthPixels;
        page.focusGuid = focusItem != null ? focusItem.guid : null;
        ScrollView scroller = verticalScroller(container);
        page.scrollY = scroller != null ? scroller.getScrollY() : 0;
        View focused = getCurrentFocus();
        if (focused != null && container != null && isDescendantOf(focused, container)) {
            page.focus = focused;
        }
        if (container == null) return page;
        while (container.getChildCount() > 0) {
            View child = container.getChildAt(0);
            container.removeViewAt(0);
            page.children.add(child);
        }
        return page;
    }

    /** 详情返回：把进详情之前的那一页放回去，并落到点开的那一项。 */
    private void popDetailToPrevious() {
        ParkedPage page = detailReturn;
        detailReturn = null;
        savedDetailItem = null;
        savedDetailInfo = null;
        showingEpisodes = false;
        showingSeasonEpisodes = false;
        detailOpenedSeason = null;
        detailPlayBtn = null;
        setDetailChrome(false);
        if (page == null || page.container == null) {
            restoreHomeOverview();
            return;
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        boolean widthChanged = page.width != width
                || (page.under != null && page.under.width != width);
        if (widthChanged) {
            String guid = page.focusGuid;
            int tab = page.tab;
            boolean overview = page.container == moviesContainer
                    ? page.overview
                    : (page.under != null && page.under.overview);
            if (tab == 1 && savedBrowseList != null) {
                revealTab(1);
                renderGridInContainer(savedBrowseList, savedBrowseTitle, libraryContainer);
                armReturnFocus(libraryContainer, null, guid);
            } else if (!overview && savedBrowseList != null) {
                revealTab(0);
                showingOverview = false;
                renderGridInContainer(savedBrowseList, savedBrowseTitle, moviesContainer);
                armReturnFocus(moviesContainer, null, guid);
            } else if (!overview && savedLiveChannelTitle != null) {
                revealTab(0);
                browseLiveChannels();
            } else {
                restoreHomeOverview();
                pendingReturnGuid = guid;
                pendingReturnContainer = moviesContainer;
            }
            return;
        }
        moviesContainer.removeAllViews();
        if (page.under != null) attachPage(page.under);
        attachPage(page);
        showingOverview = page.container == moviesContainer
                ? page.overview
                : (page.under != null && page.under.overview);
        revealTab(page.tab);
        syncHomeSwipe();
        armReturnFocus(page.container, page.focus, page.focusGuid);
        if (page.container == moviesContainer && page.overview) {
            wireOverviewFocus();
        } else if (page.container != null) {
            wireBrowseGrid(page.container);
        }
    }

    private void attachPage(ParkedPage page) {
        if (page == null || page.container == null) return;
        page.container.removeAllViews();
        for (View child : page.children) {
            if (child.getParent() instanceof ViewGroup) {
                ((ViewGroup) child.getParent()).removeView(child);
            }
            page.container.addView(child);
        }
        ScrollView scroller = verticalScroller(page.container);
        if (scroller != null) {
            int y = page.scrollY;
            scroller.post(() -> {
                scroller.scrollTo(0, y);
                scroller.post(() -> scroller.scrollTo(0, y));
            });
        }
    }

    private void armReturnFocus(LinearLayout container, View focus, String guid) {
        pendingReturnContainer = container;
        pendingReturnFocus = focus;
        pendingReturnGuid = guid;
        if (container != null) container.post(this::applyReturnFocus);
    }

    private boolean applyReturnFocus() {
        if (pendingReturnFocus == null && pendingReturnGuid == null) return false;
        View target = pendingReturnFocus;
        if (!isUsableFocus(target) && pendingReturnContainer != null) {
            target = findCardByGuid(pendingReturnContainer, pendingReturnGuid);
        }
        if (!isUsableFocus(target)) return false;
        pendingReturnFocus = null;
        pendingReturnGuid = null;
        pendingReturnContainer = null;
        focusOn(target);
        homeEntryFocused = true;
        initialFocusPlaced = true;
        holdRememberedFocus = false;
        return true;
    }

    private View findCardByGuid(ViewGroup root, String guid) {
        if (root == null || guid == null) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof PlayListItem && guid.equals(((PlayListItem) tag).guid)
                    && child.isFocusable()) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findCardByGuid((ViewGroup) child, guid);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 离开首页前记下滚动落在哪个区块，返回重建页面后滚回原处。 */
    private void captureHomeScroll() {
        if (!showingOverview || moviesContainer == null) return;
        ScrollView scroller = moviesScroller();
        if (scroller == null) return;
        int scrollY = scroller.getScrollY();
        int index = 0;
        int offset = scrollY;
        for (int i = 0; i < moviesContainer.getChildCount(); i++) {
            View child = moviesContainer.getChildAt(i);
            if (child.getTop() + child.getHeight() > scrollY) {
                index = i;
                offset = scrollY - child.getTop();
                break;
            }
        }
        pendingHomeAnchor = index;
        pendingHomeAnchorOffset = offset;
        holdRememberedFocus = false;
        View focused = getCurrentFocus();
        if (focused != null) rememberFocusSection(focused);
        holdRememberedFocus = lastFocusSectionTag != null;
    }

    private void applyPendingHomeScroll() {
        if (pendingHomeAnchor < 0 || !showingOverview || moviesContainer == null) return;
        ScrollView scroller = moviesScroller();
        if (scroller == null) return;
        final int index = pendingHomeAnchor;
        final int offset = pendingHomeAnchorOffset;
        scroller.post(() -> {
            if (!showingOverview || pendingHomeAnchor != index) return;
            if (moviesContainer.getChildCount() == 0) return;
            View anchor = moviesContainer.getChildAt(Math.min(index, moviesContainer.getChildCount() - 1));
            scroller.scrollTo(0, Math.max(0, anchor.getTop() + offset));
            initialFocusPlaced = true;
            homeEntryFocused = true;
            View restore = restoreInRememberedSection();
            if (isUsableFocus(restore) && !restore.isFocused()) restore.requestFocus();
            if (isUsableFocus(restore) && restore.isFocused()) holdRememberedFocus = false;
            scroller.post(() -> {
                if (!showingOverview || pendingHomeAnchor != index) return;
                if (moviesContainer.getChildCount() == 0) return;
                View again = moviesContainer.getChildAt(Math.min(index, moviesContainer.getChildCount() - 1));
                scroller.scrollTo(0, Math.max(0, again.getTop() + offset));
            });
        });
    }

    private void rememberFocusSection(View v) {
        if (holdRememberedFocus) return;
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
                    int idx = focusIndexIn(items, v, p);
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

    /** 焦点可能在卡片内部，沿父级找到这一行里真正可聚焦的那一张。 */
    private int focusIndexIn(List<View> items, View focused, View section) {
        View cursor = focused;
        while (cursor != null) {
            int idx = items.indexOf(cursor);
            if (idx >= 0) return idx;
            if (cursor == section) break;
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return -1;
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
        if (pendingReturnFocus != null || pendingReturnGuid != null) {
            applyReturnFocus();
            return;
        }
        if (currentTab != 0 || !showingOverview) return;
        if (pendingHomeAnchor >= 0) return;
        View focused = getCurrentFocus();
        View searchBtn = findViewById(R.id.btnHomeSearch);
        List<View> continueCards = continueCards();
        List<View> shortcuts = shortcutCards();
        boolean onRealContent = isUsableFocus(focused) && !isHomeHeader(focused) && !isTabBar(focused)
                && isInCurrentPanel(focused);
        if (!homeEntryFocused && (focused == null || isHomeHeader(focused))) {
            View first = !continueCards.isEmpty() ? continueCards.get(0)
                    : (!shortcuts.isEmpty() ? shortcuts.get(0) : null);
            if (isUsableFocus(first)) {
                focusOn(first);
                homeEntryFocused = true;
                if (!continueCards.isEmpty()) initialFocusPlaced = true;
                return;
            }
        }
        if (isHomeHeader(focused)) return;

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
        List<View> seasons = detailSeasonRow != null
                ? TvFocus.collectVisibleFocusables(detailSeasonRow) : Collections.emptyList();
        List<View> chips = detailChipRow != null
                ? TvFocus.collectVisibleFocusables(detailChipRow) : Collections.emptyList();
        List<View> ranges = detailRangeRow != null
                ? TvFocus.collectVisibleFocusables(detailRangeRow) : Collections.emptyList();
        List<View> eps = detailEpisodeBox != null
                ? TvFocus.collectVisibleFocusables(detailEpisodeBox) : Collections.emptyList();

        List<List<View>> rows = new ArrayList<>();
        if (!play.isEmpty()) rows.add(play);
        if (!ov.isEmpty()) rows.add(ov);
        if (!seasons.isEmpty()) rows.add(seasons);
        if (!chips.isEmpty()) rows.add(chips);
        if (!ranges.isEmpty()) rows.add(ranges);
        if (!eps.isEmpty()) rows.add(eps);

        for (List<View> row : rows) TvFocus.bindRow(row);
        for (View season : seasons) {
            if (season.isSelected()) {
                TvFocus.remember(season);
                break;
            }
        }
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
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN
                && etSearch != null && etSearch.isShown() && etSearch.isFocused()
                && !touchInside(etSearch, ev)) {
            hideKeyboard();
            etSearch.clearFocus();
        }
        if (pendingHomeAnchor >= 0 && ev.getAction() == android.view.MotionEvent.ACTION_MOVE) {
            pendingHomeAnchor = -1;
            holdRememberedFocus = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean touchInside(View view, android.view.MotionEvent ev) {
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x < loc[0] + view.getWidth()
                && y >= loc[1] && y < loc[1] + view.getHeight();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            pendingHomeAnchor = -1;
            holdRememberedFocus = false;
        }
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
        if (!shown.isLaidOut()) {
            shown.post(() -> revealInVerticalScroll(target));
            return;
        }
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
    public void onBackPressed() {
        if (handleBack()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 首页根层级再按一次只退到后台，任务和登录态都留着。 */
    private boolean handleBack() {
        if (showingSeasonEpisodes && savedDetailItem != null && savedDetailInfo != null) {
            showingSeasonEpisodes = false;
            detailOpenedSeason = null;
            buildDetailPage(savedDetailItem, savedDetailInfo);
            return true;
        }
        if (showingEpisodes && savedDetailItem != null && savedDetailInfo != null) {
            showingEpisodes = false;
            buildDetailPage(savedDetailItem, savedDetailInfo);
            return true;
        }
        if (savedDetailItem != null || detailReturn != null) {
            popDetailToPrevious();
            return true;
        }
        if ((etSearch != null && etSearch.isFocused()) || isSearching) {
            leaveSearch();
            return true;
        }
        if (currentTab == 1 && savedBrowseGuid != null) {
            savedBrowseGuid = null; savedBrowseList = null;
            loadMediaLibraries();
            return true;
        }
        if (!showingOverview) {
            restoreHomeOverview();
            return true;
        }
        if (isTelevision() && currentTab != 0) {
            openTab(0, true);
            return true;
        }
        if (backPressedTime + 2000 > System.currentTimeMillis()) {
            moveTaskToBack(true);
        } else {
            backPressedTime = System.currentTimeMillis();
            AppToast.show(this, "再按一次返回桌面");
        }
        return true;
    }
}
