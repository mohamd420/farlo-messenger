import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    packaging { jniLibs { useLegacyPackaging = true } }
    namespace = "com.desertcomm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.desertcomm"
        minSdk = 26
        targetSdk = 37
        versionCode = 88
        versionName = "4.9"
        ndk { abiFilters += listOf("arm64-v8a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            val sf = localProps.getProperty("SADA_STORE_FILE")
            if (sf != null && file(sf).exists()) {
                storeFile = file(sf)
                storePassword = localProps.getProperty("SADA_STORE_PASS", "")
                keyAlias = localProps.getProperty("SADA_KEY_ALIAS", "")
                keyPassword = localProps.getProperty("SADA_KEY_PASS", "")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("info.guardianproject:tor-android:0.4.9.11")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}