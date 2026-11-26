package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** 里面的横向海报行在滑动时，竖向滚动不要先把事件抢走。 */
public class HomeScrollView extends ScrollView {
    private final int touchSlop;
    private float startX;
    private float startY;
    private boolean horizontal;
    private boolean holdScroll;
    /** 焦点交还给内容时，不要跟着把列表滚走。 */
    private boolean suppressScroll;
    private ScrollYListener scrollYListener;

    public interface ScrollYListener {
        void onScrollY(int y);
    }

    public void setScrollYListener(ScrollYListener listener) {
        scrollYListener = listener;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrollYListener != null) scrollYListener.onScrollY(t);
    }

    public HomeScrollView(Context context) {
        this(context, null);
    }

    public HomeScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean requestChildRectangleOnScreen(android.view.View child, android.graphics.Rect rectangle,
                                                 boolean immediate) {
        if (holdScroll) return false;
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override
    public void requestChildFocus(android.view.View child, android.view.View focused) {
        if (!holdScroll) {
            super.requestChildFocus(child, focused);
            return;
        }
        // 仍然把窗口焦点交出去。只拦住随之而来的滚动，否则海报会亮、方向键还停在刷新上。
        suppressScroll = true;
        try {
            super.requestChildFocus(child, focused);
        } finally {
            suppressScroll = false;
        }
    }

    @Override
    public void scrollTo(int x, int y) {
        if (suppressScroll) return;
        super.scrollTo(x, y);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getScrollX();
        int y = getScrollY();
        super.onLayout(changed, l, t, r, b);
        if (holdScroll && (getScrollX() != x || getScrollY() != y)) {
            super.scrollTo(x, y);
        }
    }

    /** 恢复滚动位置时，焦点不要再把列表拽走。 */
    public void setHoldScroll(boolean hold) {
        holdScroll = hold;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                horizontal = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - startX);
                float dy = Math.abs(ev.getY() - startY);
                if (horizontal || (dx > touchSlop && dx > dy)) {
                    horizontal = true;
                    return false;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                horizontal = false;
                break;
            default:
                break;
        }
        if (horizontal) return false;
        return super.onInterceptTouchEvent(ev);
    }
}
