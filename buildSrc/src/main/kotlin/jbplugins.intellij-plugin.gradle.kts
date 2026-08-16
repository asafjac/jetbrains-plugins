import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Shared build conventions for every IntelliJ Platform plugin in this monorepo.
 *
 * A plugin's own build file carries only what is genuinely its own: its version, and any IDE
 * plugins it depends on. Platform, JDK, compatibility range and verification are repo-wide
 * policy and live here so a bump is one edit rather than one per plugin.
 *
 * Usage:
 *
 *     plugins { id("jbplugins.intellij-plugin") }
 *     version = "0.1.0"
 *     dependencies { intellijPlatform { bundledPlugin("JavaScript") } }
 */

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = "com.asafjac.jbplugins"

// local.properties (gitignored) holds per-machine overrides. `localIdePath` builds against an
// installed IDE instead of downloading one; `javaToolchain` accompanies it, because the JDK
// required to compile is a property of the platform being compiled against.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun setting(name: String): String =
    localProperties.getProperty(name) ?: providers.gradleProperty(name).get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIdePath = localProperties.getProperty("localIdePath")
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            create(
                providers.gradleProperty("platformType"),
                providers.gradleProperty("platformVersion"),
            )
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test"))
}

kotlin {
    // Two distinct numbers, and conflating them ships a plugin that cannot load:
    //
    //  - javaToolchain is the JDK used to COMPILE, and must satisfy the platform being built
    //    against (2026.2 requires 25, 2025.x requires 21).
    //  - jvmTarget is the bytecode level EMITTED, and must not exceed the JVM of the oldest
    //    IDE in the supported range (pluginSinceBuild). A newer target loads on no older IDE,
    //    failing at class-load time with UnsupportedClassVersionError rather than at build.
    jvmToolchain(setting("javaToolchain").toInt())
}

// Set on the tasks, not on the kotlin{} extension: jvmToolchain() configures jvmTarget at the
// task level, and a task-level value beats an extension-level default. Setting it only on the
// extension silently leaves the toolchain's version in place - verified by reading the class
// file major version out of the built jar, which is the only check that actually catches this.
val bytecodeTarget = providers.gradleProperty("jvmTarget").get()

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(bytecodeTarget))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeTarget.toInt())
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: an untilBuild locks the plugin out of every IDE released after
            // the platform it was built against, so it silently stops loading on an update.
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides { recommended() }
    }
}
