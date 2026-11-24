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
}

// JavaFX 25는 JDK 25와 같은 LTS 라인이다.
val javafxVersion = "25.0.5.1"

// JavaFX jar는 OS·CPU별 분류자가 붙는다. 빌드하는 OS용만 받는다.
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    for (module in listOf("base", "graphics", "controls", "media")) {
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    implementation("tools.jackson.core:jackson-databind:3.3.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainModule = "koja"
    mainClass = "koja.ui.KojaApp"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "--enable-native-access=javafx.graphics,javafx.media")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// 현재 OS용 설치파일(.msi / .dmg). jpackage는 크로스 빌드가 안 돼서 CI가 OS별로 돌린다(D-002).
// 버전: macOS jpackage는 첫 자리가 0인 버전을 거부한다. 릴리스 버전을 정하면 -PappVersion으로 넘긴다.
val appVersion = providers.gradleProperty("appVersion").getOrElse("1.0.0")
val jpackageLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "현재 OS용 설치파일을 build/jpackage에 만든다"
    dependsOn(tasks.jar)
    val os = System.getProperty("os.name").lowercase()
    val windows = os.contains("win")
    val outDir = layout.buildDirectory.dir("jpackage").get().asFile
    val runtimeJars = configurations.runtimeClasspath
    val appJar = tasks.jar.flatMap { it.archiveFile }
    // scripts/bundle_ffmpeg.sh가 만든다. 앱 안에서는 맥 Contents/ffmpeg, 윈도우 <앱 폴더>/ffmpeg가 된다(D-042).
    val ffmpegDir = layout.buildDirectory.dir("ffmpeg").get().asFile

    doFirst {
        if (!ffmpegDir.isDirectory) {
            throw GradleException("번들 ffmpeg가 없다: ${ffmpegDir}. 먼저 scripts/bundle_ffmpeg.sh를 실행한다")
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
            "--app-content", ffmpegDir.path,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "--enable-native-access=javafx.graphics,javafx.media",
        )
    }
}
