import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Settings CI passes in (.github/workflows/android.yml), as -P properties or environment variables.
fun setting(name: String): String? =
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

// The Play upload key. Without it, release builds come out unsigned (CI checks for it before uploading).
val uploadKeystore = setting("POCKETCASH_UPLOAD_KEYSTORE")

android {
    namespace = "com.patternsystems.pocketcash"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.patternsystems.pocketcash"
        minSdk = 26
        targetSdk = 36
        // CI sets 100 + the run number. Play needs it to go up with every upload.
        versionCode = setting("POCKETCASH_VERSION_CODE")?.toInt() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = setting("POCKETCASH_UPLOAD_STORE_PASSWORD")
                keyAlias = setting("POCKETCASH_UPLOAD_KEY_ALIAS")
                keyPassword = setting("POCKETCASH_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.billing)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
