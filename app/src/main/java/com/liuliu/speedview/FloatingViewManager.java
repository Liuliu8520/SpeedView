package com.liuliu.speedview;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 悬浮球窗口管理器（单例）。
 * 职责：
 * 1. 通过 {@link WindowManager} 把 {@link SpeedFloatingView} 添加到 TYPE_APPLICATION_OVERLAY 层。
 * 2. 处理拖拽（更新 LayoutParams.x/y 并做屏幕边界夹紧）与点击（8dp 阈值区分）。
 * 3. 持有骑行统计数据，供 {@link FloatingService} 写入、{@link MainActivity} 读取。
 */
public class FloatingViewManager {

    /** 点击与拖拽的判定阈值（dp） */
    private static final float CLICK_TOLERANCE_DP = 8f;

    private static volatile FloatingViewManager instance;

    private Context appContext;
    private WindowManager windowManager;
    private SpeedFloatingView floatingView;
    private LayoutParams layoutParams;
    private boolean added = false;

    // 骑行统计（在主线程访问，定位回调同样通过主线程 looper 投递）
    private float currentSpeedKmh = 0f;
    private float totalDistanceMeter = 0f;
    private float maxSpeedKmh = 0f;
    private double speedSumKmh = 0d;
    private long speedSampleCount = 0L;
    // 变色参考速度（km/h），缓存以便球创建时立即应用
    private int speedLimitKmh = 60;
    // 进度弧 100% 对应的最大速度（km/h），缓存
    private int arcMaxSpeedKmh = 80;
    // 悬浮球直径（dp），缓存
    private float ballSizeDp = 112f;
    // 统计数据更新监听器（主线程回调）
    private final List<OnStatsListener> statsListeners = new CopyOnWriteArrayList<>();

    /** 统计数据（速度/里程/均速/最高）更新时回调，回调在主线程。 */
    public interface OnStatsListener {
        void onStatsUpdated();
    }

    private FloatingViewManager() {
    }

    public static FloatingViewManager getInstance() {
        if (instance == null) {
            synchronized (FloatingViewManager.class) {
                if (instance == null) {
                    instance = new FloatingViewManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 显示悬浮球。需要先确保已获取 SYSTEM_ALERT_WINDOW 权限。 */
    public void show() {
        if (added || windowManager == null) return;
        if (floatingView == null) {
            floatingView = new SpeedFloatingView(appContext);
            floatingView.setOnTouchListener(new FloatingTouchListener());
            floatingView.setSizeDp(ballSizeDp);
        }
        if (layoutParams == null) {
            layoutParams = new LayoutParams();
            // Android 8.0+ 统一使用 APPLICATION_OVERLAY
            layoutParams.type = LayoutParams.TYPE_APPLICATION_OVERLAY;
            layoutParams.format = PixelFormat.TRANSLUCENT;
            layoutParams.flags = LayoutParams.FLAG_NOT_FOCUSABLE
                    | LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            layoutParams.gravity = Gravity.TOP | Gravity.START;
            layoutParams.width = LayoutParams.WRAP_CONTENT;
            layoutParams.height = LayoutParams.WRAP_CONTENT;
            layoutParams.x = 0;
            layoutParams.y = 200;
        }
        try {
            windowManager.addView(floatingView, layoutParams);
            added = true;
            floatingView.setSpeed(currentSpeedKmh);
            floatingView.setSpeedLimit(speedLimitKmh);
            floatingView.setArcMaxSpeed(arcMaxSpeedKmh);
        } catch (Exception e) {
            // 缺少权限或 ROM 拦截时会抛异常，忽略避免崩溃
            added = false;
        }
    }

    /** 设置变色参考速度阈值（km/h）。即使球未显示也会缓存，显示时自动应用。 */
    public void setSpeedLimit(int kmh) {
        if (kmh <= 0) return;
        this.speedLimitKmh = kmh;
        if (floatingView != null) {
            floatingView.setSpeedLimit(kmh);
        }
    }

    public int getSpeedLimit() {
        return speedLimitKmh;
    }

    /** 设置进度弧 100% 对应的最大速度（km/h）。即使球未显示也会缓存，显示时自动应用。 */
    public void setArcMaxSpeed(int kmh) {
        if (kmh <= 0) return;
        this.arcMaxSpeedKmh = kmh;
        if (floatingView != null) {
            floatingView.setArcMaxSpeed(kmh);
        }
    }

    public int getArcMaxSpeed() {
        return arcMaxSpeedKmh;
    }

    /** 设置悬浮球直径（dp）。即使球未显示也会缓存，显示时自动应用。 */
    public void setBallSize(float dp) {
        this.ballSizeDp = dp;
        if (floatingView != null) {
            floatingView.setSizeDp(dp);
            // 已显示时需刷新窗口布局，使 WindowManager 按新尺寸重新测量
            if (added && layoutParams != null) {
                try {
                    windowManager.updateViewLayout(floatingView, layoutParams);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public float getBallSizeDp() {
        return ballSizeDp;
    }

    /** 注册统计数据更新监听器（主线程回调）。重复注册同一实例会被忽略。 */
    public void registerStatsListener(OnStatsListener l) {
        if (l != null && !statsListeners.contains(l)) {
            statsListeners.add(l);
        }
    }

    /** 取消注册监听器。 */
    public void unregisterStatsListener(OnStatsListener l) {
        statsListeners.remove(l);
    }

    /** 通知所有监听器刷新（主线程调用）。 */
    private void notifyStatsUpdated() {
        for (OnStatsListener l : statsListeners) {
            l.onStatsUpdated();
        }
    }

    /** 隐藏悬浮球。 */
    public void hide() {
        if (!added || floatingView == null) return;
        try {
            windowManager.removeView(floatingView);
        } catch (Exception ignored) {
        }
        added = false;
    }

    public boolean isShowing() {
        return added;
    }

    /** 更新当前速度并刷新悬浮球显示，同时更新最大/平均速度。 */
    public void updateSpeed(float kmh) {
        this.currentSpeedKmh = kmh;
        if (kmh > maxSpeedKmh) maxSpeedKmh = kmh;
        speedSumKmh += kmh;
        speedSampleCount++;
        if (floatingView != null && added) {
            floatingView.setSpeed(kmh);
        }
        notifyStatsUpdated();
    }

    /** 累加里程（单位：米）。 */
    public void addDistance(float meter) {
        if (meter > 0f) {
            totalDistanceMeter += meter;
            notifyStatsUpdated();
        }
    }

    /** 重置统计（不重置悬浮球位置）。 */
    public void resetStats() {
        currentSpeedKmh = 0f;
        totalDistanceMeter = 0f;
        maxSpeedKmh = 0f;
        speedSumKmh = 0d;
        speedSampleCount = 0L;
        if (floatingView != null && added) floatingView.setSpeed(0f);
        notifyStatsUpdated();
    }

    public float getCurrentSpeedKmh() {
        return currentSpeedKmh;
    }

    /** 累计里程，单位 km，保留 1 位小数语义。 */
    public float getDistanceKm() {
        return totalDistanceMeter / 1000f;
    }

    public float getMaxSpeedKmh() {
        return maxSpeedKmh;
    }

    public float getAvgSpeedKmh() {
        if (speedSampleCount == 0L) return 0f;
        return (float) (speedSumKmh / speedSampleCount);
    }

    /** 拖拽与点击判定。 */
    private class FloatingTouchListener implements View.OnTouchListener {
        private float downX, downY;
        private int initParamsX, initParamsY;
        private boolean isDragging;
        private final float touchSlop;

        FloatingTouchListener() {
            touchSlop = CLICK_TOLERANCE_DP
                    * appContext.getResources().getDisplayMetrics().density;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (layoutParams == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    initParamsX = layoutParams.x;
                    initParamsY = layoutParams.y;
                    isDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!isDragging && Math.hypot(dx, dy) > touchSlop) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        layoutParams.x = clamp(initParamsX + (int) dx,
                                -(floatingView.getWidth() / 2),
                                getScreenWidth() - floatingView.getWidth() / 2);
                        layoutParams.y = clamp(initParamsY + (int) dy,
                                -(floatingView.getHeight() / 2),
                                getScreenHeight() - floatingView.getHeight() / 2);
                        try {
                            windowManager.updateViewLayout(floatingView, layoutParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        openMainActivity();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    private void openMainActivity() {
        Intent intent = new Intent(appContext, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        appContext.startActivity(intent);
    }

    private int getScreenWidth() {
        return appContext.getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return appContext.getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
