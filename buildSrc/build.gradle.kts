plugins {
    `kotlin-dsl`
}

dependencies {
    // Putting these on buildSrc's classpath is what lets the precompiled script plugin in
    // src/main/kotlin apply them by id without restating a version.
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.intellijPlatform.gradle.plugin)
}
