package com.liuliu.speedview;

/**
 * 模拟测试数据的速度范围预设，按活动类型区分。
 * {@link #minKmh} 与 {@link #maxKmh} 为模拟速度的波动区间。
 */
public final class MockProfile {

    public final String name;
    public final float minKmh;
    public final float maxKmh;

    public MockProfile(String name, float minKmh, float maxKmh) {
        this.name = name;
        this.minKmh = minKmh;
        this.maxKmh = maxKmh;
    }

    /** 预设列表，顺序即下拉索引。 */
    public static final MockProfile[] PROFILES = {
            new MockProfile("步行", 3f, 6f),
            new MockProfile("跑步", 7f, 13f),
            new MockProfile("自行车", 10f, 30f),
            new MockProfile("电动轻便摩托车", 15f, 50f),
            new MockProfile("摩托车", 20f, 90f),
            new MockProfile("汽车", 30f, 110f),
    };

    /** 默认索引：摩托车（与本项目主题一致）。 */
    public static int defaultIndex() {
        return 4;
    }

    public static MockProfile get(int index) {
        if (index < 0 || index >= PROFILES.length) {
            return PROFILES[defaultIndex()];
        }
        return PROFILES[index];
    }

    public static String[] names() {
        String[] arr = new String[PROFILES.length];
        for (int i = 0; i < PROFILES.length; i++) {
            arr[i] = PROFILES[i].name;
        }
        return arr;
    }
}
