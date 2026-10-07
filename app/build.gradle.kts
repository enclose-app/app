plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.app.enclose"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.app.enclose"
        minSdk = 35
        //noinspection OldTargetApi
        targetSdk = 36
        // Both from gradle.properties, bumped by CI on every push to master.
        versionCode = providers.gradleProperty("enclose.versionCode").get().toInt()
        versionName = providers.gradleProperty("enclose.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: shrinks, obfuscates and optimises code, and strips unused
            // resources. The mapping file is embedded in the bundle, which is
            // what Play reads to deobfuscate crash and ANR stack traces.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG is what keeps the developer affordances (test mode,
        // and with it the tap-to-inject path that records no GPS at all) out of
        // the build that ships. AGP stopped generating BuildConfig by default in
        // 8.0, so this has to be asked for.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

ksp {
    // Room writes each schema version to app/schemas. These are checked in on
    // purpose: the database carries walked territories and ships no destructive
    // fallback, so every future migration has to be written against the real
    // previous schema rather than a remembered one.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.material)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.jts.core)

    // Galaxy Watch companion: the shared protocol, and the Data Layer.
    implementation(project(":watchlink"))
    implementation(libs.play.services.wearable)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
