plugins { id("com.android.application") }

android {
    namespace = "io.github.ouse.ousecode"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ouse.ousecode"
        minSdk = 26
        // PRoot guests execute Linux programs from app-private storage, as in Termux.
        // This first release is distributed as a sideloadable APK, not through Play.
        targetSdk = 28
        versionCode = 11
        versionName = "0.2.5"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "io.github.ouse.ousecode.SmokeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { aidl = true }
    buildTypes {
        release {
            isMinifyEnabled = false
            // A separate, stable local key; never reuse DSH Ha's signing material.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so", "**/libtalloc.so", "**/libandroid-shmem.so")
    }
    androidResources { noCompress += listOf("xz", "tgz") }
    lint { disable += "ExpiredTargetSdkVersion" }
}
dependencies {
    implementation("org.tukaani:xz:1.10")
    implementation(project(":terminal-view"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
