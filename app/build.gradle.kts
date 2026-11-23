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
    for (module in listOf("base", "graphics", "controls")) {
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
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "--enable-native-access=javafx.graphics")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
