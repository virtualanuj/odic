pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "url-inspector"

include(":core")
include(":data")
include(":androidApp")
