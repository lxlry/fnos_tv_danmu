package com.fntv.app;

import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 电视遥控器焦点：同行左右、按屏幕横坐标上下对齐，到尽头停住。
 * 竖向目标在按键时再算，这样经过全宽控件后还能回到原来那一列。
 */
final class TvFocus {

    private static final Map<View, List<View>> upLanes = new WeakHashMap<>();
    private static final Map<View, List<View>> downLanes = new WeakHashMap<>();
    private static final Map<View, View> cornerUp = new WeakHashMap<>();
    private static int lastNarrowCx = -1;

    private TvFocus() {}

    static void ensureId(View v) {
        if (v != null && v.getId() == View.NO_ID) {
            v.setId(View.generateViewId());
        }
    }

    static List<View> listOf(View v) {
        List<View> list = new ArrayList<>(1);
        if (v != null) list.add(v);
        return list;
    }

    static boolean usable(View v) {
        return v != null && v.isShown() && v.isFocusable() && v.getVisibility() == View.VISIBLE;
    }

    private static boolean bindable(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.isFocusable();
    }

    static int dirFromKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return View.FOCUS_LEFT;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return View.FOCUS_RIGHT;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return View.FOCUS_UP;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return View.FOCUS_DOWN;
        return 0;
    }

    /** 记住最近一个非全宽焦点的屏幕横坐标，供全宽控件按列回落。 */
    static void remember(View v) {
        if (v == null || v.getWidth() <= 0) return;
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        if (v.getWidth() > screenW * 4 / 5) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        lastNarrowCx = loc[0] + v.getWidth() / 2;
    }

    static void point(View from, int dir, View to) {
        if (from == null) return;
        ensureId(from);
        View target = bindable(to) ? to : from;
        if (!bindable(target)) target = from;
        ensureId(target);
        int id = target.getId();
        if (dir == View.FOCUS_LEFT) from.setNextFocusLeftId(id);
        else if (dir == View.FOCUS_RIGHT) from.setNextFocusRightId(id);
        else if (dir == View.FOCUS_UP) from.setNextFocusUpId(id);
        else if (dir == View.FOCUS_DOWN) from.setNextFocusDownId(id);
    }

    private static void setLane(View from, int dir, List<View> lane) {
        if (from == null) return;
        List<View> copy = lane == null ? null : new ArrayList<>(lane);
        if (dir == View.FOCUS_UP) {
            if (copy == null) upLanes.remove(from);
            else upLanes.put(from, copy);
        } else if (dir == View.FOCUS_DOWN) {
            if (copy == null) downLanes.remove(from);
            else downLanes.put(from, copy);
        }
    }

    /** 未指定的方向指回自己，避免系统几何搜索窜到底栏或其他页。 */
    static void seal(View v) {
        if (v == null) return;
        ensureId(v);
        int id = v.getId();
        if (v.getNextFocusLeftId() == View.NO_ID) v.setNextFocusLeftId(id);
        if (v.getNextFocusRightId() == View.NO_ID) v.setNextFocusRightId(id);
        if (v.getNextFocusUpId() == View.NO_ID) v.setNextFocusUpId(id);
        if (v.getNextFocusDownId() == View.NO_ID) v.setNextFocusDownId(id);
    }

    static void sealAll(List<View> items) {
        if (items == null) return;
        for (View v : items) seal(v);
    }

    static void bindRow(List<View> items) {
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i);
            if (v == null) continue;
            point(v, View.FOCUS_LEFT, items.get(Math.max(0, i - 1)));
            point(v, View.FOCUS_RIGHT, items.get(Math.min(items.size() - 1, i + 1)));
        }
    }

    /**
     * 选目标行里和自己水平位置最接近的一项。
     * 全宽控件用上次窄控件的横坐标，这样下键再上键能回到原来那一列。
     */
    static View nearestX(View from, List<View> candidates) {
        if (from == null || candidates == null || candidates.isEmpty()) return null;
        List<View> ok = new ArrayList<>();
        for (View c : candidates) {
            if (bindable(c)) ok.add(c);
        }
        if (ok.isEmpty()) return null;
        if (!hasLaidOut(from) || !hasLaidOut(ok)) return ok.get(0);

        int screenW = from.getResources().getDisplayMetrics().widthPixels;
        boolean wide = from.getWidth() > screenW * 4 / 5;
        int[] loc = new int[2];
        int fromL;
        int fromR;
        int fromCx;
        if (wide && lastNarrowCx >= 0) {
            fromCx = lastNarrowCx;
            fromL = fromCx - 24;
            fromR = fromCx + 24;
        } else {
            from.getLocationOnScreen(loc);
            fromL = loc[0];
            fromR = fromL + Math.max(from.getWidth(), 1);
            fromCx = fromL + Math.max(from.getWidth(), 1) / 2;
        }

        View best = ok.get(0);
        long bestScore = Long.MAX_VALUE;
        for (View c : ok) {
            c.getLocationOnScreen(loc);
            int w = Math.max(c.getWidth(), 1);
            int l = loc[0];
            int r = l + w;
            int cx = l + w / 2;
            int overlap = Math.min(fromR, r) - Math.max(fromL, l);
            int centerDist = Math.abs(cx - fromCx);
            long score = overlap > 0 ? centerDist : 1_000_000L + centerDist;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** 上排按下、下排按上，按屏幕横坐标对齐；未布局时退回按列号。 */
    static void bindVertical(List<View> upper, List<View> lower) {
        if (upper == null || upper.isEmpty() || lower == null || lower.isEmpty()) return;
        boolean laidOut = hasLaidOut(upper) && hasLaidOut(lower);
        for (View v : upper) setLane(v, View.FOCUS_DOWN, lower);
        for (View v : lower) setLane(v, View.FOCUS_UP, upper);
        if (laidOut) {
            for (View v : upper) point(v, View.FOCUS_DOWN, nearestX(v, lower));
            for (View v : lower) point(v, View.FOCUS_UP, nearestX(v, upper));
            return;
        }
        for (int i = 0; i < upper.size(); i++) {
            point(upper.get(i), View.FOCUS_DOWN, lower.get(Math.min(i, lower.size() - 1)));
        }
        for (int i = 0; i < lower.size(); i++) {
            point(lower.get(i), View.FOCUS_UP, upper.get(Math.min(i, upper.size() - 1)));
        }
    }

    /** 右上角搜索：按下到最近磁贴；只有正下方附近的磁贴按上才回搜索。 */
    static void bindCornerAbove(View corner, List<View> lower) {
        if (corner == null || lower == null || lower.isEmpty()) return;
        setLane(corner, View.FOCUS_DOWN, lower);
        View down = nearestX(corner, lower);
        point(corner, View.FOCUS_DOWN, down != null ? down : lower.get(0));
        for (View v : lower) {
            if (v == null) continue;
            cornerUp.put(v, corner);
            if (underCorner(v, corner)) {
                point(v, View.FOCUS_UP, corner);
            } else {
                point(v, View.FOCUS_UP, v);
            }
        }
    }

    static void bindDown(List<View> items, View target) {
        if (items == null) return;
        List<View> lane = listOf(target);
        for (View v : items) {
            setLane(v, View.FOCUS_DOWN, lane);
            point(v, View.FOCUS_DOWN, target);
        }
    }

    static void bindUp(List<View> items, View target) {
        if (items == null) return;
        List<View> lane = listOf(target);
        for (View v : items) {
            setLane(v, View.FOCUS_UP, lane);
            point(v, View.FOCUS_UP, target);
        }
    }

    /** 底栏 Tab 按上：落到内容行里和自己水平位置最接近的项。 */
    static void bindUpToNearest(List<View> from, List<View> lane) {
        if (from == null || lane == null || lane.isEmpty()) return;
        boolean laidOut = hasLaidOut(from) && hasLaidOut(lane);
        for (int i = 0; i < from.size(); i++) {
            View v = from.get(i);
            setLane(v, View.FOCUS_UP, lane);
            View to;
            if (laidOut) {
                to = nearestX(v, lane);
            } else if (from.size() <= 1 || lane.size() == 1) {
                to = lane.get(0);
            } else {
                to = lane.get(i * (lane.size() - 1) / (from.size() - 1));
            }
            point(v, View.FOCUS_UP, to);
        }
    }

    /** 纵向列表：相邻上下，左右停在自己身上。 */
    static void bindChain(List<View> items) {
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i);
            if (v == null) continue;
            point(v, View.FOCUS_UP, i > 0 ? items.get(i - 1) : v);
            point(v, View.FOCUS_DOWN, i < items.size() - 1 ? items.get(i + 1) : v);
            point(v, View.FOCUS_LEFT, v);
            point(v, View.FOCUS_RIGHT, v);
        }
    }

    static void stay(View v) {
        if (v == null) return;
        point(v, View.FOCUS_LEFT, v);
        point(v, View.FOCUS_RIGHT, v);
        point(v, View.FOCUS_UP, v);
        point(v, View.FOCUS_DOWN, v);
        setLane(v, View.FOCUS_UP, null);
        setLane(v, View.FOCUS_DOWN, null);
        cornerUp.remove(v);
    }

    /** 横向/纵向滚动容器只当轨道，不抢卡片焦点。 */
    static void asScroller(View v) {
        if (v == null) return;
        v.setFocusable(false);
        v.setFocusableInTouchMode(false);
        if (v instanceof ViewGroup) {
            ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    static List<View> collectVisibleFocusables(View root) {
        List<View> out = new ArrayList<>();
        collectVisibleFocusables(root, out);
        return out;
    }

    private static void collectVisibleFocusables(View v, List<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (isPassThrough(g)) {
                for (int i = 0; i < g.getChildCount(); i++) {
                    collectVisibleFocusables(g.getChildAt(i), out);
                }
                return;
            }
            int before = out.size();
            for (int i = 0; i < g.getChildCount(); i++) {
                collectVisibleFocusables(g.getChildAt(i), out);
            }
            if (out.size() > before) return;
        }
        if (v.isFocusable()) out.add(v);
    }

    /** 包含可聚焦容器自身及其子项（用于焦点记忆恢复）。 */
    static List<View> collectAllFocusables(View root) {
        List<View> out = new ArrayList<>();
        collectAllFocusables(root, out);
        return out;
    }

    private static void collectAllFocusables(View v, List<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof ViewGroup && isPassThrough((ViewGroup) v)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAllFocusables(g.getChildAt(i), out);
            }
            return;
        }
        if (v.isFocusable()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAllFocusables(g.getChildAt(i), out);
            }
        }
    }

    private static boolean isPassThrough(ViewGroup g) {
        return g instanceof HorizontalScrollView || g instanceof ScrollView;
    }

    static List<List<View>> collectGridRows(ViewGroup container) {
        List<List<View>> rows = new ArrayList<>();
        if (container == null) return rows;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            if (row.getOrientation() != LinearLayout.HORIZONTAL) continue;
            if ("lib_sort_bar".equals(row.getTag())) continue;
            List<View> cards = collectVisibleFocusables(row);
            if (!cards.isEmpty()) rows.add(cards);
        }
        return rows;
    }

    static void bindGrid(List<List<View>> rows, View upTarget, View downTarget) {
        if (rows == null || rows.isEmpty()) return;
        for (List<View> row : rows) bindRow(row);
        for (int r = 0; r < rows.size(); r++) {
            List<View> row = rows.get(r);
            if (r + 1 < rows.size()) {
                bindVertical(row, rows.get(r + 1));
            } else if (downTarget != null) {
                bindDown(row, downTarget);
            }
            if (r == 0 && upTarget != null) {
                bindUp(row, upTarget);
            }
        }
    }

    /**
     * 分区标题不挡下行：上排按下仍落到对齐的卡片。
     * 标题从首张卡按上进入，再上回到上一行；右键进首张卡。
     */
    static void bindSectionHeader(View header, List<View> cards, List<View> prevLane) {
        if (header == null || cards == null || cards.isEmpty()) return;
        setLane(header, View.FOCUS_DOWN, cards);
        point(header, View.FOCUS_DOWN, cards.get(0));
        point(header, View.FOCUS_RIGHT, cards.get(0));
        point(header, View.FOCUS_LEFT, header);
        if (prevLane != null && !prevLane.isEmpty()) {
            setLane(header, View.FOCUS_UP, prevLane);
            point(header, View.FOCUS_UP, nearestX(header, prevLane));
        } else {
            setLane(header, View.FOCUS_UP, null);
            point(header, View.FOCUS_UP, header);
        }
        setLane(cards.get(0), View.FOCUS_UP, listOf(header));
        point(cards.get(0), View.FOCUS_UP, header);
        seal(header);
    }

    static List<View> present(View... views) {
        List<View> out = new ArrayList<>();
        if (views == null) return out;
        for (View v : views) {
            if (bindable(v)) out.add(v);
        }
        return out;
    }

    static View resolve(View from, int dir) {
        if (from == null || dir == 0) return null;
        if (dir == View.FOCUS_UP) {
            View corner = cornerUp.get(from);
            if (corner != null) {
                return underCorner(from, corner) && usable(corner) ? corner : from;
            }
        }
        List<View> lane = dir == View.FOCUS_UP ? upLanes.get(from)
                : dir == View.FOCUS_DOWN ? downLanes.get(from) : null;
        if (lane != null && !lane.isEmpty()) {
            View pick = nearestX(from, lane);
            if (usable(pick)) return pick;
            for (View v : lane) {
                if (usable(v)) return v;
            }
            return from;
        }
        int nextId = nextFocusId(from, dir);
        if (nextId == View.NO_ID) return null;
        if (nextId == from.getId()) return from;
        View root = from.getRootView();
        View next = root != null ? root.findViewById(nextId) : null;
        return next;
    }

    static boolean move(View focused, int keyCode) {
        int dir = dirFromKey(keyCode);
        if (dir == 0 || focused == null) return false;
        View next = resolve(focused, dir);
        if (next == null) return false;
        if (next == focused) return true;
        if (!usable(next)) return true;
        next.requestFocus();
        next.requestRectangleOnScreen(
                new Rect(0, 0, Math.max(next.getWidth(), 1), Math.max(next.getHeight(), 1)),
                false);
        return true;
    }

    private static int nextFocusId(View v, int dir) {
        if (dir == View.FOCUS_LEFT) return v.getNextFocusLeftId();
        if (dir == View.FOCUS_RIGHT) return v.getNextFocusRightId();
        if (dir == View.FOCUS_UP) return v.getNextFocusUpId();
        if (dir == View.FOCUS_DOWN) return v.getNextFocusDownId();
        return View.NO_ID;
    }

    private static boolean underCorner(View v, View corner) {
        if (!bindable(v) || !bindable(corner)) return false;
        if (!hasLaidOut(v) || !hasLaidOut(corner)) return false;
        int screenW = corner.getResources().getDisplayMetrics().widthPixels;
        int[] loc = new int[2];
        corner.getLocationOnScreen(loc);
        int topCx = loc[0] + Math.max(corner.getWidth(), 1) / 2;
        v.getLocationOnScreen(loc);
        int cx = loc[0] + Math.max(v.getWidth(), 1) / 2;
        int slack = Math.max(Math.max(corner.getWidth(), 1) * 2, screenW / 5);
        return Math.abs(cx - topCx) <= slack;
    }

    private static boolean hasLaidOut(View v) {
        return v != null && v.getWidth() > 0;
    }

    private static boolean hasLaidOut(List<View> items) {
        if (items == null) return false;
        for (View v : items) {
            if (hasLaidOut(v)) return true;
        }
        return false;
    }
}
