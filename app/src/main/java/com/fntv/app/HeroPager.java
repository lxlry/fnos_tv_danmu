package com.fntv.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** 一屏一张的横向海报。松手或甩动都停在整页上；多于一张时首尾循环。 */
public class HeroPager extends HorizontalScrollView {

    public interface Listener {
        void onPageSettled(int index);
        void onUserHold(boolean hold);
    }

    private final PageStrip strip;
    private Listener listener;
    private int pageCount;
    private boolean loop;
    private boolean snapped;
    private int pendingX = -1;
    private int pendingJumpX = -1;
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

    /**
     * @param loop 为 true 时 pages 须在真实页前后各多放一张衔接页：
     *             [末张副本, 第1张, …, 末张, 第1张副本]
     */
    public void setPages(java.util.List<View> pages, boolean loop) {
        strip.removeAllViews();
        pendingX = -1;
        pendingJumpX = -1;
        pageCount = pages == null ? 0 : pages.size();
        this.loop = loop && pageCount > 2;
        if (pages != null) {
            for (View page : pages) {
                strip.addView(page, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT));
            }
        }
        scrollTo(0, 0);
        requestLayout();
        post(() -> {
            int width = getWidth();
            if (width > 0 && this.loop) scrollTo(width, 0);
        });
    }

    public void setPages(java.util.List<View> pages) {
        setPages(pages, false);
    }

    public void scrollToPage(int index, boolean smooth) {
        int width = getWidth();
        if (width <= 0) {
            post(() -> scrollToPage(index, smooth));
            return;
        }
        int logicalCount = logicalCount();
        if (logicalCount <= 0) return;
        int safe = (index % logicalCount + logicalCount) % logicalCount;
        int physical = loop ? safe + 1 : safe;
        if (loop && smooth) {
            int current = toLogical(Math.round(getScrollX() / (float) width));
            if (current == logicalCount - 1 && safe == 0) physical = pageCount - 1;
            else if (current == 0 && safe == logicalCount - 1) physical = 0;
        }
        scrollPhysical(physical, smooth);
    }

    private int logicalCount() {
        if (pageCount <= 0) return 0;
        return loop ? pageCount - 2 : pageCount;
    }

    private int toLogical(int physical) {
        if (!loop) return Math.max(0, Math.min(pageCount - 1, physical));
        if (physical <= 0) return pageCount - 3;
        if (physical >= pageCount - 1) return 0;
        return physical - 1;
    }

    /** 衔接页和真实页是同一张图，动画结束后跳回真实页，下次还能继续滑。 */
    private int unwrap(int physical) {
        if (!loop) return physical;
        if (physical <= 0) return pageCount - 2;
        if (physical >= pageCount - 1) return 1;
        return physical;
    }

    private void scrollPhysical(int physical, boolean smooth) {
        int width = getWidth();
        if (width <= 0 || pageCount <= 0) return;
        physical = Math.max(0, Math.min(pageCount - 1, physical));
        int x = physical * width;
        int jump = unwrap(physical) * width;
        if (!smooth || jump == x || Math.abs(getScrollX() - x) <= 2) {
            pendingX = -1;
            pendingJumpX = -1;
            scrollTo(jump, 0);
            return;
        }
        pendingX = x;
        pendingJumpX = jump;
        smoothScrollTo(x, 0);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (pendingX >= 0 && Math.abs(l - pendingX) <= 2) {
            int jump = pendingJumpX;
            pendingX = -1;
            pendingJumpX = -1;
            if (jump >= 0 && jump != l) scrollTo(jump, 0);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && w != oldw) {
            strip.requestLayout();
            int physical = oldw > 0 ? Math.round(getScrollX() / (float) oldw) : (loop ? 1 : 0);
            int x = unwrap(physical) * w;
            post(() -> scrollTo(x, 0));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                snapped = false;
                pendingX = -1;
                pendingJumpX = -1;
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
        scrollPhysical(index, true);
        if (listener != null) listener.onPageSettled(toLogical(index));
    }

    private void settle() {
        int width = getWidth();
        if (width <= 0 || pageCount <= 0) return;
        int index = Math.round(getScrollX() / (float) width);
        index = Math.max(0, Math.min(pageCount - 1, index));
        scrollPhysical(index, true);
        if (listener != null) listener.onPageSettled(toLogical(index));
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
