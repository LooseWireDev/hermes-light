pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val ownerSdk = file(providers.gradleProperty("lightSdk.path").orElse("../light-sdk").get())
check(ownerSdk.resolve("sdk/client/build.gradle.kts").isFile) {
    "Light SDK missing at $ownerSdk; set -PlightSdk.path=/path/to/light-sdk"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "hermes-light"
includeBuild(ownerSdk.resolve("plugin"))
include(":lint-rules", ":sdk:shared", ":sdk:ui", ":sdk:client", ":tool")
project(":sdk").projectDir = ownerSdk.resolve("sdk")
project(":lint-rules").projectDir = ownerSdk.resolve("lint-rules")
listOf("shared", "ui", "client").forEach { module ->
    project(":sdk:$module").projectDir = ownerSdk.resolve("sdk/$module")
}
