import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.app_clavier"
    ndkVersion = "30.0.16248370"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.app_clavier"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
        // Copie de release, signée en debug et « profileable » : uniquement pour le module :benchmark.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------
// Cœur Rust (rust/) : compilé pour Android avec l'éditeur de liens du NDK, puis
// ajouté aux jniLibs. Pas de plugin tiers : ce script est la seule glue (ADR-0021).
// ---------------------------------------------------------------------------
abstract class CargoBuildTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Input abstract val ndkDirectory: Property<String>
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val minApi: Property<Int>
    @get:Input abstract val abis: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction fun build() {
        val triples = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")
        val os = System.getProperty("os.name").lowercase()
        val host = when { os.contains("win") -> "windows-x86_64"; os.contains("mac") -> "darwin-x86_64"; else -> "linux-x86_64" }
        val bin = File(ndkDirectory.get(), "toolchains/llvm/prebuilt/$host/bin")
        val root = workspace.get().asFile
        val cargoHome = System.getenv("CARGO_HOME") ?: File(System.getProperty("user.home"), ".cargo").path
        // Chemins neutres dans le binaire : deux machines doivent produire le même .so (build reproductible).
        val rustFlags = listOf(
            "--remap-path-prefix=${root.path}=/keyra",
            "--remap-path-prefix=$cargoHome=/cargo",
            "-Clink-arg=-Wl,-z,max-page-size=16384",
        ).joinToString("\u001f") // séparateur imposé par CARGO_ENCODED_RUSTFLAGS
        val out = outputDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        for (abi in abis.get()) {
            val triple = triples[abi] ?: error("ABI non prise en charge : $abi")
            val variable = triple.uppercase().replace('-', '_')
            val suffix = if (host.startsWith("windows")) ".cmd" else ""
            val clang = File(bin, "$triple${minApi.get()}-clang$suffix")
            exec.exec {
                commandLine(cargo.get(), "build", "--release", "--locked", "-p", "keyra-jni", "--target", triple)
                workingDir = root
                environment("CARGO_TARGET_${variable}_LINKER", clang.path)
                environment("CC_${triple.replace('-', '_')}", clang.path)
                environment("AR_${triple.replace('-', '_')}", File(bin, "llvm-ar").path)
                environment("CARGO_ENCODED_RUSTFLAGS", rustFlags)
            }
            File(root, "target/$triple/release/libkeyra_jni.so").copyTo(File(out, "$abi/libkeyra_jni.so"), overwrite = true)
        }
    }
}

val rustWorkspace = rootProject.layout.projectDirectory.dir("rust")
val cargoBuild = tasks.register<CargoBuildTask>("cargoBuild") {
    workspace.set(rustWorkspace)
    sources.from(rustWorkspace.asFileTree.matching { include("**/*.rs", "**/Cargo.toml", "Cargo.lock"); exclude("target/**", "fuzz/**") })
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    cargo.set(providers.gradleProperty("keyra.cargo").orElse(providers.environmentVariable("CARGO").orElse(
        File(System.getProperty("user.home"), ".cargo/bin/cargo" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "").path)))
    minApi.set(29)
    abis.set(listOf("arm64-v8a", "x86_64"))
    outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoBuildTask::outputDir)
}
