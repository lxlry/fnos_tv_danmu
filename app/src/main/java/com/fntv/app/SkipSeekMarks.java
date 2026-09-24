package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

/** 在播放进度条上标出片头、片尾位置，只画小竖线，不抢触摸和焦点。 */
public class SkipSeekMarks extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long introMs;
    private long outroMs;
    private long durationMs;

    public SkipSeekMarks(Context context) {
        this(context, null);
    }

    public SkipSeekMarks(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    public void setMarks(long introMs, long outroMs, long durationMs) {
        if (this.introMs == introMs && this.outroMs == outroMs && this.durationMs == durationMs) return;
        this.introMs = introMs;
        this.outroMs = outroMs;
        this.durationMs = durationMs;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (durationMs <= 0) return;
        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup)) return;
        View bar = ((ViewGroup) parent).findViewById(R.id.seekBar);
        if (!(bar instanceof SeekBar) || bar.getWidth() <= 0) return;
        int left = bar.getPaddingLeft();
        int right = bar.getWidth() - bar.getPaddingRight();
        if (right <= left) return;
        float cy = getHeight() / 2f;
        float half = 7f * getResources().getDisplayMetrics().density;
        if (introMs > 0 && introMs < durationMs) drawTick(canvas, left, right, introMs, cy, half);
        if (outroMs > 0 && outroMs < durationMs) drawTick(canvas, left, right, outroMs, cy, half);
    }

    private void drawTick(Canvas canvas, int left, int right, long atMs, float cy, float half) {
        float scale = atMs / (float) durationMs;
        float x = left + (right - left) * scale;
        canvas.drawLine(x, cy - half, x, cy + half, paint);
    }
}
