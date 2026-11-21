plugins { id("com.android.test") }

android {
    namespace = "io.github.ouse.ousecode.test"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "io.github.ouse.ousecode.SmokeInstrumentation"
    }
    sourceSets["main"].apply {
        java.srcDir("../app/src/androidTest/java")
        kotlin.srcDir("../app/src/androidTest/java")
        manifest.srcFile("../app/src/androidTest/AndroidManifest.xml")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { compileOnly(project(":terminal-view")) }
