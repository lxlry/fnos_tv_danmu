package com.fntv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ImageView;

public class RoundedImageView extends ImageView {
    private float radius = 10;
    private Path clipPath;
    private RectF rect;

    public RoundedImageView(Context c) { this(c, null); }
    public RoundedImageView(Context c, AttributeSet a) { this(c, a, 0); }
    public RoundedImageView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setCornerRadius(float dp) {
        radius = dp * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rect = new RectF(0, 0, w, h);
        clipPath = new Path();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        if (clipPath != null) canvas.clipPath(clipPath);
        super.draw(canvas);
    }
}
