package com.fntv.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import androidx.appcompat.widget.AppCompatTextView;

/** 加载提示：每个字按正弦上下错开，形成轻微波动。 */
public class WaveTextView extends AppCompatTextView {
    private float phase;
    private ValueAnimator wave;

    public WaveTextView(Context context) {
        this(context, null);
    }

    public WaveTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setTextColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForceDarkAllowed(false);
        }
        setShadowLayer(8f, 0f, 1f, 0xCC000000);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) startWave();
        else stopWave();
    }

    private void startWave() {
        if (wave != null) return;
        wave = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        wave.setDuration(1100);
        wave.setRepeatCount(ValueAnimator.INFINITE);
        wave.setInterpolator(new LinearInterpolator());
        wave.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        wave.start();
    }

    private void stopWave() {
        if (wave != null) {
            wave.cancel();
            wave = null;
        }
        phase = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWave();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null || text.length() == 0) {
            super.onDraw(canvas);
            return;
        }
        getPaint().setColor(Color.WHITE);
        getPaint().setShadowLayer(8f, 0f, 1f, 0xCC000000);
        float amp = 4f * getResources().getDisplayMetrics().density;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            float x = layout.getPrimaryHorizontal(i) + getPaddingLeft();
            int line = layout.getLineForOffset(i);
            float y = layout.getLineBaseline(line) + getPaddingTop();
            float dy = (float) Math.sin(phase + i * 0.85f) * amp;
            canvas.drawText(text, i, i + 1, x, y + dy, getPaint());
        }
    }
}
