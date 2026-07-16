plugins {
    id("com.android.application")
}

android {
    namespace = "com.wici.androidalbumdemo.wrapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wici.androidalbumdemo"
        minSdk = 33
        targetSdk = 34
        versionCode = 9
        versionName = "0.4.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":viewer"))
}
