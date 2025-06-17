plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    id("maven-publish")
}

android {
    namespace = "org.operatorfoundation.signalbridge"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    // Required for JitPack
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.OperatorFoundation"
                artifactId = "SignalBridge"
                version = "0.1.0"

                pom {
                    name.set("SignalBridge")
                    description.set("Android library for USB audio input using AudioRecord API")
                    url.set("https://github.com/OperatorFoundation/SignalBridge")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("operatorfoundation")
                            name.set("Operator Foundation")
                            email.set("info@operatorfoundation.org")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/OperatorFoundation/SignalBridge.git")
                        developerConnection.set("scm:git:ssh://github.com:OperatorFoundation/SignalBridge.git")
                        url.set("https://github.com/OperatorFoundation/SignalBridge/tree/main")
                    }
                }
            }
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.timber)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
