import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "aismo.sms"
    compileSdk = 37
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "aismo.sms"
        minSdk = 29
        targetSdk = 37
        versionCode = 9
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships about 15 MB of native code per architecture. Override with -Pabis=arm64-v8a for a phone-only build.
        val abis = (findProperty("abis") as String?)?.split(",")?.map(String::trim) ?: listOf("arm64-v8a", "x86_64")
        ndk { abiFilters += abis }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_CPP_DIR=${layout.buildDirectory.dir("llama.cpp-src").get().asFile.absolutePath}",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    // llama.cpp: shared libraries, with the CPU code built in variants and the best one picked at run time,
                    // and none of its optional extras (HTTP and TLS downloads, its tools, tests and examples).
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The Play Store upload key is never committed: it is referenced only when the environment supplies it
    // (AISMO_PLAY_KEYSTORE=/path/to/keystore AISMO_PLAY_PASS=... ./gradlew bundleRelease), so an unsigned
    // release is the default and the signed bundle is the one you upload to the console.
    signingConfigs {
        create("play") {
            val store = System.getenv("AISMO_PLAY_KEYSTORE")
            val pass = System.getenv("AISMO_PLAY_PASS")
            if (!store.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                storeFile = File(store)
                storePassword = pass
                keyAlias = "aismo-play-upload"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val play = signingConfigs.getByName("play")
            if (play.storeFile != null) signingConfig = play
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // ONNX Runtime is 35 MB of native code per architecture. Compressed in the APK and unpacked at install it costs about
    // 13 MB of download instead; the model is copied out of the APK by stream, so it can be compressed too.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

// The embedding model for on-device semantic search is not committed. It is fetched once from a pinned revision of its
// public source, checked against a pinned SHA-256, kept in the Gradle user home, and added to the APK as a generated asset.
/** Downloads a file once, checks it against a pinned SHA-256, and keeps it in the Gradle user home. */
object VerifiedDownload {
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The cached copy of [url], fetched and verified if it is missing or does not match [sha256]. */
    fun cached(gradleHome: File, subdir: String, name: String, url: String, sha256: String, what: String): File {
        val cache = File(gradleHome, "$subdir/$name")
        if (cache.exists() && sha256Of(cache) == sha256) return cache
        cache.parentFile.mkdirs()
        val partial = File(cache.parentFile, cache.name + ".part")
        try {
            URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        } catch (e: java.io.IOException) {
            partial.delete()
            throw GradleException("Could not download $what from $url (${e.message}). Download it once and save it as ${cache.path}.", e)
        }
        val actual = sha256Of(partial)
        if (actual != sha256) {
            partial.delete()
            throw GradleException("The downloaded $what has SHA-256 $actual, expected $sha256.")
        }
        check(partial.renameTo(cache)) { "Could not move $what into place" }
        return cache
    }
}

abstract class FetchEmbeddingModel : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input abstract val assetPath: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val cache = VerifiedDownload.cached(project.gradle.gradleUserHomeDir, "aismo-models", "${sha256.get()}.onnx", url.get(), sha256.get(), "the embedding model")
        val target = outputDir.file(assetPath.get()).get().asFile
        target.parentFile.mkdirs()
        cache.copyTo(target, overwrite = true)
    }
}

/** The llama.cpp source used for the on-device writing assistant: a pinned release, fetched once, never committed. */
abstract class FetchLlamaCpp : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val archives: ArchiveOperations
    @get:javax.inject.Inject abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val tarball = VerifiedDownload.cached(project.gradle.gradleUserHomeDir, "aismo-sources", "llama.cpp-${sha256.get()}.tar.gz", url.get(), sha256.get(), "the llama.cpp source")
        files.delete { delete(outputDir) }
        files.copy {
            from(archives.tarTree(archives.gzip(tarball)))
            into(outputDir)
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) } // drop the archive's top folder
            includeEmptyDirs = false
        }
    }
}

val fetchEmbeddingModel = tasks.register<FetchEmbeddingModel>("fetchEmbeddingModel") {
    url.set("https://huggingface.co/Xenova/bge-small-en-v1.5/resolve/ea104dacec62c0de699686887e3f920caeb4f3e3/onnx/model_quantized.onnx")
    sha256.set("6c9c6101a956d62dfb5e7190c538226c0c5bb9cb27b651234b6df063ee7dbfe4")
    assetPath.set("models/bge-small-en-v1.5-int8.onnx")
    outputDir.set(layout.buildDirectory.dir("generated/embeddingModel"))
}

val fetchLlamaCpp = tasks.register<FetchLlamaCpp>("fetchLlamaCpp") {
    url.set("https://github.com/ggml-org/llama.cpp/archive/refs/tags/v1.0.0.tar.gz")
    sha256.set("3f5f750e36326aea4d65c7623e7d597a33e395d65f4d76e5e0532171f252532b")
    outputDir.set(layout.buildDirectory.dir("llama.cpp-src"))
}
// The native build reads the source, so it must wait for the fetch.
tasks.configureEach { if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLlamaCpp) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchEmbeddingModel, FetchEmbeddingModel::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.onnxruntime.android)
    implementation(libs.litertlm.android)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.libphonenumber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
