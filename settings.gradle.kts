plugins {
    // 로컬에 JDK 25가 없으면 Gradle이 받아 온다(CI는 setup-java로 미리 설치).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "koja"
include("app")
