plugins {
    id("com.android.application")
}

android {
    namespace = "com.onroonlink.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onroonlink.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 83
        versionName = "0.8.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
