package com.liuliu.speedview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * 悬浮球前台服务。
 * 职责：
 * 1. 以 foregroundServiceType=location 运行，保证后台持续定位。
 * 2. 使用 FusedLocationProviderClient 动态间隔获取定位：速度超过变色参考速度时 0.5s，否则 1s。
 * 3. 过滤：accuracy < 20m 且单次位移 0~50m 才计入里程。
 * 4. 将速度/里程写入 {@link FloatingViewManager}，由其刷新悬浮球与统计。
 */
public class FloatingService extends Service {

    private static final String CHANNEL_ID = "speedview_location";
    private static final int NOTIF_ID = 1001;
    /** Intent extra：为 true 时使用模拟数据，不依赖真实定位（用于功能测试）。 */
    public static final String EXTRA_MOCK_MODE = "mock_mode";

    /**
     * 服务运行状态静态标志：onCreate 置 true，onDestroy 置 false。
     * 用于 Activity 重建后判断测速是否仍在进行（前台服务可能在 Activity 销毁后继续运行）。
     */
    private static volatile boolean sRunning = false;
    /** 服务当前的模拟模式（仅当 sRunning=true 时有效）。 */
    private static volatile boolean sMockMode = false;

    /** 服务是否正在运行。 */
    public static boolean isRunning() {
        return sRunning;
    }

    /** 服务当前的模拟模式。仅在 {@link #isRunning()} 为 true 时有意义。 */
    public static boolean isMockMode() {
        return sMockMode;
    }

    /** 定位精度阈值（米），超过则视为漂移丢弃 */
    private static final float ACCURACY_THRESHOLD_M = 20f;
    /** 单次位移阈值（米），超出 [0, 50] 视为漂移丢弃 */
    private static final float MAX_STEP_M = 50f;
    private static final float MIN_STEP_M = 0f;
    /** 低速档定位间隔（毫秒）：速度 ≤ 变色参考速度时，1 秒一次以降低能耗 */
    private static final long SLOW_INTERVAL_MS = 1000L;
    /** 高速档定位间隔（毫秒）：速度 > 变色参考速度时，0.5 秒一次以提升灵敏度 */
    private static final long FAST_INTERVAL_MS = 500L;

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private boolean receivingLocation = false;
    /** 当前是否处于高速档（true=0.5s，false=1s）。仅在真实定位路径使用。 */
    private boolean fastIntervalActive = false;

    // 监听系统定位开关变化：定位被关闭时提示，重新开启后自动恢复定位
    private final BroadcastReceiver locationStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mockMode || receivingLocation) return;
            if (isLocationEnabled()) {
                // 定位已重新开启，尝试恢复
                startLocationUpdates();
                updateNotificationText(getString(R.string.notif_text));
            }
        }
    };
    private boolean receiverRegistered = false;

    // 模拟模式：用正弦波 + 抖动生成速度，便于无 GPS 环境下测试变色与里程
    private boolean mockMode = false;
    private boolean mockRunning = false;
    private float mockPhase = 0f;
    private float mockMinKmh = 20f;
    private float mockMaxKmh = 90f;
    private final Handler mockHandler = new Handler(Looper.getMainLooper());
    private final Runnable mockRunnable = new Runnable() {
        @Override
        public void run() {
            // 在 [mockMin, mockMax] 之间正弦波动，并叠加小幅随机抖动
            float mid = (mockMinKmh + mockMaxKmh) / 2f;
            float amp = (mockMaxKmh - mockMinKmh) / 2f;
            mockPhase += 0.06f;
            float base = mid + amp * (float) Math.sin(mockPhase);
            float jitter = (float) (Math.random() * (amp * 0.15f)) - amp * 0.075f;
            float speedKmh = Math.max(0f, base + jitter);
            // 动态频率：速度超过变色参考速度时 0.5s，否则 1s
            int limit = FloatingViewManager.getInstance().getSpeedLimit();
            long interval = (limit > 0 && speedKmh > limit) ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS;
            applyMockUpdate(speedKmh, interval);
            mockHandler.postDelayed(this, interval);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        FloatingViewManager.getInstance().init(this);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
        // 注册定位开关监听（API 31+ 用 ACTION_PROTOCOL_PROVIDER_CHANGED，兼容旧版 PROVIDERS_CHANGED）
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        try {
            registerReceiver(locationStateReceiver, filter);
            receiverRegistered = true;
        } catch (Exception e) {
            receiverRegistered = false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        FloatingViewManager mgr = FloatingViewManager.getInstance();
        mgr.init(this);
        // 应用持久化的设置：变色参考速度 + 进度弧最大速度 + 球大小
        mgr.setSpeedLimit(AppPrefs.getRefSpeed(this));
        mgr.setArcMaxSpeed(AppPrefs.getArcMaxSpeed(this));
        mgr.setBallSize(AppPrefs.getBallSizeDp(this));
        mgr.show();

        mockMode = intent != null && intent.getBooleanExtra(EXTRA_MOCK_MODE, false);
        sMockMode = mockMode;
        if (mockMode) {
            startMockUpdates();
        } else {
            startLocationUpdates();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopMockUpdates();
        stopLocationUpdates();
        if (receiverRegistered) {
            try {
                unregisterReceiver(locationStateReceiver);
            } catch (Exception e) {
                // 忽略未注册异常
            }
            receiverRegistered = false;
        }
        FloatingViewManager.getInstance().hide();
        sRunning = false;
        sMockMode = false;
        super.onDestroy();
    }

    /** 模拟一次速度更新：写入速度并按实际间隔累加里程。 */
    private void applyMockUpdate(float speedKmh, long intervalMs) {
        FloatingViewManager mgr = FloatingViewManager.getInstance();
        mgr.updateSpeed(speedKmh);
        // 里程 = 速度(km/h) / 3.6 * 间隔秒数 = 米
        mgr.addDistance(speedKmh / 3.6f * (intervalMs / 1000f));
    }

    private void startMockUpdates() {
        if (mockRunning) return;
        // 读取活动类型预设的速度范围
        MockProfile profile = MockProfile.get(AppPrefs.getMockProfileIdx(this));
        mockMinKmh = profile.minKmh;
        mockMaxKmh = profile.maxKmh;
        mockRunning = true;
        mockPhase = 0f;
        mockHandler.post(mockRunnable);
    }

    private void stopMockUpdates() {
        if (!mockRunning) return;
        mockHandler.removeCallbacks(mockRunnable);
        mockRunning = false;
    }

    /** 启动前台服务并指定 location 类型（Android 14+ 强制要求）。 */
    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private Notification buildNotification() {
        return buildNotification(getString(R.string.notif_text));
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /** 更新前台通知正文（用于定位关闭/缺 Google Play 服务时给用户提示）。 */
    private void updateNotificationText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }

    /** 判断系统定位是否已开启（GPS 或网络定位任一可用）。 */
    @SuppressWarnings("MissingPermission")
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return false;
        boolean gps = false, net = false;
        try {
            gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            net = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            // 某些 ROM 查询 provider 也可能抛 SecurityException
        }
        return gps || net;
    }

    /** 检查 Google Play 服务是否可用（FusedLocation 依赖它）。 */
    private boolean isGooglePlayAvailable() {
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        return code == com.google.android.gms.common.ConnectionResult.SUCCESS;
    }

    private void createNotificationChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        nm.createNotificationChannel(channel);
    }

    private void startLocationUpdates() {
        if (receivingLocation) return;
        // 前置检查 1：Google Play 服务（FusedLocation 依赖）
        if (!isGooglePlayAvailable()) {
            toastFromService(R.string.toast_no_google_play);
            updateNotificationText(getString(R.string.notif_no_google_play));
            return;
        }
        // 前置检查 2：系统定位开关
        if (!isLocationEnabled()) {
            toastFromService(R.string.toast_location_off);
            updateNotificationText(getString(R.string.notif_location_off));
            // 不请求定位；定位重新开启后由 locationStateReceiver 自动恢复
            return;
        }
        if (locationCallback == null) {
            locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(@Nullable LocationResult result) {
                    if (result == null) return;
                    for (Location loc : result.getLocations()) {
                        handleLocation(loc);
                    }
                }
            };
        }
        // 初始低速档（1s），速度超过变色参考速度后自动切高速档（0.5s）
        fastIntervalActive = false;
        try {
            fusedClient.requestLocationUpdates(buildLocationRequest(), locationCallback, Looper.getMainLooper());
            receivingLocation = true;
            updateNotificationText(getString(R.string.notif_text));
        } catch (SecurityException e) {
            // 缺少定位权限
            receivingLocation = false;
            toastFromService(R.string.perm_rationale_location);
        }
    }

    /** 根据当前档位构建定位请求（高速档 0.5s / 低速档 1s）。 */
    private LocationRequest buildLocationRequest() {
        long interval = fastIntervalActive ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS;
        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(interval)
                .setWaitForAccurateLocation(false)
                .build();
    }

    /** 速度跨越变色参考速度阈值时，切换定位频率以平衡灵敏度与能耗。 */
    private void maybeAdjustLocationInterval(float speedKmh) {
        if (!receivingLocation) return;
        int limit = FloatingViewManager.getInstance().getSpeedLimit();
        boolean shouldFast = limit > 0 && speedKmh > limit;
        if (shouldFast == fastIntervalActive) return;  // 档位未变，无需重启
        fastIntervalActive = shouldFast;
        try {
            fusedClient.removeLocationUpdates(locationCallback);
            fusedClient.requestLocationUpdates(buildLocationRequest(), locationCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {
            // 极少见：权限在运行中被撤销
        }
    }

    /** 从 Service 弹 Toast（Service 无 UI，需切主线程）。 */
    private void toastFromService(int resId) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, resId, Toast.LENGTH_LONG).show());
    }

    private void stopLocationUpdates() {
        if (!receivingLocation || locationCallback == null) return;
        fusedClient.removeLocationUpdates(locationCallback);
        receivingLocation = false;
    }

    /** 处理单次定位：过滤、计算速度与里程，写入 Manager。 */
    private void handleLocation(Location loc) {
        if (loc == null) return;
        // 精度过滤
        if (loc.getAccuracy() <= 0f || loc.getAccuracy() > ACCURACY_THRESHOLD_M) return;

        FloatingViewManager mgr = FloatingViewManager.getInstance();

        // 速度（m/s -> km/h），优先使用系统速度，缺失时用位移/时间估算
        float speedKmh;
        if (loc.hasSpeed() && loc.getSpeed() >= 0f) {
            speedKmh = loc.getSpeed() * 3.6f;
        } else {
            speedKmh = 0f;
        }

        if (lastLocation != null) {
            float distance = loc.distanceTo(lastLocation);
            // 位移过滤：0~50m 才计入里程
            if (distance > MIN_STEP_M && distance < MAX_STEP_M) {
                mgr.addDistance(distance);
                // 若系统速度缺失或为 0，则用位移/时间估算
                if (speedKmh == 0f) {
                    long dtMs = loc.getTime() - lastLocation.getTime();
                    if (dtMs > 0L) {
                        speedKmh = (distance / (dtMs / 1000f)) * 3.6f;
                    }
                }
            }
            // distance 不在区间内视为漂移，不计里程，速度也保持上一次有效值
        }
        lastLocation = loc;

        mgr.updateSpeed(speedKmh);
        // 速度跨越变色阈值时动态调整定位频率
        maybeAdjustLocationInterval(speedKmh);
    }
}
