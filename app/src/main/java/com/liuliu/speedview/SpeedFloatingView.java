package com.liuliu.speedview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 速度悬浮球自定义 View（百度/高德导航速度球风格）。
 * 外观：
 * 1. 外圈灰色背景环 + 彩色进度弧（绿/橙/红 三段变色，弧长随速度）。
 * 2. 中心白色圆面 + 大号速度数字 + 下方 "km/h"。
 * 通过 {@link #setSpeed(float)} 更新速度，{@link #setSpeedLimit(int)} 设置变色参考速度。
 */
public class SpeedFloatingView extends View {

    /** 悬浮球直径范围（dp） */
    private static final float MIN_SIZE_DP = 64f;
    private static final float MAX_SIZE_DP = 256f;
    /** 外圈环宽（dp） */
    private static final float RING_WIDTH_DP = 7f;
    /** 进度弧最大扫过角度（度）：360 表示速度达到 arcMaxSpeed 时弧闭合为整圆 */
    private static final float MAX_SWEEP_DEG = 360f;

    private final Paint ringBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private float speedKmh = 0f;
    /** 变色参考速度（km/h），仅用于环色与数字变色。 */
    private int speedLimitKmh = 60;
    /** 进度弧 100% 对应的最大速度（km/h），独立于变色阈值。 */
    private int arcMaxSpeedKmh = 80;
    /** 悬浮球直径（dp），可在设置中调整 */
    private float sizeDp = 112f;

    public SpeedFloatingView(Context context) {
        this(context, null);
    }

    public SpeedFloatingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        final float density = getResources().getDisplayMetrics().density;
        final float ringWidth = RING_WIDTH_DP * density;

        // 外圈背景环
        ringBgPaint.setStyle(Paint.Style.STROKE);
        ringBgPaint.setStrokeWidth(ringWidth);
        ringBgPaint.setColor(getContext().getColor(R.color.speed_ring_bg));
        ringBgPaint.setStrokeCap(Paint.Cap.ROUND);

        // 外圈进度弧（颜色随速度动态切换）
        ringFgPaint.setStyle(Paint.Style.STROKE);
        ringFgPaint.setStrokeWidth(ringWidth);
        ringFgPaint.setStrokeCap(Paint.Cap.ROUND);

        // 中心白圆面
        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(getContext().getColor(R.color.speed_bg_white));

        // 中心速度数字
        speedPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        speedPaint.setTextAlign(Paint.Align.CENTER);

        // 单位
        unitPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setColor(getContext().getColor(R.color.speed_text_dark));
    }

    /** 更新显示的速度（km/h）。 */
    public void setSpeed(float kmh) {
        if (kmh < 0f) kmh = 0f;
        this.speedKmh = kmh;
        invalidate();
    }

    /** 设置变色参考速度阈值（km/h），仅影响环色与数字变色。默认 60。 */
    public void setSpeedLimit(int kmh) {
        if (kmh <= 0) return;
        this.speedLimitKmh = kmh;
        invalidate();
    }

    /** 设置进度弧 100% 对应的最大速度（km/h），独立于变色阈值。默认 80。 */
    public void setArcMaxSpeed(int kmh) {
        if (kmh <= 0) return;
        this.arcMaxSpeedKmh = kmh;
        invalidate();
    }

    /** 设置悬浮球直径（dp），范围 [64, 256]，默认 112。 */
    public void setSizeDp(float dp) {
        this.sizeDp = Math.max(MIN_SIZE_DP, Math.min(MAX_SIZE_DP, dp));
        requestLayout();
        invalidate();
    }

    public float getSizeDp() {
        return sizeDp;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float px = sizeDp * getResources().getDisplayMetrics().density;
        int size = Math.round(px);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float halfStroke = ringFgPaint.getStrokeWidth() / 2f;
        final float radius = (Math.min(getWidth(), getHeight()) / 2f) - halfStroke;

        // 1. 外圈背景环（整圈灰）
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(ringRect, 0f, 360f, false, ringBgPaint);

        // 2. 外圈进度弧（从顶部 -90° 开始顺时针）
        //    弧长由 arcMaxSpeed 决定（speed/arcMaxSpeed，满量程=100%弧）；
        //    颜色由 speedLimit 决定（变色阈值）。
        float arcRatio = arcMaxSpeedKmh > 0 ? (speedKmh / arcMaxSpeedKmh) : 0f;
        float sweep = Math.min(arcRatio, 1.0f) * MAX_SWEEP_DEG;
        if (sweep > 0f) {
            float colorRatio = speedLimitKmh > 0 ? (speedKmh / speedLimitKmh) : 0f;
            ringFgPaint.setColor(resolveRingColor(colorRatio));
            // startAngle=-90 表示从顶部开始；sweepAngle>0 顺时针
            canvas.drawArc(ringRect, -90f, sweep, false, ringFgPaint);
        }

        // 3. 中心白圆面（盖住弧内侧，留出环形）
        float innerRadius = radius - ringFgPaint.getStrokeWidth();
        canvas.drawCircle(cx, cy, innerRadius, centerPaint);

        // 4. 中心速度数字 + 单位（颜色由 speedLimit 决定）
        float colorRatio = speedLimitKmh > 0 ? (speedKmh / speedLimitKmh) : 0f;
        boolean overSpeed = colorRatio >= 1.1f;
        speedPaint.setColor(overSpeed
                ? getContext().getColor(R.color.speed_red)
                : getContext().getColor(R.color.speed_blue));

        String speedText = formatSpeed(speedKmh);
        speedPaint.setTextSize(resolveSpeedTextSize(speedText, innerRadius));
        // 数字略上移，给下方单位留位
        float textY = cy - innerRadius * 0.04f + speedPaint.getTextSize() / 3f;
        canvas.drawText(speedText, cx, textY, speedPaint);

        unitPaint.setTextSize(innerRadius * 0.28f);
        canvas.drawText("km/h", cx, cy + innerRadius * 0.5f, unitPaint);
    }

    /** 根据速度比例选择环色：<0.9 绿，0.9~1.1 橙，>=1.1 红。 */
    private int resolveRingColor(float ratio) {
        if (ratio >= 1.1f) return getContext().getColor(R.color.speed_red);
        if (ratio >= 0.9f) return getContext().getColor(R.color.speed_orange);
        return getContext().getColor(R.color.speed_green);
    }

    private String formatSpeed(float kmh) {
        return String.format("%d", Math.round(kmh));
    }

    private float resolveSpeedTextSize(String text, float innerRadius) {
        float base = innerRadius * 0.78f;
        if (text.length() >= 4) return base * 0.66f;
        if (text.length() == 3) return base * 0.78f;
        return base;
    }
}
