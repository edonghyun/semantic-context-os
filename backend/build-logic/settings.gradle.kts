// 빌드 설정 convention plugin(공개 배포 단계 4 T2) — backend/settings.gradle.kts가 pluginManagement로 포함한다
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "build-logic"
