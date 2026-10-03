// The Galaxy Watch companion: a window onto the walk recording on the phone,
// with Start and Stop. The phone owns everything else — see :watchlink.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.app.enclose.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Must be the phone app's id, signed with the same key: the Data Layer
        // only connects apps that match on both, and nothing else works without it.
        applicationId = "io.app.enclose"
        // Wear OS 4. The Galaxy Watch Ultra 2 runs newer; this keeps older
        // Galaxy and Pixel watches in.
        minSdk = 33
        //noinspection OldTargetApi
        targetSdk = 36
        // Its own range, well clear of the phone's: Play requires every artifact
        // in one listing to have a distinct versionCode.
        versionCode = 100_001
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG keeps the demo walk out of release builds.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":watchlink"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    implementation(libs.play.services.wearable)
    implementation(libs.wear.remote.interactions)

    // The live map: the same engine and tiles as the phone, drawn with OpenGL
    // ES rather than Vulkan — see libs.versions.toml.
    implementation(libs.maplibre.opengl)

    testImplementation(libs.junit)
}
