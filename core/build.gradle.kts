import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "link.xtxp"
version = "0.0.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

val rustDir = rootDir.resolve("../rust").canonicalFile
// Cargo from rustup if Gradle's PATH doesn't have it (Android Studio on a Mac).
val cargo: String = listOf(System.getenv("CARGO"), "${System.getProperty("user.home")}/.cargo/bin/cargo")
    .firstOrNull { it != null && file(it).canExecute() } ?: "cargo"
val hostLib = rustDir.resolve("target/debug/" + System.mapLibraryName("xtxp_mobile"))
val bindingsDir = layout.buildDirectory.dir("generated/uniffi")

// The muppet built for this machine: the tests load it, and the bindings are
// generated from its metadata (they're the same for every target).
val cargoBuild by tasks.registering(Exec::class) {
    workingDir = rustDir
    commandLine(cargo, "build", "--features", "bindgen")
    inputs.dir(rustDir.resolve("src"))
    inputs.files(rustDir.resolve("Cargo.toml"), rustDir.resolve("Cargo.lock"), rustDir.resolve("uniffi-bindgen.rs"))
    outputs.file(hostLib)
}

val uniffiBindings by tasks.registering(Exec::class) {
    dependsOn(cargoBuild)
    workingDir = rustDir
    val out = bindingsDir.get().asFile
    commandLine(
        cargo, "run", "-q", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", hostLib.absolutePath, "--language", "kotlin", "--no-format",
        "--out-dir", out.absolutePath,
    )
    inputs.file(hostLib)
    outputs.dir(out)
}

kotlin.sourceSets["main"].kotlin.srcDir(files(bindingsDir).builtBy(uniffiBindings))

dependencies {
    // Android takes JNA's aar (with its native part); a JVM takes the jar.
    compileOnly(libs.jna)
    api(libs.coroutines.core)
    testImplementation(libs.jna)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(cargoBuild)
    systemProperty("jna.library.path", hostLib.parentFile.absolutePath)
}
