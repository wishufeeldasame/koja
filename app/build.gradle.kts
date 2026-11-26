import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // sherpa-onnx 공식 JVM·네이티브 jar는 Maven Central에 없어 GitHub Releases에서 직접 받는다(D-062).
    ivy {
        url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
        patternLayout {
            artifact("v[revision]/[artifact]-[revision].[ext]")
        }
        metadataSources { artifact() }
        content { includeGroup("com.k2fsa.sherpa.onnx") }
    }
}

// JavaFX 25는 JDK 25와 같은 LTS 라인이다.
val javafxVersion = "25.0.5.1"
val sherpaVersion = "2.0.3"
val sherpaJvmSha256 = "ad69b6a54462ba3671782617b11d35975c8d6b7c84ae11765e3a06dd0c56c0e5"
val sherpaNativeSha256ByPlatform = mapOf(
    "osx-aarch64" to "9c85930bcf081eb285a324f0f97eec63054a2a6ba7baf47408b83683add2a675",
    "osx-x64" to "9279efce3fe715a88197f97806a527ed7765bae8940ceba6aa82825e5ea107ef",
    "win-x64" to "26a9072f4a47be3a73bfbbba5f25e6e8b84b24734be651de2d480ae753ae6508",
    "win-arm64" to "8b3cb58bf3d31d9c6c80d3742455ecfea9df06ed0409d8bf90d12208063f1d30",
    "linux-x64" to "42330fbcfe4f771386545397ebf8a8dd2176ff913a254331226c01ad87982e17",
    "linux-aarch64" to "e81f84f92b3ecd724882f317c9a31893ce681b25d93a23bcc5c0a9c0c4a4df9d",
)

// JavaFX·sherpa-onnx 네이티브 jar는 빌드하는 OS·CPU용만 받는다.
val hostOs = System.getProperty("os.name").lowercase()
val hostArm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
val javafxPlatform: String = when {
    hostOs.contains("win") -> "win"
    hostOs.contains("mac") -> if (hostArm) "mac-aarch64" else "mac"
    else -> if (hostArm) "linux-aarch64" else "linux"
}
val sherpaPlatform: String = when {
    hostOs.contains("win") -> if (hostArm) "win-arm64" else "win-x64"
    hostOs.contains("mac") -> if (hostArm) "osx-aarch64" else "osx-x64"
    else -> if (hostArm) "linux-aarch64" else "linux-x64"
}

val sherpaJvmRaw = configurations.create("sherpaJvmRaw") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val sherpaNative = configurations.create("sherpaNative") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val jpackageLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
val modularSherpaJar = layout.buildDirectory.file("sherpa-modular/sherpa-onnx-jvm-$sherpaVersion.jar")
val sherpaNativeDir = layout.buildDirectory.dir("sherpa-native")

fun verifyFileSha256(file: File, expected: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    val actual = HexFormat.of().formatHex(digest.digest())
    if (actual != expected) {
        throw GradleException("SHA-256 불일치(${file.name}): 기대=$expected, 실제=$actual")
    }
}

// sherpa-onnx-jvm은 자동 모듈이라 jpackage(jlink)가 거부한다. 빌드 때 module-info.class를 넣어 명시 모듈로 만든다(D-062).
val modularizeSherpaJvm = tasks.register("modularizeSherpaJvm") {
    inputs.files(sherpaJvmRaw)
    inputs.property("sherpaVersion", sherpaVersion)
    outputs.file(modularSherpaJar)
    doLast {
        val rawJar = sherpaJvmRaw.singleFile
        verifyFileSha256(rawJar, sherpaJvmSha256)
        val outJar = modularSherpaJar.get().asFile
        val workDir = outJar.parentFile.resolve("work")
        delete(workDir)
        workDir.mkdirs()

        val modSrc = workDir.resolve("module-info.java")
        modSrc.writeText("module sherpa.onnx.jvm {\n    exports com.k2fsa.sherpa.onnx;\n}\n", Charsets.UTF_8)

        val home = jpackageLauncher.get().metadata.installationPath.asFile
        val win = System.getProperty("os.name").lowercase().contains("win")
        val javac = home.resolve(if (win) "bin/javac.exe" else "bin/javac").path
        val jarTool = home.resolve(if (win) "bin/jar.exe" else "bin/jar").path

        fun runTool(vararg cmd: String) {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (proc.waitFor() != 0) {
                throw GradleException("${cmd[0]} 실패:\n$out")
            }
        }

        val tmpJar = workDir.resolve(outJar.name)
        rawJar.copyTo(tmpJar, overwrite = true)
        runTool(javac, "--patch-module", "sherpa.onnx.jvm=${tmpJar.path}", "-d", workDir.path, modSrc.path)
        runTool(jarTool, "--update", "--file=${tmpJar.path}", "-C", workDir.path, "module-info.class")
        outJar.parentFile.mkdirs()
        tmpJar.copyTo(outJar, overwrite = true)
        delete(workDir)
    }
}

// sherpa-onnx-native-lib-* 이름은 Java 키워드 'native'가 들어 있어 JPMS 모듈 경로에 올릴 수 없다.
// 네이티브 동적 라이브러리만 꺼내 sherpa_onnx.native.path와 jpackage --app-content로 넘긴다(D-062).
val sherpaLicensesDir = layout.projectDirectory.dir("licenses/sherpa-native")
val extractSherpaNative = tasks.register("extractSherpaNative") {
    inputs.files(sherpaNative)
    inputs.dir(sherpaLicensesDir)
    inputs.property("sherpaPlatform", sherpaPlatform)
    inputs.property("sherpaVersion", sherpaVersion)
    outputs.dir(sherpaNativeDir)
    doLast {
        val nativeJar = sherpaNative.singleFile
        val expectedSha = sherpaNativeSha256ByPlatform[sherpaPlatform]
            ?: throw GradleException("지원하지 않는 sherpa-onnx 플랫폼: $sherpaPlatform")
        verifyFileSha256(nativeJar, expectedSha)

        val outDir = sherpaNativeDir.get().asFile
        delete(outDir)
        outDir.mkdirs()
        val prefix = "sherpa-onnx/native/$sherpaPlatform/"
        var extracted = 0
        ZipFile(nativeJar).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val fileName = entry.name.removePrefix(prefix)
                if (fileName.contains("/") || fileName.contains("\\") || fileName.isBlank()) continue
                zip.getInputStream(entry).use { input ->
                    outDir.resolve(fileName).outputStream().use { output -> input.copyTo(output) }
                }
                extracted++
            }
        }
        if (extracted < 2) {
            throw GradleException("sherpa-onnx 네이티브 라이브러리가 부족합니다(${extracted}개): ${nativeJar.name}")
        }
        val licenseFiles = listOf(
            "LICENSE-sherpa-onnx.txt",
            "LICENSE-onnxruntime.txt",
            "ThirdPartyNotices-onnxruntime.txt",
        )
        for (name in licenseFiles) {
            val src = sherpaLicensesDir.file(name).asFile
            if (!src.isFile) {
                throw GradleException("sherpa-onnx 번들 라이선스 파일이 없습니다: $src")
            }
            src.copyTo(outDir.resolve(name), overwrite = true)
        }
        outDir.resolve("NOTICE.txt").writeText(
            """
            sherpa-onnx $sherpaVersion ($sherpaPlatform) + ONNX Runtime 1.28.2
            Licenses: Apache-2.0 (LICENSE-sherpa-onnx.txt), MIT (LICENSE-onnxruntime.txt)
            Third-party notices: ThirdPartyNotices-onnxruntime.txt
            Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v$sherpaVersion
            Archive SHA-256: $expectedSha
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}

dependencies {
    for (module in listOf("base", "graphics", "controls", "media")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    implementation("tools.jackson.core:jackson-databind:3.3.0")
    sherpaJvmRaw("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaVersion")
    sherpaNative("com.k2fsa.sherpa.onnx:sherpa-onnx-native-lib-$sherpaPlatform:$sherpaVersion")
    implementation(files(modularizeSherpaJvm))

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(extractSherpaNative)
    jvmArgs(
        "-Dsherpa_onnx.native.path=${sherpaNativeDir.get().asFile.path}",
        "--enable-native-access=ALL-UNNAMED",
    )
}

application {
    mainModule = "koja"
    mainClass = "koja.ui.KojaApp"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=javafx.graphics,javafx.media,sherpa.onnx.jvm",
    )
}

tasks.named<JavaExec>("run") {
    dependsOn(extractSherpaNative)
    jvmArgs("-Dsherpa_onnx.native.path=${sherpaNativeDir.get().asFile.path}")
}

// 단위 테스트는 클래스패스로 돌므로 JPMS 모듈 경로에서의 sherpa-onnx 네이티브 로딩을 별도로 검증한다(D-062).
val verifyModuleNativeLoad = tasks.register<JavaExec>("verifyModuleNativeLoad") {
    group = "verification"
    description = "JPMS 모듈 경로에서 sherpa-onnx 네이티브 라이브러리가 적재되는지 확인한다"
    dependsOn(extractSherpaNative)
    mainModule = "koja"
    mainClass = "koja.engine.SherpaRuntimeCheck"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "--enable-native-access=sherpa.onnx.jvm",
        "-Dsherpa_onnx.native.path=${sherpaNativeDir.get().asFile.path}",
    )
}

tasks.check {
    dependsOn(verifyModuleNativeLoad)
}

// 설계 9장 drive.file 폴더 PoC(D-057). 실제 구글 로그인이 필요해 CI에서는 돌리지 않는다.
tasks.register<JavaExec>("oauthPoc") {
    group = "verification"
    description = "구글 로그인 후 drive.file로 시트를 만들 수 있는 위치를 확인한다"
    mainModule = "koja"
    mainClass = "koja.export.DriveFolderPoc"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("-Dfile.encoding=UTF-8")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 현재 OS용 설치파일(.msi / .dmg). jpackage는 크로스 빌드가 안 돼서 CI가 OS별로 돌린다(D-002).
// 버전: macOS jpackage는 첫 자리가 0인 버전을 거부한다. 릴리스 버전을 정하면 -PappVersion으로 넘긴다.
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "현재 OS용 설치파일을 build/jpackage에 만든다"
    dependsOn(tasks.jar, extractSherpaNative)
    val os = System.getProperty("os.name").lowercase()
    val windows = os.contains("win")
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile
    val runtimeJars = configurations.runtimeClasspath
    val appJar = tasks.jar.flatMap { it.archiveFile }
    // scripts/bundle_*.sh가 만든다. 앱 안에서는 맥 Contents/<폴더>, 윈도우 <앱 폴더>/<폴더>가 된다(D-042, D-059, D-065, D-069).
    // 폴더 → 들어 있어야 할 파일. cuda/는 윈도우만 있다(whisper-server·llama-server가 함께 쓰는 CUDA 런타임, D-060).
    val exe = if (windows) ".exe" else ""
    val bundles = linkedMapOf(
        "ffmpeg" to "ffmpeg$exe",
        "whisper" to "whisper-server$exe",
        "llama" to "llama-server$exe",
    )
    if (windows) bundles["cuda"] = "cudart64_12.dll"
    val bundleDirs = bundles.mapKeys { layout.buildDirectory.dir(it.key).get().asFile }
    val sherpaDir = sherpaNativeDir.get().asFile

    doFirst {
        for ((dir, file) in bundleDirs) {
            if (!dir.resolve(file).isFile || !dir.resolve("NOTICE.txt").isFile) {
                throw GradleException("번들 ${dir.name}이(가) 없다: ${dir}. 먼저 scripts/bundle_ffmpeg.sh, scripts/bundle_whisper.sh, scripts/bundle_llama.sh를 실행한다")
            }
        }
        val llamaDir = bundleDirs.keys.first { it.name == "llama" }
        val requiredLlamaFiles = if (windows) {
            listOf(
                "llama-server.exe",
                "llama-server-impl.dll",
                "llama.dll",
                "llama-common.dll",
                "mtmd.dll",
                "ggml.dll",
                "ggml-base.dll",
                "ggml-cuda.dll",
                "libomp.dll",
                "LICENSE",
                "LICENSE-LLVM-OpenMP",
                "NOTICE.txt",
            )
        } else {
            listOf(
                "llama-server",
                "libllama-server-impl.dylib",
                "libllama.dylib",
                "libllama-common.dylib",
                "libmtmd.dylib",
                "libggml.dylib",
                "libggml-base.dylib",
                "libggml-cpu.dylib",
                "libggml-metal.dylib",
                "LICENSE",
                "NOTICE.txt",
            )
        }
        val missingLlama = requiredLlamaFiles.filterNot { llamaDir.resolve(it).isFile }
        val hasWinCpuBackend = !windows || (llamaDir.listFiles()?.any {
            it.isFile && it.name.startsWith("ggml-cpu-") && it.name.endsWith(".dll")
        } == true)
        if (missingLlama.isNotEmpty() || !hasWinCpuBackend) {
            val missingDesc = if (hasWinCpuBackend) missingLlama.joinToString(", ") else (missingLlama + "ggml-cpu-*.dll").joinToString(", ")
            throw GradleException("번들 llama 필수 파일이 빠졌다($missingDesc): $llamaDir. scripts/bundle_llama.sh를 다시 실행한다")
        }
        if (windows) {
            val sharedCudaDlls = listOf("cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll")
            val cudaDir = bundleDirs.keys.first { it.name == "cuda" }
            val missingCuda = (sharedCudaDlls + listOf("LICENSE.txt", "NOTICE.txt")).filterNot { cudaDir.resolve(it).isFile }
            if (missingCuda.isNotEmpty()) {
                throw GradleException("공유 cuda 필수 파일이 빠졌다(${missingCuda.joinToString(", ")}): $cudaDir. scripts/bundle_whisper.sh 또는 scripts/bundle_llama.sh를 다시 실행한다")
            }
            for (dir in bundleDirs.keys.filter { it.name == "llama" || it.name == "whisper" }) {
                val dup = sharedCudaDlls.firstOrNull { dir.resolve(it).isFile }
                if (dup != null) {
                    val dupFile = dir.resolve(dup)
                    throw GradleException(
                        "공유 CUDA DLL이 ${dir.name} 폴더에 중복돼 있다: ${dupFile.path}. " +
                            "해당 파일을 삭제하거나 ${dir.path}를 지운 뒤 scripts/bundle_${dir.name}.sh를 다시 실행한다",
                    )
                }
            }
        }
        val jniLib = if (windows) "sherpa-onnx-jni.dll" else if (os.contains("mac")) "libsherpa-onnx-jni.dylib" else "libsherpa-onnx-jni.so"
        val requiredSherpaFiles = listOf(
            jniLib,
            "NOTICE.txt",
            "LICENSE-sherpa-onnx.txt",
            "LICENSE-onnxruntime.txt",
            "ThirdPartyNotices-onnxruntime.txt",
        )
        if (requiredSherpaFiles.any { !sherpaDir.resolve(it).isFile }) {
            throw GradleException("번들 sherpa-onnx 네이티브 라이브러리 또는 라이선스 파일이 없다: $sherpaDir")
        }
        delete(outDir)
        val home = jpackageLauncher.get().metadata.installationPath.asFile
        executable = home.resolve(if (windows) "bin/jpackage.exe" else "bin/jpackage").path
        val type = when {
            windows -> listOf("--type", "msi", "--win-menu", "--win-shortcut", "--win-dir-chooser")
            os.contains("mac") -> listOf("--type", "dmg")
            else -> listOf("--type", "app-image")
        }
        args = type + listOf(
            "--name", "koja",
            "--app-version", appVersion,
            "--dest", outDir.path,
            "--module-path", (listOf(appJar.get().asFile) + runtimeJars.get().files).joinToString(File.pathSeparator),
            "--module", "koja/koja.ui.KojaApp",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "--enable-native-access=javafx.graphics,javafx.media,sherpa.onnx.jvm",
        ) + (bundleDirs.keys + sherpaDir).flatMap { listOf("--app-content", it.path) } // 쉼표 구분은 경로의 쉼표와 섞인다
    }
}
