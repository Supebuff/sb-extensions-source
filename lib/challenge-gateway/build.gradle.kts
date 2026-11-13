plugins {
    alias(kei.plugins.library)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.named("test") {
        java.directories.add("test")
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlin.json)
    testImplementation(libs.coroutines.core)
}
