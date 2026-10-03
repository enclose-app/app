// What the phone and the watch say to each other: the Data Layer paths and the
// shape of the walk status. Plain Kotlin with no Android in it, so both apps
// depend on one definition and the encoding is tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    testImplementation(libs.junit)
}
