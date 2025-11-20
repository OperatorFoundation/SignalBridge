plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.operatorfoundation.signalbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.operatorfoundation.signalbridge"
        minSdk = 33
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":SignalBridge"))

    implementation("com.github.OperatorFoundation:AudioCoderAndroid:v0.0.2") // Radio codec (WSPR) Library
    implementation("com.github.OperatorFoundation:TransmissionAndroid:v1.3.5") // Serial communications
    implementation("com.github.OperatorFoundation:ion-android:1.0.8") // Communication protocol
    implementation("com.github.OperatorFoundation:iota-android:1.0.7")
    implementation("com.github.OperatorFoundation:CodexKotlin:0.0.1") // Message Encoding


    // 3rd party libraries
    implementation(libs.usb.serial.android)
    implementation(libs.timber)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom.v20240200))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Permissions
    implementation(libs.accompanist.permissions)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}