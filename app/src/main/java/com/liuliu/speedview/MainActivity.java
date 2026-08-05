package com.liuliu.speedview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
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
    private MaterialSwitch switchMock;
    private boolean mockEnabled = false;

    private boolean riding = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final long refreshIntervalMs = 500L;

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
        switchMock = findViewById(R.id.switch_mock);

        btnToggle.setOnClickListener(v -> onToggleClicked());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        switchMock.setOnCheckedChangeListener((button, checked) -> mockEnabled = checked);
        updateButtonUI();
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
        uiHandler.postDelayed(refreshRunnable, refreshIntervalMs);
    }

    private void stopRide() {
        stopService(new Intent(this, FloatingService.class));
        riding = false;
        updateButtonUI();
        uiHandler.removeCallbacks(refreshRunnable);
        refreshUI();
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUI();
            if (riding) {
                uiHandler.postDelayed(this, refreshIntervalMs);
            }
        }
    };

    private void refreshUI() {
        FloatingViewManager mgr = FloatingViewManager.getInstance();
        tvSpeed.setText(formatSpeed(mgr.getCurrentSpeedKmh()));
        tvDistance.setText(formatDistance(mgr.getDistanceKm()));
        tvAvg.setText(formatSpeedKmh(mgr.getAvgSpeedKmh()));
        tvMax.setText(formatSpeedKmh(mgr.getMaxSpeedKmh()));
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

    /** 设置弹窗：变色参考速度 / 弧满量程最大速度 / 测试数据范围 / 悬浮球大小。 */
    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        TextInputEditText etRefSpeed = dialogView.findViewById(R.id.et_ref_speed);
        TextInputEditText etArcMax = dialogView.findViewById(R.id.et_arc_max_speed);
        MaterialAutoCompleteTextView actvProfile = dialogView.findViewById(R.id.actv_mock_profile);
        Slider sliderBallSize = dialogView.findViewById(R.id.slider_ball_size);

        etRefSpeed.setText(String.valueOf(AppPrefs.getRefSpeed(this)));
        etArcMax.setText(String.valueOf(AppPrefs.getArcMaxSpeed(this)));
        setSelectionEnd(etRefSpeed);
        setSelectionEnd(etArcMax);

        // 悬浮球大小滑块
        float savedSize = AppPrefs.getBallSizeDp(this);
        sliderBallSize.setValue(snapToStep(savedSize, 64f, 256f, 8f));

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
        return String.format(kmh < 10f ? "%.1f" : "%.0f", kmh);
    }

    private String formatDistance(float km) {
        return String.format("%.1f", km);
    }

    private String formatSpeedKmh(float kmh) {
        return String.format("%.1f km/h", kmh);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(refreshRunnable);
    }
}
