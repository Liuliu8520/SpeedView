package com.liuliu.speedview;

import android.content.Context;

/**
 * 简单的偏好存储封装，用于持久化设置项。
 */
public final class AppPrefs {

    private static final String NAME = "speedview_prefs";
    private static final String KEY_REF_SPEED = "ref_speed_kmh";
    private static final String KEY_ARC_MAX_SPEED = "arc_max_speed_kmh";
    private static final String KEY_MOCK_PROFILE_IDX = "mock_profile_idx";
    private static final String KEY_BALL_SIZE_DP = "ball_size_dp";
    private static final String KEY_MOCK_ENABLED = "mock_enabled";
    private static final int DEFAULT_REF_SPEED = 60;
    private static final int DEFAULT_ARC_MAX_SPEED = 80;
    private static final float DEFAULT_BALL_SIZE_DP = 112f;
    private static final float MIN_BALL_SIZE_DP = 64f;
    private static final float MAX_BALL_SIZE_DP = 256f;

    private AppPrefs() {
    }

    /** 变色参考速度阈值（km/h），默认 60。 */
    public static int getRefSpeed(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(KEY_REF_SPEED, DEFAULT_REF_SPEED);
    }

    public static void setRefSpeed(Context c, int kmh) {
        if (kmh <= 0) return;
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_REF_SPEED, kmh)
                .apply();
    }

    /** 进度弧 100% 对应的最大速度（km/h），默认 80。 */
    public static int getArcMaxSpeed(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(KEY_ARC_MAX_SPEED, DEFAULT_ARC_MAX_SPEED);
    }

    public static void setArcMaxSpeed(Context c, int kmh) {
        if (kmh <= 0) return;
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_ARC_MAX_SPEED, kmh)
                .apply();
    }

    /** 模拟数据活动类型索引（见 {@link MockProfile}），默认摩托车。 */
    public static int getMockProfileIdx(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MOCK_PROFILE_IDX, MockProfile.defaultIndex());
    }

    public static void setMockProfileIdx(Context c, int idx) {
        if (idx < 0 || idx >= MockProfile.PROFILES.length) return;
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MOCK_PROFILE_IDX, idx)
                .apply();
    }

    /** 悬浮球直径（dp），范围 [64, 256]，默认 112。 */
    public static float getBallSizeDp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_BALL_SIZE_DP, DEFAULT_BALL_SIZE_DP);
    }

    public static void setBallSizeDp(Context c, float dp) {
        float clamped = Math.max(MIN_BALL_SIZE_DP, Math.min(MAX_BALL_SIZE_DP, dp));
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_BALL_SIZE_DP, clamped)
                .apply();
    }

    /** 模拟模式开关是否打开（仅当服务未运行时用作恢复初值），默认 false。 */
    public static boolean getMockEnabled(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MOCK_ENABLED, false);
    }

    public static void setMockEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MOCK_ENABLED, enabled)
                .apply();
    }
}
