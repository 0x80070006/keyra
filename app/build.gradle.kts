import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.app_clavier"
    ndkVersion = "30.0.16248370"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.app_clavier"
        minSdk = 29
        targetSdk = 37
        versionCode = 11
        versionName = "11.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Seules ABI livrées : arm64 (téléphones) et x86_64 (émulateur). Voir ADR-0021.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Lint strict : tout nouvel avertissement fait échouer le build. Les avertissements de la 11.0 sont
    // listés dans lint-baseline.xml et seront traités au fil des phases. Les vérifications qui dépendent de
    // la date (nouvelles versions disponibles) sont coupées : elles casseraient la CI sans changement de code.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkReleaseBuilds = true
        baseline = file("lint-baseline.xml")
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    // F-Droid : pas de bloc de dépendances chiffré par Google dans l'APK ni l'AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            // R8 : réduction du code, et suppression des appels à android.util.Log (src/main/keepRules).
            optimization {
                enable = true
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
}

// Aucune dépendance d'exécution hors de la bibliothèque standard Kotlin (ADR-0013 à 0016).
dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
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

// ---------------------------------------------------------------------------
// Garde-fou « aucun texte tapé dans les journaux » (menace I-8). Échoue le build si un
// appel de journalisation, de trace, de Toast ou d'exception reçoit autre chose qu'un
// littéral constant. Exception explicite : commentaire « journal-ok: raison » sur la ligne.
// ---------------------------------------------------------------------------
abstract class LogGuardTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction fun check() {
        val forbidden = listOf(
            Regex("""\bimport\s+android\.util\.Log\b""") to "android.util.Log est interdit dans le code de l'application",
            Regex("""(?<![\w.])Log\.(v|d|i|w|e|wtf)\(""") to "journalisation interdite",
            Regex("""\b(println|print)\(""") to "println interdit",
            Regex("""\.printStackTrace\(""") to "printStackTrace interdit",
            Regex("""System\.(out|err)""") to "System.out/err interdit",
        )
        val literalOnly = listOf(
            Regex("""(?<!fun\s)(?<!fun <T> )\btraced\((?!\s*"[^"$]*"\s*\))""") to "traced() exige un nom littéral constant",
            Regex("""Trace\.beginSection\((?!\s*"[^"$]*"\s*\))""") to "Trace.beginSection() exige un nom littéral constant",
            Regex("""Toast\.makeText\([^,]+,\s*(?!"[^"$]*"\s*,|R\.string\.\w+\s*,)""") to "le message d'un Toast doit être un littéral constant ou une ressource R.string",
            Regex("""\b(error|IllegalStateException|IllegalArgumentException)\(\s*"[^"]*\$""") to "message d'exception construit à partir d'une variable",
        )
        val problems = ArrayList<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("journal-ok:")) return@forEachIndexed
                val code = line.substringBefore("//")
                (forbidden + literalOnly).forEach { (regex, message) ->
                    if (regex.containsMatchIn(code)) problems.add("${file.name}:${index + 1}: $message\n    ${line.trim()}")
                }
            }
        }
        report.get().asFile.writeText(problems.joinToString("\n").ifEmpty { "OK" })
        if (problems.isNotEmpty()) throw GradleException("Garde-fou des journaux :\n" + problems.joinToString("\n"))
    }
}
val logGuard = tasks.register<LogGuardTask>("logGuard") {
    sources.from(fileTree("src/main/java") { include("**/*.kt", "**/*.java") })
    report.set(layout.buildDirectory.file("reports/keyra/log-guard.txt"))
}
tasks.named("check") { dependsOn(logGuard) }
tasks.named("preBuild") { dependsOn(logGuard) }

// ---------------------------------------------------------------------------
// SBOM CycloneDX 1.6 de ce qui est livré dans l'APK : bibliothèques JVM d'exécution (release)
// et crates Rust liées dans libkeyra_jni.so (dépendances normales seulement, sans dev ni build).
// Sans plugin tiers ; sortie déterministe (pas de numéro de série ni de date).
// ---------------------------------------------------------------------------
abstract class SbomTask : DefaultTask() {
    @get:Input abstract val appVersion: Property<String>
    @get:Input abstract val jvmComponents: ListProperty<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val jvmFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val cargoLock: RegularFileProperty
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Input abstract val cargo: Property<String>
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @TaskAction fun write() {
        val components = ArrayList<String>()
        jvmComponents.get().zip(jvmFiles.files.sortedBy { it.name }.let { files -> jvmComponents.get().map { id -> files.first { it.name.startsWith(id.split(':')[1] + "-") } } })
            .forEach { (id, file) ->
                val (group, name, version) = id.split(':')
                components += """{"type":"library","name":${json(name)},"group":${json(group)},"version":${json(version)},"purl":${json("pkg:maven/$group/$name@$version")},"scope":"required","hashes":[{"alg":"SHA-256","content":"${sha256(file)}"}]}"""
            }
        // Crates : graphe résolu par cargo, filtré sur la cible Android, depuis keyra-jni, liens « normaux » seulement.
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine(cargo.get(), "metadata", "--format-version", "1", "--locked", "--filter-platform", "aarch64-linux-android")
            workingDir = workspace.get().asFile; standardOutput = out
        }
        @Suppress("UNCHECKED_CAST") val meta = groovy.json.JsonSlurper().parseText(out.toString(Charsets.UTF_8)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val packages = (meta["packages"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        @Suppress("UNCHECKED_CAST") val nodes = ((meta["resolve"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        val checksums = Regex("""name = "([^"]+)"\nversion = "([^"]+)"\nsource = "[^"]+"\nchecksum = "([0-9a-f]+)"""")
            .findAll(cargoLock.get().asFile.readText().replace("\r\n", "\n")).associate { "${it.groupValues[1]}@${it.groupValues[2]}" to it.groupValues[3] }
        val root = packages.values.first { it["name"] == "keyra-jni" }["id"] as String
        val seen = sortedSetOf<String>(); val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst(); if (!seen.add(id)) continue
            @Suppress("UNCHECKED_CAST") (nodes[id]?.get("deps") as? List<Map<String, Any?>>).orEmpty()
                .filter { dep -> (dep["dep_kinds"] as List<Map<String, Any?>>).any { it["kind"] == null } }
                .forEach { queue.addLast(it["pkg"] as String) }
        }
        seen.map { packages.getValue(it) }.sortedBy { it["name"] as String }.forEach { p ->
            val name = p["name"] as String; val version = p["version"] as String
            val hash = checksums["$name@$version"]?.let { ""","hashes":[{"alg":"SHA-256","content":"$it"}]""" } ?: ""
            val local = p["source"] == null
            components += """{"type":"library","name":${json(name)},"version":${json(version)},"purl":${json(if (local) "pkg:generic/keyra/$name@$version" else "pkg:cargo/$name@$version")},"scope":"required"$hash${if (local) ""","description":"Crate du dépôt Keyra (rust/$name)"""" else ""}}"""
        }
        output.get().asFile.writeText("""{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,"metadata":{"component":{"type":"application","name":"Keyra","version":${json(appVersion.get())},"licenses":[{"license":{"id":"MIT"}}]}},"components":[
${components.joinToString(",\n")}
]}
""")
    }
}
tasks.register<SbomTask>("sbom") {
    val runtime = configurations.named("releaseRuntimeClasspath")
    appVersion.set(android.defaultConfig.versionName ?: "inconnue")
    jvmComponents.set(runtime.map { c -> c.incoming.resolutionResult.allComponents.mapNotNull { (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { m -> "${m.group}:${m.module}:${m.version}" } }.sorted() })
    jvmFiles.from(runtime.map { c -> c.incoming.artifactView { attributes { attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "jar") } }.files })
    cargoLock.set(rustWorkspace.file("Cargo.lock"))
    workspace.set(rustWorkspace)
    cargo.set(cargoBuild.flatMap { it.cargo })
    output.set(layout.buildDirectory.file("reports/keyra/sbom.cdx.json"))
}
