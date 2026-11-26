package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
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
            // 系统只会把按钮滚进屏幕。满屏海报要整张回到顶部。
            snapHeroTop(focused);
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
        if (holdScroll) {
            if (getScrollX() != x || getScrollY() != y) super.scrollTo(x, y);
            return;
        }
        View focused = findFocus();
        if (focused != null && focused != this) snapHeroTop(focused);
    }

    /**
     * 焦点在满屏海报里、并且按钮在第一屏内时，滚到海报顶部。
     * 只把「开始播放」露出来会把背景上沿裁掉。
     */
    private boolean snapHeroTop(android.view.View from) {
        android.view.View hero = taggedAncestor(from, "home_hero");
        if (hero == null || getChildCount() == 0) return false;
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        if (viewport <= 0 || hero.getHeight() <= 0) return false;
        int dest = offsetInScroll(hero);
        int focusBottom = offsetInScroll(from) + Math.max(from.getHeight(), 1);
        if (focusBottom > dest + viewport) return false;
        dest = Math.max(0, dest);
        if (getScrollY() != dest) scrollTo(getScrollX(), dest);
        return true;
    }

    private android.view.View taggedAncestor(android.view.View from, String tag) {
        android.view.View v = from;
        while (v != null && v != this) {
            if (tag.equals(v.getTag())) return v;
            android.view.ViewParent parent = v.getParent();
            v = parent instanceof android.view.View ? (android.view.View) parent : null;
        }
        return null;
    }

    private int offsetInScroll(android.view.View target) {
        int y = 0;
        android.view.View v = target;
        while (v != null && v != this) {
            y += v.getTop();
            android.view.ViewParent parent = v.getParent();
            if (!(parent instanceof android.view.View) || parent == this) break;
            v = (android.view.View) parent;
        }
        return y;
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
