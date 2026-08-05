package com.liuliu.speedview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private TextView tvSpeed, tvDistance, tvAvg, tvMax, tvStatus;
    private MaterialButton btnToggle;
    private ImageButton btnSettings;
    private SpeedChartView chartSpeed;
    private boolean mockEnabled = false;

    private boolean riding = false;
    // 二次返回退出的首次按下时间戳
    private long backPressedTime = 0L;
    // 统计数据更新监听器：服务写入新值时主线程回调，同步刷新主界面（替代轮询）
    private final FloatingViewManager.OnStatsListener statsListener = this::refreshUI;

    // 权限请求：定位 + 通知（Android 13+）
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Boolean fineGranted = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                        if (fineGranted != null && fineGranted) {
                            // Android 10+：后台定位必须在前台定位授权后单独请求
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                requestBackgroundLocation();
                            } else {
                                requestOverlayPermission();
                            }
                        } else {
                            toast(R.string.perm_rationale_location);
                        }
                    });

    private final ActivityResultLauncher<String> backgroundLocationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> requestOverlayPermission());

    private final ActivityResultLauncher<Intent> overlayLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (Settings.canDrawOverlays(this)) {
                            tryStartRide();
                        } else {
                            toast(R.string.perm_rationale_overlay);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        FloatingViewManager mgr = FloatingViewManager.getInstance();
        mgr.init(this);
        // 应用持久化的设置：变色参考速度 + 进度弧最大速度 + 球大小
        mgr.setSpeedLimit(AppPrefs.getRefSpeed(this));
        mgr.setArcMaxSpeed(AppPrefs.getArcMaxSpeed(this));
        mgr.setBallSize(AppPrefs.getBallSizeDp(this));

        tvSpeed = findViewById(R.id.tv_speed_value);
        tvDistance = findViewById(R.id.tv_distance_value);
        tvAvg = findViewById(R.id.tv_avg_value);
        tvMax = findViewById(R.id.tv_max_value);
        tvStatus = findViewById(R.id.tv_status);
        btnToggle = findViewById(R.id.btn_toggle);
        btnSettings = findViewById(R.id.btn_settings);
        chartSpeed = findViewById(R.id.chart_speed);

        btnToggle.setOnClickListener(v -> onToggleClicked());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // 恢复测速/模拟状态：Activity 重建后从运行中的服务与持久化偏好读取，
        // 避免退出后再进入导致"结束测速"按钮与"模拟模式"状态被重置。
        riding = FloatingService.isRunning();
        mockEnabled = FloatingService.isRunning()
                ? FloatingService.isMockMode()
                : AppPrefs.getMockEnabled(this);

        updateButtonUI();
        setupBackPress();
    }

    /** 二次返回退出：首次提示，2 秒内再按一次退出。测速中退出，前台服务与浮球继续在后台运行。 */
    private void setupBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (System.currentTimeMillis() - backPressedTime < 2000L) {
                    finish();
                } else {
                    backPressedTime = System.currentTimeMillis();
                    Toast.makeText(MainActivity.this,
                            riding ? R.string.toast_back_again_riding : R.string.toast_back_again,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void onToggleClicked() {
        if (riding) {
            stopRide();
        } else {
            tryStartRide();
        }
    }

    /** 检查权限链，逐步申请，全部满足后启动服务。 */
    private void tryStartRide() {
        // 0. 非模拟模式下，系统定位必须已开启，否则点击无效并提示
        if (!mockEnabled && !isLocationEnabled()) {
            Toast.makeText(this, R.string.toast_location_off, Toast.LENGTH_LONG).show();
            return;
        }
        // 1. 前台定位权限
        if (!hasFineLocation()) {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
            return;
        }
        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            });
            return;
        }
        // 2. 后台定位（Android 10+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestBackgroundLocation();
            return;
        }
        // 3. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        startRide();
    }

    private void startRide() {
        FloatingViewManager.getInstance().resetStats();
        Intent service = new Intent(this, FloatingService.class);
        service.putExtra(FloatingService.EXTRA_MOCK_MODE, mockEnabled);
        ContextCompat.startForegroundService(this, service);
        riding = true;
        updateButtonUI();
        refreshUI();
    }

    private void stopRide() {
        stopService(new Intent(this, FloatingService.class));
        riding = false;
        updateButtonUI();
        refreshUI();
    }

    private void refreshUI() {
        FloatingViewManager mgr = FloatingViewManager.getInstance();
        float currentSpeed = mgr.getCurrentSpeedKmh();
        tvSpeed.setText(formatSpeed(currentSpeed));
        // 当前速度值颜色随悬浮球变色（绿/橙/红 三段，与悬浮球环色一致）
        int limit = mgr.getSpeedLimitKmh();
        float ratio = limit > 0 ? currentSpeed / limit : 0f;
        tvSpeed.setTextColor(getColor(resolveSpeedColorRes(ratio)));
        tvDistance.setText(formatDistance(mgr.getDistanceKm()));
        tvAvg.setText(formatSpeedKmh(mgr.getAvgSpeedKmh()));
        tvMax.setText(formatSpeedKmh(mgr.getMaxSpeedKmh()));
        if (chartSpeed != null) {
            chartSpeed.invalidate();
        }
    }

    /**
     * 主界面速度值变色资源：≤80% 恢复蓝色（原默认色），80%~90% 绿，90%~110% 橙，>=110% 红。
     * 高速段（绿/橙/红）与悬浮球环色保持一致，低速段恢复蓝色以保持原视觉风格。
     */
    private int resolveSpeedColorRes(float ratio) {
        if (ratio >= 1.1f) return R.color.speed_red;
        if (ratio >= 0.9f) return R.color.speed_orange;
        if (ratio > 0.8f) return R.color.speed_green;
        return R.color.speed_blue;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 订阅统计数据更新：服务每写入新速度/里程时回调刷新，与悬浮球同帧
        FloatingViewManager.getInstance().registerStatsListener(statsListener);
        // 立即刷新一次，显示服务端当前状态（恢复前台时追赶最新值）
        refreshUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不可见时停止刷新，避免无意义的主线程开销
        FloatingViewManager.getInstance().unregisterStatsListener(statsListener);
    }

    private void updateButtonUI() {
        if (riding) {
            btnToggle.setText(R.string.stop_ride);
            tvStatus.setText(R.string.riding_status_running);
        } else {
            btnToggle.setText(R.string.start_ride);
            tvStatus.setText(R.string.riding_status_idle);
        }
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
    }

    private void requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayLauncher.launch(intent);
        } else {
            startRide();
        }
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 系统定位开关是否已开启（GPS 或网络定位任一可用）。 */
    @SuppressWarnings("MissingPermission")
    private boolean isLocationEnabled() {
        android.location.LocationManager lm = (android.location.LocationManager)
                getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 设置弹窗：变色参考速度 / 弧满量程最大速度 / 测试数据范围 / 悬浮球大小。 */
    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        TextInputEditText etRefSpeed = dialogView.findViewById(R.id.et_ref_speed);
        TextInputEditText etArcMax = dialogView.findViewById(R.id.et_arc_max_speed);
        MaterialAutoCompleteTextView actvProfile = dialogView.findViewById(R.id.actv_mock_profile);
        Slider sliderBallSize = dialogView.findViewById(R.id.slider_ball_size);
        MaterialSwitch switchMock = dialogView.findViewById(R.id.switch_mock);

        etRefSpeed.setText(String.valueOf(AppPrefs.getRefSpeed(this)));
        etArcMax.setText(String.valueOf(AppPrefs.getArcMaxSpeed(this)));
        setSelectionEnd(etRefSpeed);
        setSelectionEnd(etArcMax);

        // 悬浮球大小滑块
        float savedSize = AppPrefs.getBallSizeDp(this);
        sliderBallSize.setValue(snapToStep(savedSize, 64f, 256f, 8f));

        // 模拟模式开关：初始状态取当前 mockEnabled
        switchMock.setChecked(mockEnabled);

        // 活动类型下拉
        String[] profileNames = MockProfile.names();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, profileNames);
        actvProfile.setAdapter(adapter);
        int curIdx = AppPrefs.getMockProfileIdx(this);
        actvProfile.setText(profileNames[curIdx], false);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings)
                .setView(dialogView)
                .setPositiveButton(R.string.settings_confirm, (d, w) -> {
                    int refSpeed = parsePositiveInt(textOf(etRefSpeed), -1);
                    int arcMax = parsePositiveInt(textOf(etArcMax), -1);
                    if (refSpeed <= 0 || arcMax <= 0) {
                        toast(R.string.settings_invalid_input);
                        return;
                    }
                    if (refSpeed > arcMax) {
                        toast(R.string.settings_ref_exceeds_arc);
                        return;
                    }
                    // 下拉选中的索引
                    String selected = actvProfile.getText() == null
                            ? "" : actvProfile.getText().toString().trim();
                    int profileIdx = indexOf(profileNames, selected, MockProfile.defaultIndex());

                    float ballSize = sliderBallSize.getValue();

                    AppPrefs.setRefSpeed(this, refSpeed);
                    AppPrefs.setArcMaxSpeed(this, arcMax);
                    AppPrefs.setMockProfileIdx(this, profileIdx);
                    AppPrefs.setBallSizeDp(this, ballSize);

                    // 模拟模式开关：仅持久化并更新内存值，运行中的服务模式不变（需重启测速生效）
                    mockEnabled = switchMock.isChecked();
                    AppPrefs.setMockEnabled(this, mockEnabled);

                    FloatingViewManager m = FloatingViewManager.getInstance();
                    m.setSpeedLimit(refSpeed);
                    m.setArcMaxSpeed(arcMax);
                    m.setBallSize(ballSize);
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /** 将任意值对齐到滑块的步进刻度（Slider 要求 valueFrom + n*stepSize）。 */
    private float snapToStep(float value, float from, float to, float step) {
        float clamped = Math.max(from, Math.min(to, value));
        int n = Math.round((clamped - from) / step);
        return from + n * step;
    }

    private void setSelectionEnd(TextInputEditText et) {
        if (et.getText() != null) {
            et.setSelection(et.getText().length());
        }
    }

    private String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int indexOf(String[] arr, String target, int fallback) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(target)) return i;
        }
        return fallback;
    }

    private int parsePositiveInt(String s, int fallback) {
        if (s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String formatSpeed(float kmh) {
        return String.valueOf(Math.round(kmh));
    }

    private String formatDistance(float km) {
        return String.format("%.1f", km);
    }

    private String formatSpeedKmh(float kmh) {
        return Math.round(kmh) + " km/h";
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }
}
