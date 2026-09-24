package com.fntv.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** 到点后退出当前播放。计时不跟着播放页销毁。 */
final class SleepTimer {
    private static long deadlineMs;
    private static PlayerActivity player;
    private static boolean watching;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable fire = () -> {
        deadlineMs = 0;
        PlayerActivity playing = player;
        if (playing != null && !playing.isFinishing()) playing.finish();
    };

    private SleepTimer() {}

    static void cancel() {
        deadlineMs = 0;
        handler.removeCallbacks(fire);
    }

    static void startMinutes(Activity activity, int minutes) {
        if (minutes <= 0) {
            cancel();
            return;
        }
        watch(activity);
        handler.removeCallbacks(fire);
        deadlineMs = SystemClock.elapsedRealtime() + minutes * 60_000L;
        handler.postDelayed(fire, minutes * 60_000L);
    }

    static long remainingMs() {
        if (deadlineMs <= 0) return 0;
        long left = deadlineMs - SystemClock.elapsedRealtime();
        if (left <= 0) return 0;
        return left;
    }

    static boolean isRunning() {
        return remainingMs() > 0;
    }

    private static void watch(Activity activity) {
        if (activity instanceof PlayerActivity) player = (PlayerActivity) activity;
        if (watching) return;
        watching = true;
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                if (a instanceof PlayerActivity) player = (PlayerActivity) a;
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {
                if (player == a) player = null;
            }
        });
    }
}
