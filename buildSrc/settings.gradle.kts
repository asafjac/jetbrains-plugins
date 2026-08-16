dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // buildSrc has its own build with its own settings, so it does not see the root
    // version catalog unless it is imported explicitly. Without this the conventions
    // plugin would carry a second, silently diverging copy of the version numbers.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
