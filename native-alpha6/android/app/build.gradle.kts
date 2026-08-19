plugins { id("com.android.application") }
android {
    namespace = "com.onroonlink.nativev1udp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.nativev1udp"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0-alpha7-udp"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
