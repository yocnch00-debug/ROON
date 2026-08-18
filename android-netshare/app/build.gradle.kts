plugins { id("com.android.application") }
android {
    namespace = "com.onroonlink.nativev1.netshare"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.onroonlink.nativev1.netshare"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.0-r8-fulltunnel-v21-integrated"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
