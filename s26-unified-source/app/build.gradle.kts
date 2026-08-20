plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.s26source"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.onroonlink.s26source.gwfix11"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1-gateway-always-on-fresh-package"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
