plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.matepet"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.matepet"
        minSdk = 23            // temi V2 tablet = Android 6.0.1 (API 23)
        targetSdk = 30         // temi V3 = Android 11
        versionCode = 1; versionName = "0.1"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf(
            "-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE", "-DCMAKE_BUILD_TYPE=Release",
            "-DGGML_NATIVE=OFF", "-DGGML_OPENMP=OFF", "-DLLAMA_CURL=OFF") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation("com.robotemi:sdk:1.138.0")
    implementation("com.google.android.filament:filament-android:1.56.0")
    implementation("com.google.android.filament:gltfio-android:1.56.0")
    implementation("com.google.android.filament:filament-utils-android:1.56.0")
}
