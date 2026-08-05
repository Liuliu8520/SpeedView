package com.liuliu.speedview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 主界面速度折线图。
 * 从 {@link FloatingViewManager#getSpeedHistory()} 读取最近 120 个速度采样（1 秒一个），
 * 绘制网格、折线、填充与当前值。折线颜色随当前速度变色（绿/橙/红），与悬浮球一致。
 * 由 {@link MainActivity} 的统计数据回调触发 {@link #invalidate()} 重绘。
 */
public class SpeedChartView extends View {

    private static final float GRID_LINES = 4f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public SpeedChartView(Context context) {
        super(context);
        init();
    }

    public SpeedChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeedChartView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(getContext().getColor(R.color.speed_blue));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(getContext().getColor(R.color.speed_blue));
        fillPaint.setAlpha(38);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(0xFFD0D7DE);

        axisLabelPaint.setTextSize(10f * density);
        axisLabelPaint.setColor(0xFF8A94A6);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(getContext().getColor(R.color.speed_blue));

        valuePaint.setTextSize(11f * density);
        valuePaint.setColor(getContext().getColor(R.color.speed_text_dark));
        valuePaint.setFakeBoldText(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        FloatingViewManager mgr = FloatingViewManager.getInstance();
        List<Float> data = mgr.getSpeedHistory();

        float density = getResources().getDisplayMetrics().density;
        float padL = 30f * density;
        float padR = 10f * density;
        float padT = 8f * density;
        float padB = 14f * density;
        float w = getWidth();
        float h = getHeight();
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;
        if (plotW <= 0 || plotH <= 0) return;

        // Y 轴量程：取历史最大值，下限 10，并向上取整到 10 的倍数，留出顶部空间
        float maxSample = 10f;
        for (Float v : data) {
            if (v != null && v > maxSample) maxSample = v;
        }
        float scaleMax = (float) Math.ceil(maxSample / 10f) * 10f;

        // 网格 + Y 轴标签
        axisLabelPaint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= GRID_LINES; i++) {
            float y = padT + plotH * (i / GRID_LINES);
            canvas.drawLine(padL, y, w - padR, y, gridPaint);
            String label = String.valueOf(Math.round(scaleMax * (1f - i / GRID_LINES)));
            canvas.drawText(label, padL - 4f * density, y + axisLabelPaint.getTextSize() / 3f, axisLabelPaint);
        }

        if (data.isEmpty()) {
            return;
        }

        // 折线颜色：按当前速度 / 变色参考速度
        float currentSpeed = mgr.getCurrentSpeedKmh();
        int limit = mgr.getSpeedLimitKmh();
        float ratio = limit > 0 ? currentSpeed / limit : 0f;
        int lineColor = resolveSpeedColor(ratio);
        linePaint.setColor(lineColor);
        fillPaint.setColor(lineColor);
        fillPaint.setAlpha(38);
        dotPaint.setColor(lineColor);

        int n = data.size();
        float xStep = n > 1 ? plotW / (n - 1) : 0f;

        linePath.reset();
        fillPath.reset();
        for (int i = 0; i < n; i++) {
            Float v = data.get(i);
            float speed = v == null ? 0f : v;
            float x = padL + (n > 1 ? i * xStep : plotW);
            float y = padT + plotH * (1f - Math.min(speed / scaleMax, 1f));
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, padT + plotH);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }
        }
        // 闭合填充路径到右下
        float lastX = padL + (n > 1 ? (n - 1) * xStep : plotW);
        fillPath.lineTo(lastX, padT + plotH);
        fillPath.close();
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // 末端当前值圆点 + 数值
        float lastSpeed = data.get(n - 1) == null ? 0f : data.get(n - 1);
        float lastY = padT + plotH * (1f - Math.min(lastSpeed / scaleMax, 1f));
        float dotR = 3.5f * density;
        canvas.drawCircle(lastX, lastY, dotR, dotPaint);

        valuePaint.setTextAlign(Paint.Align.LEFT);
        String valText = Math.round(lastSpeed) + " km/h";
        float tx = lastX + dotR + 4f * density;
        // 防止超出右边界
        if (tx + valuePaint.measureText(valText) > w - padR) {
            valuePaint.setTextAlign(Paint.Align.RIGHT);
            tx = lastX - dotR - 4f * density;
        }
        canvas.drawText(valText, tx, lastY + valuePaint.getTextSize() / 3f, valuePaint);
    }

    /** 与悬浮球一致的变色：<0.9 绿，0.9~1.1 橙，>=1.1 红。 */
    private int resolveSpeedColor(float ratio) {
        if (ratio >= 1.1f) return getContext().getColor(R.color.speed_red);
        if (ratio >= 0.9f) return getContext().getColor(R.color.speed_orange);
        return getContext().getColor(R.color.speed_green);
    }

    @SuppressWarnings("unused")
    private int alpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
