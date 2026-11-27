import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = rootProject.ext["compileSdk"] as Int
    signingConfigs {
        create("hermesDev") {
            storeFile =
                file(
                    providers
                        .gradleProperty("hermes.signingStore")
                        .orElse("${System.getProperty("user.home")}/keys/hermes-light/hermes-light-dev.jks")
                        .get(),
                )
            storePassword = "android"
            keyAlias = "hermes-light-dev"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("hermesDev")
        }
        release {
            // Alpha builds are signed locally with the dev key (apksigner, v2-only) and the private
            // key never leaves the owner's machine. No minification for the alpha track.
            isMinifyEnabled = false
        }
    }
    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":sdk:client"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.20")
}

// Same developer/apkInbox HMAC protocol as light-tern; never a bearer credential.
tasks.register("uploadTool") {
    group = "light sdk"
    description = "Upload the debug APK through Tool Manager and wait for installation (no adb)."
    dependsOn("assembleDebug")
    doLast {
        val ip = providers.gradleProperty("device.ip").get()
        val port = providers.gradleProperty("device.port").orElse("54449").get()
        val token = providers.gradleProperty("device.token").get()
        require(token.length % 2 == 0) { "device.token must be a hex HMAC key" }
        val key =
            ByteArray(token.length / 2) { index ->
                token.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        val host = "${ip.replace(".", "-")}.my.local-ip.co"
        val base = "https://$host:$port"
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val packageName = requireNotNull(android.defaultConfig.applicationId)
        val responseBody = HttpResponse.BodyHandlers.ofString()

        fun request(
            path: String,
            method: String,
            body: HttpRequest.BodyPublisher,
        ): String {
            val timestamp = System.currentTimeMillis().toString()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val signature =
                mac
                    .doFinal("$method\n$path\n$timestamp".toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val request =
                HttpRequest
                    .newBuilder(URI("$base$path"))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Tm-Timestamp", timestamp)
                    .header("X-Tm-Signature", signature)
                    .method(method, body)
                    .build()
            val response = client.send(request, responseBody)
            check(response.statusCode() == 200) { "Tool Manager returned HTTP ${response.statusCode()}" }
            return response.body()
        }

        val metadata = "/api/data/developer/toolMeta"
        val pattern =
            Regex(
                """\{"packageName":"${Regex.escape(packageName)}","lastUpdateMillis":(\d+)\}""",
            )

        fun installedAt(): String? = pattern.find(request(metadata, "GET", HttpRequest.BodyPublishers.noBody()))?.groupValues?.get(1)

        val before = installedAt()
        val apk =
            layout.buildDirectory
                .dir("outputs/apk/debug")
                .get()
                .asFile
                .listFiles()
                .orEmpty()
                .single { it.extension == "apk" }
        val inbox = "developer/apkInbox/$packageName.apk"
        request("/api/upload/$inbox", "POST", HttpRequest.BodyPublishers.ofFile(apk.toPath()))
        request("/api/notify/$inbox", "POST", HttpRequest.BodyPublishers.noBody())
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (System.nanoTime() < deadline) {
            Thread.sleep(2000)
            val after = installedAt()
            if (after != null && after != before) {
                logger.lifecycle("$packageName installed/updated")
                return@doLast
            }
        }
        error("Tool Manager did not confirm installation within 60 seconds")
    }
}
