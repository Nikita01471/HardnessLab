plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
 namespace = "com.hardnesslab.app"; compileSdk = 35
 defaultConfig { applicationId = "com.hardnesslab.app"; minSdk = 24; targetSdk = 35; versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() + 1000; versionName = "3.2" } }

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
