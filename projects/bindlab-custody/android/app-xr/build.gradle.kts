plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bindlab.xr"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bindlab.xr"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    // Keep .so files extracted in nativeLibraryDir: QNN skel libs + ADSP_LIBRARY_PATH need real files.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":shared"))
    val aar = rootProject.extra["executorchAar"] as File?
    if (aar != null) {
        implementation(files(aar))
        implementation("com.facebook.fbjni:fbjni:0.5.1")
        implementation("com.facebook.soloader:nativeloader:0.10.5")
    } else {
        implementation(rootProject.extra["executorchMaven"] as String)
    }
}
