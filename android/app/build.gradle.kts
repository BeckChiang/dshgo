import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名材料放在仓库根的 keystore.properties（已 gitignore）。
// 注意：Gradle 的 rootProject 是 android/ 目录，仓库根是它的父目录。
// 文件不在时（例如 CI 没注入 secrets）退回 debug 签名，保证本地随时能装。
val repoRoot = rootProject.projectDir.parentFile
val keystoreProps = Properties().apply {
    val f = repoRoot.resolve("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val hasReleaseSigning = keystoreProps.isNotEmpty()

android {
    namespace = "com.dshgo.app"
    compileSdk = 35

    defaultConfig {
        // 独立 applicationId：可与官方包并存，升级链只由本项目自己的签名决定。
        applicationId = "com.beck.dshgo"
        minSdk = 26
        targetSdk = 35
        versionCode = 48
        versionName = "4.5.0-beck.5"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = repoRoot.resolve(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // CrashLog 记录版本号用
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 上游原本开 R8 + 资源压缩（体积差一个数量级），但那一步在本机要跑十几分钟且
            // 容易在中途被打断。侧载自用阶段关掉，等正式发版再打开。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有 keystore.properties 就用自签密钥，否则退回 debug 密钥（仅本地试装）。
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

dependencies {
    // ---- 原生 UI：首页 / 设置 / 连接页全部用 Compose 自绘 ----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // material-icons-core 已够用（Add / Refresh / Settings / Search / Close / ArrowBack…）
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // 事件流走 WebSocket（Android 平台没有 java.net.http.WebSocket）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 扫码。选 ZXing 的嵌入式封装而不是 ML Kit：后者依赖 Google Play 服务，
    // 侧载安装 + 国内设备上常常不可用。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 生物识别。用 androidx.biometric 而不是直接调 FingerprintManager：
    // 后者在 API 28 就废弃了，且不认面部识别。BiometricPrompt 会把设备支持的
    // 方式统一起来（指纹 / 面部 / 设备密码），正是我们要的"判断手机支持什么"。
    implementation("androidx.biometric:biometric:1.1.0")

    // MainActivity 为了 BiometricPrompt 继承了 FragmentActivity，而
    // registerForActivityResult 在 Fragment < 1.3.0 上会直接崩（lint 会拦下来）。
    // biometric 1.1.0 拉的 fragment 太旧，所以显式升到一个稳定版本。
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // 生物识别本身**不加密任何东西** —— 它只负责"是不是本人"。
    // 密码还是得存下来（解锁时要发给宿主），所以必须自己加密：
    // 用 Android Keystore 里的密钥做 AES/GCM。不引 androidx.security:security-crypto
    // 是因为它长期停在 alpha，且在部分国产 ROM 上有 keystore 异常。
    // 直接调 Keystore 也就几十行，行为完全可控。

    // JVM 单元测试：PageInject 拼出来的 JS 要能被外部校验
    testImplementation("junit:junit:4.13.2")


    // 注意：不要加 debugImplementation("androidx.compose.ui:ui-tooling")。
    // 它只服务 Android Studio 的预览，却会给 debug APK 塞进 ~18MB dex。
}
