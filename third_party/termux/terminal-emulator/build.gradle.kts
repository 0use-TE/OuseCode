plugins { id("com.android.library") }

android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild.ndkBuild {
            cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector", "-Wl,--gc-sections")
        }
    }
    externalNativeBuild.ndkBuild { path = file("src/main/jni/Android.mk") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
