plugins {
    id("jbplugins.intellij-plugin")
}

version = "0.7.0"

dependencies {
    intellijPlatform {
        // JS/TS PSI (JSReferenceExpression, JSClass, JSClassSearch) lives here. Bundled in
        // WebStorm and IDEA Ultimate; the plugin cannot load in an IDE without it.
        bundledPlugin("JavaScript")
    }
}

// Open the demo project on `runIde`, so trying the plugin is one command rather than a
// sandbox IDE at the welcome screen waiting for someone to find a project to open.
tasks.named<JavaExec>("runIde") {
    args(rootProject.file("demo").absolutePath)
}
