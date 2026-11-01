import java.util.Properties

plugins { id("com.android.application") }

val signing = Properties().apply {
    val source = rootProject.file(".signing/release.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
android {
    namespace = "dev.brahma.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.brahma.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes { release { signingConfig = signingConfigs.findByName("release") } }
    lint {
        warningsAsErrors = true
        // SDK and build-tool upgrades are separate from launcher correctness checks.
        disable += setOf("OldTargetApi", "AndroidGradlePluginVersion")
        // ML Kit brings AndroidX in transitively; the launcher's own code stays on platform APIs.
        disable += setOf("UseKtx", "UseSwitchCompatOrMaterialCode")
    }
}
dependencies {
    // On-device subject finder for stickers and the lock designer; the model comes from Play services.
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this supplies the real parser there only.
    testImplementation("org.json:json:20260814")
}
