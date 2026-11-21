plugins { id("com.android.application") }
android {
    namespace = "app.wallpapershuffle"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.wallpapershuffle"
        minSdk = 33
        targetSdk = 33
        versionCode = 3
        versionName = "1.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("personal") {
            storeFile = file("../signing/wallpaper-shuffle.jks")
            storePassword = System.getenv("WS_SIGN_PASSWORD")
            keyAlias = "wallpaper-shuffle"
            keyPassword = System.getenv("WS_SIGN_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
