import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.ksp)
}

android {
    namespace = "moe.reimu.ancsreceiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.reimu.ancsreceiver"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // https://developer.android.com/build/dependencies#dependency-info-play
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            file("../signing.properties").let { propFile ->
                if (propFile.canRead()) {
                    val properties = Properties()
                    properties.load(propFile.inputStream())

                    storeFile = file(properties.getProperty("KEYSTORE_FILE"))
                    storePassword = properties.getProperty("KEYSTORE_PASSWORD")
                    keyAlias = properties.getProperty("SIGNING_KEY_ALIAS")
                    keyPassword = properties.getProperty("SIGNING_KEY_PASSWORD")
                } else {
                    println("Unable to read signing.properties")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = signingConfigs.findByName("release")
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.lifecycle.runtime.compose)

//    ksp(libs.androidx.room.compiler)
//    implementation(libs.androidx.room.runtime)
//    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.media)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}