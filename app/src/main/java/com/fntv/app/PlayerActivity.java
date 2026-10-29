package com.fntv.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import android.content.pm.ActivityInfo;
import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.*;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;

import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.CaptionStyleCompat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private SimpleExoPlayer player;
    private TextView tvBuffering, tvTime, infoText;
    private SeekBar seekBar;
    private SkipSeekMarks skipMarks;
    private Button btnSpeed, btnInfo, btnCloseInfo, btnEpisodeList, btnDanmu, btnHdrToggle, btnHdrRow, btnQuality;
    private ImageView btnMore;
    private ImageView btnPlayPause, btnNextEp, btnBack;
    private Button[] ratioChips;
    private ImageView btnLock;
    private TextView tvTitle, tvDanmuStatus, tvDanmuMatch, tvSpeedHint, infoTextAudio, infoTextExtra;
    private Button btnCloudMode, btnBrightness, btnSkip, btnSleep;
    private boolean introSkipped = false, outroSkipped = false, outroListRequested = false;
    /** 这次播放里用户改过的片头或片尾，服务端旧值不能盖掉。 */
    private boolean skipIntroDirty, skipOutroDirty;
    /** play/info 已经回来，之后的保存可以提交到服务端。 */
    private boolean playInfoReady;
    private int playInfoGen;
    private float playbackSpeed = 1.0f;
    /** 这一次已经手动选过倍速，后面拿到剧名也不再覆盖。 */
    private boolean speedPicked;
    /** 已经套用过的倍速记忆键，避免同一部剧重复读。 */
    private String appliedSpeedKey;
    private boolean nextEpCountdownRunning;
    private int nextEpCountdownLeft;
    private final Runnable nextEpCountdownTick = this::tickNextEpisodeCountdown;
    private boolean speedBoost;
    private Runnable pendingOutroSkip;
    private DanmuView danmuView;
    private View controller, infoPanel, topBar, moreScrim;
    private boolean moreOpen;
    private float naturalAspect;
    private boolean isLocked = false;
    private DanmuManager danmuManager;
    private QualitySelectHelper qualityHelper;
    private SubtitleMergeOverlay subtitleMerge;

    private Handler handler = new Handler(Looper.getMainLooper());
    private String itemGuid, baseUrl, itemTitle, itemTV, itemPoster, itemCategory, parentGuid;
    private long itemDuration;
    private FnApiManager apiManager;
    private String mediaGuid, videoGuid, audioGuid, subtitleGuid, resolution;
    private boolean seeked = false, ctrlVis = false, infoVis = false;
    private long seekTs = 0;
    private float[] speeds = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIdx = 2, ratioIdx = 0;
    private boolean isHwDecode = true;
    private EpisodeManager episodeManager;
    private int seasonNumber = 1;
    private long backPressedTime = 0;
    private CloudStreamManager cloudStreamManager;
    private boolean useHls = false;
    private int seekStep = 10000;
    private int bufferTimeMs = 30000;
    private int streamBitrate = 0; // bps 来自 stream API
    private Runnable seekCommitR;
    private long pendingSeekMs = -1;
    private String savedPlaybackUrl = null; // 当前播放地址（用于硬解失败后切软解重试）
    private String customQualityRes = "";   // 非原画时的分辨率
    private int customQualityBitrate = 0;   // 非原画时的码率
    private String customPlayLink = "";     // 非原画时的 play_link
    private static final String TAG = "Player";

    private static final String[] RATIO_LABELS = {"适应", "填充", "4:3", "16:9", "21:9"};
    private String actualVideoDecoder = "";
    private String actualAudioDecoder = "";
    // 流 API 探测数据
    private String streamVCodec = "", streamVProfile = "", streamVPixFmt = "", streamVColor = "", streamVFps = "";
    private int streamVWidth = 0, streamVHeight = 0, streamVBitDepth = 0;
    private boolean streamVHdr = false;
    private long streamFileSize = 0;
    private int streamDuration = 0; // 秒
    private String streamContainer = "";
    private String streamResolution = "";
    private boolean hdrNotified = false; // HDR 已提示过一次
    private boolean firstReady = true;   // 首次进入 READY（用于控制初始 UI 显示）
    private boolean resumePromptShown;
    private View resumeBar;
    private Button btnRestart;
    private final Runnable hideResumePromptR = () -> hideResumePrompt(true);
    private long lastProgressMs;
    private long lastSaveUptime;
    private long lastSavedTs = -1;
    private boolean saveLoopStarted;
    private boolean progressHeld;
    private static volatile int recordsInFlight;
    private final Runnable initialSaveR = () -> saveProgress(true);
    private java.util.List<StreamResponse.AudioStreamInfo> streamAudioTracks;
    private java.util.List<StreamResponse.SubtitleStreamInfo> streamSubtitleTracks;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionGuard.attach(this);
        setContentView(R.layout.activity_player);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        apiManager = FnApiManager.getInstance();
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        isHwDecode = "hardware".equals(prefs.getString("decoder_mode", "hardware"));

        itemGuid = getIntent().getStringExtra("guid");
        seekTs = getIntent().getLongExtra("ts", 0) * 1000L;
        itemDuration = getIntent().getLongExtra("duration", 0);
        itemTitle = getIntent().getStringExtra("title");
        itemTV = getIntent().getStringExtra("tv_title");
        itemPoster = getIntent().getStringExtra("poster");
        itemCategory = getIntent().getStringExtra("category");
        parentGuid = getIntent().getStringExtra("parent_guid");

        playerView = findViewById(R.id.playerView);
        tvBuffering = findViewById(R.id.tvBuffering);
        tvTime = findViewById(R.id.tvTime);
        seekBar = findViewById(R.id.seekBar);
        skipMarks = findViewById(R.id.skipMarks);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnSpeed = findViewById(R.id.btnSpeed);
        restoreSeriesSpeed();
        btnInfo = findViewById(R.id.btnInfo);
        btnQuality = findViewById(R.id.btnQuality);
        btnCloseInfo = findViewById(R.id.btnCloseInfo);
        btnEpisodeList = findViewById(R.id.btnEpisodeList);
        btnNextEp = findViewById(R.id.btnNextEp);
        btnBack = findViewById(R.id.btnBack);
        btnMore = findViewById(R.id.btnMore);
        btnDanmu = findViewById(R.id.btnDanmu);
        moreScrim = findViewById(R.id.moreScrim);
        btnHdrRow = findViewById(R.id.btnHdrRow);
        ratioChips = new Button[] {
                findViewById(R.id.btnRatioFit),
                findViewById(R.id.btnRatioFill),
                findViewById(R.id.btnRatio43),
                findViewById(R.id.btnRatio169),
                findViewById(R.id.btnRatio219)
        };
        danmuView = findViewById(R.id.danmuView);
        btnLock = (ImageView) findViewById(R.id.btnLock);
        showLockButton(false);
        tvTitle = findViewById(R.id.tvTitle);
        tvDanmuStatus = findViewById(R.id.tvDanmuStatus);
        btnCloudMode = findViewById(R.id.btnCloudMode);
        tvDanmuMatch = findViewById(R.id.tvDanmuMatch);
        tvSpeedHint = findViewById(R.id.tvSpeedHint);
        resumeBar = findViewById(R.id.resumeBar);
        btnRestart = findViewById(R.id.btnRestart);
        if (btnRestart != null) btnRestart.setOnClickListener(v -> restartFromBeginning());
        topBar = findViewById(R.id.topBar);
        controller = findViewById(R.id.controller);
        controller.setOnTouchListener((v, e) -> true);
        topBar.setOnTouchListener((v, e) -> true);
        infoPanel = findViewById(R.id.infoPanel);
        infoText = findViewById(R.id.infoText);
        infoTextAudio = findViewById(R.id.infoTextAudio);
        infoTextExtra = findViewById(R.id.infoTextExtra);

        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        seekStep = sp.getInt("seek_step", 10) * 1000;
        bufferTimeMs = sp.getInt("buffer_time", 30) * 1000;

        initPlayer();

        danmuManager = new DanmuManager(this, new DanmuManager.DataProvider() {
            @Override public Player getPlayer() { return player; }
            @Override public long getItemDuration() { return itemDuration; }
            @Override public String getItemTV() { return itemTV; }
            @Override public String getItemTitle() { return itemTitle; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public String getParentGuid() { return parentGuid; }
        }, danmuView, tvDanmuStatus, tvDanmuMatch, btnDanmu, prefs);
        danmuManager.initFromPrefs();

        // 字幕合并覆层
        FrameLayout playerRoot = (FrameLayout) playerView.getParent();
        subtitleMerge = new SubtitleMergeOverlay(playerRoot);
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setVisibility(View.GONE);
            subtitleMerge.setSubtitleView(playerView.getSubtitleView());
        }

        findViewById(android.R.id.content).setOnTouchListener(new View.OnTouchListener() {
            private static final int GESTURE_NONE = 0;
            private static final int GESTURE_SEEK = 1;
            private static final int GESTURE_BRIGHT = 2;
            private static final int GESTURE_VOLUME = 3;
            private boolean longPressing = false;
            private int gesture = GESTURE_NONE;
            private float downX, downY;
            private long dragOriginMs;
            private float brightOrigin;
            private int volumeOrigin;
            private long lastTapAt = 0;
            private final int touchSlop = android.view.ViewConfiguration.get(PlayerActivity.this).getScaledTouchSlop();
            private final android.os.Handler longPressHandler = new android.os.Handler(Looper.getMainLooper());
            private final Runnable singleTapR = () -> {
                if (ctrlVis) showCtrl(false);
                else showCtrl(true);
            };
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (isLocked) {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        showCtrl(true);
                    }
                    return true;
                }
                if (episodeManager != null && episodeManager.isPickerShowing()) return false;
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        longPressHandler.removeCallbacks(singleTapR);
                        longPressing = false;
                        gesture = GESTURE_NONE;
                        downX = event.getX();
                        downY = event.getY();
                        longPressHandler.postDelayed(() -> {
                            if (gesture != GESTURE_NONE) return;
                            longPressing = true;
                            if (player != null) {
                                speedBoost = true;
                                applyPlaybackSpeed();
                                showCtrl(false);
                                if (tvSpeedHint != null && !nextEpCountdownRunning) {
                                    tvSpeedHint.setText("倍速播放中");
                                    tvSpeedHint.setVisibility(View.VISIBLE);
                                }
                            }
                        }, 500);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (longPressing) return true;
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (gesture == GESTURE_NONE) {
                            if (Math.abs(dy) >= touchSlop && Math.abs(dy) > Math.abs(dx)) {
                                int zone = gestureZone(downX, v.getWidth());
                                if (zone < 0) {
                                    gesture = GESTURE_BRIGHT;
                                    brightOrigin = currentBrightness();
                                } else if (zone > 0) {
                                    gesture = GESTURE_VOLUME;
                                    volumeOrigin = currentVolume();
                                }
                                if (gesture != GESTURE_NONE) {
                                    longPressHandler.removeCallbacksAndMessages(null);
                                }
                            } else if (Math.abs(dx) >= touchSlop && Math.abs(dx) > Math.abs(dy)) {
                                if (player == null || player.getDuration() <= 0) return true;
                                gesture = GESTURE_SEEK;
                                longPressHandler.removeCallbacksAndMessages(null);
                                dragOriginMs = pendingSeekMs >= 0 ? pendingSeekMs : player.getCurrentPosition();
                            }
                        }
                        if (gesture == GESTURE_SEEK) {
                            applyScreenDrag(dx, dragOriginMs);
                        } else if (gesture == GESTURE_BRIGHT) {
                            applyBrightnessDrag(dy, v.getHeight(), brightOrigin);
                        } else if (gesture == GESTURE_VOLUME) {
                            applyVolumeDrag(dy, v.getHeight(), volumeOrigin);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacksAndMessages(null);
                        if (longPressing) {
                            longPressing = false;
                            if (player != null) {
                                speedBoost = false;
                                applyPlaybackSpeed();
                            }
                            if (tvSpeedHint != null && !nextEpCountdownRunning) {
                                tvSpeedHint.setVisibility(View.GONE);
                            }
                            return true;
                        }
                        if (gesture == GESTURE_SEEK) {
                            boolean commit = event.getAction() == android.view.MotionEvent.ACTION_UP;
                            gesture = GESTURE_NONE;
                            finishScreenDrag(commit);
                            return true;
                        }
                        if (gesture == GESTURE_BRIGHT || gesture == GESTURE_VOLUME) {
                            if (gesture == GESTURE_BRIGHT) saveBrightnessGesture();
                            gesture = GESTURE_NONE;
                            handler.removeCallbacks(hideSeekOverlayR);
                            handler.postDelayed(hideSeekOverlayR, 700);
                            return true;
                        }
                        if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) return true;
                        long now = event.getEventTime();
                        if (now - lastTapAt < 300) {
                            lastTapAt = 0;
                            onZoneDoubleTap(downX, v.getWidth());
                        } else {
                            lastTapAt = now;
                            longPressHandler.postDelayed(singleTapR, 280);
                        }
                        return true;
                }
                return false;
            }
        });

        episodeManager = new EpisodeManager(new EpisodeManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getParentGuid() { return parentGuid; }
            @Override public String getItemGuid() { return itemGuid; }
            @Override public int getEpisodeNumber() { return getIntent().getIntExtra("episode_number", 0); }
            @Override public String getSeriesTitle() {
                return itemTV != null && !itemTV.isEmpty() ? itemTV : itemTitle;
            }
            @Override public int getSeasonNumber() { return seasonNumber; }
            @Override public long getPlayPositionSec() {
                return player != null ? Math.max(0, player.getCurrentPosition() / 1000) : 0;
            }
            @Override public long getDurationSec() {
                return player != null && player.getDuration() > 0 ? player.getDuration() / 1000 : 0;
            }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public void onPickerChanged(boolean open) {
                if (!open) return;
                handler.removeCallbacks(hideC);
                moreOpen = false;
                if (moreScrim != null) moreScrim.setVisibility(View.GONE);
                ctrlVis = false;
                holdResumePrompt(false);
                controller.setVisibility(View.INVISIBLE);
                topBar.setVisibility(View.INVISIBLE);
                showLockButton(false);
                applyChromeSystemUi(false);
            }
            @Override public void onSwitchEpisode(String guid, String title) {
                saveProgress(true);
                lastProgressMs = 0;
                progressHeld = true;
                introSkipped = false;
                outroSkipped = false;
                outroListRequested = false;
                skipIntroDirty = false;
                skipOutroDirty = false;
                cancelPendingOutroSkip();
                cancelNextEpisodeCountdown();
                hideResumePrompt(false);
                if (danmuManager != null) danmuManager.setHoldStatus(true);
                itemGuid = guid;
                itemTitle = title;
                mediaGuid = null;
                seeked = false;
                seekTs = 0;
                episodeManager.reset();
                loadPlayInfo();
            }
        }, btnEpisodeList, btnNextEp);

        btnPlayPause.setOnClickListener(v -> togglePlay());
        btnSpeed.setOnClickListener(v -> showSpeedSheet());
        btnSleep = findViewById(R.id.btnSleep);
        if (btnSleep != null) btnSleep.setOnClickListener(v -> showSleepDialog());
        btnMore.setOnClickListener(v -> showMore(true));
        if (moreScrim != null) moreScrim.setOnClickListener(v -> showMore(false));
        View morePanel = findViewById(R.id.morePanel);
        if (morePanel != null) morePanel.setOnClickListener(v -> { });
        for (int i = 0; i < ratioChips.length; i++) {
            final int index = i;
            if (ratioChips[i] != null) ratioChips[i].setOnClickListener(v -> setRatio(index));
        }
        setRatio(0);
        btnInfo.setOnClickListener(v -> {
            showMore(false);
            if (!infoVis) toggleInfo();
        });
        qualityHelper = new QualitySelectHelper(this, apiManager, getSharedPreferences("fntv_prefs", MODE_PRIVATE),
                new QualitySelectHelper.QualityCallback() {
                    @Override public void onQualityChanged(int level) {
                        customQualityRes = "";
                        customQualityBitrate = 0;
                        customPlayLink = "";
                        refreshQualityLabel();
                        if (cloudStreamManager != null) {
                            getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                                    .edit().putInt("stream_quality_level", level).apply();
                            cloudStreamManager.reloadPlayback();
                        }
                    }
                    @Override public void onPlayLinkChanged(String playLink, String res, int bps) {
                        customQualityRes = res;
                        customQualityBitrate = bps;
                        customPlayLink = playLink;
                        refreshQualityLabel();
                        // 记录切换前的播放位置（秒）
                        final long seekPosMs = player != null ? Math.max(0, player.getCurrentPosition()) : 0;
                        String fullUrl = baseUrl + playLink;
                        Log.d(TAG, "画质切换新链接: " + fullUrl + " res=" + res + " bitrate=" + bps + " seek=" + seekPosMs);
                        if (player != null) {
                            savedPlaybackUrl = fullUrl;
                            useHls = fullUrl.contains(".m3u8");
                            player.setMediaSource(buildMediaSource(fullUrl, useHls));
                            player.prepare();
                            player.setPlayWhenReady(true);
                            // 等播放器就绪后 seek 到切换前位置 + 更新信息面板
                            player.addListener(new Player.Listener() {
                                @Override public void onPlaybackStateChanged(int s) {
                                    if (s == Player.STATE_READY) {
                                        if (seekPosMs > 0) player.seekTo(seekPosMs);
                                        updateInfo();
                                        player.removeListener(this);
                                    }
                                }
                                @Override public void onCues(java.util.List<com.google.android.exoplayer2.text.Cue> cues) {
                                    if (subtitleMerge != null) subtitleMerge.onNewCues(cues);
                                }
                            });
                        }
                    }
                    @Override public String getMediaGuid() { return mediaGuid; }
                    @Override public String getSubtitlePrefId() {
                        if (parentGuid != null && !parentGuid.isEmpty()) return parentGuid;
                        if (itemGuid != null && !itemGuid.isEmpty()) return itemGuid;
                        return mediaGuid != null ? mediaGuid : "";
                    }
                    @Override public String getAccount() {
                        return getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("user", "video");
                    }
                    @Override public long getPlaybackPosition() {
                        return player != null ? player.getCurrentPosition() / 1000 : 0;
                    }
                });
        if (btnQuality != null) {
            btnQuality.setOnClickListener(v -> qualityHelper.showQualityDialog());
            // 初始检查：如果右上角直链按钮已显示，隐藏画质按钮
            if (btnCloudMode.getVisibility() == View.VISIBLE) {
                btnQuality.setVisibility(View.GONE);
            }
        }
        btnBack.setOnClickListener(v -> finish());
        btnDanmu.setOnClickListener(v -> danmuManager.showSettings());
        btnLock.setOnClickListener(v -> {
            isLocked = !isLocked;
            btnLock.setImageResource(isLocked ? R.drawable.ic_lock : R.drawable.ic_unlock);
            if (isLocked) {
                holdResumePrompt(false);
                topBar.setVisibility(View.INVISIBLE);
                controller.setVisibility(View.INVISIBLE);
                showLockButton(false);
            } else {
                showCtrl(true);
                // 解锁后焦点还给视频区域
                playerView.requestFocus();
            }
        });
        btnCloseInfo.setOnClickListener(v -> toggleInfo());
        btnBrightness = findViewById(R.id.btnBrightness);
        if (btnBrightness != null) {
            btnBrightness.setOnClickListener(v -> showBrightnessDialog());
        }
        btnSkip = findViewById(R.id.btnSkip);
        if (btnSkip != null) {
            btnSkip.setOnClickListener(v -> showIntroOutroDialog());
        }
        // 应用保存的亮度和 HDR 设置
        int savedBright = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("video_brightness", 100);
        if (savedBright != 100) applyBrightness(savedBright);
        applyHdrMode();
        btnEpisodeList.setOnClickListener(v -> episodeManager.showPicker());
        btnNextEp.setOnClickListener(v -> playPendingNextEpisode());

        cloudStreamManager = new CloudStreamManager(new CloudStreamManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getMediaGuid() { return mediaGuid; }
            @Override public String getSubtitlePrefId() {
                if (parentGuid != null && !parentGuid.isEmpty()) return parentGuid;
                if (itemGuid != null && !itemGuid.isEmpty()) return itemGuid;
                return mediaGuid != null ? mediaGuid : "";
            }
            @Override public FnApiManager getApiManager() { return apiManager; }
            @Override public Context getContext() { return PlayerActivity.this; }
            @Override public SharedPreferences getPrefs() { return getSharedPreferences("fntv_prefs", MODE_PRIVATE); }
            @Override public void onStreamInfoParsed(CloudStreamManager.StreamInfo info) {
                streamBitrate = info.bitrate;
                streamVCodec = info.vCodec;
                streamVProfile = info.vProfile;
                streamVWidth = info.width;
                streamVHeight = info.height;
                streamVBitDepth = info.bitDepth;
                streamVHdr = info.vHdr;
                streamVPixFmt = info.vPixFmt;
                streamVColor = info.vColor;
                streamVFps = info.vFps;
                streamDuration = info.duration;
                streamFileSize = info.fileSize;
                streamContainer = info.container;
                streamResolution = info.resolution != null ? info.resolution : "";
                streamAudioTracks = info.audioTracks;
                streamSubtitleTracks = info.subtitleTracks;
                if (streamVCodec.isEmpty() || streamContainer.isEmpty()) {
                    probeWithMediaExtractor();
                }
            }
            @Override public void onStreamDataFailed() { startPlayback(); }
            @Override public void startPlayback() { PlayerActivity.this.startPlayback(); }
            @Override public void onTrackChanged() {
                final Format oldFmt = player != null ? player.getAudioFormat() : null;
                final int[] tries = {6};
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (player == null) return;
                        Format newFmt = player.getAudioFormat();
                        if (newFmt != null && newFmt != oldFmt) {
                            updateInfo();
                        } else if (tries[0] > 0) {
                            tries[0]--;
                            handler.postDelayed(this, 500);
                        }
                    }
                });
            }
            @Override public void reloadPlayback() {
                mediaGuid = null;
                seeked = false;
                seekTs = 0;
                cloudStreamManager.resetForQualitySwitch();
                loadPlayInfo();
            }
            @Override public void probeWithMediaExtractor() { PlayerActivity.this.probeWithMediaExtractor(); }
            @Override public void onCloudBtnVisibilityChanged(boolean vis) {
                // 直链/STRM 按钮显示时，隐藏画质按钮
                if (vis && btnQuality != null) btnQuality.setVisibility(View.GONE);
                if (ctrlVis) wirePlayerFocus();
            }
            @Override public void runOnUiThread(Runnable r) { PlayerActivity.this.runOnUiThread(r); }
        }, btnCloudMode, getSharedPreferences("fntv_prefs", MODE_PRIVATE));
        cloudStreamManager.initFromPrefs();
        cloudStreamManager.setPlayer(player);

        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF != null) TvFocus.remember(newF);
        });
        wirePlayerFocus();

        // 音轨/字幕选择按钮
        Button btnAudioTrack = findViewById(R.id.btnAudioTrack);
        Button btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        btnHdrToggle = findViewById(R.id.btnHdrToggle);
        if (btnAudioTrack != null) {
            btnAudioTrack.setOnClickListener(v -> cloudStreamManager.showAudioTrackDialog(PlayerActivity.this));
        }
        if (btnSubtitleTrack != null) {
            btnSubtitleTrack.setOnClickListener(v -> cloudStreamManager.showSubtitleTrackDialog(PlayerActivity.this));
        }
        if (btnHdrToggle != null) {
            btnHdrToggle.setOnClickListener(v -> toggleHdr());
        }
        if (btnHdrRow != null) {
            btnHdrRow.setOnClickListener(v -> toggleHdr());
        }
        updateHdrButtonText();

        setupFocusAutoHide();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    cancelNextEpisodeCountdown();
                    resetHideTimer();
                    // 立即更新 UI（时间显示）
                    tvTime.setText(playClock(p, player.getDuration()));
                    if (tvSeekOverlay.getVisibility() == View.VISIBLE) {
                        tvSeekOverlay.setText(playClock(p, player.getDuration()));
                    }
                    // 防抖：停止操作 1s 后才真正 seek，避免按住时大量请求
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    pendingSeekMs = p;
                    final int seekPos = p;
                    seekCommitR = () -> {
                        seekToSafe(seekPos);
                        pendingSeekMs = -1;
                    };
                    handler.postDelayed(seekCommitR, 1000);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // 触摸松开时立即执行最后的 seek
                pendingSeekMs = -1;
                if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                if (player != null && sb.getProgress() >= 0) {
                    seekToSafe(sb.getProgress());
                }
            }
        });

        showCtrl(true);
        loadPlayInfo();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // 控制栏隐藏时的进度时间浮层
        tvSeekOverlay = new TextView(this);
        tvSeekOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((FrameLayout.LayoutParams) tvSeekOverlay.getLayoutParams()).gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tvSeekOverlay.setPadding(32, 16, 32, 16);
        tvSeekOverlay.setTextColor(Color.WHITE);
        tvSeekOverlay.setTextSize(22);
        tvSeekOverlay.setBackgroundColor(0x88000000);
        tvSeekOverlay.setVisibility(View.GONE);
        ((FrameLayout) findViewById(android.R.id.content)).addView(tvSeekOverlay);

        playerView.setFocusable(true);
        focusEntrySeekBar();
    }

    /** 刚进播放器时把焦点放到进度条，方便电视立刻调进度。 */
    private void focusEntrySeekBar() {
        if (seekBar == null) return;
        seekBar.post(() -> {
            if (!ctrlVis || seekBar == null || !seekBar.isShown()) return;
            wirePlayerFocus();
            if (!seekBar.requestFocus()) seekBar.post(seekBar::requestFocus);
        });
    }

    /** 续播时提示可以回到片头。只在这次进入播放器时出现一次。 */
    private void offerResumeRestart() {
        if (resumePromptShown || resumeBar == null || seekTs <= 0) return;
        resumePromptShown = true;
        resumeBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideResumePromptR);
        handler.postDelayed(hideResumePromptR, 8000);
        resumeBar.post(() -> {
            wirePlayerFocus();
            if (btnRestart != null && !btnRestart.requestFocus()) btnRestart.post(btnRestart::requestFocus);
        });
    }

    private void hideResumePrompt(boolean returnFocus) {
        handler.removeCallbacks(hideResumePromptR);
        if (resumeBar == null || resumeBar.getVisibility() != View.VISIBLE) return;
        boolean focused = btnRestart != null && btnRestart.hasFocus();
        resumeBar.setVisibility(View.GONE);
        attachResumePromptToMenu();
        wirePlayerFocus();
        if (returnFocus && focused) {
            if (ctrlVis && seekBar != null) seekBar.requestFocus();
            else if (playerView != null) playerView.requestFocus();
        }
    }

    /** 菜单栏收起时把续播提示留在画面右下角，菜单回来再放回进度条上方。 */
    private void holdResumePrompt(boolean menuVisible) {
        if (resumeBar == null || resumeBar.getVisibility() != View.VISIBLE || controller == null) return;
        if (menuVisible) attachResumePromptToMenu();
        else detachResumePromptFromMenu();
    }

    private void attachResumePromptToMenu() {
        if (resumeBar == null || !(controller instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) resumeBar.getParent();
        if (parent == controller) return;
        boolean focused = btnRestart != null && btnRestart.hasFocus();
        if (parent != null) parent.removeView(resumeBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ((ViewGroup) controller).addView(resumeBar, Math.min(1, ((ViewGroup) controller).getChildCount()), lp);
        if (focused) btnRestart.requestFocus();
    }

    private void detachResumePromptFromMenu() {
        if (resumeBar == null || controller == null || !(controller.getParent() instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) controller.getParent();
        if (resumeBar.getParent() == root) return;
        boolean focused = btnRestart != null && btnRestart.hasFocus();
        ViewGroup parent = (ViewGroup) resumeBar.getParent();
        if (parent != null) parent.removeView(resumeBar);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        lp.rightMargin = margin;
        lp.bottomMargin = margin;
        root.addView(resumeBar, root.indexOfChild(controller) + 1, lp);
        if (btnRestart != null) {
            TvFocus.point(btnRestart, View.FOCUS_UP, btnRestart);
            TvFocus.point(btnRestart, View.FOCUS_DOWN, btnRestart);
            TvFocus.point(btnRestart, View.FOCUS_LEFT, btnRestart);
            TvFocus.point(btnRestart, View.FOCUS_RIGHT, btnRestart);
            if (focused && !btnRestart.requestFocus()) btnRestart.post(btnRestart::requestFocus);
        }
    }

    private void restartFromBeginning() {
        hideResumePrompt(false);
        seekTs = 0;
        seeked = true;
        introSkipped = true;
        outroSkipped = false;
        outroListRequested = false;
        cancelPendingOutroSkip();
        if (player != null) {
            player.seekTo(0);
            if (danmuManager != null) danmuManager.onSeekTo(0);
        }
        updateTime();
        if (ctrlVis && seekBar != null) {
            if (!seekBar.requestFocus()) seekBar.post(seekBar::requestFocus);
        }
    }

    private void initPlayer() {
        // 强制最高刷新率（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window win = getWindow();
            if (win != null) {
                WindowManager.LayoutParams lp = win.getAttributes();
                Display.Mode[] modes = getWindowManager().getDefaultDisplay().getSupportedModes();
                float maxRefresh = 60f;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() > maxRefresh) maxRefresh = m.getRefreshRate();
                }
                lp.preferredDisplayModeId = 0;
                for (Display.Mode m : modes) {
                    if (m.getRefreshRate() == maxRefresh) {
                        lp.preferredDisplayModeId = m.getModeId();
                        break;
                    }
                }
                win.setAttributes(lp);
            }
        }
        DefaultRenderersFactory rf = createRenderersFactory(false);
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setPreferredTextLanguages("zh", "zh-Hans", "zh-CN", "chi", "zho", "cmn")
                .setRendererDisabled(com.google.android.exoplayer2.C.TRACK_TYPE_TEXT, false)
                .setTunnelingEnabled(false)
                // 多音轨时声道约束会反复重选并打断 seek，电视/盒子上关掉
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build());
        player = new SimpleExoPlayer.Builder(this, rf)
                .setTrackSelector(trackSelector)
                .setLoadControl(createLoadControl())
                .build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setKeepScreenOn(true);
        // 字幕样式：白色文字，透明背景，黑色描边
        com.google.android.exoplayer2.ui.CaptionStyleCompat captionStyle =
                new com.google.android.exoplayer2.ui.CaptionStyleCompat(
                        Color.WHITE,                    // 前景色
                        Color.TRANSPARENT,              // 背景色（透明）
                        Color.TRANSPARENT,              // 窗口色（透明）
                        com.google.android.exoplayer2.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,                    // 描边色
                        null                            // 字体
                );
        if (playerView.getSubtitleView() != null) {
            playerView.getSubtitleView().setStyle(captionStyle);
        }

        player.addAnalyticsListener(new com.google.android.exoplayer2.analytics.AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs) {
                actualVideoDecoder = decoderName;
                Log.d(TAG, "视频解码器: " + decoderName);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs) {
                actualAudioDecoder = decoderName;
                Log.d(TAG, "音频解码器: " + decoderName);
            }
        });

        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(com.google.android.exoplayer2.video.VideoSize videoSize) {
                if (videoSize != null && videoSize.height > 0) {
                    float ratio = videoSize.width * videoSize.pixelWidthHeightRatio / (float) videoSize.height;
                    if (ratio > 0f) naturalAspect = ratio;
                }
                handler.post(() -> applyAspectRatio());
            }
            @Override public void onPlaybackStateChanged(int s) {
                tvBuffering.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) {
                    progressHeld = false;
                    if (danmuManager != null) danmuManager.setHoldStatus(false);
                    boolean resumedNow = !seeked && seekTs > 0;
                    if (resumedNow) { player.seekTo(seekTs); seeked = true; }
                    ensureSaveLoop(); updateTime();
                    if (firstReady) { scheduleInitialSave(); showCtrl(true); focusEntrySeekBar(); firstReady = false; }
                    if (resumedNow) offerResumeRestart();
                    syncPlayButton();
                    applyPlaybackSpeed();
                    if (danmuManager != null) danmuManager.onPlayerReady();
                    if (cloudStreamManager != null) cloudStreamManager.applyDefaultChineseSubtitle();
                    // HDR 检测（延时等格式就绪）
                    checkHdr();
                    // 打印音轨信息
                    com.google.android.exoplayer2.Format af2 = player.getAudioFormat();
                    if (af2 != null) {
                        Log.d(TAG, "音轨: codec=" + af2.codecs + " mime=" + af2.sampleMimeType
                                + " 采样率=" + af2.sampleRate + "Hz"
                                + " 声道=" + af2.channelCount
                                + " 码率=" + af2.bitrate);
                    } else {
                        Log.d(TAG, "音轨: 无音频信息");
                    }
                } else if (s == Player.STATE_ENDED) {
                    Log.d(TAG, "STATE_ENDED hasNext=" + (episodeManager != null && episodeManager.hasNext()));
                    noteProgress();
                    saveProgress(true);
                    stopSave();
                    if (episodeManager != null && episodeManager.hasNext()) {
                        offerNextEpisodeCountdown();
                    }
                } else {
                    noteProgress();
                    saveProgress(false);
                    if (danmuManager != null) danmuManager.onPlayerPause();
                }
            }
            int retryCount = 0;
            private boolean swDecoderTried = false;
            @Override public void onPlayerError(PlaybackException e) {
                // 打印完整错误链
                StringBuilder sb2 = new StringBuilder("播放错误: " + e.getMessage());
                Throwable tc = e;
                while (tc != null) {
                    sb2.append("\n  ").append(tc.getClass().getSimpleName()).append(": ").append(tc.getMessage());
                    tc = tc.getCause();
                }
                Log.e(TAG, sb2.toString());
                // 视频/音频解码器崩溃 → 自动切软解重试
                if (!swDecoderTried && e.getMessage() != null
                        && (e.getMessage().contains("MediaCodecVideoRenderer")
                            || e.getMessage().contains("MediaCodecAudioRenderer"))) {
                    swDecoderTried = true;
                    Log.d(TAG, "硬解失败，仅本次播放切到软解");
                    isHwDecode = false;
                    handler.post(() -> recreatePlayerWithSwDecoder());
                    return;
                }
                // 按响应码切换
                int code = OkHttpExoDataSource.lastResponseCode;
                if (code == 200 && !useHls && cloudStreamManager.hasDirectUrl()) {
                    useHls = true;
                    Log.d(TAG, "响应200，切换到HLS");
                    switchMediaSource(true);
                    return;
                } else if (code == 206 && useHls && cloudStreamManager.hasDirectUrl()) {
                    useHls = false;
                    Log.d(TAG, "响应206，切换到渐进式");
                    switchMediaSource(false);
                    return;
                } else if (cloudStreamManager.hasDirectUrl() && useHls) {
                    // 非200/206时按渐进式重试
                    useHls = false;
                    Log.d(TAG, "非200/206响应码(" + code + ")，切换到渐进式");
                    switchMediaSource(false);
                    return;
                }
                if (retryCount < 5 && player != null) {
                    retryCount++;
                    handler.postDelayed(() -> {
                        if (player != null) {
                            player.prepare();
                            player.setPlayWhenReady(true);
                        }
                    }, 2000 * retryCount);
                }
            }
            @Override public void onTracksChanged(com.google.android.exoplayer2.Tracks tracks) {
                if (cloudStreamManager != null) cloudStreamManager.applyDefaultChineseSubtitle();
            }
            @Override public void onCues(java.util.List<com.google.android.exoplayer2.text.Cue> cues) {
                if (subtitleMerge != null) {
                    subtitleMerge.onNewCues(cues);
                }
                if (cues != null && TAG.equals("Player")) {
                    Log.d(TAG, "字幕Cues数量: " + cues.size());
                    for (int i = 0; i < cues.size(); i++) {
                        com.google.android.exoplayer2.text.Cue c = cues.get(i);
                        Log.d(TAG, "  Cue[" + i + "] text=" + c.text
                                + " position=" + c.position
                                + " line=" + c.line);
                    }
                }
            }
        });


    }

    private void loadPlayInfo() {
        final int gen = ++playInfoGen;
        playInfoReady = false;
        hdrNotified = false;
        if (cloudStreamManager != null) cloudStreamManager.resetSubtitleChoice();
        Map<String, String> b = new HashMap<>(); b.put("item_guid", itemGuid);
        Log.d(TAG, "play/info 请求: " + new com.google.gson.Gson().toJson(b));
        apiManager.getApi().getPlayInfo(b).enqueue(new retrofit2.Callback<ApiResponse<PlayInfoResponse>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<PlayInfoResponse>> call,
                                             retrofit2.Response<ApiResponse<PlayInfoResponse>> r) {
                if (gen != playInfoGen) return;
                if (r.isSuccessful() && r.body() != null && r.body().code == 0 && r.body().data != null) {
                    PlayInfoResponse info = r.body().data;
                    mediaGuid = info.mediaGuid; videoGuid = info.videoGuid; audioGuid = info.audioGuid;
                    if (info.guid != null && !info.guid.isEmpty()) itemGuid = info.guid;
                    if (info.parentGuid != null && !info.parentGuid.isEmpty()) parentGuid = info.parentGuid;
                    Log.d(TAG, "play/info 返回: type=" + info.getClass().getSimpleName()
                            + " guid=" + info.guid
                            + " mediaGuid=" + info.mediaGuid
                            + " audioGuid='" + info.audioGuid + "'"
                            + " videoGuid=" + info.videoGuid
                            + " subtitleGuid=" + info.subtitleGuid
                            + " raw=" + new com.google.gson.Gson().toJson(info));
                    // 从 intent 的 parent_guid 兜底（详情页传递的）
                    if (parentGuid == null || parentGuid.isEmpty()) {
                        parentGuid = getIntent().getStringExtra("parent_guid");
                    }
                    subtitleGuid = info.subtitleGuid != null ? info.subtitleGuid : "_no_display_";
                    if (info.item != null && info.item.tvTitle != null) itemTV = info.item.tvTitle;
                    if (info.item != null) itemTitle = info.item.title;
                    restoreSeriesSpeed();
                    applyServerSkip(info.playConfig);
                    if (info.item != null && info.item.seasonNumber > 0) seasonNumber = info.item.seasonNumber;
                    if (info.item != null) getIntent().putExtra("episode_number", info.item.episodeNumber);
                    int epNum = info.item != null ? info.item.episodeNumber : 0;
                    String matchName = itemTV != null && !itemTV.isEmpty() ? itemTV : itemTitle;
                    if (matchName != null && !matchName.isEmpty() && epNum > 0) {
                        matchName = matchName + " S" + String.format("%02d", seasonNumber) + "E" + String.format("%02d", epNum);
                    }
                    if (danmuManager != null) danmuManager.loadDanmu(matchName, itemGuid);
                    if (info.item != null && info.item.mediaStream != null
                            && info.item.mediaStream.resolutions != null
                            && !info.item.mediaStream.resolutions.isEmpty())
                        resolution = info.item.mediaStream.resolutions.get(0);

                    // 直播频道：直接从 live_channels 取第一个流地址播放
                    if (info.liveChannels != null && !info.liveChannels.isEmpty()) {
                        String liveUrl = info.liveChannels.get(0).path;
                        Log.d(TAG, "直播频道播放地址: " + liveUrl);
                        playLiveStream(liveUrl);
                        tvTitle.setText(itemTitle != null ? itemTitle : "直播");
                        return;
                    }

                    // 获取直链信息，获取完后开始播放
                    cloudStreamManager.fetchDirectLink(itemGuid, mediaGuid);
                } else {
                    playInfoReady = true;
                    if (skipIntroDirty || skipOutroDirty) pushSkipConfig();
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {
                if (gen != playInfoGen) return;
                playInfoReady = true;
                if (skipIntroDirty || skipOutroDirty) pushSkipConfig();
            }
        });
    }

    /** 视频源。没有内嵌字幕时附上中文外部字幕。 */
    private com.google.android.exoplayer2.source.MediaSource buildMediaSource(String url, boolean hls) {
        com.google.android.exoplayer2.upstream.DataSource.Factory f = () -> new OkHttpExoDataSource(apiManager.getStreamClient());
        CloudStreamManager.ExternalSubtitle side = cloudStreamManager != null
                ? cloudStreamManager.defaultExternalSubtitle(baseUrl) : null;
        if (side != null) Log.d(TAG, "外挂中文字幕: " + side.label + " " + side.url);
        if (hls) {
            com.google.android.exoplayer2.source.hls.HlsMediaSource video =
                    new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(f)
                            .createMediaSource(MediaItem.fromUri(url));
            return withExternalSubtitle(video, f, side);
        }
        com.google.android.exoplayer2.source.ProgressiveMediaSource video =
                new ProgressiveMediaSource.Factory(f, new DefaultExtractorsFactory())
                        .createMediaSource(MediaItem.fromUri(url));
        return withExternalSubtitle(video, f, side);
    }

    private com.google.android.exoplayer2.source.MediaSource withExternalSubtitle(
            com.google.android.exoplayer2.source.MediaSource video,
            com.google.android.exoplayer2.upstream.DataSource.Factory f,
            CloudStreamManager.ExternalSubtitle side) {
        if (side == null) return video;
        com.google.android.exoplayer2.source.MediaSource text =
                new com.google.android.exoplayer2.source.SingleSampleMediaSource.Factory(f)
                        .createMediaSource(new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(side.url))
                                .setMimeType(side.mimeType)
                                .setLanguage(side.language)
                                .setLabel(side.label)
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(), C.TIME_UNSET);
        return new com.google.android.exoplayer2.source.MergingMediaSource(video, text);
    }

    /** 开始播放（加载到 ExoPlayer） */
    private void startPlayback() {
        if (mediaGuid == null) return;
        CloudStreamManager.PlaybackConfig cfg = cloudStreamManager.getPlaybackConfig(baseUrl, mediaGuid);
        OkHttpExoDataSource.setChunkedMode(cfg.chunkedModeSize);
        useHls = cfg.hls;
        savedPlaybackUrl = cfg.url;
        player.setMediaSource(buildMediaSource(cfg.url, useHls));
        Log.d(TAG, useHls ? "播放器: HLS" : "播放器: 渐进式");
        player.prepare(); player.setPlayWhenReady(true);
        Log.d(TAG, "startPlayback: parentGuid=" + parentGuid + " episodeLoaded=" + (episodeManager != null && episodeManager.isLoaded()) + " loadingEp=" + (episodeManager != null && episodeManager.isLoading()));
        if (parentGuid != null && !parentGuid.isEmpty() && episodeManager != null && !episodeManager.isLoaded() && !episodeManager.isLoading())
            episodeManager.loadList(parentGuid);
    }

    /** 下载 ASS 字幕 → 合并双语 Dialogue → 保存到缓存 */
    /** 直播频道播放（直接用 live_channels 返回的地址） */
    private void playLiveStream(String url) {
        if (player == null) return;
        savedPlaybackUrl = url;
        useHls = url.contains(".m3u8");
        com.google.android.exoplayer2.upstream.DataSource.Factory f = () -> new OkHttpExoDataSource(apiManager.getStreamClient());
        if (useHls) {
            player.setMediaSource(new com.google.android.exoplayer2.source.hls.HlsMediaSource.Factory(f).createMediaSource(MediaItem.fromUri(url)));
            Log.d(TAG, "直播: HLS");
        } else {
            player.setMediaSource(new com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(f, new DefaultExtractorsFactory()).createMediaSource(MediaItem.fromUri(url)));
            Log.d(TAG, "直播: 渐进式");
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** 硬解失败后切到软解，重新创建播放器 */
    private void recreatePlayerWithSwDecoder() {
        saveProgress(true);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        DefaultRenderersFactory rf2 = createRenderersFactory(true);
        DefaultTrackSelector swSelector = new DefaultTrackSelector(this);
        swSelector.setParameters(swSelector.buildUponParameters()
                .setTunnelingEnabled(false)
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build());
        player = new SimpleExoPlayer.Builder(this, rf2)
                .setTrackSelector(swSelector)
                .setLoadControl(createLoadControl())
                .build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        if (cloudStreamManager != null) {
            cloudStreamManager.setPlayer(player);
            cloudStreamManager.resetSubtitleChoice();
        }
        // 重新挂载事件监听（错误处理等）
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                tvBuffering.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                if (s == Player.STATE_READY) {
                    progressHeld = false;
                    if (danmuManager != null) danmuManager.setHoldStatus(false);
                    boolean resumedNow = !seeked && seekTs > 0;
                    if (resumedNow) { player.seekTo(seekTs); seeked = true; }
                    ensureSaveLoop();
                    updateTime();
                    applyPlaybackSpeed();
                    if (firstReady) { scheduleInitialSave(); showCtrl(true); focusEntrySeekBar(); firstReady = false; }
                    if (resumedNow) offerResumeRestart();
                    syncPlayButton();
                    if (cloudStreamManager != null) cloudStreamManager.applyDefaultChineseSubtitle();
                } else if (s == Player.STATE_ENDED) {
                    noteProgress();
                    saveProgress(true);
                    stopSave();
                    if (episodeManager != null && episodeManager.hasNext()) offerNextEpisodeCountdown();
                } else {
                    noteProgress();
                    saveProgress(false);
                }
            }
            int retryCount = 0;
            private boolean swDecoderTried = false;
            @Override public void onCues(java.util.List<com.google.android.exoplayer2.text.Cue> cues) {
                if (subtitleMerge != null) subtitleMerge.onNewCues(cues);
            }
            @Override public void onPlayerError(PlaybackException e) {
                StringBuilder sb2 = new StringBuilder("播放错误(软解): " + e.getMessage());
                Throwable tc = e;
                while (tc != null) {
                    sb2.append("\n  ").append(tc.getClass().getSimpleName()).append(": ").append(tc.getMessage());
                    tc = tc.getCause();
                }
                Log.e(TAG, sb2.toString());
            }
        });
        // 重放（直播和普通视频都用 savedPlaybackUrl）
        if (savedPlaybackUrl != null) {
            useHls = savedPlaybackUrl.contains(".m3u8");
            player.setMediaSource(buildMediaSource(savedPlaybackUrl, useHls));
            player.prepare();
            player.setPlayWhenReady(true);
            Log.d(TAG, "已切 Google 软解重试: " + savedPlaybackUrl);
        }
    }

    // ========== 剧集移至 EpisodeManager ==========

    // ========== 控制 ==========

    private void togglePlay() {
        if (nextEpCountdownRunning) cancelNextEpisodeCountdown();
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            syncPlayButton();
            if (danmuManager != null) danmuManager.onPlayerPause();
        } else {
            player.play();
            syncPlayButton();
            updateTime();
            if (danmuManager != null) danmuManager.onPlayerReady();
        }
    }

    private void seekRel(int ms) {
        if (nextEpCountdownRunning) cancelNextEpisodeCountdown();
        if (player == null) return;
        seekToSafe(player.getCurrentPosition() + ms);
    }

    /**
     * 安全 seek：时长未知（TIME_UNSET）时不能用 Math.min(duration, …)，
     * 否则结果变成 0，表现为「一快进就从头播放」。
     */
    private void seekToSafe(long targetMs) {
        if (player == null) return;
        long dur = player.getDuration();
        long target = Math.max(0, targetMs);
        if (dur > 0) target = Math.min(dur, target);
        player.seekTo(target);
        if (danmuManager != null) danmuManager.onSeekTo(target);
    }

    /** 左 25%、中 50%、右 25%。 */
    private int gestureZone(float x, int width) {
        int w = Math.max(1, width);
        if (x < w * 0.25f) return -1;
        if (x > w * 0.75f) return 1;
        return 0;
    }

    private void onZoneDoubleTap(float x, int width) {
        int zone = gestureZone(x, width);
        if (zone < 0) {
            seekRel(-seekStep);
            showGestureHint("快退 " + (seekStep / 1000) + "秒");
        } else if (zone > 0) {
            seekRel(seekStep);
            showGestureHint("快进 " + (seekStep / 1000) + "秒");
        } else {
            togglePlay();
            return;
        }
        handler.removeCallbacks(hideSeekOverlayR);
        handler.postDelayed(hideSeekOverlayR, 700);
    }

    private void showGestureHint(String text) {
        if (tvSeekOverlay == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvSeekOverlay.getLayoutParams();
        if (lp.gravity != Gravity.CENTER) {
            lp.gravity = Gravity.CENTER;
            tvSeekOverlay.setLayoutParams(lp);
        }
        tvSeekOverlay.setText(text);
        tvSeekOverlay.setVisibility(View.VISIBLE);
    }

    private float currentBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        return b >= 0f ? b : 0.5f;
    }

    private void applyBrightnessDrag(float dy, int height, float origin) {
        float h = Math.max(1, height);
        float next = origin - dy / h;
        next = Math.max(0.01f, Math.min(1f, next));
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = next;
        getWindow().setAttributes(lp);
        showGestureHint("亮度 " + Math.round(next * 100) + "%");
    }

    private void saveBrightnessGesture() {
        float b = currentBrightness();
        int stored = Math.round(b * 100);
        if (stored >= 100) stored = 200;
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putInt("video_brightness", stored).apply();
    }

    private int currentVolume() {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return 0;
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
    }

    private void applyVolumeDrag(float dy, int height, int origin) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        float h = Math.max(1, height);
        int next = origin + Math.round(-dy / h * max);
        next = Math.max(0, Math.min(max, next));
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0);
        showGestureHint("音量 " + Math.round(next * 100f / max) + "%");
    }

    /** 横向拖动画面：一整屏大约快进或快退 10 分钟，短片则对应整段时长。 */
    private void applyScreenDrag(float dx, long originMs) {
        if (player == null || tvSeekOverlay == null) return;
        long dur = player.getDuration();
        if (dur <= 0) return; // TIME_UNSET / 未知时长时不拖进度
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        long span = Math.min(dur, 10 * 60 * 1000L);
        long delta = (long) (dx / width * span);
        long target = Math.max(0, Math.min(dur, originMs + delta));
        pendingSeekMs = target;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvSeekOverlay.getLayoutParams();
        if (lp.gravity != Gravity.CENTER) {
            lp.gravity = Gravity.CENTER;
            tvSeekOverlay.setLayoutParams(lp);
        }
        String label = (delta >= 0 ? "快进  " : "快退  ") + playClock(target, dur);
        tvSeekOverlay.setText(label);
        tvSeekOverlay.setVisibility(View.VISIBLE);
        tvTime.setText(playClock(target, dur));
        if (dur <= Integer.MAX_VALUE) {
            seekBar.setMax((int) dur);
            seekBar.setProgress((int) target);
        }
        handler.removeCallbacks(hideSeekOverlayR);
    }

    private void finishScreenDrag(boolean commit) {
        long target = pendingSeekMs;
        pendingSeekMs = -1;
        if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
        if (commit && player != null && target >= 0) {
            seekToSafe(target);
        } else {
            updateTime();
        }
        if (tvSeekOverlay != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvSeekOverlay.getLayoutParams();
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            tvSeekOverlay.setLayoutParams(lp);
        }
        handler.removeCallbacks(hideSeekOverlayR);
        handler.postDelayed(hideSeekOverlayR, commit ? 700 : 0);
    }

    private DefaultLoadControl createLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        bufferTimeMs,   // MIN_BUFFER_MS
                        bufferTimeMs,   // MAX_BUFFER_MS
                        2500,           // BUFFER_FOR_PLAYBACK_MS
                        5000            // BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build();
    }

    /** 倍速从右侧栏点选，当前档用勾标出。 */
    private void showSpeedSheet() {
        java.util.ArrayList<SideSheet.Choice> choices = new java.util.ArrayList<>();
        int selected = speedIdx;
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - playbackSpeed) < 0.01f) selected = i;
            String detail = Math.abs(speeds[i] - 1f) < 0.01f ? "正常" : "";
            choices.add(new SideSheet.Choice(speedText(speeds[i]), detail, null));
        }
        SideSheet.showCards(this, "倍速", null, choices, selected, "", (dialog, which) -> {
            if (which < 0 || which >= speeds.length) return;
            speedIdx = which;
            playbackSpeed = speeds[which];
            speedBoost = false;
            speedPicked = true;
            rememberSeriesSpeed();
            refreshSpeedLabel();
            applyPlaybackSpeed();
        });
    }

    /** 一部剧共用一个倍速。电影没有剧名时按这一部自己记住。 */
    private String speedMemoryKey() {
        if (itemTV != null && !itemTV.isEmpty()
                && parentGuid != null && !parentGuid.isEmpty()) {
            return "tv:" + itemTV;
        }
        if (parentGuid != null && !parentGuid.isEmpty()) return "p:" + parentGuid;
        if (itemGuid != null && !itemGuid.isEmpty()) return "i:" + itemGuid;
        return null;
    }

    private void restoreSeriesSpeed() {
        String key = speedMemoryKey();
        if (key == null || key.equals(appliedSpeedKey)) return;
        if (speedPicked) {
            appliedSpeedKey = key;
            rememberSeriesSpeed();
            return;
        }
        appliedSpeedKey = key;
        float saved = getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .getFloat("series_speed_" + key, 1f);
        int idx = 2;
        float best = 1f;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < speeds.length; i++) {
            float diff = Math.abs(speeds[i] - saved);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = speeds[i];
                idx = i;
            }
        }
        speedIdx = idx;
        playbackSpeed = best;
        refreshSpeedLabel();
        applyPlaybackSpeed();
    }

    private void rememberSeriesSpeed() {
        String key = speedMemoryKey();
        if (key == null) return;
        appliedSpeedKey = key;
        getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .edit().putFloat("series_speed_" + key, playbackSpeed).apply();
    }

    /** 这一集放到结尾后，倒计时再进下一集。返回、拖动或暂停都会取消。 */
    private void offerNextEpisodeCountdown() {
        if (nextEpCountdownRunning) return;
        if (episodeManager == null || !episodeManager.hasNext()) return;
        nextEpCountdownRunning = true;
        nextEpCountdownLeft = 5;
        if (tvSpeedHint != null) {
            tvSpeedHint.setClickable(true);
            tvSpeedHint.setFocusable(false);
            tvSpeedHint.setOnClickListener(v -> cancelNextEpisodeCountdown());
            tvSpeedHint.bringToFront();
        }
        handler.removeCallbacks(nextEpCountdownTick);
        tickNextEpisodeCountdown();
    }

    private void tickNextEpisodeCountdown() {
        if (!nextEpCountdownRunning) return;
        if (episodeManager == null || !episodeManager.hasNext()) {
            cancelNextEpisodeCountdown();
            return;
        }
        if (nextEpCountdownLeft <= 0) {
            playPendingNextEpisode();
            return;
        }
        if (tvSpeedHint != null) {
            tvSpeedHint.setText(nextEpCountdownLeft + " 秒后播放下一集");
            tvSpeedHint.setVisibility(View.VISIBLE);
        }
        nextEpCountdownLeft--;
        handler.postDelayed(nextEpCountdownTick, 1000);
    }

    private void playPendingNextEpisode() {
        nextEpCountdownRunning = false;
        handler.removeCallbacks(nextEpCountdownTick);
        hideNextEpisodeCountdown();
        if (episodeManager != null) episodeManager.playNext();
    }

    private void cancelNextEpisodeCountdown() {
        if (!nextEpCountdownRunning) return;
        nextEpCountdownRunning = false;
        handler.removeCallbacks(nextEpCountdownTick);
        hideNextEpisodeCountdown();
    }

    private void hideNextEpisodeCountdown() {
        if (tvSpeedHint == null) return;
        tvSpeedHint.setOnClickListener(null);
        tvSpeedHint.setClickable(false);
        if (speedBoost) {
            tvSpeedHint.setText("倍速播放中");
            tvSpeedHint.setVisibility(View.VISIBLE);
            return;
        }
        tvSpeedHint.setText("倍速播放中");
        tvSpeedHint.setVisibility(View.GONE);
    }

    /** 把当前倍速写进播放器。长按加速时暂时用 2 倍，松手回到用户选的倍速。 */
    private void applyPlaybackSpeed() {
        if (player == null) return;
        float speed = speedBoost ? 2f : playbackSpeed;
        PlaybackParameters now = player.getPlaybackParameters();
        if (Math.abs(now.speed - speed) > 0.01f) {
            player.setPlaybackParameters(new PlaybackParameters(speed));
            Log.d(TAG, "倍速 " + speed);
        }
        if (danmuView != null) danmuView.setPlaybackSpeed(speed);
    }

    /**
     * 电视盒子遇到杜比/DTS 会走音频直通，直通轨不接受倍速，画面就一直是 1 倍。
     * 这里强制解成 PCM，倍速才能作用到声音和画面。
     */
    private DefaultRenderersFactory createRenderersFactory(boolean googleSoftwareVideo) {
        boolean preferExtension = googleSoftwareVideo
                || "software".equals(getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .getString("decoder_mode", "hardware"));
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                                boolean enableAudioTrackPlaybackParams,
                                                boolean enableOffload) {
                return new DefaultAudioSink.Builder()
                        .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        .setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_DISABLED)
                        .build();
            }

            @Override
            protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                                com.google.android.exoplayer2.mediacodec.MediaCodecSelector mediaCodecSelector,
                                                boolean enableDecoderFallback, android.os.Handler eventHandler,
                                                com.google.android.exoplayer2.video.VideoRendererEventListener eventListener,
                                                long allowedVideoJoiningTimeMs, java.util.ArrayList<Renderer> out) {
                if (!googleSoftwareVideo) {
                    super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector,
                            enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
                    return;
                }
                com.google.android.exoplayer2.mediacodec.MediaCodecSelector googleOnly =
                        new com.google.android.exoplayer2.mediacodec.MediaCodecSelector() {
                            @Override
                            public java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> getDecoderInfos(
                                    String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                                    throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
                                java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> all =
                                        com.google.android.exoplayer2.mediacodec.MediaCodecSelector.DEFAULT
                                                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                                java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> google =
                                        new java.util.ArrayList<>();
                                for (com.google.android.exoplayer2.mediacodec.MediaCodecInfo info : all) {
                                    if (info.name.startsWith("omx.google.") || info.name.startsWith("c2.android.")) {
                                        google.add(info);
                                    }
                                }
                                return !google.isEmpty() ? google : all;
                            }
                        };
                super.buildVideoRenderers(context, extensionRendererMode, googleOnly, enableDecoderFallback,
                        eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
            }
        };
        rf.setExtensionRendererMode(preferExtension
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        rf.setEnableDecoderFallback(true);
        rf.setEnableAudioTrackPlaybackParams(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        return rf;
    }

    private String speedText(float s) {
        String n = s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
        return n + "X";
    }

    /** 1 倍时菜单栏显示「倍速」，其他档显示具体倍率。 */
    private void refreshSpeedLabel() {
        if (btnSpeed == null) return;
        btnSpeed.setText(Math.abs(playbackSpeed - 1f) < 0.01f ? "倍速" : speedText(playbackSpeed));
    }

    private String playClock(long cur, long dur) {
        return FormatUtils.fmt(cur) + "/" + FormatUtils.fmt(dur);
    }

    private void refreshQualityLabel() {
        if (btnQuality == null || qualityHelper == null) return;
        btnQuality.setText(qualityHelper.getCurrentLabel());
    }

    private void syncPlayButton() {
        if (btnPlayPause == null) return;
        boolean playing = player != null && player.isPlaying();
        btnPlayPause.setImageResource(playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        btnPlayPause.setContentDescription(playing ? "暂停" : "播放");
    }

    private void setRatio(int index) {
        if (index < 0 || index >= RATIO_LABELS.length) return;
        ratioIdx = index;
        if (ratioChips != null) {
            for (int i = 0; i < ratioChips.length; i++) {
                if (ratioChips[i] != null) ratioChips[i].setSelected(i == index);
            }
        }
        applyAspectRatio();
    }

    private void applyAspectRatio() {
        if (playerView == null) return;
        com.google.android.exoplayer2.ui.AspectRatioFrameLayout frame = findAspectFrame(playerView);
        if (ratioIdx == 1) {
            playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            if (frame != null && naturalAspect > 0f) frame.setAspectRatio(naturalAspect);
        } else if (ratioIdx >= 2) {
            float forced = ratioIdx == 2 ? 4f / 3f : ratioIdx == 3 ? 16f / 9f : 21f / 9f;
            playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (frame != null) frame.setAspectRatio(forced);
        } else {
            playerView.setResizeMode(com.google.android.exoplayer2.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            if (frame != null && naturalAspect > 0f) frame.setAspectRatio(naturalAspect);
        }
        if (playerView.getSubtitleView() != null) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) playerView.getSubtitleView().getLayoutParams();
            if (lp != null) {
                int bottom = ratioIdx == 0 ? 0 : (int) (55 * getResources().getDisplayMetrics().density);
                if (lp.bottomMargin != bottom) {
                    lp.bottomMargin = bottom;
                    playerView.getSubtitleView().setLayoutParams(lp);
                }
            }
        }
    }

    private com.google.android.exoplayer2.ui.AspectRatioFrameLayout findAspectFrame(View v) {
        if (v instanceof com.google.android.exoplayer2.ui.AspectRatioFrameLayout) {
            return (com.google.android.exoplayer2.ui.AspectRatioFrameLayout) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                com.google.android.exoplayer2.ui.AspectRatioFrameLayout found = findAspectFrame(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void checkHdr() {
        handler.postDelayed(() -> {
            if (player == null) return;
            Log.d(TAG, "HDR检查: isHdr=" + isHdrVideo()
                    + " streamVHdr=" + streamVHdr
                    + " color=" + streamVColor);
            // 统一用 applyHdrMode 处理开/关（切剧集、重缓冲时也会正确切换 colorMode）
            applyHdrMode();
            updateHdrButtonText();
        }, 1500);
    }

    private boolean deviceSupportsHdr() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Display.HdrCapabilities caps = getWindowManager()
                    .getDefaultDisplay().getHdrCapabilities();
            if (caps != null) {
                for (int type : caps.getSupportedHdrTypes()) {
                    if (type == Display.HdrCapabilities.HDR_TYPE_HDR10
                            || type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    private LinearLayout skipSheetBody;

    private void showIntroOutroDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF1A1A1A);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        skipSheetBody = new LinearLayout(this);
        skipSheetBody.setOrientation(LinearLayout.VERTICAL);
        skipSheetBody.setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(16));
        scroll.addView(skipSheetBody, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        showSkipMenu(dialog);
        dialog.setContentView(scroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));
            int screenH = getResources().getDisplayMetrics().heightPixels;
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.9f));
        }
        SideSheet.place(dialog);
        dialog.show();
        focusSkipPage(dialog);
    }

    /** 电视剧用父级 guid，电影用影片 guid。都没有时才用片名，兼容以前存在本地的设置。 */
    private String skipCacheId() {
        if (parentGuid != null && !parentGuid.isEmpty()) return parentGuid;
        if (itemGuid != null && !itemGuid.isEmpty()) return itemGuid;
        if (itemTV != null && !itemTV.isEmpty()) return itemTV;
        return "";
    }

    private String skipPrefsKey() {
        return "skip_" + skipCacheId();
    }

    private int readSkipSec(boolean intro) {
        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        String suffix = intro ? "_intro" : "_outro";
        String key = skipPrefsKey() + suffix;
        if (sp.contains(key)) return sp.getInt(key, 0);
        if (itemTV != null && !itemTV.isEmpty()) {
            return sp.getInt("skip_" + itemTV + suffix, 0);
        }
        return 0;
    }

    private void writeSkipSec(boolean intro, int sec) {
        int saved = Math.max(0, Math.min(sec, 20 * 60));
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit()
                .putInt(skipPrefsKey() + (intro ? "_intro" : "_outro"), saved).apply();
        if (intro) {
            skipIntroDirty = true;
            introSkipped = false;
        } else {
            skipOutroDirty = true;
            outroSkipped = false;
            cancelPendingOutroSkip();
        }
        refreshSkipMarks();
        if (playInfoReady) pushSkipConfig();
        if (player == null) return;
        if (intro) maybeSkipIntro();
        else maybeSkipOutro(player.getCurrentPosition(), player.getDuration());
    }

    /** 把服务端账号上的片头片尾写入本地。用户这次已经改过的那一项保留。 */
    private void applyServerSkip(PlayInfoResponse.PlayConfig config) {
        String id = skipCacheId();
        if (config != null && id != null && !id.isEmpty()) {
            SharedPreferences.Editor editor = getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit();
            boolean wrote = false;
            if (config.skipOpening != null && !skipIntroDirty) {
                editor.putInt("skip_" + id + "_intro", Math.max(0, config.skipOpening));
                wrote = true;
            }
            if (config.skipEnding != null && !skipOutroDirty) {
                editor.putInt("skip_" + id + "_outro", Math.max(0, config.skipEnding));
                wrote = true;
            }
            if (wrote) editor.apply();
            Log.d(TAG, "片头片尾同步 guid=" + id
                    + " opening=" + (config.skipOpening == null ? "null" : config.skipOpening)
                    + " ending=" + (config.skipEnding == null ? "null" : config.skipEnding));
        }
        boolean migrate = false;
        if (!skipIntroDirty && (config == null || config.skipOpening == null) && readSkipSec(true) > 0) migrate = true;
        if (!skipOutroDirty && (config == null || config.skipEnding == null) && readSkipSec(false) > 0) migrate = true;
        playInfoReady = true;
        if (skipIntroDirty || skipOutroDirty || migrate) pushSkipConfig();
        refreshSkipMarks();
    }

    /** 片头、片尾一起提交，避免只改一项时把另一项在服务端清掉。 */
    private void pushSkipConfig() {
        String guid = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemGuid;
        if (guid == null || guid.isEmpty() || apiManager == null) return;
        Map<String, Object> body = new HashMap<>();
        body.put("guid", guid);
        body.put("skip_opening", readSkipSec(true));
        body.put("skip_ending", readSkipSec(false));
        Log.d(TAG, "setConfigByItem 请求: " + new com.google.gson.Gson().toJson(body));
        apiManager.getApi().setSkipConfig(body).enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<Object>> call,
                                             retrofit2.Response<ApiResponse<Object>> response) {
                String msg = response.body() != null ? "code=" + response.body().code + " msg=" + response.body().msg : "nullBody";
                Log.d(TAG, "setConfigByItem 响应: HTTP " + response.code() + " " + msg);
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                Log.e(TAG, "setConfigByItem 失败: " + t.getMessage());
            }
        });
    }

    private void cancelPendingOutroSkip() {
        if (pendingOutroSkip == null) return;
        handler.removeCallbacks(pendingOutroSkip);
        pendingOutroSkip = null;
    }

    /** 片头只在还没播过设定点时跳一次。续播位置还没落到播放器上时先不判断。 */
    private void maybeSkipIntro() {
        if (introSkipped || player == null) return;
        int introSec = readSkipSec(true);
        if (introSec <= 0) return;
        long introMs = introSec * 1000L;
        long pos = player.getCurrentPosition();
        if (seekTs > introMs && pos + 1500 < seekTs) return;
        if (pos < introMs) {
            introSkipped = true;
            player.seekTo(introMs);
            if (danmuManager != null) danmuManager.showDanmuStatus("跳过片头 " + introSec + "秒");
        } else {
            introSkipped = true;
        }
    }

    /**
     * 进入片尾区间才跳。剧集列表还在加载时不记账，否则第一次到片尾会把机会用掉，
     * 要重新保存一次才会再跳。
     */
    private void maybeSkipOutro(long cur, long dur) {
        if (player == null || dur <= 0) return;
        int outroSec = readSkipSec(false);
        if (outroSec <= 0) return;
        long skipAt = dur - outroSec * 1000L;
        if (skipAt <= 0) return;
        if (cur + 1500 < skipAt) {
            cancelPendingOutroSkip();
            outroSkipped = false;
            return;
        }
        if (outroSkipped || cur < skipAt) return;
        if (episodeManager != null && episodeManager.isLoading()) return;
        if (episodeManager != null && !episodeManager.isLoaded() && !episodeManager.isLoading()
                && parentGuid != null && !parentGuid.isEmpty()) {
            if (!outroListRequested) {
                outroListRequested = true;
                episodeManager.loadList(parentGuid);
            }
            return;
        }
        if (episodeManager != null && episodeManager.hasNext()) {
            outroSkipped = true;
            if (danmuManager != null) danmuManager.showDanmuStatus("检测到片尾，即将跳过");
            cancelPendingOutroSkip();
            pendingOutroSkip = () -> {
                pendingOutroSkip = null;
                if (episodeManager != null && episodeManager.hasNext()) episodeManager.playNext();
            };
            handler.postDelayed(pendingOutroSkip, 1000);
            return;
        }
        boolean movie = parentGuid == null || parentGuid.isEmpty();
        if (movie || (episodeManager != null && episodeManager.isLastEpisode())) {
            outroSkipped = true;
            if (danmuManager != null) danmuManager.showDanmuStatus("跳过片尾");
            player.seekTo(Math.max(0, dur - 300));
        }
    }

    /** 把已保存的片头、片尾画到进度条上。片尾记的是距结尾的秒数。 */
    private void refreshSkipMarks() {
        if (skipMarks == null) return;
        long dur = player != null ? player.getDuration() : 0;
        int intro = readSkipSec(true);
        int outro = readSkipSec(false);
        long introAt = intro > 0 ? intro * 1000L : 0;
        long outroAt = outro > 0 && dur > outro * 1000L ? dur - outro * 1000L : 0;
        skipMarks.setMarks(introAt, outroAt, dur);
    }

    private String formatSkipClock(int sec) {
        int s = Math.max(0, sec);
        return (s / 60) + "分" + (s % 60) + "秒";
    }

    private String skipScopeText() {
        String name = itemTV != null && !itemTV.isEmpty() ? itemTV
                : (itemTitle != null && !itemTitle.isEmpty() ? itemTitle : "当前视频");
        if (itemTV != null && !itemTV.isEmpty()) {
            name = name + " · " + (seasonNumber == 0 ? "特别篇" : "第" + seasonNumber + "季");
        }
        return "生效范围: " + name;
    }

    private int dpPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showSkipMenu(android.app.Dialog dialog) {
        SideSheet.setBackAction(dialog, null);
        LinearLayout root = skipSheetPage();
        root.addView(skipHeader(null, "片头片尾", null));
        TextView scope = skipHint(skipScopeText());
        scope.setPadding(0, 0, 0, dpPx(16));
        root.addView(scope);
        View intro = skipLinkRow("片头时长", formatSkipClock(readSkipSec(true)),
                () -> showSkipEditor(dialog, true));
        intro.setTag("skip_focus");
        root.addView(intro);
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(10)));
        root.addView(gap);
        root.addView(skipLinkRow("片尾时长", formatSkipClock(readSkipSec(false)),
                () -> showSkipEditor(dialog, false)));
        focusSkipPage(dialog);
    }

    private void showSkipEditor(android.app.Dialog dialog, boolean intro) {
        SideSheet.setBackAction(dialog, () -> showSkipMenu(dialog));
        LinearLayout root = skipSheetPage();
        TextView clock = new TextView(this);
        clock.setGravity(Gravity.CENTER);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(32);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clock.setPadding(0, dpPx(28), 0, dpPx(28));
        Runnable refresh = () -> clock.setText(formatSkipClock(readSkipSec(intro)));
        refresh.run();
        root.addView(skipHeader(() -> showSkipMenu(dialog), intro ? "片头时长" : "片尾时长", () -> {
            writeSkipSec(intro, 0);
            refresh.run();
        }));
        TextView scope = skipHint(skipScopeText());
        scope.setGravity(Gravity.CENTER);
        scope.setPadding(0, dpPx(4), 0, 0);
        root.addView(scope);
        root.addView(clock);
        View position = skipPositionRow(intro, () -> {
            int sec = currentSkipSeconds(intro);
            if (sec < 0) return;
            writeSkipSec(intro, sec);
            refresh.run();
        });
        position.setTag("skip_focus");
        root.addView(position);
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(10)));
        root.addView(gap);
        View custom = skipLinkRow("自定义", "", () -> showSkipCustom(dialog, intro));
        custom.setTag("skip_custom");
        root.addView(custom);
        focusSkipPage(dialog);
        // 侧边栏会再绑一次焦点，晚一拍盖住它，否则上键到不了重置。
        skipSheetBody.post(() -> skipSheetBody.post(() -> wireSkipEditorFocus(position, custom)));
    }

    /** 片头/片尾设置页：正文上下移动，顶栏返回和重置左右互切。 */
    private void wireSkipEditorFocus(View position, View custom) {
        if (position == null || custom == null || !position.isAttachedToWindow()) return;
        View back = skipSheetBody != null ? skipSheetBody.findViewWithTag("skip_back") : null;
        View reset = skipSheetBody != null ? skipSheetBody.findViewWithTag("skip_reset") : null;
        java.util.List<View> header = new java.util.ArrayList<>();
        if (back != null && back.isFocusable()) header.add(back);
        if (reset != null && reset.isFocusable()) header.add(reset);
        TvFocus.bindVertical(java.util.Collections.singletonList(position),
                java.util.Collections.singletonList(custom));
        TvFocus.point(position, View.FOCUS_LEFT, position);
        TvFocus.point(position, View.FOCUS_RIGHT, position);
        TvFocus.point(custom, View.FOCUS_LEFT, custom);
        TvFocus.point(custom, View.FOCUS_RIGHT, custom);
        TvFocus.point(custom, View.FOCUS_DOWN, custom);
        if (header.isEmpty()) {
            TvFocus.point(position, View.FOCUS_UP, position);
        } else {
            TvFocus.bindRow(header);
            TvFocus.bindVertical(header, java.util.Collections.singletonList(position));
            for (View h : header) TvFocus.point(h, View.FOCUS_UP, h);
        }
        java.util.List<View> all = new java.util.ArrayList<>();
        all.add(position);
        all.add(custom);
        all.addAll(header);
        TvFocus.sealAll(all);
    }

    private int playheadSeconds() {
        if (player == null) return 0;
        return (int) Math.max(0, player.getCurrentPosition() / 1000);
    }

    /** 片尾快捷条显示距结尾还剩多久。片长未知时返回 -1。 */
    private int remainingToEndSeconds() {
        if (player == null) return -1;
        long dur = player.getDuration();
        if (dur <= 0) return -1;
        return (int) Math.max(0, dur / 1000 - playheadSeconds());
    }

    /** 快捷条上跟着走的时间：片头是播放进度，片尾是距片尾。 */
    private int shortcutSeconds(boolean intro) {
        if (intro) return playheadSeconds();
        return Math.max(0, remainingToEndSeconds());
    }

    private int currentSkipSeconds(boolean intro) {
        if (intro) return playheadSeconds();
        int left = remainingToEndSeconds();
        if (left < 0) {
            AppToast.show(this, "还不知道片长，稍后再设片尾");
            return -1;
        }
        return left;
    }

    private void showSkipCustom(android.app.Dialog dialog, boolean intro) {
        SideSheet.setBackAction(dialog, () -> showSkipEditor(dialog, intro));
        int current = readSkipSec(intro);
        LinearLayout root = skipSheetPage();
        root.addView(skipHeader(() -> showSkipEditor(dialog, intro),
                intro ? "自定义片头时长" : "自定义片尾时长", null));

        android.widget.NumberPicker minutes = skipPicker(0, 20, current / 60);
        android.widget.NumberPicker seconds = skipPicker(0, 59, current % 60);
        LinearLayout wheels = new LinearLayout(this);
        LinearLayout.LayoutParams wheelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpPx(180));
        wheelLp.topMargin = dpPx(24);
        wheels.setLayoutParams(wheelLp);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER);
        wheels.addView(minutes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView minUnit = skipHint("分");
        minUnit.setTextSize(16);
        minUnit.setTextColor(Color.WHITE);
        minUnit.setPadding(0, 0, dpPx(12), 0);
        wheels.addView(minUnit);
        wheels.addView(seconds, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView secUnit = skipHint("秒");
        secUnit.setTextSize(16);
        secUnit.setTextColor(Color.WHITE);
        wheels.addView(secUnit);
        root.addView(wheels);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(spacer);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dpPx(12), 0, 0);
        Button reset = skipActionButton("重置", false);
        reset.setOnClickListener(v -> {
            minutes.setValue(0);
            seconds.setValue(0);
        });
        Button ok = skipActionButton("确定", true);
        ok.setTag("wheel_confirm");
        ok.setOnClickListener(v -> {
            writeSkipSec(intro, minutes.getValue() * 60 + seconds.getValue());
            showSkipEditor(dialog, intro);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dpPx(44), 1);
        btnLp.rightMargin = dpPx(8);
        actions.addView(reset, btnLp);
        actions.addView(ok, new LinearLayout.LayoutParams(0, dpPx(44), 1));
        root.addView(actions);
        focusSkipPage(dialog);
        skipSheetBody.post(() -> skipSheetBody.post(() ->
                wireSkipCustomFocus(minutes, seconds, reset, ok)));
    }

    /** 自定义时长：滚轮左右互切，底部重置和确定左右互切，上键回到滚轮。 */
    private void wireSkipCustomFocus(android.widget.NumberPicker minutes,
            android.widget.NumberPicker seconds, View reset, View ok) {
        if ((minutes != null && !minutes.isAttachedToWindow())
                || (seconds != null && !seconds.isAttachedToWindow())) return;
        wireTimeWheels(minutes, seconds, ok);
        if (reset == null || ok == null) return;
        java.util.List<View> actions = java.util.Arrays.asList(reset, ok);
        TvFocus.bindRow(actions);
        java.util.List<View> wheels = TvFocus.present(minutes, seconds);
        if (!wheels.isEmpty()) TvFocus.bindUpToNearest(actions, wheels);
        for (View v : actions) TvFocus.point(v, View.FOCUS_DOWN, v);
        TvFocus.sealAll(actions);
    }

    /** 滚轮左右只互切，上下改数值；确认键再落到确定。 */
    private void wireTimeWheels(android.widget.NumberPicker left, android.widget.NumberPicker right, View confirm) {
        if (left == null || right == null) return;
        java.util.List<View> wheels = java.util.Arrays.asList(left, right);
        TvFocus.bindRow(wheels);
        for (View w : wheels) {
            TvFocus.point(w, View.FOCUS_UP, w);
            TvFocus.point(w, View.FOCUS_DOWN, w);
        }
        TvFocus.sealAll(wheels);
        if (confirm != null) confirm.setTag("wheel_confirm");
    }

    private LinearLayout skipSheetPage() {
        skipSheetBody.removeAllViews();
        return skipSheetBody;
    }

    /** 换页后旧控件被清掉，把焦点落到主选项，避开返回按钮。 */
    private void focusSkipPage(android.app.Dialog dialog) {
        if (dialog == null || skipSheetBody == null) return;
        SideSheet.focus(dialog);
        skipSheetBody.post(() -> {
            View first = skipSheetBody.findViewWithTag("skip_focus");
            if (first == null) first = findFirstSkipType(skipSheetBody, android.widget.NumberPicker.class);
            if (first == null) first = firstSkipFocusable(skipSheetBody);
            if (first == null) return;
            View target = first;
            if (!target.requestFocus()) target.post(target::requestFocus);
            target.post(() -> {
                if (!target.isFocused()) target.requestFocus();
            });
        });
    }

    private View findFirstSkipType(ViewGroup root, Class<?> type) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (type.isInstance(child) && child.isFocusable()) return child;
            if (child instanceof ViewGroup) {
                View found = findFirstSkipType((ViewGroup) child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View firstSkipFocusable(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (isSkipChrome(child)) {
                if (child instanceof ViewGroup) {
                    View found = firstSkipFocusable((ViewGroup) child);
                    if (found != null) return found;
                }
                continue;
            }
            if (child.isFocusable() && child.isClickable()) return child;
            if (child instanceof ViewGroup) {
                View found = firstSkipFocusable((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean isSkipChrome(View v) {
        CharSequence desc = v.getContentDescription();
        if (desc != null && "返回".contentEquals(desc)) return true;
        if (v instanceof Button) {
            CharSequence text = ((Button) v).getText();
            if (text != null && "重置".contentEquals(text)) return true;
        }
        return false;
    }

    private View sheetDivider() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpPx(1)));
        lp.bottomMargin = dpPx(4);
        line.setLayoutParams(lp);
        line.setBackgroundColor(0x33FFFFFF);
        return line;
    }

    private View skipHeader(Runnable back, String title, Runnable reset) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpPx(48));
        row.setClipChildren(true);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (back != null) {
            FrameLayout hit = new FrameLayout(this);
            hit.setLayoutParams(new LinearLayout.LayoutParams(dpPx(48), dpPx(48)));
            hit.setBackgroundResource(R.drawable.bg_player_action);
            hit.setContentDescription("返回");
            hit.setFocusable(true);
            hit.setFocusableInTouchMode(isTvDevice());
            hit.setClickable(true);
            hit.setTag("skip_back");
            androidx.appcompat.widget.AppCompatImageView backBtn =
                    new androidx.appcompat.widget.AppCompatImageView(this);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dpPx(28), dpPx(28));
            iconLp.gravity = Gravity.CENTER;
            backBtn.setLayoutParams(iconLp);
            backBtn.setImageResource(R.drawable.ic_player_back);
            backBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            backBtn.setClickable(false);
            backBtn.setFocusable(false);
            hit.addView(backBtn);
            hit.setOnClickListener(v -> {
                if (back != null) back.run();
            });
            hit.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    back.run();
                    return true;
                }
                return false;
            });
            row.addView(hit);
        }
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20);
        heading.setIncludeFontPadding(false);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(heading);
        if (reset != null) {
            Button resetBtn = skipTextButton("重置");
            resetBtn.setTag("skip_reset");
            resetBtn.setMinHeight(dpPx(36));
            resetBtn.setMinimumHeight(dpPx(36));
            resetBtn.setFocusableInTouchMode(isTvDevice());
            LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dpPx(36));
            resetBtn.setLayoutParams(resetLp);
            resetBtn.setOnClickListener(v -> reset.run());
            row.addView(resetBtn);
        } else {
            View pad = new View(this);
            pad.setLayoutParams(new LinearLayout.LayoutParams(dpPx(40), 1));
            row.addView(pad);
        }
        wrap.addView(row);
        wrap.addView(sheetDivider());
        return wrap;
    }

    private TextView skipHint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFB0B0B0);
        tv.setTextSize(13);
        return tv;
    }

    private View skipPositionRow(boolean intro, Runnable onSet) {
        LinearLayout row = skipCard();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView title = new TextView(this);
        title.setText(intro ? "当前播放时间" : "距离片尾");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        text.addView(title);
        TextView time = skipHint(formatSkipClock(shortcutSeconds(intro)));
        time.setPadding(0, dpPx(4), 0, 0);
        text.addView(time);
        Runnable tickPlayhead = new Runnable() {
            @Override public void run() {
                if (!time.isAttachedToWindow()) return;
                String next = formatSkipClock(shortcutSeconds(intro));
                if (!next.equals(time.getText().toString())) time.setText(next);
                time.postDelayed(this, 200);
            }
        };
        time.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.post(tickPlayhead); }
            @Override public void onViewDetachedFromWindow(View v) { v.removeCallbacks(tickPlayhead); }
        });
        row.addView(text);
        Button set = skipTextButton(intro ? "设为片头" : "设为片尾");
        set.setFocusable(false);
        set.setFocusableInTouchMode(false);
        set.setClickable(true);
        set.setOnClickListener(v -> onSet.run());
        row.setOnClickListener(v -> onSet.run());
        row.addView(set);
        return row;
    }

    private View skipLinkRow(String title, String value, Runnable onClick) {
        LinearLayout row = skipCard();
        row.setOnClickListener(v -> onClick.run());
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);
        if (value != null && !value.isEmpty()) {
            TextView val = skipHint(value);
            val.setPadding(dpPx(8), 0, dpPx(8), 0);
            row.addView(val);
        }
        TextView arrow = new TextView(this);
        arrow.setText(">");
        arrow.setTextColor(0xFFB0B0B0);
        arrow.setTextSize(18);
        row.addView(arrow);
        return row;
    }

    private LinearLayout skipCard() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dpPx(48));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(dpPx(14), dpPx(10), dpPx(14), dpPx(10));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.bg_chip);
        return row;
    }

    private Button skipTextButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(15);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setPadding(dpPx(8), 0, dpPx(8), 0);
        btn.setBackgroundResource(R.drawable.bg_chip);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(isTvDevice());
        return btn;
    }

    private Button skipActionButton(String text, boolean primary) {
        Button btn = skipTextButton(text);
        btn.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_chip);
        return btn;
    }

    private android.widget.NumberPicker skipPicker(int min, int max, int value) {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setFocusable(true);
        picker.setFocusableInTouchMode(false);
        picker.setBackgroundResource(R.drawable.bg_poster_card);
        picker.setPadding(dpPx(8), dpPx(4), dpPx(8), dpPx(4));
        try {
            java.lang.reflect.Field paint = android.widget.NumberPicker.class.getDeclaredField("mSelectorWheelPaint");
            paint.setAccessible(true);
            ((android.graphics.Paint) paint.get(picker)).setColor(Color.WHITE);
            java.lang.reflect.Field divider = android.widget.NumberPicker.class.getDeclaredField("mSelectionDivider");
            divider.setAccessible(true);
            divider.set(picker, new android.graphics.drawable.ColorDrawable(0xFF7AA2FF));
        } catch (Exception ignored) {}
        return picker;
    }

    private void switchMediaSource(boolean toHls) {
        if (player == null || !cloudStreamManager.hasDirectUrl()) return;
        handler.post(() -> {
            String u = cloudStreamManager.getCloudDirectUrl();
            com.google.android.exoplayer2.source.MediaSource ms = buildMediaSource(u, toHls);
            player.stop();
            player.setMediaSource(ms);
            player.prepare();
            player.setPlayWhenReady(true);
        });
    }

    private int sleepChoiceMinutes;

    private final Runnable sleepLabelTick = new Runnable() {
        @Override public void run() {
            if (!moreOpen) return;
            refreshSleepLabel();
            if (SleepTimer.isRunning()) handler.postDelayed(this, 1000);
        }
    };

    private void refreshSleepLabel() {
        if (btnSleep == null) return;
        long left = SleepTimer.remainingMs();
        if (left <= 0) {
            btnSleep.setText("定时关闭");
            sleepChoiceMinutes = 0;
            return;
        }
        long sec = (left + 999) / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        String clock = h > 0
                ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.US, "%02d:%02d", m, s);
        btnSleep.setText("定时关闭  " + clock);
    }

    private void showSleepDialog() {
        if (!SleepTimer.isRunning()) sleepChoiceMinutes = 0;
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        View page = sleepOptionView(dialog);
        dialog.setContentView(page);
        SideSheet.place(dialog);
        if (isTvDevice() && playerView != null) {
            playerView.setFocusable(false);
            SideSheet.afterDismiss(dialog, () -> {
                if (playerView != null && !moreOpen) playerView.setFocusable(true);
            });
        }
        dialog.show();
        SideSheet.focus(dialog);
        focusSleepFirst(page);
    }

    private void focusSleepFirst(View page) {
        if (page == null) return;
        page.post(() -> {
            View first = page.findViewWithTag("sleep_first");
            if (first == null && page instanceof ViewGroup) {
                first = firstSkipFocusable((ViewGroup) page);
            }
            if (first == null) return;
            View target = first;
            if (!target.requestFocus()) target.post(target::requestFocus);
            target.post(() -> {
                if (!target.isFocused()) target.requestFocus();
            });
        });
    }

    private View sleepOptionView(android.app.Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121212);
        root.setPadding(dpPx(20), dpPx(22), dpPx(20), dpPx(16));
        root.setLayoutParams(new FrameLayout.LayoutParams(
                dpPx(320), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        TextView title = new TextView(this);
        title.setText("定时关闭");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setIncludeFontPadding(false);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dpPx(4));
        root.addView(title);
        root.addView(sheetDivider());

        TextView hint = new TextView(this);
        hint.setText("到时间后退出播放");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(13);
        hint.setPadding(0, dpPx(6), 0, dpPx(16));
        root.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int[] minutes = {0, 15, 30, 60};
        String[] labels = {"不开启", "15:00", "30:00", "60:00"};
        for (int i = 0; i < minutes.length; i++) {
            if (i > 0) row.addView(sleepDivider());
            TextView chip = sleepChip(dialog, labels[i], minutes[i], false);
            if (i == 0) chip.setTag("sleep_first");
            row.addView(chip);
        }
        row.addView(sleepDivider());
        row.addView(sleepChip(dialog, "自定义", -1, true));
        root.addView(row);
        return root;
    }

    private View sleepDivider() {
        View line = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpPx(1), dpPx(16));
        lp.leftMargin = dpPx(2);
        lp.rightMargin = dpPx(2);
        line.setLayoutParams(lp);
        line.setBackgroundColor(0x33FFFFFF);
        return line;
    }

    private TextView sleepChip(android.app.Dialog dialog, String label, int minutes, boolean custom) {
        TextView chip = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpPx(40), 1);
        chip.setLayoutParams(lp);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(isTvDevice());
        chip.setClickable(true);
        boolean on = !custom && minutes == sleepChoiceMinutes && (minutes == 0 || SleepTimer.isRunning());
        chip.setTextColor(on ? 0xFF4C8DFF : Color.WHITE);
        chip.setBackgroundResource(R.drawable.bg_player_action);
        chip.setOnClickListener(v -> {
            if (custom) {
                View page = sleepCustomView(dialog);
                dialog.setContentView(page);
                SideSheet.place(dialog);
                SideSheet.ensurePinned(dialog, 320);
                View hours = page.findViewWithTag("sleep_hours");
                View mins = page.findViewWithTag("sleep_mins");
                View confirm = page.findViewWithTag("wheel_confirm");
                if (hours != null) hours.setTag("skip_focus");
                if (hours instanceof android.widget.NumberPicker
                        && mins instanceof android.widget.NumberPicker) {
                    wireTimeWheels((android.widget.NumberPicker) hours,
                            (android.widget.NumberPicker) mins, confirm);
                }
                // 等钉板布局完成后再绑焦点，避免滚轮高亮闪一下就没了
                page.post(() -> page.post(() -> {
                    if (hours instanceof android.widget.NumberPicker
                            && mins instanceof android.widget.NumberPicker) {
                        wireTimeWheels((android.widget.NumberPicker) hours,
                                (android.widget.NumberPicker) mins, confirm);
                    }
                    SideSheet.focus(dialog);
                    if (hours != null) {
                        hours.requestFocus();
                        hours.post(() -> {
                            if (hours.isShown() && !hours.isFocused()) hours.requestFocus();
                        });
                    }
                }));
                return;
            }
            sleepChoiceMinutes = minutes;
            if (minutes <= 0) SleepTimer.cancel();
            else SleepTimer.startMinutes(this, minutes);
            refreshSleepLabel();
            if (SleepTimer.isRunning()) handler.postDelayed(sleepLabelTick, 1000);
            dialog.dismiss();
        });
        return chip;
    }

    private View sleepCustomView(android.app.Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121212);
        root.setPadding(dpPx(20), dpPx(22), dpPx(20), dpPx(16));
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                dpPx(320), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        TextView title = new TextView(this);
        title.setText("自定义");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout wheels = new LinearLayout(this);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(android.view.Gravity.CENTER);
        wheels.setPadding(0, dpPx(18), 0, dpPx(12));
        android.widget.NumberPicker hours = skipPicker(0, 12, 0);
        hours.setTag("sleep_hours");
        hours.setFocusableInTouchMode(isTvDevice());
        android.widget.NumberPicker mins = skipPicker(0, 59, 0);
        mins.setTag("sleep_mins");
        mins.setFocusableInTouchMode(isTvDevice());
        hours.setClickable(true);
        mins.setClickable(true);
        wheels.addView(hours, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView hourLabel = new TextView(this);
        hourLabel.setText("小时");
        hourLabel.setTextColor(Color.WHITE);
        hourLabel.setTextSize(15);
        hourLabel.setPadding(dpPx(6), 0, dpPx(12), 0);
        wheels.addView(hourLabel);
        wheels.addView(mins, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView minLabel = new TextView(this);
        minLabel.setText("分钟");
        minLabel.setTextColor(Color.WHITE);
        minLabel.setTextSize(15);
        minLabel.setPadding(dpPx(6), 0, 0, 0);
        wheels.addView(minLabel);
        root.addView(wheels);

        Button ok = new Button(this);
        ok.setText("确定");
        ok.setTextColor(Color.WHITE);
        ok.setTextSize(16);
        ok.setAllCaps(false);
        ok.setBackgroundResource(R.drawable.bg_player_action);
        ok.setFocusable(true);
        ok.setClickable(true);
        ok.setTag("wheel_confirm");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpPx(44));
        okLp.topMargin = dpPx(8);
        ok.setLayoutParams(okLp);
        ok.setOnClickListener(v -> {
            int total = hours.getValue() * 60 + mins.getValue();
            if (total <= 0) {
                sleepChoiceMinutes = 0;
                SleepTimer.cancel();
            } else {
                sleepChoiceMinutes = total;
                SleepTimer.startMinutes(this, total);
            }
            refreshSleepLabel();
            if (SleepTimer.isRunning()) handler.postDelayed(sleepLabelTick, 1000);
            dialog.dismiss();
        });
        root.addView(ok);
        wireTimeWheels(hours, mins, ok);
        return root;
    }

    private void showBrightnessDialog() {
        SharedPreferences p = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        int brightness = p.getInt("video_brightness", 100);
        if (brightness > 100) brightness = 100;
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        dialog.setContentView(R.layout.dialog_brightness);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xDD1A1A1A));

        final TextView label = dialog.findViewById(R.id.dm_label);
        final SeekBar sb = dialog.findViewById(R.id.dm_seekbar);
        final Button cancel = dialog.findViewById(R.id.dm_cancel);
        final Button ok = dialog.findViewById(R.id.dm_ok);
        final Button reset = dialog.findViewById(R.id.dm_reset);

        if (label != null) label.setText("亮度: " + (brightness - 100) + "%");
        if (sb != null) {
            sb.setMax(200);
            sb.setProgress(brightness);
            sb.setKeyProgressIncrement(5);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek, int val, boolean fromUser) {
                    int adj = val - 100;
                    if (label != null) label.setText("亮度: " + (adj > 0 ? "+" : "") + adj + "%");
                    if (fromUser) applyBrightness(val);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
        if (reset != null) reset.setOnClickListener(v -> { if (sb != null) { sb.setProgress(100); applyBrightness(100); if (label != null) label.setText("亮度: 0%"); } });
        if (cancel != null) cancel.setOnClickListener(v -> dialog.dismiss());
        if (ok != null) ok.setOnClickListener(v -> {
            if (sb != null) p.edit().putInt("video_brightness", sb.getProgress()).apply();
            dialog.dismiss();
        });
        SideSheet.place(dialog);
        dialog.show();
        SideSheet.focus(dialog);
    }

    /** 切换 HDR 开关 */
    private void toggleHdr() {
        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        boolean wasEnabled = prefs.getBoolean("hdr_enabled", false);
        prefs.edit().putBoolean("hdr_enabled", !wasEnabled).apply();
        applyHdrMode();
        updateHdrButtonText();
    }

    /** 更新 HDR 按钮文字 */
    private void updateHdrButtonText() {
        boolean enabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", false);
        boolean videoHdr = isHdrVideo();
        String label = videoHdr ? (enabled ? "HDR:开" : "HDR:关") : "HDR";
        int color = videoHdr ? (enabled ? 0xFF81C784 : 0xFFE57373) : 0xFFB0B0B0;
        if (btnHdrToggle != null) {
            btnHdrToggle.setText(label);
            btnHdrToggle.setTextColor(color);
        }
        if (btnHdrRow != null) {
            btnHdrRow.setText(label);
            btnHdrRow.setTextColor(color);
        }
    }

    private void applyHdrMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        boolean enabled = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getBoolean("hdr_enabled", false);
        boolean videoHdr = isHdrVideo();
        Log.d(TAG, "applyHdrMode: enabled=" + enabled + " videoHdr=" + videoHdr);
        if (enabled && videoHdr) {
            getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
            danmuManager.showDanmuStatus("HDR 已开启");
        } else {
            getWindow().setColorMode(0);
            if (videoHdr) danmuManager.showDanmuStatus("HDR 已关闭");
        }
    }

    private boolean isHdrVideo() {
        Format vf = player != null ? player.getVideoFormat() : null;
        if (vf != null && vf.colorInfo != null) {
            int cs = vf.colorInfo.colorSpace;
            int ct = vf.colorInfo.colorTransfer;
            if (cs >= 6 && (ct == 7 || ct == 16 || ct == 18)) return true;
        }
        // streamVHdr（杜比视界）→ 仅在设备支持 Dolby Vision 时算 HDR
        if (streamVHdr) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.view.Display.HdrCapabilities caps = getWindowManager()
                        .getDefaultDisplay().getHdrCapabilities();
                if (caps != null) {
                    for (int type : caps.getSupportedHdrTypes()) {
                        if (type == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
                    }
                }
            }
            return false;
        }
        return !streamVColor.isEmpty() && (streamVColor.contains("bt2020") || streamVColor.contains("2020"));
    }

    /** 调节屏幕亮度（仅当前 Activity），val 0~200，100=系统默认 */
    private void applyBrightness(int val) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (val == 100) {
            lp.screenBrightness = -1f; // 恢复系统默认
        } else {
            float f = val / 100f;
            f = Math.max(0.01f, Math.min(1.0f, f));
            lp.screenBrightness = f;
        }
        getWindow().setAttributes(lp);
    }

    private void toggleInfo() {
        infoVis = !infoVis;
        infoPanel.setVisibility(infoVis ? View.VISIBLE : View.GONE);
        if (infoVis) {
            // 信息面板打开时，禁止焦点跳到其他控件
            ((ViewGroup) controller).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            ((ViewGroup) topBar).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            // 信息面板内焦点全方向循环（防止方向键逃出面板）
            View btnHdr = findViewById(R.id.btnHdrToggle);
            if (btnHdr != null) {
                btnHdr.setNextFocusUpId(btnCloseInfo.getId());
                btnHdr.setNextFocusDownId(btnCloseInfo.getId());
                btnHdr.setNextFocusLeftId(btnCloseInfo.getId());
                btnHdr.setNextFocusRightId(btnCloseInfo.getId());
            }
            int closeTarget = btnHdr != null ? btnHdr.getId() : btnCloseInfo.getId();
            btnCloseInfo.setNextFocusDownId(closeTarget);
            btnCloseInfo.setNextFocusUpId(closeTarget);
            btnCloseInfo.setNextFocusLeftId(closeTarget);
            btnCloseInfo.setNextFocusRightId(closeTarget);
            updateInfo();
            btnCloseInfo.post(() -> btnCloseInfo.requestFocus());
        } else {
            // 关闭时恢复焦点导航
            ((ViewGroup) controller).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            ((ViewGroup) topBar).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            // 确保控制栏可见，否则焦点无法设置到按钮上；焦点回到暂停按钮
            showCtrl(true);
            btnPlayPause.postDelayed(() -> btnPlayPause.requestFocus(), 50);
        }
    }

    private void updateTitle() {
        int epNum = getIntent().getIntExtra("episode_number", 0);
        String epName = itemTitle != null ? itemTitle : "";
        StringBuilder sb = new StringBuilder();
        if (itemTV != null && !itemTV.isEmpty()) {
            sb.append(itemTV);
            if (epNum > 0) sb.append(" 第").append(epNum).append("集");
            if (epName != null && !epName.isEmpty() && !epName.equals(itemTV)) {
                sb.append(" ").append(epName);
            }
        } else {
            sb.append(epName);
        }
        tvTitle.setText(sb.toString().trim());
    }

    private void showCtrl(boolean show) {
        if (show && isLocked) {
            showLockButton(true);
            applyChromeSystemUi(true);
            return;
        }
        if (show && moreOpen) {
            ctrlVis = true;
            applyChromeSystemUi(true);
            return;
        }
        if (!show) {
            moreOpen = false;
            if (moreScrim != null) moreScrim.setVisibility(View.GONE);
        }
        ctrlVis = show;
        if (!show) holdResumePrompt(false);
        controller.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) holdResumePrompt(true);
        topBar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        showLockButton(show);
        if (show) {
            updateTitle();
            syncPlayButton();
            resetHideTimer();
            controller.post(this::wirePlayerFocus);
        }
        applyChromeSystemUi(show);
    }

    /** 电视不提供锁屏，按钮收起且不可聚焦。 */
    private void showLockButton(boolean show) {
        if (btnLock == null) return;
        if (isTvDevice()) {
            btnLock.setVisibility(View.GONE);
            btnLock.setFocusable(false);
            btnLock.setFocusableInTouchMode(false);
            return;
        }
        btnLock.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    /** 侧边栏打开时收起状态栏，关掉后若菜单栏还在再露出来。 */
    void suppressChromeForOverlay() {
        hideSystemUi();
        if (topBar != null) topBar.setPadding(0, 0, 0, topBar.getPaddingBottom());
    }

    void restoreChromeSystemUi() {
        applyChromeSystemUi(ctrlVis && !isLocked && !moreOpen);
    }

    /** 手机弹出控制栏时露出状态栏。电视保持全屏。 */
    private void applyChromeSystemUi(boolean controlsVisible) {
        if (isTvDevice() || !controlsVisible) {
            hideSystemUi();
            if (topBar != null) topBar.setPadding(0, 0, 0, topBar.getPaddingBottom());
            return;
        }
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController insets = decor.getWindowInsetsController();
            if (insets != null) {
                insets.show(android.view.WindowInsets.Type.statusBars());
                insets.hide(android.view.WindowInsets.Type.navigationBars());
                insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }
        try {
            new androidx.core.view.WindowInsetsControllerCompat(getWindow(), decor)
                    .setAppearanceLightStatusBars(false);
        } catch (Exception ignored) {}
        int inset = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) inset = getResources().getDimensionPixelSize(resId);
        if (topBar != null) topBar.setPadding(0, inset, 0, topBar.getPaddingBottom());
    }

    private void showMore(boolean show) {
        moreOpen = show;
        if (moreScrim != null) moreScrim.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            holdResumePrompt(false);
            controller.setVisibility(View.INVISIBLE);
            topBar.setVisibility(View.INVISIBLE);
            showLockButton(false);
            View panel = findViewById(R.id.morePanel);
            if (panel != null) {
                ViewGroup.LayoutParams panelLp = panel.getLayoutParams();
                panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                panel.setLayoutParams(panelLp);
                panel.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                panel.setPadding(pad, pad, pad, pad);
            }
            suppressChromeForOverlay();
            refreshSleepLabel();
            handler.removeCallbacks(sleepLabelTick);
            if (SleepTimer.isRunning()) handler.postDelayed(sleepLabelTick, 1000);
            handler.removeCallbacks(hideC);
            if (topBar != null) topBar.clearFocus();
            if (controller != null) controller.clearFocus();
            if (btnLock != null) btnLock.clearFocus();
            if (playerView != null) playerView.setFocusable(false);
            if (moreScrim != null) {
                moreScrim.setFocusable(false);
                moreScrim.post(this::focusMorePanel);
            }
        } else if (ctrlVis && !isLocked) {
            if (playerView != null) playerView.setFocusable(true);
            controller.setVisibility(View.VISIBLE);
            holdResumePrompt(true);
            topBar.setVisibility(View.VISIBLE);
            showLockButton(true);
            resetHideTimer();
            applyChromeSystemUi(true);
            controller.post(this::wirePlayerFocus);
        }
    }

    private void resetHideTimer() {
        handler.removeCallbacks(hideC);
        handler.postDelayed(hideC, 3000);
    }
    private final Runnable hideC = () -> {
        if (moreOpen || infoVis) {
            resetHideTimer();
            return;
        }
        // 有焦点也要收起（TV 首次进播放器焦点在进度条上，否则菜单永远不消失）
        boolean keepResumeFocus = resumeBar != null && resumeBar.getVisibility() == View.VISIBLE
                && btnRestart != null && btnRestart.hasFocus();
        if (controller != null && !keepResumeFocus) controller.clearFocus();
        if (topBar != null) topBar.clearFocus();
        if (btnLock != null) btnLock.clearFocus();
        if (!keepResumeFocus && playerView != null) {
            playerView.setFocusable(true);
            playerView.requestFocus();
        }
        showCtrl(false);
    };

    private boolean chromeHasFocus() {
        return (controller != null && controller.hasFocus())
                || (topBar != null && topBar.hasFocus())
                || (btnLock != null && btnLock.hasFocus());
    }

    private void wirePlayerFocus() {
        List<View> top = TvFocus.present(btnBack, btnMore);
        List<View> sides = TvFocus.present(btnLock);
        List<View> seek = TvFocus.present(seekBar);
        List<View> bottom = bottomControlButtons();
        TvFocus.bindRow(top);
        TvFocus.bindRow(sides);
        TvFocus.bindRow(bottom);
        if (!top.isEmpty() && !sides.isEmpty()) TvFocus.bindVertical(top, sides);
        else if (!top.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(top, seek);
        if (!sides.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(sides, seek);
        else if (!top.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(top, seek);
        if (!seek.isEmpty() && !bottom.isEmpty()) {
            // 进度条下移：首次暂停，之后回到上次选过的底栏按钮
            TvFocus.bindDownToRow(seek, bottom);
            TvFocus.bindUp(bottom, seek.get(0));
        }
        if (resumeBar != null && resumeBar.getVisibility() == View.VISIBLE && btnRestart != null && !seek.isEmpty()) {
            java.util.List<View> resume = TvFocus.present(btnRestart);
            if (!resume.isEmpty()) {
                TvFocus.bindVertical(resume, seek);
                for (View v : resume) {
                    TvFocus.point(v, View.FOCUS_UP, v);
                    TvFocus.point(v, View.FOCUS_LEFT, v);
                    TvFocus.point(v, View.FOCUS_RIGHT, v);
                }
                TvFocus.sealAll(resume);
            }
        }
        for (View v : top) TvFocus.point(v, View.FOCUS_UP, v);
        for (View v : bottom) TvFocus.point(v, View.FOCUS_DOWN, v);
        TvFocus.sealAll(top);
        TvFocus.sealAll(sides);
        TvFocus.sealAll(seek);
        TvFocus.sealAll(bottom);
    }

    /** 底栏可落焦点的按钮（顺序：暂停优先为首次下移目标）。 */
    private List<View> bottomControlButtons() {
        View btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        View btnAudioTrack = findViewById(R.id.btnAudioTrack);
        return TvFocus.present(btnPlayPause, btnNextEp, btnEpisodeList, btnSkip, btnDanmu, btnSpeed,
                btnAudioTrack, btnSubtitleTrack, btnQuality);
    }

    /** 从进度条/画面下移时的落点：有记忆用记忆，否则暂停。 */
    private View seekBarDownTarget() {
        View pick = TvFocus.pickEntry(bottomControlButtons());
        return pick != null ? pick : btnPlayPause;
    }

    private void focusMorePanel() {
        View panel = findViewById(R.id.morePanel);
        if (panel instanceof ViewGroup) {
            relaxScrollerFocus((ViewGroup) panel);
        }
        wireMoreFocus();
        List<View> rows = TvFocus.present(btnSleep, btnCloudMode, btnInfo, btnBrightness, btnHdrRow);
        if (rows.isEmpty()) return;
        for (View row : rows) row.setFocusableInTouchMode(true);
        if (ratioChips != null) {
            for (Button chip : ratioChips) {
                if (chip != null) chip.setFocusableInTouchMode(true);
            }
        }
        View first = rows.get(0);
        if (!first.requestFocus()) first.post(first::requestFocus);
    }

    private boolean focusInMore() {
        View focused = getCurrentFocus();
        View panel = findViewById(R.id.morePanel);
        View v = focused;
        while (v != null) {
            if (v == panel) return true;
            Object parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void relaxScrollerFocus(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.ScrollView) {
                child.setFocusable(false);
                ((android.widget.ScrollView) child).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            } else if (child instanceof ViewGroup) {
                relaxScrollerFocus((ViewGroup) child);
            }
        }
    }

    private void wireMoreFocus() {
        List<View> rows = TvFocus.present(btnSleep, btnCloudMode, btnInfo, btnBrightness, btnHdrRow);
        List<View> ratios = new java.util.ArrayList<>();
        if (ratioChips != null) {
            for (Button chip : ratioChips) {
                if (chip != null && chip.getVisibility() == View.VISIBLE) ratios.add(chip);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            List<View> one = java.util.Collections.singletonList(rows.get(i));
            if (i + 1 < rows.size()) {
                TvFocus.bindVertical(one, java.util.Collections.singletonList(rows.get(i + 1)));
            } else if (!ratios.isEmpty()) {
                TvFocus.bindVertical(one, ratios);
            }
            TvFocus.point(rows.get(i), View.FOCUS_LEFT, rows.get(i));
            TvFocus.point(rows.get(i), View.FOCUS_RIGHT, rows.get(i));
        }
        if (!rows.isEmpty()) TvFocus.point(rows.get(0), View.FOCUS_UP, rows.get(0));
        if (!ratios.isEmpty()) {
            TvFocus.bindRow(ratios);
            for (View chip : ratios) TvFocus.point(chip, View.FOCUS_DOWN, chip);
            TvFocus.sealAll(ratios);
        } else if (!rows.isEmpty()) {
            TvFocus.point(rows.get(rows.size() - 1), View.FOCUS_DOWN, rows.get(rows.size() - 1));
        }
        TvFocus.sealAll(rows);
    }

    private void setupFocusAutoHide() {
        View.OnFocusChangeListener l = (v, hasFocus) -> {
            if (hasFocus) resetHideTimer();
        };
        if (seekBar != null) seekBar.setOnFocusChangeListener(l);
        btnPlayPause.setOnFocusChangeListener(l);
        btnSpeed.setOnFocusChangeListener(l);
        btnInfo.setOnFocusChangeListener(l);
        if (btnQuality != null) btnQuality.setOnFocusChangeListener(l);
        btnEpisodeList.setOnFocusChangeListener(l);
        btnNextEp.setOnFocusChangeListener(l);
        btnBack.setOnFocusChangeListener(l);
        if (btnMore != null) btnMore.setOnFocusChangeListener(l);
        btnDanmu.setOnFocusChangeListener(l);
        btnLock.setOnFocusChangeListener(l);
        btnCloudMode.setOnFocusChangeListener(l);
        if (btnBrightness != null) btnBrightness.setOnFocusChangeListener(l);
        if (btnHdrRow != null) btnHdrRow.setOnFocusChangeListener(l);
        if (btnSkip != null) btnSkip.setOnFocusChangeListener(l);
        if (ratioChips != null) {
            for (Button chip : ratioChips) {
                if (chip != null) chip.setOnFocusChangeListener(l);
            }
        }
        View btnAudioTrack = findViewById(R.id.btnAudioTrack);
        View btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        if (btnAudioTrack != null) btnAudioTrack.setOnFocusChangeListener(l);
        if (btnSubtitleTrack != null) btnSubtitleTrack.setOnFocusChangeListener(l);
        if (btnHdrToggle != null) btnHdrToggle.setOnFocusChangeListener(l);
        btnCloseInfo.setOnFocusChangeListener(l);
        infoPanel.setOnFocusChangeListener(l);
    };

    private void updateTime() {
        if (player == null) return;
        long cur = player.getCurrentPosition(), dur = player.getDuration();
        // TIME_UNSET 为负数，不能当 max
        seekBar.setMax((int) Math.max(dur > 0 ? dur : 1, 1));
        refreshSkipMarks();
        seekBar.setKeyProgressIncrement(5000); // 方向键每次 5 秒
        // 防抖期间不覆盖 UI，避免抽搐（tvTime 和 seekBar 进度由 onProgressChanged 控制）
        if (pendingSeekMs < 0) {
            tvTime.setText(playClock(cur, dur > 0 ? dur : 0));
            seekBar.setProgress((int) Math.max(cur, 0));
        }
        // 显示缓冲进度（灰色条）
        long buffered = player.getBufferedPosition();
        seekBar.setSecondaryProgress((int) buffered);
        if (danmuManager != null) danmuManager.setPlayTime(cur);
        if (!progressHeld) {
            maybeSkipIntro();
            maybeSkipOutro(cur, dur);
        }
        handler.postDelayed(timeR, 200);
    }
    private final Runnable timeR = () -> {
        if (player != null) {
            int state = player.getPlaybackState();
            if (player.isPlaying() || state == Player.STATE_BUFFERING || state == Player.STATE_READY) {
                updateTime();
            }
        }
    };

    private void probeWithMediaExtractor() {
        if (mediaGuid == null || baseUrl == null) return;
        final String url = baseUrl + "/v/api/v1/media/range/" + mediaGuid;
        new Thread(() -> {
            try {
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                try {
                    ex.setDataSource(url);
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        android.media.MediaFormat mf = ex.getTrackFormat(i);
                        String mime = mf.getString(android.media.MediaFormat.KEY_MIME);
                        if (mime == null) continue;
                        if (mime.startsWith("video/")) {
                            if (streamVWidth <= 0) streamVWidth = mf.containsKey(android.media.MediaFormat.KEY_WIDTH) ? mf.getInteger(android.media.MediaFormat.KEY_WIDTH) : 0;
                            if (streamVHeight <= 0) streamVHeight = mf.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? mf.getInteger(android.media.MediaFormat.KEY_HEIGHT) : 0;
                            if (streamBitrate <= 0) streamBitrate = mf.containsKey(android.media.MediaFormat.KEY_BIT_RATE) ? mf.getInteger(android.media.MediaFormat.KEY_BIT_RATE) : 0;
                            if (streamVCodec.isEmpty()) streamVCodec = mime.replace("video/", "");
                        }
                    }
                } finally { ex.release(); }
            } catch (Exception e) {
                Log.w(TAG, "MediaExtractor 失败: " + e.getMessage());
            }
        }).start();
    }

    private void updateInfo() {
        if (player == null) return;
        Format vf = player.getVideoFormat();
        Format af = player.getAudioFormat();

        // 视频（左列）
        StringBuilder v = new StringBuilder();
        v.append("── 视频 ──\n");
        String codec = FormatUtils.fmtVideoCodec(streamVCodec.isEmpty() ? (vf != null ? vf.codecs : null) : streamVCodec);
        v.append("编码 ").append(codec).append("\n");
        // 优先用 ExoPlayer 实际解码的格式（切换画质后自动更新）
        int w = vf != null && vf.width > 0 ? vf.width : streamVWidth;
        int h = vf != null && vf.height > 0 ? vf.height : streamVHeight;
        if (w > 0 && h > 0) v.append("分辨率 ").append(w).append("×").append(h).append("\n");
        float fps = 0;
        if (!streamVFps.isEmpty()) { try { fps = Float.parseFloat(streamVFps.replaceAll("[^0-9.]", "")); } catch (Exception ignored) {} }
        if (fps <= 0 && vf != null) fps = vf.frameRate;
        if (fps > 0) v.append("帧率 ").append(String.format("%.3f fps", fps)).append("\n");
        if (vf != null && vf.bitrate > 0) v.append("码率 ").append(FormatUtils.formatBitrate(vf.bitrate)).append("\n");
        else if (streamBitrate > 0) v.append("码率 ").append(FormatUtils.formatBitrate(streamBitrate)).append("\n");
        if (streamVBitDepth > 0) v.append("色深 ").append(streamVBitDepth).append("bit\n");
        if (streamVHdr || (vf != null && vf.colorInfo != null)) v.append("HDR10\n");
        v.append("解码 ").append(actualVideoDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualVideoDecoder);
        infoText.setText(v.toString());

        // 音频（右列）
        StringBuilder a = new StringBuilder();
        a.append("── 音频 ──\n");
        if (af != null) {
            String ac = FormatUtils.fmtAudioCodec(af.codecs != null ? af.codecs : af.sampleMimeType);
            a.append("编码 ").append(ac).append("\n");
            int ch = af.channelCount;
            a.append("声道 ").append(ch > 0 ? (ch == 8 ? "7.1" : ch == 6 ? "5.1" : ch + "ch") : "?").append("\n");
            a.append("采样 ").append(af.sampleRate > 0 ? af.sampleRate + "Hz" : "?").append("\n");
            if (af.bitrate > 0) a.append("码率 ").append(af.bitrate/1000).append("kbps\n");
            a.append("解码 ").append(actualAudioDecoder.isEmpty() ? (isHwDecode ? "硬解" : "软解") : actualAudioDecoder);
            // 显示用户选择的音轨（如有）
            String selAudio = cloudStreamManager != null ? cloudStreamManager.getLastAudioTrackLabel() : "";
            if (!selAudio.isEmpty() && !selAudio.equals("默认")) {
                a.append("\n已选 ").append(selAudio);
            }
        } else {
            a.append("无音轨\n");
        }
        if (infoTextAudio != null) infoTextAudio.setText(a.toString());

        // 额外信息（字幕、音轨、时长）
        StringBuilder x = new StringBuilder();
        // 额外音轨
        if (streamAudioTracks != null && streamAudioTracks.size() > 1) {
            for (int i = 1; i < streamAudioTracks.size(); i++) {
                StreamResponse.AudioStreamInfo asi = streamAudioTracks.get(i);
                String an = FormatUtils.fmtAudioCodec(asi.codecName);
                String al = asi.language != null && !asi.language.isEmpty() ? asi.language : "";
                String ach = asi.channels > 0 ? (asi.channels == 8 ? "7.1" : asi.channels == 6 ? "5.1" : asi.channels + "ch") : "?";
                String ab = asi.bps > 0 ? " " + FormatUtils.formatBitrate(asi.bps) : "";
                x.append("音轨").append(i + 1).append(" ").append(an);
                if (!al.isEmpty()) x.append(" ").append(al);
                x.append(" ").append(ach).append(ab).append("  ");
            }
        }
        // 字幕
        if (streamSubtitleTracks != null && !streamSubtitleTracks.isEmpty()) {
            if (x.length() > 0) x.append("\n");
            x.append("字幕 ");
            for (int i = 0; i < streamSubtitleTracks.size(); i++) {
                StreamResponse.SubtitleStreamInfo sub = streamSubtitleTracks.get(i);
                if (i > 0) x.append("  ");
                String sf = sub.codecName != null ? sub.codecName.toUpperCase() : "?";
                String lang = sub.language != null && !sub.language.isEmpty() ? sub.language : "?";
                String def = sub.isDefault != 0 ? "[默认]" : "";
                x.append(sf).append(" ").append(lang).append(def);
            }
        }
        // 时长
        long durMs = player.getDuration();
        if (durMs > 0) {
            if (x.length() > 0) x.append("\n");
            x.append("时长 ").append(FormatUtils.fmtTime((int)(durMs/1000)));
        }
        if (infoTextExtra != null) infoTextExtra.setText(x.toString());
    }

    // ========== 弹幕全部移至 DanmuManager ==========

    // ========== 进度保存 ==========

    static boolean hasPendingRecord() {
        return recordsInFlight > 0;
    }

    private void ensureSaveLoop() {
        if (saveLoopStarted) return;
        saveLoopStarted = true;
        handler.postDelayed(saveR, 10000);
    }

    private void stopSave() {
        saveLoopStarted = false;
        handler.removeCallbacks(saveR);
    }

    private final Runnable saveR = new Runnable() {
        @Override public void run() {
            saveProgress(false);
            if (saveLoopStarted) handler.postDelayed(this, 15000);
        }
    };

    /** 等跳转进度或片头跳过落到播放器后再记第一笔。 */
    private void scheduleInitialSave() {
        handler.removeCallbacks(initialSaveR);
        handler.postDelayed(initialSaveR, 800);
    }

    private void noteProgress() {
        if (progressHeld || player == null) return;
        long p = player.getCurrentPosition();
        if (p > 0) lastProgressMs = p;
    }

    private void saveProgress() {
        saveProgress(true);
    }

    private void saveProgress(boolean force) {
        if (progressHeld) return;
        noteProgress();
        if (itemGuid == null || itemGuid.isEmpty() || lastProgressMs < 1000) return;
        long ts = lastProgressMs / 1000;
        long now = android.os.SystemClock.uptimeMillis();
        if (ts == lastSavedTs && now - lastSaveUptime < 2000) return;
        if (!force && lastSaveUptime > 0 && now - lastSaveUptime < 8000 && ts - lastSavedTs < 15) return;
        if (mediaGuid == null || mediaGuid.isEmpty()) return;

        long durationSec = itemDuration;
        if (durationSec <= 0 && player != null) {
            long d = player.getDuration();
            if (d > 0) durationSec = d / 1000;
        }
        Map<String, Object> r = new HashMap<>();
        r.put("item_guid", itemGuid);
        r.put("media_guid", mediaGuid);
        r.put("video_guid", videoGuid != null ? videoGuid : "");
        r.put("audio_guid", audioGuid != null ? audioGuid : "");
        r.put("subtitle_guid", subtitleGuid != null ? subtitleGuid : "_no_display_");
        if (customQualityBitrate > 0 && !customQualityRes.isEmpty()) {
            r.put("resolution", customQualityRes);
            r.put("bitrate", customQualityBitrate);
            if (!customPlayLink.isEmpty()) r.put("play_link", customPlayLink);
        } else {
            r.put("resolution", !streamResolution.isEmpty() ? streamResolution : (resolution != null ? resolution : ""));
            r.put("bitrate", streamBitrate);
        }
        r.put("ts", ts);
        r.put("duration", durationSec);
        lastSaveUptime = now;
        lastSavedTs = ts;
        apiManager.setReferer(baseUrl + "/v/video/" + itemGuid + "?media_guid=" + mediaGuid);
        sendRecord(r, ts, 0);
    }

    private void sendRecord(Map<String, Object> body, long ts, int attempt) {
        recordsInFlight++;
        Log.d(TAG, "recordPlayStatus 请求: " + new com.google.gson.Gson().toJson(body) + " attempt=" + attempt);
        apiManager.getApi().recordPlayStatus(body).enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<Object>> call, retrofit2.Response<ApiResponse<Object>> response) {
                recordsInFlight = Math.max(0, recordsInFlight - 1);
                String respBody = response.body() != null
                        ? "code=" + response.body().code + " msg='" + response.body().msg + "' data=" + response.body().data
                        : "nullBody";
                Log.d(TAG, "recordPlayStatus 响应: HTTP " + response.code() + " " + respBody
                        + " (raw: " + (response.body() != null ? new com.google.gson.Gson().toJson(response.body()) : "null") + ")");
                boolean ok = response.isSuccessful() && response.body() != null && response.body().code == 0;
                if (!ok) retryRecord(body, ts, attempt);
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                recordsInFlight = Math.max(0, recordsInFlight - 1);
                Log.e(TAG, "recordPlayStatus 失败: " + t.getMessage());
                retryRecord(body, ts, attempt);
            }
        });
    }

    private void retryRecord(Map<String, Object> body, long ts, int attempt) {
        if (attempt >= 2 || ts < lastSavedTs) return;
        handler.postDelayed(() -> {
            if (ts < lastSavedTs) return;
            sendRecord(body, ts, attempt + 1);
        }, 3000);
    }

    // ========== 按键 ==========

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (SideSheet.onActivityKey(event)) return true;
        if (moreOpen && event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                showMore(false);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!focusInMore()) focusMorePanel();
                else TvFocus.move(getCurrentFocus(), k);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (nextEpCountdownRunning
                && (episodeManager == null || !episodeManager.isPickerShowing())) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                cancelNextEpisodeCountdown();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                playPendingNextEpisode();
                return true;
            }
        }
        if (episodeManager != null && episodeManager.isPickerShowing()) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                episodeManager.dismissPicker();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                View focused = getCurrentFocus();
                if (focused != null && focused.isClickable()) focused.performClick();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!TvFocus.move(getCurrentFocus(), k)) {
                    View focused = getCurrentFocus();
                    if (focused != null) focused.requestFocus();
                }
                return true;
            }
        }
        if (isLocked) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (btnLock.hasFocus() || controller.hasFocus()) {
                    controller.clearFocus();
                    btnLock.clearFocus();
                    return true;
                }
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_unlock);
                showCtrl(true);
                btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                isLocked = false;
                btnLock.setImageResource(R.drawable.ic_unlock);
                showCtrl(true);
                btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                return true;
            }
            return true;
        }
        if (ctrlVis) {
            // 遥控操作中保持控制栏，松手空闲后再自动收起
            if (k != KeyEvent.KEYCODE_BACK) resetHideTimer();
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (moreOpen) { showMore(false); return true; }
                    if (infoVis) { toggleInfo(); return true; }
                    // 有控件焦点 → 清掉，自动回退到 playerView
                    if (controller.hasFocus() || btnLock.hasFocus() || topBar.hasFocus()
                            || (moreScrim != null && moreScrim.hasFocus())) {
                        topBar.clearFocus();
                        controller.clearFocus();
                        btnLock.clearFocus();
                        return true;
                    }
                    // 无按钮焦点（playerView 或其它）→ 收起控制栏
                    showCtrl(false);
                    return true;
                // LEFT/RIGHT 由 SeekBar 自身处理（已设 keyProgressIncrement=5000）
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (moreOpen) {
                        if (!focusInMore()) focusMorePanel();
                        return true;
                    }
                    View subtitleBtn = findViewById(R.id.btnSubtitleTrack);
                    View audioBtn = findViewById(R.id.btnAudioTrack);
                    if (seekBar.hasFocus() || btnSpeed.hasFocus()
                            || btnEpisodeList.hasFocus() || btnNextEp.hasFocus()
                            || btnSkip.hasFocus() || btnQuality.hasFocus() || btnMore.hasFocus()
                            || (subtitleBtn != null && subtitleBtn.hasFocus())
                            || (audioBtn != null && audioBtn.hasFocus())) {
                        return true;
                    }
                    togglePlay(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (!infoVis && !moreOpen && topBar.hasFocus()) {
                        View next = TvFocus.resolve(getCurrentFocus(), View.FOCUS_UP);
                        if (next == null || next == getCurrentFocus()) {
                            showCtrl(false);
                            return true;
                        }
                    }
                    break;
                case KeyEvent.KEYCODE_MENU:
                    showMore(!moreOpen);
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    toggleInfo(); return true;
            }
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && seekBar != null && seekBar.hasFocus() && !moreOpen) {
                return super.onKeyDown(k, e);
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && seekBar != null && seekBar.hasFocus() && !moreOpen) {
                View target = seekBarDownTarget();
                if (target != null) {
                    target.requestFocus();
                    return true;
                }
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (moreOpen && !focusInMore()) {
                    focusMorePanel();
                    return true;
                }
                if (isTvDevice() && k == KeyEvent.KEYCODE_DPAD_DOWN && !chromeHasFocus()) {
                    View target = seekBarDownTarget();
                    if (target == null) target = btnPlayPause;
                    if (target != null) target.requestFocus();
                    return true;
                }
                if (TvFocus.move(getCurrentFocus(), k)) return true;
            }
            return super.onKeyDown(k, e);
        } else {
            switch (k) {
                case KeyEvent.KEYCODE_BACK:
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        finish();
                    } else {
                        backPressedTime = System.currentTimeMillis();
                        AppToast.show(this, "再按一次退出播放");
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showCtrl(true);
                    final View downTarget = seekBarDownTarget();
                    if (downTarget != null) {
                        downTarget.postDelayed(() -> {
                            if (downTarget.isShown()) downTarget.requestFocus();
                        }, 50);
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    long step = k == KeyEvent.KEYCODE_DPAD_LEFT ? -seekStep : seekStep;
                    long cur = pendingSeekMs >= 0 ? pendingSeekMs : (player != null ? player.getCurrentPosition() : 0);
                    long dur = player != null ? player.getDuration() : 0;
                    long target = Math.max(0, cur + step);
                    // TIME_UNSET 为很大的负数，不能参与 Math.min，否则目标会变成 0
                    if (dur > 0) target = Math.min(dur, target);
                    // 立即更新 UI
                    String timeText = playClock(target, dur > 0 ? dur : 0);
                    tvSeekOverlay.setText(timeText);
                    tvSeekOverlay.setVisibility(View.VISIBLE);
                    tvTime.setText(timeText);
                    handler.removeCallbacks(hideSeekOverlayR);
                    handler.postDelayed(hideSeekOverlayR, 2000);
                    // 防抖：真正 seek 延迟到停止操作后
                    pendingSeekMs = target;
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    final long commitTarget = target;
                    seekCommitR = () -> {
                        seekToSafe(commitTarget);
                        pendingSeekMs = -1;
                    };
                    handler.postDelayed(seekCommitR, 1000);
                    return true;
                }
                case KeyEvent.KEYCODE_MENU:
                    showCtrl(true);
                    showMore(true);
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    toggleInfo(); return true;
            }
            return super.onKeyDown(k, e);
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController insets = decor.getWindowInsetsController();
            if (insets != null) {
                insets.hide(android.view.WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private TextView tvSeekOverlay;
    private final Runnable hideSeekOverlayR = () -> { if (tvSeekOverlay != null) tvSeekOverlay.setVisibility(View.GONE); };


    /** 控制栏隐藏时显示进度时间浮层 */
    private void showSeekOverlay() {
        if (player == null) return;
        updateTime();
        tvSeekOverlay.setText(playClock(player.getCurrentPosition(), player.getDuration()));
        tvSeekOverlay.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSeekOverlayR);
        handler.postDelayed(hideSeekOverlayR, 2000);
    }

    private boolean isTvDevice() {
        android.app.UiModeManager uiModeManager = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
    }

    @Override
    public void finish() {
        // 退出时恢复系统亮度。电视上再设横屏会闪一下，只在手机恢复自动旋转。
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = -1f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
        if (player != null) player.setPlayWhenReady(false);
        if (!isTvDevice()) applyExitOrientation();
        super.finish();
    }

    /** 电视保持横屏。手机按系统自动旋转：打开就跟设备转，关掉就停在用户锁定的方向。 */
    private void applyExitOrientation() {
        int orientation = isTvDevice()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        setRequestedOrientation(orientation);
    }

    @Override protected void onStop() { super.onStop(); saveProgress(true); if (player != null) player.setPlayWhenReady(false); }
    @Override protected void onDestroy() {
        saveProgress(true);
        super.onDestroy(); handler.removeCallbacksAndMessages(null);
        if (danmuManager != null) { danmuManager.destroy(); }
        if (player != null) { player.release(); player = null; }
    }
}


