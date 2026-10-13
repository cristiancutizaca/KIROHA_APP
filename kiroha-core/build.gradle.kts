plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "kiroha.core"
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
