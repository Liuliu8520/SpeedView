# SpeedView 时速计

一个 Android 悬浮球应用，骑行时在其他应用上层实时显示当前速度，并提供累计里程、平均速度、最高速度等统计。支持拖拽移动、点击进入主界面。

## 功能特性

- **悬浮显示**：圆形悬浮球，`TYPE_APPLICATION_OVERLAY` 层级，始终显示在其他应用上方
- **实时速度**：`FusedLocationProviderClient` 1 秒间隔回调，数字醒目显示（km/h）
- **累计里程**：服务运行期间持续累计，精确到 0.1 km
- **速度统计**：平均速度、最高速度
- **拖拽与点击**：任意拖拽移动（边界夹紧），8dp 阈值区分点击/拖拽，点击打开主界面
- **速度环**：外圈进度弧随速度填充，达到「弧满量程最大速度」时闭合为整圆
- **三段变色**：相对「变色参考速度」`<90%` 绿 / `90%~110%` 橙 / `≥110%` 红（超速时数字也变红）
- **可配置**：变色参考速度、弧满量程最大速度、悬浮球大小（64~256 dp）
- **模拟测试**：内置模拟数据生成器，按活动类型（步行/跑步/自行车/电动轻便摩托车/摩托车/汽车）在区间内波动，无需真实 GPS 即可测试

## 截图

**主界面与悬浮球**

<img width="210" alt="主界面与悬浮球" src="https://github.com/user-attachments/assets/0a8a2d18-ce78-4fe2-bb03-e514a7861c79" />

**设置弹窗**

<img width="210" alt="设置弹窗" src="https://github.com/user-attachments/assets/a73ad378-a8d6-4f3f-8bae-98881fcae0c4" />

## 技术栈

| 项 | 配置 |
|----|------|
| 语言 | Java 11 |
| 构建 | Android Gradle Plugin 9.2.1 + Gradle 版本目录（libs.versions.toml） |
| 最低 SDK | 29（Android 10） |
| 目标 SDK | 36 |
| UI | Material Components 3 |
| 定位 | Google Play Services Location 21.0.1（FusedLocationProviderClient） |
| 包名 | `com.liuliu.speedview` |

## 项目结构

```
app/src/main/
├── AndroidManifest.xml              # 权限声明、FloatingService 注册
├── java/com/liuliu/speedview/
│   ├── MainActivity.java            # 主界面、权限申请、设置弹窗
│   ├── FloatingService.java         # 前台服务、定位回调、模拟数据
│   ├── FloatingViewManager.java     # 悬浮球窗口管理（单例）、拖拽/点击、统计
│   ├── SpeedFloatingView.java       # 自定义 View：圆形速度球绘制
│   ├── AppPrefs.java                # SharedPreferences 设置项封装
│   └── MockProfile.java             # 模拟数据活动类型预设
└── res/
    ├── layout/
    │   ├── activity_main.xml        # 主界面布局
    │   └── dialog_settings.xml      # 设置弹窗布局
    └── values/                      # colors.xml / strings.xml / themes.xml
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 悬浮球显示在其他应用上方 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 获取定位计算速度 |
| `ACCESS_BACKGROUND_LOCATION` | Android 10+ 后台持续定位 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 前台服务（location 类型） |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |

## 构建与运行

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11+（项目未启用 `java.toolchain`，使用本机 JDK，避免 jlink 路径问题）

### 步骤

1. 克隆仓库

```bash
git clone <your-repo-url>
cd SpeedView
```

2. 用 Android Studio 打开项目根目录，等待 Gradle Sync 完成

3. 连接设备或启动模拟器（API 29+），点击 Run 或执行：

```bash
./gradlew :app:assembleDebug
```

4. 安装 debug 包到设备

```bash
./gradlew :app:installDebug
```

## 使用说明

1. 启动应用，点击「开始骑行」
2. 依次授权：前台定位 → 后台定位（Android 10+，单独弹窗，需选「始终允许」）→ 悬浮窗权限
3. 全部通过后，悬浮球出现并开始显示速度与累计里程
4. 悬浮球可拖拽到任意位置；点击悬浮球回到主界面查看统计
5. 点击「结束骑行」停止服务并隐藏悬浮球

### 设置

主界面右上角齿轮按钮打开设置弹窗：

- **变色参考速度**：环色与数字变色的参考值（不得大于弧满量程最大速度）
- **弧满量程最大速度**：进度弧 100% 对应的速度，达到该值弧闭合为整圆
- **测试数据范围**：模拟模式的速度波动区间
- **悬浮球大小**：64~256 dp，步进 8，确定后即时生效

### 模拟测试模式

主界面底部「模拟模式（测试用）」开关开启后，点击「开始骑行」将使用模拟数据而非真实 GPS：

| 类型 | 速度区间 (km/h) |
|------|----------------|
| 步行 | 3 ~ 6 |
| 跑步 | 7 ~ 13 |
| 自行车 | 10 ~ 30 |
| 电动轻便摩托车 | 15 ~ 50 |
| 摩托车（默认） | 20 ~ 90 |
| 汽车 | 30 ~ 110 |

> 模拟模式下仍需授予定位权限（前台服务类型声明为 `location`，Android 14+ 启动时强制校验），但不会真正访问 GPS。

## 定位与过滤策略

为避免 GPS 漂移导致速度/里程失真，采用以下过滤：

- 定位精度 `accuracy < 20m` 才采纳
- 单次位移 `0~50m` 才计入里程（超出视为漂移丢弃）
- 速度取 `location.getSpeed() * 3.6`（m/s → km/h）
- 统计在主线程执行（定位回调通过主线程 looper 投递）

## 兼容性说明

- **Android 14+**：启动前台服务需在前台，已通过 `startForegroundService` + 立即 `startForeground` 处理
- **Android 10+**：后台定位需 `ACCESS_BACKGROUND_LOCATION`，且在系统设置中保持「始终允许」
- **国产 ROM**（MIUI / HarmonyOS / OPPO 等）：可能需额外允许「后台弹窗」「电池优化白名单」，属 ROM 层限制，代码无法绕过，需用户手动授予

## 常见问题

**Q：悬浮球不出现？**
确认已授予「显示在其他应用上层」权限（`SYSTEM_ALERT_WINDOW`）。部分国产 ROM 还需开启「后台弹窗」权限。

**Q：速度一直为 0？**
确认 GPS 已开启、定位权限为「始终允许」，并到室外获取卫星信号。室内测试请使用模拟模式。

**Q：弧达到最大速度也不闭合？**
进度弧在速度达到「弧满量程最大速度」时闭合为整圆（360°）。请检查设置中该值是否偏大。

## License

待添加
