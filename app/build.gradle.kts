plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dlidar.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dlidar.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The composition root: the only module that sees every layer.
dependencies {
    implementation(project(":domain"))
    implementation(project(":data:protocol"))
    implementation(project(":data:scanner"))
    implementation(project(":infra:ble"))
    implementation(project(":infra:wifi"))
    implementation(project(":presentation"))
    debugImplementation(project(":debug:linktest"))
    debugImplementation(project(":debug:traffic"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
