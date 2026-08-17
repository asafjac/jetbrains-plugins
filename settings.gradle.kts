pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download the JDK the IntelliJ Platform requires instead of demanding the
    // right one already be installed. WebStorm's own bundled JBR also satisfies it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jetbrains-plugins"

include("plugins:registry-navigator")
include("plugins:demo-driver")
