plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing is opt-in through the environment so a debug build never needs a
 * keystore. `storeFile = file("")` throws "path may not be null or empty string" at
 * configuration time, so the signing config is only declared when a store is set.
 */
val releaseStoreFile: String? =
    System.getenv("NOIZ_STORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.noiz.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noiz.app"
        minSdk = 29
        // 35 is the current Play floor; 34 was not submittable and also opted out of
        // API-35 edge-to-edge enforcement and FGS changes.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is opt-in via environment variables so a debug build never needs a
    // keystore. `storeFile = file("")` throws "path may not be null or empty string" at
    // configuration time, so the config is only declared when a store file is set.
    signingConfigs {
        releaseStoreFile?.let { store ->
            create("release") {
                storeFile = file(store)
                storePassword = System.getenv("NOIZ_STORE_PASSWORD")
                keyAlias = System.getenv("NOIZ_KEY_ALIAS")
                keyPassword = System.getenv("NOIZ_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Required by :benchmark's macrobenchmark tests.
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    testOptions {
        // Macrobenchmarks need profileable + a release-like build.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        // NoizApplication reads BuildConfig.DEBUG to pick the WorkManager log level.
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:playback"))
    implementation(project(":core:download"))

    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:library"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:lyrics"))
    implementation(project(":feature:queue"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:upload"))
    implementation(project(":feature:settings"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.room.testing)
}
