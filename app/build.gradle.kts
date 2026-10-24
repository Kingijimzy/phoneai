import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Official sherpa-onnx Android AAR (pinned version + verified SHA-256).
// Downloaded during builds rather than checked into this repository as a binary.
val sherpaTtsAar = layout.buildDirectory.file("downloads/sherpa-onnx-1.13.8.aar")
val sherpaTtsAarSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

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

val downloadSherpaTtsAar by tasks.registering {
    val destination = sherpaTtsAar.get().asFile
    outputs.file(destination)
    outputs.upToDateWhen { destination.isFile && sha256Of(destination) == sherpaTtsAarSha256 }
    doLast {
        if (!destination.isFile || sha256Of(destination) != sherpaTtsAarSha256) {
            destination.parentFile.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".part")
            temporary.delete()
            val connection = URL(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
            ).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 90_000
                connection.instanceFollowRedirects = true
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw org.gradle.api.GradleException("Could not download official sherpa-onnx AAR: HTTP " + connection.responseCode)
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
            } finally {
                connection.disconnect()
                temporary.delete()
            }
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
    }

    // A fixed debug key, so every new build can be installed over the old one.
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
    implementation(files(sherpaTtsAar).builtBy(downloadSherpaTtsAar))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    // Bundled on-device OCR; screen text stays on device until Orion explicitly chooses a tool.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // CPU TFLite runtime for the optional bundled UI detector; no network inference.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
