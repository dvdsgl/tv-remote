plugins {
    id("com.android.application")
}

// Read through providers so the configuration cache notices when these change.
fun env(name: String): String? = providers.environmentVariable(name).orNull

android {
    namespace = "xyz.dvdsgl.tvremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.dvdsgl.tvremote"
        minSdk = 33
        targetSdk = 36
        // CI sets these: VERSION_CODE is the release workflow's run number (always rising),
        // VERSION_NAME is 1.0.<that number>.
        versionCode = env("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = env("VERSION_NAME") ?: "0.1.0"
    }

    // The release key comes from the environment (GitHub Actions secrets). Without it, release
    // builds fall back to the debug key so local builds still work.
    val releaseKeystore = env("RELEASE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = env("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = env("RELEASE_KEY_ALIAS")
            keyPassword = env("RELEASE_KEY_PASSWORD")
        }
    }

    // oss: the GitHub build, dark mode free. play: the Google Play build, dark mode is a
    // $2.99 in-app purchase after a 30-day free period.
    flavorDimensions += "store"
    productFlavors {
        create("oss") { dimension = "store" }
        create("play") { dimension = "store" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// tools/e2e.sh passes the fake Apple TV's address to CompanionE2ETest through this.
tasks.withType<Test>().configureEach {
    environment("FAKE_TV", System.getenv("FAKE_TV") ?: "")
    outputs.upToDateWhen { System.getenv("FAKE_TV").isNullOrEmpty() }
}

dependencies {
    "playImplementation"("com.android.billingclient:billing:8.0.0")
    // The oss build has no runtime dependencies beyond the Android framework and the Kotlin
    // stdlib; the play build adds Google Play Billing.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
