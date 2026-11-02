plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNo: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.webo.siteviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.webo.siteviewer"
        minSdk = 24
        targetSdk = 34
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("keystore.p12")
            storeType = "pkcs12"
            storePassword = "weboKey2026x"
            keyAlias = "webo"
            keyPassword = "weboKey2026x"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("fixed")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
