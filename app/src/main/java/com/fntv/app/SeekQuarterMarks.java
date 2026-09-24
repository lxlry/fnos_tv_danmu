package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

/** 盖在进度条上的五个小节点：0%、25%、50%、75%、100%。不抢触摸和焦点。 */
public class SeekQuarterMarks extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        for (int i = 0; i < 5; i++) {
            float x = left + (right - left) * i / 4f;
            canvas.drawCircle(x, cy, radius, paint);
        }
    }
}
