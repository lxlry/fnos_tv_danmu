package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/** 左右滑海报时不要被下拉刷新抢走。页面在顶部时，斜着滑也会被当成下拉。 */
public class HomeSwipeRefresh extends SwipeRefreshLayout {
    private final int touchSlop;
    private float startX;
    private float startY;
    private boolean horizontal;

    public HomeSwipeRefresh(Context context) {
        this(context, null);
    }

    public HomeSwipeRefresh(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        if (disallowIntercept) horizontal = true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getX();
                startY = ev.getY();
                horizontal = false;
                super.onInterceptTouchEvent(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - startX);
                float dy = Math.abs(ev.getY() - startY);
                if (horizontal || (dx > touchSlop && dx >= dy)) {
                    horizontal = true;
                    return false;
                }
                if (dy <= dx) return false;
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
