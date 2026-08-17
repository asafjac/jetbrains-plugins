plugins {
    id("jbplugins.intellij-plugin")
}

version = "0.6.0"

// No bundledPlugin: the driver is language-agnostic. It resolves targets through the
// editor and document, so it works in any JetBrains IDE rather than only the JS ones.
