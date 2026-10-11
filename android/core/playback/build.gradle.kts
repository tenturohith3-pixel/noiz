plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.noiz.playback"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(libs.media3.exoplayer)
    api(libs.media3.session)
    api(libs.media3.datasource)
    api(libs.media3.datasource.okhttp)
    api(libs.media3.common)
    implementation(libs.okhttp)
    implementation(libs.guava)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
