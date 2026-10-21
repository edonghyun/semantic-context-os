import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// 버전(공식 문서 확인 2026-10-13): Spring Boot 4.1.1, Gradle 9.8.1, Java 21.
// Flyway·PostgreSQL 드라이버·Testcontainers 버전은 Boot BOM이 관리한다(ADR-0013·0015).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    // ADR-0012(2026-10-13 공식 확인): Spotless 8.10.4(plugins.gradle.org) + google-java-format 1.37.0(github releases), Error Prone 플러그인 5.1.1 + 코어 2.50.0(JDK 21+)
    id("com.diffplug.spotless") version "8.10.4"
    id("net.ltgt.errorprone") version "5.1.1"
    // TESTING §12 변이 테스트(공식 확인 2026-10-14): gradle-pitest-plugin 1.19.0(plugins.gradle.org, Gradle 8.14·9.0 호환, 기본 PIT 1.22.1),
    // pitest-junit5-plugin 1.2.3(Maven Central). 별도 실행(`check`에 넣지 않음) — 정책 클래스만 대상이라 PR 라벨·수동 실행으로 돌린다(.github/workflows/backend.yml).
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "io.scos"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Jackson 3(tools.jackson) — 유닛 JSON 적재·MCP 응답 직렬화
    implementation("org.springframework.boot:spring-boot-starter-json")
    // 설정 검증(@ConfigurationProperties + @Validated, ADR-0009, 리뷰 I-7)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // MCP Java SDK 직접(ADR-0010 M2, E-7). 버전 확인 2026-10-13: mcp-bom 2.0.1
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    // 재정렬 캐시(DESIGN-PATTERNS §12, Boot BOM 관리 버전)
    implementation("com.github.ben-manes.caffeine:caffeine")
    // P4: 토큰 계산기(ADR-0019, o200k_base) — TS js-tiktoken과 같은 랭크
    implementation("com.knuddels:jtokkit:1.1.0")
    // P4: RestClient(Slack 어댑터, ADR-0018) — starter 없이 spring-web만(BOM 버전)
    implementation("org.springframework:spring-web")
    // P5: 지표(ADR-0009·0020 §3) — Micrometer 코어(BOM 버전). HTTP 노출은 daemon 프로필의 Actuator(아래)
    implementation("io.micrometer:micrometer-core")
    // P5(ADR-0020 §3): daemon 프로필만 Actuator health/readiness/metrics를 로컬 HTTP로 노출 — 다른 명령은 WebApplicationType.NONE
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    runtimeOnly("org.postgresql:postgresql")
    // blob GC 경합 번역에 서버 오류의 제약 이름이 필요하다(collection.postgres.BlobReferences) — 어댑터 패키지만 쓴다
    compileOnly("org.postgresql:postgresql")
    // BlobReferences 단위 테스트가 PSQLException·ServerErrorMessage를 만든다
    testImplementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.0.5(Boot BOM 관리) — 2.x에서 모듈 아티팩트가 testcontainers-<module>로 바뀌었다
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // ArchUnit 1.5.1(archunit.org getting-started) — 모듈 경계 규칙(PublicBoundaryRulesTest)과 제품 의존 규칙(ArchitectureRulesTest)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    // 조건 대기(TESTING §7): Boot BOM이 관리하는 버전(4.3.0). support.ChildJvm 한 곳에서만 쓴다
    testImplementation("org.awaitility:awaitility")
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.28.0") // 1.37.0은 Spotless 8.10.4에서 InvocationTargetException(단독 실행은 정상) — 2026-10-13 확인
    }
    kotlinGradle { target("*.gradle.kts") }
}

tasks.withType<JavaCompile>().configureEach {
    // Error Prone 2.46+ on JDK 21: 타입 애노테이션 기호 플래그 필요(release notes 2.46.0)
    options.compilerArgs.add("-XDaddTypeAnnotationsToSymbol=true")
    options.errorprone.disableWarningsInGeneratedCode = true
}

// 하네스 대역(카세트 임베더·고정 시각, ADR-0017·리뷰 I-6)은 제품 JAR 밖의 별도 소스셋·JAR이다. 하네스가 `-Dloader.path`로만 얹는다.
sourceSets {
    create("harness") {
        java.srcDir("src/harness/java")
        compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}
val harnessJar by tasks.registering(Jar::class) {
    description = "하네스 전용 어댑터 JAR(scos-harness-adapters.jar) — 제품 bootJar에 포함되지 않는다"
    group = "build"
    archiveFileName = "scos-harness-adapters.jar"
    from(sourceSets["harness"].output)
}
dependencies {
    // 하네스 대역의 슬라이스 테스트(ConfigurationValidationTest)만 테스트 클래스패스에 얹는다 — 제품 main 클래스패스에는 없다
    testImplementation(sourceSets["harness"].output)
}

// 테스트 병렬(TESTING §6.3): 포크마다 JVM이 따로라 정적 상태·Testcontainers 컨테이너(PostgresSupport)가 포크마다 하나다.
// fastTest는 코어 절반까지(최대 6), processTest는 코어 1/4(최대 4 — 스크립트가 CPU를 많이 쓴다. 16코어에서 3포크 54s·4포크 45s·6포크 49s, 임계 경로는
// RollbackScriptTest 약 37s, 2026-10-18 측정), dbTest는 포크마다 PostgreSQL 컨테이너 하나라 코어 1/4(최대 3 — 4는 더 빠르지 않았다, 2026-10-18 측정).
// 순서 의존을 의심할 때는 `-Pscos.test.forks=1 -Pscos.dbTest.forks=1 -Pscos.test.inJvmParallel=false`로 예전처럼 한 JVM에서 차례로 돌린다
// (scos.test.forks는 fastTest·processTest 둘 다에 쓴다).
val cores = Runtime.getRuntime().availableProcessors()
val testForks = providers.gradleProperty("scos.test.forks").map(String::toInt).getOrElse((cores / 2).coerceIn(1, 6))
val processTestForks = providers.gradleProperty("scos.test.forks").map(String::toInt).getOrElse((cores / 4).coerceIn(1, 4))
val dbTestForks = providers.gradleProperty("scos.dbTest.forks").map(String::toInt).getOrElse((cores / 4).coerceIn(1, 3))

// 테스트 층(TESTING §6.4): 실행 비용과 외부 자원으로 나눈다. fastTest(JVM만) · processTest(@Tag("process") — bash·자식 JVM) ·
// dbTest(@Tag("db") — Docker, db+process 포함) · perfTest(@Tag("perf") — 게이트 밖). 태그는 TestCodeRulesTest가 강제한다.
val fastTest by tasks.registering(Test::class) {
    description = "빠른 층: JVM만 쓰는 테스트(process·db·perf 태그 제외) — 작업하는 동안 돌린다"
    useJUnitPlatform { excludeTags("db", "process", "perf") }
    maxParallelForks = testForks
    // 포크 안에서도 클래스끼리 동시에 돈다(메서드는 클래스 안에서 차례로). JVM 전역 상태를 바꾸는 클래스는 @Isolated·@ResourceLock(TESTING §6.3).
    // processTest·dbTest는 켜지 않는다(스크립트가 CPU를 먼저 채운다·포크마다 DB 하나를 TRUNCATE로 나눠 쓴다, §6.1). 속성 파일이 아니라 여기 둔다 — 같은 리소스를 dbTest·PIT도 읽는다.
    systemProperty("junit.jupiter.execution.parallel.enabled", providers.gradleProperty("scos.test.inJvmParallel").getOrElse("true"))
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    // 포크당 4 스레드: 8과 벽시계 차이가 없었다(CPU가 먼저 찬다, 2026-10-18 측정)
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", "4")
    // 1초를 넘는 클래스는 경고만 한다(실패시키지 않는다) — 빠른 층이 느려지면 줄이거나 다른 층으로 옮긴다(TESTING §6.4). @Nested는 바깥 클래스에 합친다.
    val xmlDir = reports.junitXml.outputLocation
    doLast {
        val suiteTime = Regex("""<testsuite [^>]*\btime="([0-9.]+)"""")
        val slow = xmlDir.get().asFile.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }.orEmpty()
            .groupBy { it.name.removePrefix("TEST-").removeSuffix(".xml").substringBefore('$') }
            .mapValues { (_, files) -> files.sumOf { f -> f.useLines { lines -> lines.firstNotNullOfOrNull { suiteTime.find(it)?.groupValues?.get(1)?.toDouble() } } ?: 0.0 } }
            .filterValues { it > 1.0 }
        if (slow.isNotEmpty())
            logger.warn("fastTest: 1초 넘는 클래스 ${slow.size}개(경고, TESTING §6.4)\n" +
                slow.entries.sortedByDescending { it.value }.joinToString("\n") { "  %5.1fs %s".format(it.value, it.key) })
    }
}

val processTest by tasks.registering(Test::class) {
    description = "프로세스 층: 셸 스크립트(ScriptHarness)·자식 JVM·mkfifo를 쓰는 테스트(@Tag(\"process\"), db·perf 제외)"
    useJUnitPlatform { includeTags("process"); excludeTags("db", "perf") }
    maxParallelForks = processTestForks
}

// test = fast + process("Docker 없이 도는 전부"). 두 층은 포크 수·포크 안 병렬이 달라 한 Test 작업에 섞지 않고, test는 둘을 묶기만 한다
// (테스트를 직접 돌리지 않는다). 하나만 고르려면 fastTest·processTest에 --tests를 준다.
tasks.test {
    dependsOn(fastTest, processTest)
    testClassesDirs = files()
}
if (gradle.startParameter.taskNames.zipWithNext().any { (task, option) -> task in setOf("test", ":test") && option.startsWith("--tests") })
    throw GradleException("test는 fastTest·processTest를 묶기만 한다 — --tests는 fastTest·processTest·dbTest에 준다(TESTING §6.4)")

// fastTest·processTest 공통: 같은 소스셋·fixture·상한(Docker가 필요한 테스트(@Tag("db"))는 dbTest로 분리한다. check는 둘 다 요구한다 — ADR-0015: Docker 없으면 실패, skip 아님)
listOf(fastTest, processTest).forEach { it.configure {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // 언어 중립 결정론 fixture(하네스 P1) — TextDeterminismTest·SearchDeterminismTest·WireDeterminismTest가 소비한다
    systemProperty("scos.fixtures.dir", layout.projectDirectory.dir("../harness/fixtures/p1").asFile.absolutePath)
    // P4 수집·투영 fixture(토큰 수·Slack 데이터셋·기대 유닛, TS 생성) — ADR-0018 §7
    systemProperty("scos.fixtures.p4.dir", layout.projectDirectory.dir("../harness/fixtures/p4").asFile.absolutePath)
    // F5 Jira fixture(가짜 Jira 데이터셋·기대 원본·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.jira.dir", layout.projectDirectory.dir("../harness/fixtures/jira").asFile.absolutePath)
    // F8 Tiro 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.tiro.dir", layout.projectDirectory.dir("../harness/fixtures/tiro").asFile.absolutePath)
    // F7 Google Drive 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.google-drive.dir", layout.projectDirectory.dir("../harness/fixtures/google-drive").asFile.absolutePath)
    // F6 Notion 수집 fixture(가짜 Notion 데이터셋·기대 원본·장애·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.notion.dir", layout.projectDirectory.dir("../harness/fixtures/notion").asFile.absolutePath)
    // F10 운영 로그 형식 fixture(질의 로그·JSON 로그 줄, TS 생성)
    systemProperty("scos.fixtures.ops.dir", layout.projectDirectory.dir("../harness/fixtures/ops").asFile.absolutePath)
    // fixture를 다시 생성하면 테스트도 다시 돈다(시스템 속성은 경로만이라 내용 변경을 보지 못한다)
    inputs.dir("../harness/fixtures").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    // F10 T10.4 운영 래퍼 스크립트·launchd 템플릿(ScriptHarness가 실제 /bin/bash로 실행) — 스크립트를 바꾸면 테스트도 다시 돈다
    systemProperty("scos.deploy.dir", layout.projectDirectory.dir("../deploy/java").asFile.absolutePath)
    inputs.dir("../deploy/java").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("deployScripts")
    // 사내 운영 문서(OperationsDocCommandsTest·OperationsDocPinTest, 사내 오버레이) — 문서를 바꾸면 테스트도 다시 돈다.
    // 공개 내보내기에는 문서도 테스트도 없다(D11). inputs.dir은 optional()이어도 없는 디렉터리를 검증 오류로 보므로 fileTree로 둔다(없으면 빈 입력)
    inputs.files(fileTree("../docs/operations")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("operationsDocs")
    // 공개 데모(DemoCorpusTest가 마크다운 원본과 파생 파일을, PostgresVersionPinTest가 compose.yaml의 이미지 digest를 대조한다) — 바꾸면 테스트도 다시 돈다
    systemProperty("scos.demo.dir", layout.projectDirectory.dir("../demo").asFile.absolutePath)
    inputs.dir("../demo").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoCorpus")
    // 멈춘 테스트는 스위트를 붙잡지 않고 그 테스트만 실패한다(TESTING §7). 단위 테스트의 가장 긴 게이트 상한(10s)보다 넉넉하게.
    systemProperty("junit.jupiter.execution.timeout.default", "60 s")
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
} }

val dbTest by tasks.registering(Test::class) {
    description = "PostgreSQL(Testcontainers) 계약·통합·장애 테스트"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // perf(측정 프로브, 게이트 아님)는 perfTest로만
    useJUnitPlatform { includeTags("db"); excludeTags("perf") }
    maxParallelForks = dbTestForks
    // 순서 힌트일 뿐이다 — 구성 캐시(gradle.properties)에서는 test와 동시에 돈다(Gradle은 병렬 실행에서 shouldRunAfter를 무시한다)
    shouldRunAfter(tasks.test)
    // 테스트 하나가 멈추면 스위트가 아니라 그 테스트가 실패한다(TESTING §7). 경합·kill 테스트의 상한보다 넉넉하게.
    systemProperty("junit.jupiter.execution.timeout.default", "240 s")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("scos.java.home", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    systemProperty("scos.fixtures.p4.dir", layout.projectDirectory.dir("../harness/fixtures/p4").asFile.absolutePath)
    systemProperty("scos.fixtures.jira.dir", layout.projectDirectory.dir("../harness/fixtures/jira").asFile.absolutePath)
    // F8 Tiro 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.tiro.dir", layout.projectDirectory.dir("../harness/fixtures/tiro").asFile.absolutePath)
    // F7 Google Drive 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.google-drive.dir", layout.projectDirectory.dir("../harness/fixtures/google-drive").asFile.absolutePath)
    // F6 Notion 수집 fixture(가짜 Notion 데이터셋·기대 원본·장애·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.notion.dir", layout.projectDirectory.dir("../harness/fixtures/notion").asFile.absolutePath)
    inputs.dir("../harness/fixtures").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    // 공개 데모 코퍼스 적재 테스트(DemoCorpusLoadTest)
    systemProperty("scos.demo.dir", layout.projectDirectory.dir("../demo").asFile.absolutePath)
    inputs.dir("../demo").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoCorpus")
    // F10 T10.1 백업·복원 리허설(BackupRestoreRehearsalTest)이 실제 스크립트를 실행한다
    systemProperty("scos.deploy.dir", layout.projectDirectory.dir("../deploy/java").asFile.absolutePath)
    inputs.dir("../deploy/java").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("deployScripts")
    // P5 프로세스 테스트(DaemonProcessTest)는 실제 bootJar를 `java -jar`로 띄운다(ADR-0020 §7)
    dependsOn(tasks.bootJar)
    systemProperty("scos.boot.jar", layout.buildDirectory.file("libs/scos-backend.jar").get().asFile.absolutePath)
    // P3 품질 판정 세트(P-4 TS 유닛·질의) — KeywordQualityTest
    systemProperty("scos.p4.dir", layout.projectDirectory.dir("src/test/resources/keyword-quality").asFile.absolutePath)
    // Docker가 없으면 실패여야 한다(ADR-0015, TESTING §13): 건너뛴 테스트가 하나라도 있으면 태스크 실패
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null && result.skippedTestCount > 0)
                throw GradleException("dbTest skipped ${result.skippedTestCount} test(s) — db 테스트는 건너뛰지 않는다(ADR-0015)")
        }
    })
}

// 하네스(별도 Gradle 빌드 ../harness)의 자체 테스트. check(통합 게이트)가 함께 돌린다 — 하네스만 돌릴 때는 `cd harness && ./gradlew test`.
// tsTest·javaTest(TS checkout·bootJar 필요)는 넣지 않는다.
val harnessTest by tasks.registering(Exec::class) {
    description = "하네스 자체 테스트(../harness의 ./gradlew test)"
    group = "verification"
    workingDir = layout.projectDirectory.dir("../harness").asFile
    commandLine("./gradlew", "test")
}

tasks.check { dependsOn(dbTest, harnessTest) }

// 측정 프로브(@Tag("perf"), 예: IngestThroughputProbeTest — M-11 적재 처리량). check 밖, 필요할 때만. 수치는 VERIFICATION "성능".
val perfTest by tasks.registering(Test::class) {
    description = "측정 프로브(게이트 아님) — PostgreSQL(Testcontainers) 필요"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    // 규모 프로브(SearchScaleProbeTest) 크기: -Pscos.perf.units=N(기본 200,000 — 운영 약 20.7만 유닛, ts-replacement-gap G9)
    systemProperty("scos.perf.units", providers.gradleProperty("scos.perf.units").getOrElse("200000"))
    maxHeapSize = "2g"
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

// 실행 JAR: `java -jar build/libs/scos-backend.jar import|mcp …`(하네스 JavaServerLauncher가 쓴다)
// 제품 bootJar는 JarLauncher 그대로(재검토 N-3: PropertiesLauncher는 LOADER_PATH·loader.properties로 외부 클래스패스 주입 통로를 연다).
// 하네스만 `java -cp scos-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher`를 명시 호출해 어댑터 JAR을 얹는다.
tasks.bootJar { archiveFileName = "scos-backend.jar" }
springBoot { mainClass = "io.scos.server.ScosApplication" }

// 변이 테스트(TESTING §12): 순수 정책 클래스만, `test` 태스크의 테스트만(db 태그 제외 — Docker·공용 DB를 PIT 워커마다 띄울 수 없고
// 어댑터 SQL은 PIT가 변형하지 못한다). 대상은 아래 targetClasses, ratchet 임계값은 mutationThreshold·testStrengthThreshold다.
pitest {
    pitestVersion = "1.22.1"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf(
        "io.scos.engine.internal.search.*",
        "io.scos.mcp.ToolArguments", "io.scos.mcp.McpTools", "io.scos.mcp.SourceKind",
        "io.scos.collection.internal.WorkClaim", "io.scos.collection.internal.PageCommit", "io.scos.collection.internal.RunCompletion",
        "io.scos.collection.internal.ProjectionRetryPolicy", "io.scos.engine.support.call.ProviderCallExecutor", "io.scos.engine.support.call.ProviderCallPolicy", "io.scos.engine.support.call.ProviderCallMetrics", "io.scos.engine.support.call.ProviderHttp",
        "io.scos.collection.internal.MutationRules", "io.scos.collection.internal.LeaseHeartbeat", "io.scos.collection.internal.LeaseHeartbeats",
        "io.scos.collection.internal.SourceSupervisor", "io.scos.collection.internal.CollectionMetrics",
        // ADR-0028 스냅샷 축소 보호: 판정 기준·소스별 값·판정과 보고(순수, 메모리 포트 테스트가 `test`에 있다)
        "io.scos.collection.internal.SnapshotShrinkLimits", "io.scos.collection.internal.SnapshotShrinkOverrides", "io.scos.collection.internal.SnapshotShrinkGuard*",
        "io.scos.collection.internal.SourceSchedule", "io.scos.collection.internal.SourceAdapters", "io.scos.collection.internal.SourceRegistrar", "io.scos.collection.internal.SourceStartupCheck", "io.scos.collection.internal.RawContentCapture",
        "io.scos.server.SourceConfigInput", "io.scos.engine.support.call.RetryAfter", "io.scos.engine.support.text.JsJson", "io.scos.engine.support.text.JsonLineEncoder", "io.scos.engine.support.text.EventLog",
        "io.scos.mcp.JsonlQueryLog", "io.scos.server.DaemonMaintenance",
        "io.scos.collection.internal.ChangeCursor", "io.scos.collection.internal.RawCatalogCursor", "io.scos.collection.internal.BlobGarbageCollector", "io.scos.collection.internal.Maintenance", "io.scos.collection.internal.GracePeriod",
        "io.scos.collection.internal.credentials.*", "io.scos.connector.jira.*", "io.scos.connector.googledrive.*", "io.scos.engine.support.text.JsStrings",
        // 단계 3 T7: 옛 collection.jira.* glob이 덮던 Jira 조립(boot로 옮겼다)도 그대로 대상에 둔다
        "io.scos.boot.JiraCollectionConfiguration*", "io.scos.boot.JiraProjectionConfiguration*", "io.scos.collection.internal.projection.Projectors",
        // F8 Tiro: 조립(TiroCollectionConfiguration)은 제외 — Spring 구성(TESTING §12)
        "io.scos.connector.tiro.TiroAdapter*", "io.scos.connector.tiro.internal.TiroApiClient", "io.scos.connector.tiro.internal.TiroApiException",
        "io.scos.connector.tiro.internal.TiroCheckpoint", "io.scos.connector.tiro.internal.TiroConfiguration*", "io.scos.connector.tiro.internal.TiroResponses*",
        "io.scos.connector.tiro.internal.CursorWalk", "io.scos.connector.tiro.TiroProjector",
        // F6 Notion: 조립(Notion*Configuration — Spring 구성)은 excludedClasses로 뺀다(TESTING §12)
        "io.scos.connector.notion.*",
        "io.scos.collection.internal.projection.ProjectionMetrics", "io.scos.server.CollectionDaemon", "io.scos.server.DaemonObserver", "io.scos.server.DaemonGauges", "io.scos.server.DaemonHealthIndicator*",
        "io.scos.store.postgres.internal.Bigrams", "io.scos.store.postgres.KeywordPlan*", "io.scos.collection.internal.postgres.BlobReferences", "io.scos.engine.support.text.Text", "io.scos.engine.support.text.Utf8",
        // 커넥터 SPI 정화(공개 배포 단계 3 T1): 헤더 값 검사(ProviderHttp가 위임한다)·목록 필드
        "io.scos.spi.connector.HeaderValues", "io.scos.spi.connector.ListingField",
        // T2: 호출 실행기·원본 캡처 계약(본문 없는 인터페이스 — 이동 뒤 이름 대응을 위해 둔다)
        "io.scos.spi.connector.CallExecutor", "io.scos.spi.connector.ContentCapture",
        // T3: 커넥터가 보는 소스(런타임 필드 없는 다섯 필드)·소스 설정
        "io.scos.spi.connector.SourceDescriptor", "io.scos.spi.connector.SourceConfig",
        // T4: 투영 동기화 계약·guard 거부 기반 예외(본문 없음 — 이동 뒤 이름 대응을 위해 둔다)
        "io.scos.engine.support.projection.SourceSync", "io.scos.engine.support.projection.WriteGuardException",
        // T5: 읽기 파사드 구현·builder(T6+7에서 search → engine)·공개 값
        "io.scos.engine.DefaultContextEngine", "io.scos.engine.ContextEngineBuilder",
        "io.scos.engine.Freshness", "io.scos.spi.store.Principal", "io.scos.spi.store.AccessScope",
        // F9 임베딩 캐시: 캐시 적중·미적중 경로(DocumentIngestor 전체는 저장 어댑터 조합이라 dbTest가 본다)·캐시 키
        "io.scos.engine.internal.projection.CachedEmbeddings*", "io.scos.spi.store.EmbeddingCache*"
    )
    excludedClasses = setOf(
        "io.scos.boot.NotionCollectionConfiguration*", "io.scos.boot.NotionProjectionConfiguration*",
        "io.scos.boot.GoogleDriveCollectionConfiguration*", "io.scos.boot.GoogleDriveProjectionConfiguration*"
    )
    targetTests = setOf("io.scos.*")
    excludedGroups = setOf("db")
    threads = 4
    // 테스트의 hang 방지 상한(게이트·합류 대기 10s)이 PIT 감시(기본 4s + 평소 시간 × 1.25)보다 먼저 실패하게 — 변이를 TIMED_OUT이 아니라
    // 단언으로 죽인다(MUTATION.md). 진짜 무한 루프 변이만 이 상한에 걸린다.
    timeoutConstInMillis = 15000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    failWhenNoMutations = true
    // ratchet(TESTING §12): 2026-10-14 P5 수정 뒤 점수 96.3%·test strength 96.6%의 내림값(MUTATION.md). 올라가면 올리고 내리지 않는다(낮추려면 ADR).
    mutationThreshold = 96
    testStrengthThreshold = 96
    // 증분 기록 — 바뀐 클래스의 변이만 다시 돈다
    historyInputLocation = layout.buildDirectory.file("pitest/history.bin")
    historyOutputLocation = layout.buildDirectory.file("pitest/history.bin")
    jvmArgs = listOf(
        // 워커 힙 상한: 기본(물리 메모리의 1/4, 이 기기 12GB)이면 메모리를 끝없이 쓰는 변이 하나가 워커를 8GB까지 키우고, 전체 빌드(test·dbTest와 겹침)
        // 중 다른 워커가 제때 뜨지 못해 RUN_ERROR가 난다(MUTATION.md "워커 메모리"). 평소 워커는 RSS p90 약 1.2GB.
        "-Xmx1536m",
        // 언어 중립 fixture(TextDeterminismTest 등)가 읽는 경로 — test 태스크와 같은 값
        "-Dscos.fixtures.dir=" + layout.projectDirectory.dir("../harness/fixtures/p1").asFile.absolutePath,
        "-Dscos.fixtures.p4.dir=" + layout.projectDirectory.dir("../harness/fixtures/p4").asFile.absolutePath,
        "-Dscos.fixtures.jira.dir=" + layout.projectDirectory.dir("../harness/fixtures/jira").asFile.absolutePath,
        "-Dscos.fixtures.tiro.dir=" + layout.projectDirectory.dir("../harness/fixtures/tiro").asFile.absolutePath,
        "-Dscos.fixtures.notion.dir=" + layout.projectDirectory.dir("../harness/fixtures/notion").asFile.absolutePath,
        "-Dscos.fixtures.ops.dir=" + layout.projectDirectory.dir("../harness/fixtures/ops").asFile.absolutePath,
        "-Dscos.fixtures.google-drive.dir=" + layout.projectDirectory.dir("../harness/fixtures/google-drive").asFile.absolutePath
    )
}

// 비단언 감지 게이트(MUTATION.md "F2 리뷰 반영"): TIMED_OUT·MEMORY_ERROR는 PIT가 "검출"로 세지만 단언이 죽인 것이 아니다. 임계값은 이것을
// 구분하지 못하므로(테스트 집합이 커져 정상 변이가 MEMORY_ERROR가 되면 생존이 조용히 검출로 바뀐다) 0이 아니면 실패한다. RUN_ERROR는 minion
// 이상 종료(환경 요인)라 경고만 한다.
tasks.named("pitest") {
    // fixture를 다시 생성하면 PIT도 다시 돈다(test·dbTest와 같은 선언). history는 클래스 기준이라 fixture만 바꿨으면 history.bin을 지운다(MUTATION.md)
    inputs.dir("../harness/fixtures").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    val report = layout.buildDirectory.file("reports/pitest/mutations.xml")
    doLast {
        val statuses = Regex("status='([A-Z_]+)'").findAll(report.get().asFile.readText())
            .map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val notByAssertion = statuses.filterKeys { it == "TIMED_OUT" || it == "MEMORY_ERROR" }
        statuses["RUN_ERROR"]?.let { logger.warn("PIT RUN_ERROR ${it}건 — minion 이상 종료(MUTATION.md), 다시 실행해 확인한다") }
        if (notByAssertion.isNotEmpty()) {
            throw GradleException("PIT 비단언 감지 $notByAssertion — 단언으로 죽이거나 무한 루프 변이를 없앤다. 테스트만 고쳤다면 history는 TIMED_OUT을 재사용하므로 build/pitest/history.bin을 지우고 다시 돌린다(MUTATION.md)")
        }
    }
}

// 데모 코퍼스 생성(demo/README.md "Editing the corpus")·가짜 공급자 프로세스(io.scos.demo, 테스트 소스셋)의 클래스패스와 Java 21 실행 파일 경로를 파일로
val writeDemoClasspath by tasks.registering {
    description = "데모 가짜 공급자 프로세스의 클래스패스를 build/demo/classpath.txt에 쓴다"
    group = "application"
    dependsOn(tasks.testClasses)
    val cp = sourceSets.test.get().runtimeClasspath
    val out = layout.buildDirectory.file("demo/classpath.txt")
    // 데모는 빌드와 같은 Java 21 toolchain으로 실행한다(PATH의 java가 다른 버전일 수 있다)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val javaOut = layout.buildDirectory.file("demo/java.txt")
    outputs.files(out, javaOut)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText(cp.asPath) }
        javaOut.get().asFile.writeText(java.get().executablePath.asFile.absolutePath)
    }
}
