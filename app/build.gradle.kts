import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建版本号：默认按北京时间「yyMMddHH」生成，保证每次发版单调递增；
// 也可由 CI / build-release.sh 通过 -PversionCode= / -PversionName= 或同名环境变量覆盖。
// versionCode 上限为 2100000000，8 位时间戳（约 2600 万）在可预见年份内安全。
val buildStamp: String = SimpleDateFormat("yyMMddHH", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
    .format(Date())
val resolvedVersionCode: Int =
    (project.findProperty("versionCode") as String?)?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull()
        ?: buildStamp.toIntOrNull()
        ?: 1
val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)
        ?: System.getenv("VERSION_NAME")
        ?: "1.1.$buildStamp"

// 读取签名配置（可选）。发布签名库 traccar-release.keystore 放在工程根目录，
// 随工程一起保留；key.properties 的 storeFile 用相对路径，缺失时本文件不会静默换签名
// （build-release.sh 会在构建前校验证书指纹并中止）。
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.traccar.nativeapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.traccar.nativeapp"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        vectorDrawables.useSupportLibrary = true
        // 仅保留手机架构，剔除模拟器用的 x86/x86_64，减小包体
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩与资源收缩：减小包体，并提高反编译成本。
            // 保留规则见 app/proguard-rules.pro（MapLibre/OkHttp 自带 consumer rules）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 网络：REST + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 地图引擎：与 Traccar Web 同源的 MapLibre GL Native
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    // 实时背景模糊：Android 12+ 使用 RenderEffect，旧版本由导航栏显示磨砂底色回退
    implementation("com.github.Dimezis:BlurView:version-4.0.0")

    // 纯 JVM 单元测试（坐标转换 / 围栏解析 / 单位换算 / 网络策略）
    testImplementation("junit:junit:4.13.2")
}
