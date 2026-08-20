plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.s26source"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.s26source"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-source-rebuild"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
