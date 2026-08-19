plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.sidecar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onroonlink.sidecar"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.4-final-sood-multi-iface"
    }

    signingConfigs {
        create("onTest") {
            storeFile = file("../onroonlink-test.jks")
            storePassword = "onroonlink"
            keyAlias = "onroonlink"
            keyPassword = "onroonlink"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("onTest") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("onTest")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
