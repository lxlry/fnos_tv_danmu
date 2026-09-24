package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

/** 盖在进度条上的小节点。默认按轨道四等分，也可按实际数值定位。不抢触摸和焦点。 */
public class SeekQuarterMarks extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] fractions = {0f, 0.25f, 0.5f, 0.75f, 1f};

    /** 节点在轨道上的位置，0 是最左，1 是最右。 */
    public void setFractions(float[] stops) {
        if (stops == null || stops.length == 0) return;
        fractions = stops;
        invalidate();
    }

    public SeekQuarterMarks(Context context) {
        this(context, null);
    }

    public SeekQuarterMarks(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xE6FFFFFF);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int left = 0;
        int right = getWidth();
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            View bar = ((ViewGroup) parent).findViewById(R.id.dm_seekbar);
            if (bar instanceof SeekBar && bar.getWidth() > 0) {
                left = bar.getPaddingLeft();
                right = bar.getWidth() - bar.getPaddingRight();
            }
        }
        if (right <= left) return;
        float cy = getHeight() / 2f;
        float radius = 3f * getResources().getDisplayMetrics().density;
        float span = right - left;
        for (float fraction : fractions) {
            float x = left + span * fraction;
            canvas.drawCircle(x, cy, radius, paint);
        }
    }
}
