plugins { id("com.android.application") }

android {
    namespace = "com.onroonlink.s26gateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onroonlink.s26gateway"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "2.0-final-transport-only"
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
