# Traccar 原生 Android 客户端

基于 Traccar 6.17.0 服务端的原生 Android 客户端（Kotlin + 传统 View），覆盖登录、实时地图、设备/用户管理、围栏、告警、轨迹回放与报表。服务端零改动，仅使用官方 REST 与 WebSocket 接口。

## 功能

- 服务器配置与登录：`/api/server` 探活，Basic 登录后换 6 个月 Bearer token，支持自动登录。
- 首页地图：MapLibre + 高德 XYZ 栅格瓦片（免 Key），WGS84→GCJ-02 纠偏，WebSocket 实时位置，围栏叠加，道路/卫星样式切换，支持深色模式。
- 设备详情与轨迹回放：最新位置、地址、电量、速度；今天/昨天/近一周/自定义时间范围，1x/2x/4x 播放，断点虚线。
- 告警中心：近 90 天事件、类型筛选、未读红点；前台服务实时推送通知并轮询兜底。
- 报表与行程：汇总卡、行程列表、单段轨迹地图。
- 设备/用户管理：按服务端权限（readonly / deviceReadonly / deviceLimit / userLimit）做界面降级，删除二次确认。
- 全量中文文案资源化、列表 DiffUtil 精确刷新、统一沉浸式界面。

## 开发环境

要求：

- JDK 17 或 21
- Android SDK Platform 35、Build-Tools 35+
- 已随工程提供 Gradle wrapper（8.11.1），无需本机安装 Gradle

构建：

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

测试与 CI：

```bash
./gradlew testDebugUnitTest
```

覆盖：节→km/h 换算、私有网段判定、围栏 WKT 解析、WGS84/GCJ-02 转换与往返精度。

CI：`.github/workflows/android.yml` 在 push / PR 时安装 JDK 17 与 Android SDK 35，运行单元测试并构建 Debug APK。
