plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.r8v31"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.onroonlink.r8v31"
        minSdk = 26
        targetSdk = 29
        versionCode = 31
        versionName = "1.0-r8-netshare-policy-v31"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(files("libs/tunbridge.aar")) }
