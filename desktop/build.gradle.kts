import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

/** XPress for Windows: the shared screens in a window, FFmpeg bundled next to the app. */
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

/*
 * FFmpeg for the installer: a static GPL build of the same 8.1 line FFmpegKit uses on Android,
 * pinned by checksum. Only ffmpeg.exe and ffprobe.exe (plus their license) are shipped.
 */
val ffmpegVersion = "8.1.2"
val ffmpegSha256 = "db580001caa24ac104c8cb856cd113a87b0a443f7bdf47d8c12b1d740584a2ec"
val ffmpegZip = layout.buildDirectory.file("ffmpeg/ffmpeg-$ffmpegVersion-essentials_build.zip")
val ffmpegResourcesDir = layout.buildDirectory.dir("ffmpeg/resources")

val downloadFfmpeg by tasks.registering {
    val url = "https://github.com/GyanD/codexffmpeg/releases/download/$ffmpegVersion/ffmpeg-$ffmpegVersion-essentials_build.zip"
    val expected = ffmpegSha256
    val zip = ffmpegZip
    inputs.property("url", url)
    inputs.property("sha256", expected)
    outputs.file(zip)
    doLast {
        val file = zip.get().asFile
        fun sha256(): String {
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(file.inputStream(), md).use { it.copyTo(OutputStream.nullOutputStream()) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (file.isFile && sha256() == expected) return@doLast
        file.parentFile.mkdirs()
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        val actual = sha256()
        if (actual != expected) {
            file.delete()
            throw GradleException("FFmpeg checksum mismatch: expected $expected, got $actual")
        }
    }
}

val ffmpegResources by tasks.registering(Sync::class) {
    dependsOn(downloadFfmpeg)
    from({ zipTree(ffmpegZip.get().asFile) }) {
        include("*/bin/ffmpeg.exe", "*/bin/ffprobe.exe", "*/LICENSE", "*/README.txt")
        eachFile {
            path = "windows/" + when (name) {
                "LICENSE" -> "FFmpeg-LICENSE.txt"
                "README.txt" -> "FFmpeg-README.txt"
                else -> name
            }
        }
        includeEmptyDirs = false
    }
    into(ffmpegResourcesDir)
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(ffmpegResources) }

tasks.test {
    dependsOn(ffmpegResources)
    systemProperty("xpress.ffmpeg.dir", ffmpegResourcesDir.get().dir("windows").asFile.absolutePath)
}

// MSI wants MAJOR.MINOR.BUILD; CI passes the tag (v1.2.3 -> 1.2.3), local builds get 1.0.0.
val desktopVersion = providers.gradleProperty("versionName").orNull
    ?.substringBefore('-')
    ?.takeIf { Regex("""[1-9]\d{0,2}\.\d{1,3}\.\d{1,5}""").matches(it) }
    ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "com.xpress.app.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(file("proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "XPress"
            packageVersion = desktopVersion
            // The MSI database is codepage 1252: no Cyrillic in anything that goes into it.
            description = "XPress - compress videos to an exact file size"
            vendor = "XPress"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(ffmpegResourcesDir)
            // jdk.localedata: Russian number formatting (decimal comma) in the trimmed runtime.
            modules("java.desktop", "java.prefs", "jdk.unsupported", "jdk.localedata")

            windows {
                iconFile.set(file("icons/xpress.ico"))
                menuGroup = "XPress"
                menu = true
                shortcut = true
                // Installs into %LOCALAPPDATA% without asking for admin rights.
                perUserInstall = true
                // Keeps upgrades replacing the old version instead of installing side by side. Never change it.
                upgradeUuid = "552288ec-148c-46e8-8946-741979f39ee9"
            }
        }
    }
}
