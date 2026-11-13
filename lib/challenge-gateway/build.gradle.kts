plugins {
    alias(kei.plugins.library)
}

android.sourceSets.named("test") {
    java.setSrcDirs(listOf("test"))
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlin.json)
    testImplementation(libs.coroutines.core)
}
