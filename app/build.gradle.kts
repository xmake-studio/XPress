import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing, first match wins:
 * 1. SIGNING_* environment variables (GitHub Actions),
 * 2. a properties file at -Pxpress.signing=<path> or ~/.xpress-signing/signing.properties,
 * 3. the debug key, so a fresh checkout still builds an installable APK.
 */
val releaseSigning: Map<String, String>? = run {
    val env = System.getenv()
    if (env["SIGNING_STORE_FILE"] != null) {
        return@run mapOf(
            "storeFile" to env.getValue("SIGNING_STORE_FILE"),
            "storePassword" to env.getValue("SIGNING_STORE_PASSWORD"),
            "keyAlias" to env.getValue("SIGNING_KEY_ALIAS"),
            "keyPassword" to env.getValue("SIGNING_KEY_PASSWORD"),
        )
    }
    val path = providers.gradleProperty("xpress.signing").orNull
        ?: "${System.getProperty("user.home")}/.xpress-signing/signing.properties"
    val file = File(path)
    if (!file.isFile) return@run null
    val p = Properties().apply { file.inputStream().use(::load) }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

android {
    namespace = "com.xpress.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xpress.app"
        minSdk = 29
        targetSdk = 36
        // CI passes these from the git tag: v1.2.3 -> 1.2.3 / 10203.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    // FFmpegKit ships only 64-bit binaries: one APK per ABI keeps the download small.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Compressed native libs: ~2x smaller APK, FFmpeg is extracted once on install.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ffmpeg.kit)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
