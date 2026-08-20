plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.s26source"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.s26source"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-gateway-always-on"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
