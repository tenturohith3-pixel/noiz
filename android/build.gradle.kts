// Root build file: declares every plugin at the pinned version; modules apply them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // :benchmark uses com.android.test; it must be declared here too, otherwise Gradle
    // finds the id inside the AGP jar already on the classpath and refuses the versioned
    // request in the module ("already on the classpath with an unknown version").
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
