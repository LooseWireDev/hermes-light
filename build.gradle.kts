import org.gradle.api.attributes.Bundling

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

ext["compileSdk"] =
    libs.versions.androidCompileSdk
        .get()
        .toInt()
ext["minSdk"] =
    libs.versions.androidMinSdk
        .get()
        .toInt()
ext["targetSdk"] =
    libs.versions.androidTargetSdk
        .get()
        .toInt()
ext["jvmTarget"] = libs.versions.jvmTarget.get()
ext["lintVersion"] = libs.versions.lint.get()

val sdkProperties =
    java.util.Properties().apply {
        project(":sdk")
            .projectDir.parentFile
            .resolve("gradle.properties")
            .inputStream()
            .use { load(it) }
    }
allprojects {
    extra["sdkGroup"] = sdkProperties.getProperty("sdkGroup")
    extra["sdkVersion"] = sdkProperties.getProperty("sdkVersion")
}

val ktlint by configurations.creating {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:1.7.1")
}

val kotlinSources = fileTree("tool/src") { include("**/*.kt") }
val kotlinScripts = files("settings.gradle.kts", "build.gradle.kts", "tool/build.gradle.kts")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Checks tool Kotlin and project build scripts with ktlint."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args((kotlinSources.files + kotlinScripts.files).map { it.path })
}

tasks.register("checkColorLiterals") {
    group = "verification"
    description = "Rejects literal colors; use LightThemeTokens throughout the tool."
    inputs.files(kotlinSources)
    doLast {
        val names = "Black|White|Gray|DarkGray|LightGray|Red|Green|Blue|Yellow|Cyan|Magenta|Transparent"
        val literal = Regex("""\bColor\s*\(|\bColor\.($names)\b""")
        val violations =
            kotlinSources.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (literal.containsMatchIn(line)) "${source.relativeTo(rootDir)}:${index + 1}" else null
                }
            }
        check(violations.isEmpty()) { "Use LightThemeTokens colors, not literals: ${violations.joinToString()}" }
    }
}
