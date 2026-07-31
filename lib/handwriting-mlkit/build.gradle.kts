plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.handwriting_mlkit"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lib:common"))
    implementation(libs.mlkit.digital.ink)
    testImplementation(libs.junit)
}
