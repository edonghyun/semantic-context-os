pluginManagement {
    // 빌드 설정 convention plugin(scos.java-module)과 플러그인 버전(공개 배포 단계 4 T2)
    includeBuild("build-logic")
}

plugins {
    // Java 21 toolchain 자동 프로비저닝(ADR-0001, 사용자 결정 2026-10-13)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "scos-backend"

// 제품 모듈 14개(공개 배포 설계 §5.1, 단계 4)와 배포하지 않는 보조 프로젝트 2개(scos-test-support·verification).
// 디렉터리 이름은 프로젝트 이름과 같다. 모듈 사이 방향: spi ← engine ← {store·collection·connector·provider·mcp} ← starter ← server
include(
    "scos-spi",
    "scos-engine",
    "scos-store-memory",
    "scos-store-postgres",
    "scos-collection",
    "scos-connector-slack",
    "scos-connector-notion",
    "scos-connector-jira",
    "scos-connector-google-drive",
    "scos-connector-tiro",
    "scos-provider-gemini",
    "scos-mcp",
    "scos-spring-boot-starter",
    "scos-server",
    "scos-test-support",
    "verification",
)

