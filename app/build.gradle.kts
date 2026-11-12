import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("requests")
            install("beautifulsoup4")
        }
    }
}

// Official sherpa-onnx Android AAR (pinned version + verified SHA-256).
// Prefer app/libs (CI pre-download / durable path). Fall back to build/downloads.
val sherpaTtsAarSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaLibsAar = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar").asFile
val sherpaBuildAar = layout.buildDirectory.file("downloads/sherpa-onnx-1.13.8.aar").get().asFile

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun resolveSherpaAar(): File {
    if (sherpaLibsAar.isFile && sha256Of(sherpaLibsAar) == sherpaTtsAarSha256) return sherpaLibsAar
    if (sherpaBuildAar.isFile && sha256Of(sherpaBuildAar) == sherpaTtsAarSha256) return sherpaBuildAar
    return sherpaBuildAar
}

val downloadSherpaTtsAar by tasks.registering {
    outputs.upToDateWhen {
        (sherpaLibsAar.isFile && sha256Of(sherpaLibsAar) == sherpaTtsAarSha256) ||
            (sherpaBuildAar.isFile && sha256Of(sherpaBuildAar) == sherpaTtsAarSha256)
    }
    doLast {
        if (sherpaLibsAar.isFile && sha256Of(sherpaLibsAar) == sherpaTtsAarSha256) {
            logger.lifecycle("Using pre-placed sherpa-onnx AAR at ${sherpaLibsAar}")
            return@doLast
        }
        if (sherpaBuildAar.isFile && sha256Of(sherpaBuildAar) == sherpaTtsAarSha256) {
            logger.lifecycle("Using cached sherpa-onnx AAR at ${sherpaBuildAar}")
            return@doLast
        }
        val destination = sherpaBuildAar
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        temporary.delete()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
        var lastError: Exception? = null
        for (attempt in 1..4) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 45_000
                    connection.readTimeout = 120_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "phoneai-ci")
                    connection.connect()
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw org.gradle.api.GradleException("Could not download official sherpa-onnx AAR: HTTP $code (attempt $attempt)")
                    }
                    connection.inputStream.buffered().use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    val actual = sha256Of(temporary)
                    if (actual != sherpaTtsAarSha256) {
                        temporary.delete()
                        throw org.gradle.api.GradleException("sherpa-onnx AAR SHA-256 mismatch; refusing to use an unverified native binary")
                    }
                    if (destination.exists() && !destination.delete()) {
                        throw org.gradle.api.GradleException("Could not replace cached sherpa-onnx AAR")
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    lastError = null
                    break
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                temporary.delete()
                if (attempt < 4) Thread.sleep(attempt * 2000L)
            }
        }
        if (lastError != null) {
            throw org.gradle.api.GradleException(
                "Could not download official sherpa-onnx AAR after retries: ${lastError.message}. " +
                    "Place a verified file at app/libs/sherpa-onnx-1.13.8.aar (CI pre-download path).",
                lastError
            )
        }
    }
}

android {
    namespace = "com.phoneai.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneai.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    // Resolve after download task; prefer app/libs pre-placement from CI.
    implementation(files(provider { resolveSherpaAar() }).builtBy(downloadSherpaTtsAar))
    // Shizuku exposes explicitly granted ADB/shell-identity system APIs; it is optional at runtime.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.9.1")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events = setOf(
            org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED,
            org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
        )
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
