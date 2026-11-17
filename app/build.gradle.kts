import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "link.xtxp.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "link.xtxp.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// The Rust muppet for phones and the emulator, through cargo-ndk.
val rustDir = rootDir.resolve("rust")
val cargo: String = listOf(System.getenv("CARGO"), "${System.getProperty("user.home")}/.cargo/bin/cargo")
    .firstOrNull { it != null && file(it).canExecute() } ?: "cargo"
val cargoNdk by tasks.registering(Exec::class) {
    val out = layout.buildDirectory.dir("rustJniLibs").get().asFile
    workingDir = rustDir
    commandLine(
        cargo, "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-o", out.absolutePath,
        "build", "--release",
    )
    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"))
    outputs.dir(out)
}
tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    implementation("link.xtxp:core")
    // JNA's aar carries its native part for Android.
    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.core.ktx)
}
