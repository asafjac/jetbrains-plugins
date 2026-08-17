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

/**
 * The plugin's display name, read from its own plugin.xml.
 *
 * Needed to find this plugin's section of the shared CHANGELOG, which is organized by display name
 * rather than by module directory because that is what a reader of the changelog recognizes.
 */
val pluginDisplayName: String? =
    file("src/main/resources/META-INF/plugin.xml").takeIf { it.exists() }?.readText()
        ?.let { Regex("<name>(.*?)</name>").find(it)?.groupValues?.get(1) }

/**
 * This version's changelog section, as the HTML the IDE wants for What's New.
 *
 * The notes shown in the Plugins list are otherwise empty, so an update tells the user nothing about
 * what changed. Taking them from CHANGELOG.md means there is one place to write them, and the
 * release notes on GitHub and the notes inside the IDE cannot disagree.
 */
fun changeNotesFor(version: String): String? {
    val changelog = rootProject.file("CHANGELOG.md").takeIf { it.exists() } ?: return null
    val name = pluginDisplayName ?: return null

    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.trim() == "## $name" }
    if (start < 0) return null
    // Bounded by the next plugin's heading, so one plugin's notes never bleed into another's.
    val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## ") }
        .let { if (it < 0) lines.size else start + 1 + it }

    val section = lines.subList(start + 1, end)
    val from = section.indexOfFirst { it.trim() == "### $version" }
    if (from < 0) return null
    val to = section.drop(from + 1).indexOfFirst { it.startsWith("### ") }
        .let { if (it < 0) section.size else from + 1 + it }

    val body = section.subList(from + 1, to)

    // Just enough Markdown for what the changelog actually uses: bullets, paragraphs and inline
    // code. A fuller converter would be dead weight for prose this plain.
    //
    // Blocks are gathered whole before any HTML is written, because the changelog hard-wraps: a
    // bullet spans several lines and only a blank line or the next bullet ends it.
    val blocks = mutableListOf<Pair<Boolean, StringBuilder>>()

    body.forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> blocks += false to StringBuilder()
            line.startsWith("- ") -> blocks += true to StringBuilder(line.removePrefix("- "))
            // A wrapped continuation of whatever block is open, not a block of its own.
            else -> blocks.lastOrNull()?.second?.takeIf { it.isNotEmpty() }?.append(' ')?.append(line)
                ?: run { blocks += false to StringBuilder(line) }
        }
    }

    fun inline(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")

    val html = StringBuilder()
    var inList = false
    blocks.filter { it.second.isNotBlank() }.forEach { (bullet, text) ->
        if (bullet && !inList) html.append("<ul>")
        if (!bullet && inList) html.append("</ul>")
        inList = bullet
        html.append(if (bullet) "<li>" else "<p>")
            .append(inline(text.toString()))
            .append(if (bullet) "</li>" else "</p>")
    }
    if (inList) html.append("</ul>")
    return html.toString().ifBlank { null }
}

intellijPlatform {
    pluginConfiguration {
        // Shown as What's New in the Plugins list, and after an update.
        changeNotes = provider { changeNotesFor(project.version.toString()) }
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
