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
    }

    defaultConfig {
        applicationId = "com.deylin.autledger"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "2.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
