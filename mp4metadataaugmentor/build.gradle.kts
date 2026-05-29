plugins {
    id("com.android.library") // Sets this module up as a reusable SDK library
    id("kotlin-android")
    id("maven-publish")      // Enables Maven publication packaging
}

android {
    namespace = "games.kliq.mp4metadataaugmentor"
    compileSdk = 34 // Make sure this matches your main app's SDK target

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Crucial for JitPack to know which build variant to pick up
    publishing {
        singleVariant("release")
    }
}

// Map out the public SDK coordinates JitPack will expose
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "games.kliq"
                artifactId = "mp4-metadata-augmentor"
                version = "1.0.0"

                from(components["release"])
            }
        }
    }
}