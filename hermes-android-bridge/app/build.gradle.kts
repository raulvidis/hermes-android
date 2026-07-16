plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermesandroid.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermesandroid.bridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.3.0-media"
    }

    buildFeatures {
        buildConfig = true
    }

    // Optional distribution signing via environment variables (never commit keystores/passwords):
    //   HERMES_ANDROID_KEYSTORE=/path/to.keystore
    //   HERMES_ANDROID_KEYSTORE_PASSWORD=...
    //   HERMES_ANDROID_KEY_ALIAS=hermes-android
    //   HERMES_ANDROID_KEY_PASSWORD=...   (defaults to keystore password)
    signingConfigs {
        create("envRelease") {
            val ks = System.getenv("HERMES_ANDROID_KEYSTORE")
            if (!ks.isNullOrBlank()) {
                storeFile = file(ks)
                storePassword = System.getenv("HERMES_ANDROID_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("HERMES_ANDROID_KEY_ALIAS") ?: "hermes-android"
                keyPassword = System.getenv("HERMES_ANDROID_KEY_PASSWORD")
                    ?: System.getenv("HERMES_ANDROID_KEYSTORE_PASSWORD")
                    ?: ""
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val ks = System.getenv("HERMES_ANDROID_KEYSTORE")
            if (!ks.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("envRelease")
            }
        }
        release {
            isMinifyEnabled = false
            val ks = System.getenv("HERMES_ANDROID_KEYSTORE")
            if (!ks.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("envRelease")
            }
        }
    }

    // Name the built APK `hermes-android-<version>.apk` instead of the default
    // `app-debug.apk`, for local builds, the CI artifact, and the release asset alike.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "hermes-android-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
