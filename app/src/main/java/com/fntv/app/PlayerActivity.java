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
    private boolean introSkipped = false, outroSkipped = false;
    private float speedBeforeLongPress = 1.0f;
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
    private float[] speeds = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f};
    private int speedIdx = 0, ratioIdx = 0;
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
        tvTitle = findViewById(R.id.tvTitle);
        tvDanmuStatus = findViewById(R.id.tvDanmuStatus);
        btnCloudMode = findViewById(R.id.btnCloudMode);
        tvDanmuMatch = findViewById(R.id.tvDanmuMatch);
        tvSpeedHint = findViewById(R.id.tvSpeedHint);
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
                                speedBeforeLongPress = player.getPlaybackParameters().speed;
                                player.setPlaybackSpeed(2.0f);
                                danmuView.setPlaybackSpeed(2.0f);
                                showCtrl(false);
                                if (tvSpeedHint != null) {
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
                                player.setPlaybackSpeed(speedBeforeLongPress);
                                danmuView.setPlaybackSpeed(speedBeforeLongPress);
                            }
                            if (tvSpeedHint != null) {
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
                controller.setVisibility(View.INVISIBLE);
                topBar.setVisibility(View.INVISIBLE);
                btnLock.setVisibility(View.INVISIBLE);
                applyChromeSystemUi(false);
            }
            @Override public void onSwitchEpisode(String guid, String title) {
                saveProgress(true);
                lastProgressMs = 0;
                progressHeld = true;
                introSkipped = false;
                outroSkipped = false;
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
        btnSpeed.setOnClickListener(v -> cycleSpeed());
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
                topBar.setVisibility(View.INVISIBLE);
                controller.setVisibility(View.INVISIBLE);
                btnLock.setVisibility(View.INVISIBLE);
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
        btnNextEp.setOnClickListener(v -> episodeManager.playNext());

        cloudStreamManager = new CloudStreamManager(new CloudStreamManager.Callback() {
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getMediaGuid() { return mediaGuid; }
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
                    // 立即更新 UI（时间显示）
                    tvTime.setText(playClock(p, player.getDuration()));
                    if (tvSeekOverlay.getVisibility() == View.VISIBLE) {
                        tvSeekOverlay.setText(playClock(p, player.getDuration()));
                    }
                    // 防抖：停止操作 1s 后才真正 seek，避免按住时大量请求
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    pendingSeekMs = p;
                    seekCommitR = () -> {
                        if (player != null) {
                            player.seekTo(p);
                            if (danmuManager != null) danmuManager.onSeekTo(p);
                        }
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
                    player.seekTo(sb.getProgress());
                    if (danmuManager != null) danmuManager.onSeekTo(sb.getProgress());
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

        // 初始焦点给视频区域，始终由 playerView 持有焦点
        playerView.setFocusable(true);
        playerView.requestFocus();
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
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this);
        if ("software".equals(getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("decoder_mode", "hardware"))) {
            rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        } else {
            rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        }
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setPreferredTextLanguages("zh", "zh-Hans", "zh-CN", "chi", "zho", "cmn")
                .setRendererDisabled(com.google.android.exoplayer2.C.TRACK_TYPE_TEXT, false)
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
                    if (!seeked && seekTs > 0) { player.seekTo(seekTs); seeked = true; }
                    ensureSaveLoop(); updateTime();
                    if (firstReady) { scheduleInitialSave(); showCtrl(true); firstReady = false; }
                    syncPlayButton();
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
                    // 片头跳过（只开始触发一次，片尾在 updateTime 实时监测）
                    if (!introSkipped && (parentGuid != null || (itemTV != null && !itemTV.isEmpty()))) {
                        SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
                        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
                        int introSec = sp.getInt("skip_" + skipId + "_intro", 0);
                        if (introSec > 0) {
                            int pos = (int)(player.getCurrentPosition() / 1000);
                            if (pos < introSec) { player.seekTo(introSec * 1000L); danmuManager.showDanmuStatus("跳过片头 " + introSec + "秒"); }
                            introSkipped = true;
                        }
                    }
                } else if (s == Player.STATE_ENDED) {
                    Log.d(TAG, "STATE_ENDED hasNext=" + (episodeManager != null && episodeManager.hasNext()));
                    noteProgress();
                    saveProgress(true);
                    stopSave();
                    if (episodeManager != null && episodeManager.hasNext()) {
                        episodeManager.playNext();
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
                    Log.d(TAG, "硬解失败，切换到软解重试");
                    getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                            .edit().putString("decoder_mode", "software").apply();
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
        hdrNotified = false;
        if (cloudStreamManager != null) cloudStreamManager.resetSubtitleChoice();
        Map<String, String> b = new HashMap<>(); b.put("item_guid", itemGuid);
        Log.d(TAG, "play/info 请求: " + new com.google.gson.Gson().toJson(b));
        apiManager.getApi().getPlayInfo(b).enqueue(new retrofit2.Callback<ApiResponse<PlayInfoResponse>>() {
            @Override public void onResponse(retrofit2.Call<ApiResponse<PlayInfoResponse>> call,
                                             retrofit2.Response<ApiResponse<PlayInfoResponse>> r) {
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
                }
            }
            @Override public void onFailure(retrofit2.Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
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
        // 重新创建播放器（只允许 Google 软件解码器）
        DefaultRenderersFactory rf2 = new DefaultRenderersFactory(this) {
            @Override
            protected void buildVideoRenderers(Context context, int extensionRendererMode,
                                                com.google.android.exoplayer2.mediacodec.MediaCodecSelector mediaCodecSelector,
                                                boolean enableDecoderFallback, android.os.Handler eventHandler,
                                                com.google.android.exoplayer2.video.VideoRendererEventListener eventListener,
                                                long allowedVideoJoiningTimeMs, java.util.ArrayList<Renderer> out) {
                // 只保留 omx.google. 开头的软件解码器
                com.google.android.exoplayer2.mediacodec.MediaCodecSelector googleOnly = new com.google.android.exoplayer2.mediacodec.MediaCodecSelector() {
                    @Override
                    public java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> getDecoderInfos(
                            String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                            throws com.google.android.exoplayer2.mediacodec.MediaCodecUtil.DecoderQueryException {
                        java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> all = com.google.android.exoplayer2.mediacodec.MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                        java.util.List<com.google.android.exoplayer2.mediacodec.MediaCodecInfo> google = new java.util.ArrayList<>();
                        for (com.google.android.exoplayer2.mediacodec.MediaCodecInfo info : all) {
                            // omx.google.* = 旧版 Google 软解, c2.android.* = 新版 Google 软解
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
        rf2.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        player = new SimpleExoPlayer.Builder(this, rf2)
                .setTrackSelector(new DefaultTrackSelector(this))
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
                    if (!seeked && seekTs > 0) { player.seekTo(seekTs); seeked = true; }
                    ensureSaveLoop();
                    if (firstReady) { scheduleInitialSave(); showCtrl(true); firstReady = false; }
                    syncPlayButton();
                    if (cloudStreamManager != null) cloudStreamManager.applyDefaultChineseSubtitle();
                } else if (s == Player.STATE_ENDED) {
                    noteProgress();
                    saveProgress(true);
                    stopSave();
                    if (episodeManager != null && episodeManager.hasNext()) episodeManager.playNext();
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
        if (player == null) return;
        long p = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + ms));
        player.seekTo(p);
        if (danmuManager != null) danmuManager.onSeekTo(p);
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
        if (dur <= 0) return;
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
            player.seekTo(target);
            if (danmuManager != null) danmuManager.onSeekTo(target);
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

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.length;
        float s = speeds[speedIdx];
        btnSpeed.setText(speedText(s));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) player.setPlaybackSpeed(s);
        if (danmuManager != null) danmuView.setPlaybackSpeed(s);
    }

    private String speedText(float s) {
        String n = s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
        return n + "X";
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


    private void showIntroOutroDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        showSkipMenu(dialog);
        dialog.show();
        SideSheet.focus(dialog);
    }

    private String skipPrefsKey() {
        String skipId = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : itemTV;
        return "skip_" + skipId;
    }

    private int readSkipSec(boolean intro) {
        return getSharedPreferences("fntv_prefs", MODE_PRIVATE)
                .getInt(skipPrefsKey() + (intro ? "_intro" : "_outro"), 0);
    }

    private void writeSkipSec(boolean intro, int sec) {
        int saved = Math.max(0, Math.min(sec, 20 * 60));
        getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit()
                .putInt(skipPrefsKey() + (intro ? "_intro" : "_outro"), saved).apply();
        if (intro) introSkipped = false;
        else outroSkipped = false;
        refreshSkipMarks();
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

    private void placeSkipDialog(android.app.Dialog dialog) {
        SideSheet.place(dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dpPx(360), ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void showSkipContent(android.app.Dialog dialog, View content) {
        dialog.setContentView(content);
        placeSkipDialog(dialog);
        if (dialog.isShowing()) SideSheet.focus(dialog);
    }

    private void showSkipMenu(android.app.Dialog dialog) {
        LinearLayout root = skipSheetRoot();
        root.addView(skipHeader(null, "片头片尾", null));
        TextView scope = skipHint(skipScopeText());
        scope.setPadding(0, 0, 0, dpPx(16));
        root.addView(scope);
        root.addView(skipLinkRow("片头时长", formatSkipClock(readSkipSec(true)),
                () -> showSkipEditor(dialog, true)));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(10)));
        root.addView(gap);
        root.addView(skipLinkRow("片尾时长", formatSkipClock(readSkipSec(false)),
                () -> showSkipEditor(dialog, false)));
        showSkipContent(dialog, root);
    }

    private void showSkipEditor(android.app.Dialog dialog, boolean intro) {
        LinearLayout root = skipSheetRoot();
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
        root.addView(skipPositionRow(intro, () -> {
            int sec = currentSkipSeconds(intro);
            if (sec < 0) return;
            writeSkipSec(intro, sec);
            refresh.run();
        }));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(10)));
        root.addView(gap);
        root.addView(skipLinkRow("自定义", "", () -> showSkipCustom(dialog, intro)));
        showSkipContent(dialog, root);
    }

    private int playheadSeconds() {
        if (player == null) return 0;
        return (int) Math.max(0, player.getCurrentPosition() / 1000);
    }

    private int currentSkipSeconds(boolean intro) {
        if (player == null) return 0;
        int pos = (int) Math.max(0, player.getCurrentPosition() / 1000);
        if (intro) return pos;
        long dur = player.getDuration();
        if (dur <= 0) {
            AppToast.show(this, "还不知道片长，稍后再设片尾");
            return -1;
        }
        return (int) Math.max(0, dur / 1000 - pos);
    }

    private void showSkipCustom(android.app.Dialog dialog, boolean intro) {
        int current = readSkipSec(intro);
        LinearLayout root = skipSheetRoot();
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
        ok.setOnClickListener(v -> {
            writeSkipSec(intro, minutes.getValue() * 60 + seconds.getValue());
            showSkipEditor(dialog, intro);
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dpPx(44), 1);
        btnLp.rightMargin = dpPx(8);
        actions.addView(reset, btnLp);
        actions.addView(ok, new LinearLayout.LayoutParams(0, dpPx(44), 1));
        root.addView(actions);
        showSkipContent(dialog, root);
        minutes.requestFocus();
    }

    private LinearLayout skipSheetRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121212);
        root.setPadding(dpPx(18), dpPx(16), dpPx(18), dpPx(16));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
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
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpPx(44)));
        if (back != null) {
            androidx.appcompat.widget.AppCompatImageView backBtn = new androidx.appcompat.widget.AppCompatImageView(this);
            backBtn.setLayoutParams(new LinearLayout.LayoutParams(dpPx(40), dpPx(40)));
            backBtn.setBackgroundResource(R.drawable.bg_player_action);
            backBtn.setImageResource(R.drawable.ic_player_back);
            backBtn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            backBtn.setPadding(dpPx(6), dpPx(6), dpPx(6), dpPx(6));
            backBtn.setContentDescription("返回");
            backBtn.setFocusable(true);
            backBtn.setClickable(true);
            backBtn.setOnClickListener(v -> back.run());
            row.addView(backBtn);
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
        title.setText("当前播放时间");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        text.addView(title);
        TextView time = skipHint(formatSkipClock(playheadSeconds()));
        time.setPadding(0, dpPx(4), 0, 0);
        text.addView(time);
        Runnable tickPlayhead = new Runnable() {
            @Override public void run() {
                if (!time.isAttachedToWindow()) return;
                String next = formatSkipClock(playheadSeconds());
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
        set.setOnClickListener(v -> onSet.run());
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
        row.setMinimumHeight(dpPx(64));
        row.setPadding(dpPx(14), dpPx(10), dpPx(14), dpPx(10));
        row.setFocusable(true);
        row.setClickable(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dpPx(10));
        bg.setStroke(dpPx(1), 0x66FFFFFF);
        row.setBackground(bg);
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
        btn.setBackgroundResource(R.drawable.bg_player_action);
        btn.setFocusable(true);
        return btn;
    }

    private Button skipActionButton(String text, boolean primary) {
        Button btn = skipTextButton(text);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dpPx(8));
        bg.setColor(primary ? 0xFF3B6FFF : 0xFF3A4150);
        btn.setBackground(bg);
        return btn;
    }

    private android.widget.NumberPicker skipPicker(int min, int max, int value) {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setWrapSelectorWheel(true);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        try {
            java.lang.reflect.Field paint = android.widget.NumberPicker.class.getDeclaredField("mSelectorWheelPaint");
            paint.setAccessible(true);
            ((android.graphics.Paint) paint.get(picker)).setColor(Color.WHITE);
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
        dialog.setContentView(sleepOptionView(dialog));
        SideSheet.place(dialog);
        dialog.show();
        SideSheet.focus(dialog);
    }

    private View sleepOptionView(android.app.Dialog dialog) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpPx(20), dpPx(22), dpPx(20), dpPx(16));

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
            row.addView(sleepChip(dialog, labels[i], minutes[i], false));
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
        boolean on = !custom && minutes == sleepChoiceMinutes && (minutes == 0 || SleepTimer.isRunning());
        chip.setTextColor(on ? 0xFF4C8DFF : Color.WHITE);
        chip.setBackgroundResource(R.drawable.bg_player_action);
        chip.setOnClickListener(v -> {
            if (custom) {
                dialog.setContentView(sleepCustomView(dialog));
                SideSheet.focus(dialog);
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
        root.setPadding(dpPx(20), dpPx(22), dpPx(20), dpPx(16));
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

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
        android.widget.NumberPicker mins = skipPicker(0, 59, 0);
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
            btnLock.setVisibility(View.VISIBLE);
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
        controller.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        topBar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        btnLock.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (show) {
            updateTitle();
            syncPlayButton();
            resetHideTimer();
            controller.post(this::wirePlayerFocus);
        }
        applyChromeSystemUi(show);
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
            controller.setVisibility(View.INVISIBLE);
            topBar.setVisibility(View.INVISIBLE);
            btnLock.setVisibility(View.INVISIBLE);
            View panel = findViewById(R.id.morePanel);
            if (panel != null) {
                ViewGroup.LayoutParams panelLp = panel.getLayoutParams();
                panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                panel.setLayoutParams(panelLp);
                panel.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);
                int pad = (int) (12 * getResources().getDisplayMetrics().density);
                panel.setPadding(pad, pad, pad, pad);
            }
            applyChromeSystemUi(true);
            refreshSleepLabel();
            handler.removeCallbacks(sleepLabelTick);
            if (SleepTimer.isRunning()) handler.postDelayed(sleepLabelTick, 1000);
            handler.removeCallbacks(hideC);
            if (topBar != null) topBar.clearFocus();
            if (controller != null) controller.clearFocus();
            if (btnLock != null) btnLock.clearFocus();
            if (moreScrim != null) {
                moreScrim.setFocusable(false);
                moreScrim.post(this::focusMorePanel);
            }
        } else if (ctrlVis && !isLocked) {
            controller.setVisibility(View.VISIBLE);
            topBar.setVisibility(View.VISIBLE);
            btnLock.setVisibility(View.VISIBLE);
            resetHideTimer();
            controller.post(this::wirePlayerFocus);
        }
    }

    private void resetHideTimer() {
        handler.removeCallbacks(hideC);
        handler.postDelayed(hideC, 5000);
    }
    private final Runnable hideC = () -> {
        if (moreOpen || infoVis) {
            resetHideTimer();
            return;
        }
        if (controller.hasFocus() || topBar.hasFocus() || btnLock.hasFocus()
                || (infoPanel != null && infoPanel.hasFocus())) {
            resetHideTimer();
            return;
        }
        showCtrl(false);
    };

    private void wirePlayerFocus() {
        List<View> top = TvFocus.present(btnBack, btnMore);
        List<View> sides = TvFocus.present(btnLock);
        List<View> seek = TvFocus.present(seekBar);
        View btnSubtitleTrack = findViewById(R.id.btnSubtitleTrack);
        View btnAudioTrack = findViewById(R.id.btnAudioTrack);
        List<View> bottom = TvFocus.present(btnPlayPause, btnNextEp, btnEpisodeList, btnSkip, btnDanmu, btnSpeed,
                btnAudioTrack, btnSubtitleTrack, btnQuality);
        TvFocus.bindRow(top);
        TvFocus.bindRow(sides);
        TvFocus.bindRow(bottom);
        if (!top.isEmpty() && !sides.isEmpty()) TvFocus.bindVertical(top, sides);
        else if (!top.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(top, seek);
        if (!sides.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(sides, seek);
        else if (!top.isEmpty() && !seek.isEmpty()) TvFocus.bindVertical(top, seek);
        if (!seek.isEmpty() && !bottom.isEmpty()) TvFocus.bindVertical(seek, bottom);
        for (View v : top) TvFocus.point(v, View.FOCUS_UP, v);
        for (View v : bottom) TvFocus.point(v, View.FOCUS_DOWN, v);
        TvFocus.sealAll(top);
        TvFocus.sealAll(sides);
        TvFocus.sealAll(seek);
        TvFocus.sealAll(bottom);
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
        seekBar.setMax((int) Math.max(dur, 1));
        refreshSkipMarks();
        seekBar.setKeyProgressIncrement(5000); // 方向键每次 5 秒
        // 防抖期间不覆盖 UI，避免抽搐（tvTime 和 seekBar 进度由 onProgressChanged 控制）
        if (pendingSeekMs < 0) {
            tvTime.setText(playClock(cur, dur));
            seekBar.setProgress((int) cur);
        }
        // 显示缓冲进度（灰色条）
        long buffered = player.getBufferedPosition();
        seekBar.setSecondaryProgress((int) buffered);
        if (danmuManager != null) danmuManager.setPlayTime(cur);
        // 实时监测片尾位置
        if (!outroSkipped && dur > 0) {
            SharedPreferences sp = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
            String sid = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : (itemTV != null ? itemTV : null);
            if (sid != null) {
                int outroSec = sp.getInt("skip_" + sid + "_outro", 0);
                if (outroSec > 0) Log.d(TAG, "片尾检测: cur=" + (cur/1000) + "s dur=" + (dur/1000) + "s 阈值=" + (dur/1000 - outroSec) + "s");
                if (outroSec > 0 && cur / 1000 > dur / 1000 - outroSec) {
                    outroSkipped = true;
                    if (episodeManager != null && episodeManager.hasNext()) {
                        danmuManager.showDanmuStatus("检测到片尾，即将跳过");
                        handler.postDelayed(() -> episodeManager.playNext(), 3000);
                    }
                }
            }
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

    @Override public boolean onKeyDown(int k, KeyEvent e) {
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
                    if (moreOpen) return true;
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
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (moreOpen && !focusInMore()) {
                    focusMorePanel();
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
                    btnPlayPause.postDelayed(new Runnable() { @Override public void run() { btnPlayPause.requestFocus(); } }, 50);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    long step = k == KeyEvent.KEYCODE_DPAD_LEFT ? -seekStep : seekStep;
                    long cur = pendingSeekMs >= 0 ? pendingSeekMs : (player != null ? player.getCurrentPosition() : 0);
                    long dur = player != null ? player.getDuration() : 0;
                    long target = Math.max(0, Math.min(dur, cur + step));
                    // 立即更新 UI
                    String timeText = playClock(target, dur);
                    tvSeekOverlay.setText(timeText);
                    tvSeekOverlay.setVisibility(View.VISIBLE);
                    tvTime.setText(timeText);
                    handler.removeCallbacks(hideSeekOverlayR);
                    handler.postDelayed(hideSeekOverlayR, 2000);
                    // 防抖：真正 seek 延迟到停止操作后
                    pendingSeekMs = target;
                    if (seekCommitR != null) handler.removeCallbacks(seekCommitR);
                    seekCommitR = () -> {
                        if (player != null) {
                            player.seekTo(target);
                            if (danmuManager != null) danmuManager.onSeekTo(target);
                        }
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
        // 退出时恢复系统亮度，方向交给系统自动旋转，不再强制竖屏
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = -1f;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
        applyExitOrientation();
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


