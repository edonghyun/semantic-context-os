// P1 공통 검증 하네스(ADR-0016). 제품 모듈(backend/)과 의존 관계가 없다 — 구현을 **프로세스**로만 본다(stdio JSON-RPC).
// 버전 확인(2026-10-13): Jackson 2.18.x·JUnit 5.11.x·AssertJ 3.26.x는 Maven Central 공개 버전(출처 확인은 README).
plugins {
    java
    id("com.diffplug.spotless") version "8.10.4" // ADR-0012, backend와 동일
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.26.3")
    // Java 구현 실행용 PostgreSQL(ADR-0015). 버전 확인 2026-10-13: Testcontainers 2.0.5(backend와 동일)
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    // 테스트 코드 규칙(TESTING §13) — backend와 같은 ArchUnit 1.5.1
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
}

// 하네스 자체 테스트(가짜 서버·비교기·fixture 스키마)는 test, Java 실행 JAR 기준 통과는 javaTest.
tasks.test {
    useJUnitPlatform { excludeTags("java") }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
    systemProperty("harness.java.home", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    // PostgresImageTest가 읽는 backend 이미지 상수(TESTING §6). 입력으로 선언해야 backend만 바꾼 digest 상향에서도 test가 다시 돈다
    val backendPostgresSupport = layout.projectDirectory.file("../backend/src/test/java/io/scos/collection/internal/postgres/PostgresSupport.java")
    inputs.file(backendPostgresSupport).withPropertyName("backendPostgresSupport").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("harness.backend.postgresSupport", backendPostgresSupport.asFile.absolutePath)
}

val javaTest by tasks.registering(Test::class) {
    description = "Java 구현(backend 실행 JAR) 기준 — expected/ts와 비교. 먼저 backend에서 ./gradlew bootJar harnessJar"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("java") }
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = true }
    systemProperty("harness.java.jar", (project.findProperty("harness.java.jar") ?: layout.projectDirectory.file("../backend/build/libs/scos-backend.jar").asFile.absolutePath).toString())
    systemProperty("harness.java.adapters", (project.findProperty("harness.java.adapters") ?: layout.projectDirectory.file("../backend/build/libs/scos-harness-adapters.jar").asFile.absolutePath).toString())
    systemProperty("harness.java.home", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    systemProperty("harness.mode", (project.findProperty("harness.mode") ?: "verify").toString())
    // 현재 단계(계획 6절): knownFailure.until이 이 단계보다 앞이면 만료 → 실패(재검토 N-2)
    systemProperty("harness.phase", (project.findProperty("harness.phase") ?: "P3").toString())
    systemProperty("harness.root", layout.projectDirectory.asFile.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.check { dependsOn(javaTest) }

// O2 병행 비교 도구(F11 T11.2, harness/README.md): 두 MCP 서버 실행 파일에 같은 질의를 보내 겹침·첫 적중·지연을 비교한다.
// 예: ./gradlew o2Compare -Po2.args="--queries q.jsonl --a-name ts --a-command <ts mcp.sh> --b-name java --b-command <scos-mcp.sh> --out build/o2/run"
// 종료 코드 0 = 오류·위반 없음, 1 = 오류나 임계값 위반, 2 = 사용법 오류. TS 퇴역 뒤 io.scos.harness.o2와 함께 지운다
val o2Compare by tasks.registering(JavaExec::class) {
    description = "O2 병행 비교(TS MCP 대 Java MCP) — -Po2.args=\"…\""
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.scos.harness.o2.ParallelComparisonMain"
    args = (project.findProperty("o2.args") ?: "").toString().split(" ").filter { it.isNotBlank() }
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.28.0")
    }
}
