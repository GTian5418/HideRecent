import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从根目录 keystore.properties 读取签名信息（该文件不入库）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "top.gtian.hiderecent"
    compileSdk = 35

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "top.gtian.hiderecent"
        minSdk = 29
        // 必须是 34：targetSdk 35 会在 Android 15 上强制 edge-to-edge，
        // 导致 android:statusBarColor 失效、状态栏变透明、ActionBar 被顶出屏幕。
        targetSdk = 34
        versionCode = 20261025
        versionName = "v26.10.25"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Modern Xposed API，框架运行时提供实现，仅编译期依赖
    compileOnly("io.github.libxposed:api:102.0.0")
    // AppCompat：AppCompatActivity + 主题自带 ActionBar + SearchView + 溢出菜单
    implementation("androidx.appcompat:appcompat:1.7.0")
    // RecyclerView 用于应用列表
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}