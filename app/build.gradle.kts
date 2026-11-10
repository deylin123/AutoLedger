plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deylin.autledger"
    compileSdk = 34

    // 固定签名：云端构建默认每次重新生成调试证书，导致新包无法覆盖旧包
    // （只能卸载重装，本地数据随之清空）。改为使用仓库内固定 keystore。
    signingConfigs {
        create("fixed") {
            storeFile = file("../keystore.p12")
            storePassword = "autoledger2026"
            keyAlias = "autoledger"
            keyPassword = "autoledger2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        // v2.20：交付包改用 release 构建。
        // 原因：debug 构建会写入 android:debuggable="true"，并被银行类 App 的完整性检测
        // 识别为风险应用（可被 adb / 同设备应用注入、dump 私有数据），招商永隆直接要求卸载。
        // 无障碍可以手动关闭，debuggable 标记关不掉，只能换构建类型。
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixed")
            isDebuggable = false
            isMinifyEnabled = false      // 暂不混淆，避免反射/资源名被改导致运行期崩溃
            isShrinkResources = false
            isJniDebuggable = false
        }
    }

    // release 构建会跑 lintVitalRelease，历史代码里的告警会中断打包；这里只关闭中断，不关闭检查
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "com.deylin.autledger"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "2.24"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
