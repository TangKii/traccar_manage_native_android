# Traccar 原生 Android 客户端

基于 Traccar 6.17.0 服务端的原生 Android 客户端（Kotlin + 传统 View），覆盖登录、实时地图、设备/用户管理、围栏、告警、轨迹回放与报表。服务端零改动，仅使用官方 REST 与 WebSocket 接口。

## 功能

- 服务器配置与登录：`/api/server` 探活，Basic 登录后换 6 个月 Bearer token，支持自动登录。
- 首页地图：MapLibre + 高德 XYZ 栅格瓦片（免 Key），WGS84→GCJ-02 纠偏，WebSocket 实时位置，围栏叠加，道路/卫星样式切换。
- 设备详情与轨迹回放：最新位置、地址、电量、速度；今天/昨天/近一周/自定义时间范围，1x/2x/4x 播放，断点虚线。
- 告警中心：近 90 天事件、类型筛选、未读红点；前台服务轮询新事件并推送通知。
- 报表与行程：汇总卡、行程列表、单段轨迹地图。
- 设备/用户管理：按服务端权限（readonly / deviceReadonly / deviceLimit / userLimit）做界面降级，删除二次确认。
- DayNight 深色模式、全量中文文案资源化、列表 DiffUtil 精确刷新。

## 目录结构

```
app/
  src/main/java/com/traccar/nativeapp/
    App.kt                    应用入口（MapLibre 初始化、崩溃日志、AppText 注入）
    data/                     Api、Socket、Models、Prefs、Report、Geofence、Units、NetworkPolicy、AppText
    map/                      MapStyles（高德瓦片样式）、CoordTransform（WGS84↔GCJ-02）
    ui/                       15 个 Activity、NotificationService、Ui.kt、glass/Glass.kt
  src/main/res/               layout / drawable / values / values-night
  src/test/java/              纯 JVM 单元测试
gradle/wrapper/               Gradle 8.11.1 wrapper（gradlew + jar）
build-release.sh              构建、校验签名、归档（作者环境发布入口）
```

## 构建

要求：

- JDK 17 或 21
- Android SDK Platform 35、Build-Tools 35+
- 已随工程提供 Gradle wrapper（8.11.1），无需本机安装 Gradle

```bash
# Debug（单元测试 + 安装包）
./gradlew testDebugUnitTest assembleDebug

# Release（R8 + 资源收缩；有 key.properties 时自动签名）
./gradlew assembleRelease
```

版本号：

- 默认按北京时间 `yyMMddHH` 生成 `versionCode`，`versionName` 为 `1.1.<yyMMddHH>`，每次发版单调递增。
- 可覆盖：`./gradlew assembleRelease -PversionCode=26111709 -PversionName=1.1.26111709`，或环境变量 `VERSION_CODE` / `VERSION_NAME`。
- `build-release.sh` 会自动传入时间戳版本号；设置 `GRADLE_OFFLINE=1` 可走离线构建。

签名：

- 工程根目录的 `key.properties` 与 `traccar-release.keystore` 是当前测试包的发布签名资产，构建脚本会校验证书指纹。
- 正式对外分发前应轮换密钥，并把 keystore 与口令移出版本库/分发压缩包（本次修复未改动该 P0 项）。

## 网络与安全策略

- 服务器地址支持 `https://`，以及局域网 `http://`（localhost、10/8、172.16/12、192.168/16、169.254/16、IPv6 回环/ULA/链路本地、单段主机名、`.local/.lan/.home`）。
- 公网明文 `http://` 会在 OkHttp 层被拒绝并提示改用 HTTPS，避免 Basic 凭据与 token 明文传输；Manifest 仍保留全局明文开关以支持内网部署。
- 服务端未配置 geocoder 时，逆地理编码自动降级显示坐标，并记录调试日志。

## API 兼容性（Traccar 6.17 OpenAPI）

- 登录与换 token 期间强制使用 Basic，避免本地旧 Bearer 抢先；登录 401 提示账号密码错误，`GET /session` 的 404 视为会话失效并清理 token。
- `/reports/events` 要求至少一个 `deviceId` 或 `groupId`；无设备时客户端直接展示空态，不发出无效请求。
- 历史轨迹改用 `/positions?deviceId=&from=&to=`；`/reports/route` 在 OpenAPI 中已标记为弃用。
- `/session/token` 与 `/server/geocode` 按文档发送 `Accept: text/plain`，其余 JSON 接口默认 `Accept: application/json`。
- WebSocket `/api/socket` 与 REST 共用 `NetworkPolicy`：wss/内网 ws 放行，公网 ws 拒绝且不再重连。
- OpenAPI 只写速度/时长为 number/integer，未定义单位；工程按 Traccar 服务端实现假定 speed=knots、duration/engineHours=ms，集中说明在 `Units` 与 `Report` 中。

## 测试与 CI

```bash
./gradlew testDebugUnitTest
```

覆盖：节→km/h 换算、私有网段判定、围栏 WKT 解析、WGS84/GCJ-02 转换与往返精度。

CI：`.github/workflows/android.yml` 在 push / PR 时安装 JDK 17 与 Android SDK 35，运行单元测试并构建 Debug APK。

## 运行时注意事项

- release 已开启 R8 与资源收缩，保留规则见 `app/proguard-rules.pro`。
- 仅打包 `armeabi-v7a` / `arm64-v8a`，x86 模拟器无法安装。
- Android 15 对 `dataSync` 前台服务有累计运行时限：超时回调 `NotificationService.onTimeout` 会停止轮询，下次打开 App 重新拉起。
- 告警轮询按「已读时间 → 现在」增量拉取（最多回溯 7 天），设备 ID 缓存 10 分钟刷新。
- 深色模式与状态栏图标随系统 DayNight 自动切换。

## 本次修复摘要

1. 轨迹回放「近一周」由单日改为连续 7 天。
2. 全链路速度由 Traccar 的「节」换算为 km/h（首页、详情、回放、报表、行程）。
3. 前台服务实现 Android 15 `onTimeout`；事件轮询增量化、设备缓存定时刷新。
4. 公网明文 HTTP 运行期拦截，局域网 HTTP 保持可用。
5. versionCode/versionName 自动递增；release 开启 R8 与资源收缩。
6. 移除未使用的定位权限；新增 values-night 深色主题；中文文案全部迁入资源；列表改用 DiffUtil。
7. 静默吞异常改为可检索日志（接口路径、deviceId 等关键参数，不记录 token/密码）。
8. 补齐 Gradle wrapper、README、单元测试与 CI。