import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }
fun properties(path: String) = Properties().apply {
    rootProject.file(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val version = properties("version.properties")
val distribution = properties("distribution.properties")
val signing = properties(".local/signing.properties")
fun secret(key: String) = System.getenv("DUALKEY_" + key.uppercase()) ?: signing.getProperty(key, "")
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val hasSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "certSha256").all { secret(it).isNotBlank() }

android {
    namespace = "io.github.user03715910.dualkey"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.user03715910.dualkey"
        minSdk = 26
        targetSdk = 35
        versionCode = (providers.gradleProperty("releaseVersionCode").orNull ?: version.getProperty("versionCode")).toInt()
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: version.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WEBSITE_URL", quoted(distribution.getProperty("websiteUrl", "")))
        buildConfigField("String", "METADATA_URL", quoted(distribution.getProperty("metadataUrl", "")))
        buildConfigField("String", "RELEASE_REPOSITORY", quoted(distribution.getProperty("repository", "")))
        buildConfigField("String", "TRUSTED_CERT_SHA256", quoted(secret("certSha256").lowercase()))
    }
    signingConfigs {
        if (hasSigning) create("release") {
            storeFile = rootProject.file(secret("storeFile"))
            storePassword = secret("storePassword")
            keyAlias = secret("keyAlias")
            keyPassword = secret("keyPassword")
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST") }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.configureEach {
    if (name == "validateSigningRelease" || name == "preReleaseBuild") doFirst {
        check(hasSigning) { "Release signing is missing. See docs/RELEASE.md; a debug key must never be used for distribution." }
    }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.android.tools.build:apksig:8.8.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
