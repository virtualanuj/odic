import java.util.Properties

plugins {
    id("com.android.application") version "9.4.0"
    kotlin("plugin.compose") version "2.4.10"
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.urlinspector.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.urlinspector.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String",
            "SAFE_BROWSING_API_KEY",
            "\"${localProperties.getProperty("safeBrowsingApiKey", "").replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // TODO(follow-up, not part of M6): this reuses the debug
            // signing config purely so assembleRelease/installRelease can
            // be built and smoke-tested on a development machine. This is
            // NOT a production signing identity — before any real
            // distribution (Play Store or otherwise), replace this with a
            // real release signingConfig backed by a properly-secured
            // keystore (never committed to source control). The debug
            // keystore is also a well-known, shared identity (not unique to
            // this project) — so a build signed with it must not be
            // side-loaded or distributed to beta testers either, not just
            // kept off the Play Store, since anyone could build and
            // distribute a convincing "update" using the same well-known
            // debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.insert-koin:koin-android:4.2.2")
    implementation("io.insert-koin:koin-androidx-compose:4.2.2")

    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.insert-koin:koin-test:4.2.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
