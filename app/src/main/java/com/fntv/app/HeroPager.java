package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 一屏一张的横向海报。松手或甩动都停在整页上。 */
public class HeroPager extends HorizontalScrollView {

    public interface Listener {
        void onPageSettled(int index);
        void onUserHold(boolean hold);
    }

    private final PageStrip strip;
    private Listener listener;
    private int pageCount;
    private boolean snapped;
    private float downX;
    private float downY;
    private boolean horizontalDrag;
    private final int touchSlop;

    public HeroPager(Context context) {
        this(context, null);
    }

    public HeroPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        touchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        strip = new PageStrip(context);
        addView(strip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPages(java.util.List<View> pages) {
        strip.removeAllViews();
        pageCount = pages == null ? 0 : pages.size();
        if (pages != null) {
            for (View page : pages) {
                strip.addView(page, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT));
            }
        }
        scrollTo(0, 0);
        requestLayout();
    }

    public void scrollToPage(int index, boolean smooth) {
        int width = getWidth();
        if (width <= 0) {
            post(() -> scrollToPage(index, smooth));
            return;
        }
        if (pageCount <= 0) return;
        int safe = Math.max(0, Math.min(pageCount - 1, index));
        int x = safe * width;
        if (smooth) smoothScrollTo(x, 0);
        else scrollTo(x, 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != oldw) strip.requestLayout();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                snapped = false;
                if (listener != null) listener.onUserHold(true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean handled = super.onTouchEvent(ev);
                if (!snapped) settle();
                if (listener != null) listener.onUserHold(false);
                return handled;
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void fling(int velocityX) {
        int width = getWidth();
        if (width <= 0 || pageCount <= 0) {
            super.fling(velocityX);
            return;
        }
        snapped = true;
        int index = Math.round(getScrollX() / (float) width);
        if (velocityX > 600) index++;
        else if (velocityX < -600) index--;
        index = Math.max(0, Math.min(pageCount - 1, index));
        smoothScrollTo(index * width, 0);
        if (listener != null) listener.onPageSettled(index);
    }

    private void settle() {
        int width = getWidth();
        if (width <= 0 || pageCount <= 0) return;
        int index = Math.round(getScrollX() / (float) width);
        index = Math.max(0, Math.min(pageCount - 1, index));
        smoothScrollTo(index * width, 0);
        if (listener != null) listener.onPageSettled(index);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                horizontalDrag = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                if (dy > touchSlop && dy > dx) {
                    return false;
                }
                if (dx > touchSlop && dx >= dy) {
                    horizontalDrag = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                horizontalDrag = false;
                break;
            default:
                break;
        }
        if (!horizontalDrag && ev.getActionMasked() == MotionEvent.ACTION_MOVE) return false;
        return super.onInterceptTouchEvent(ev);
    }

    private final class PageStrip extends LinearLayout {
        PageStrip(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            if (width <= 0 && getParent() instanceof View) {
                width = ((View) getParent()).getWidth();
            }
            int count = getChildCount();
            int childW = MeasureSpec.makeMeasureSpec(Math.max(width, 0), MeasureSpec.EXACTLY);
            int childH = MeasureSpec.makeMeasureSpec(Math.max(height, 0), MeasureSpec.EXACTLY);
            for (int i = 0; i < count; i++) {
                getChildAt(i).measure(childW, childH);
            }
            setMeasuredDimension(Math.max(width, 0) * Math.max(count, 1), Math.max(height, 0));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = getWidth() / Math.max(getChildCount(), 1);
            int height = getHeight();
            int x = 0;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(x, 0, x + width, height);
                x += width;
            }
        }
    }
}
