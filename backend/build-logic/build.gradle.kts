plugins {
    `kotlin-dsl`
}

// 플러그인 버전은 이 한 곳에 둔다(ADR-0012, TESTING §12). backend는 scos.* convention plugin으로만 적용한다
// (Gradle은 포함 빌드의 의존만으로는 버전 없는 id 요청을 풀지 못한다 — scos.boot-app·scos.pitest가 감싼다).
dependencies {
    // 버전(공식 문서 확인 2026-10-13): Spring Boot 4.1.1 — Boot BOM 좌표(SpringBootPlugin.BOM_COORDINATES)도 여기서 온다
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.1")
    // ADR-0012(2026-10-13 공식 확인): Spotless 8.10.4, Error Prone 플러그인 5.1.1(코어 2.50.0은 scos.java-module이 errorprone 구성에 둔다)
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.4")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")
    // TESTING §12 변이 테스트(공식 확인 2026-10-14): gradle-pitest-plugin 1.19.0(기본 PIT 1.22.1)
    implementation("info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0")
}
