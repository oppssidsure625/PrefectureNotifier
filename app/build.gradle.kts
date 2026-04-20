plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "xyz.oppssidsure.prefecturenotifier"
    compileSdk = 36 // release(36)などの記述がエラーを招くことがあるため、安定版の34に揃えるのが無難です

    defaultConfig {
        applicationId = "xyz.oppssidsure.prefecturenotifier"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 位置情報
    implementation("com.google.android.gms:play-services-location:21.2.0")
    // 地図の基本クラス（LatLng用）
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    // 県境判定用（PolyUtil用）
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
}