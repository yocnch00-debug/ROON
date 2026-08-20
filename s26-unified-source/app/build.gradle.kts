plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.s26source"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.s26unified.stable"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.2-alpha6-exact-gateway"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
