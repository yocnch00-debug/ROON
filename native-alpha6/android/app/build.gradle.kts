plugins { id("com.android.application") }
android {
    namespace = "com.onroonlink.nativev1udp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.nativev1udp"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0-alpha6-udp"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
