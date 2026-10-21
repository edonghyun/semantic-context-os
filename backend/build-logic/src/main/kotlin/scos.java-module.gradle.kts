import net.ltgt.gradle.errorprone.errorprone
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// 모든 backend Java 모듈이 같이 쓰는 빌드 설정(공개 배포 단계 4 T2): 컴파일러 인자·Error Prone·Spotless·테스트 층.
// 모듈마다 다른 것(제품 의존, Boot 실행 JAR, 하네스 소스셋, PIT)은 각 build.gradle.kts에 둔다.
// 버전(공식 문서 확인 2026-10-13): Spring Boot 4.1.1, Gradle 9.8.1, Java 21. 플러그인 의존은 build-logic/build.gradle.kts.
plugins {
    java
    // 모듈 간 공용 테스트 코드(계약 스위트·가짜 서버·조립 도우미, 단계 4 T1) — 다른 영역의 테스트가 쓰는 테스트 클래스만 둔다
    `java-test-fixtures`
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

group = "io.scos"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Flyway·PostgreSQL 드라이버·Testcontainers 버전은 Boot BOM이 관리한다(ADR-0013·0015). errorprone 구성에는 넣지 않는다(Error Prone 의존을 BOM이 바꾸지 않게).
dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    // Gradle 9는 JUnit Platform launcher를 싣지 않는다 — Boot 플러그인이 server에만 넣던 것을 모든 모듈에 둔다(BOM 버전)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
// testFixtures는 제품 의존(BOM 포함)을 그대로 보고, 계약 스위트·Testcontainers 지원·조건 대기에 쓰는 테스트 라이브러리만 더한다
configurations.testFixturesImplementation { extendsFrom(configurations.implementation.get()) }

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.28.0") // 1.37.0은 Spotless 8.10.4에서 InvocationTargetException(단독 실행은 정상) — 2026-10-13 확인
    }
    kotlinGradle { target("*.gradle.kts") }
}

tasks.withType<JavaCompile>().configureEach {
    // Boot 플러그인이 적용된 모듈에만 들어가던 두 설정을 모든 컴파일(main·test·testFixtures·harness)에 둔다(단계 4 Review Focus 1):
    // 이름으로 바인딩하는 Spring·Jackson이 MethodParameters를 읽고, 빠지면 클래스 바이트도 달라진다
    if ("-parameters" !in options.compilerArgs) options.compilerArgs.add("-parameters")
    options.encoding = "UTF-8"
    // Error Prone 2.46+ on JDK 21: 타입 애노테이션 기호 플래그 필요(release notes 2.46.0)
    options.compilerArgs.add("-XDaddTypeAnnotationsToSymbol=true")
    options.errorprone.disableWarningsInGeneratedCode = true
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

// fixture·스크립트·데모 경로는 backend 루트 기준이다(모듈 위치와 무관)
val backendDir = rootProject.layout.projectDirectory

// 여러 모듈의 dbTest가 동시에 돌지 않게 한다(PostgresContainers)
val postgresContainers = gradle.sharedServices.registerIfAbsent("postgresContainers", PostgresContainers::class) { maxParallelUsages = 1 }
val matchedTests = gradle.sharedServices.registerIfAbsent("matchedTests", MatchedTests::class) {}
// 콜론 없는 층 이름 다음에 --tests가 오면(`fastTest --tests X`) 그 층은 모듈마다 일치가 없어도 실패하지 않고, 루트 assertTestsMatched가 전체 일치 수를 본다.
// 경로를 붙인 호출(`:fastTest --tests X`)은 지금처럼 그 작업에 일치가 없으면 실패한다.
val relaxedTiers = gradle.startParameter.taskNames.zipWithNext()
    .filter { (task, option) -> task in setOf("fastTest", "processTest", "dbTest") && option.startsWith("--tests") }
    .map { it.first }.toSet()

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
    // TestSourceRulesTest는 소스 파일을 직접 읽는다 — 주석·문자열만 바뀌어도 다시 돌게 테스트 소스 트리를 입력으로 둔다
    inputs.files(fileTree("src/test/java"), fileTree("src/testFixtures/java")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("testSources")
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

// fastTest·processTest 공통: 같은 소스셋·fixture·상한(Docker가 필요한 테스트(@Tag("db"))는 dbTest로 분리한다. check는 둘 다 요구한다 — ADR-0015: Docker 없으면 실패, skip 아님)
listOf(fastTest, processTest).forEach { it.configure {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    // 언어 중립 결정론 fixture(하네스 P1) — TextDeterminismTest·SearchDeterminismTest·WireDeterminismTest가 소비한다
    systemProperty("scos.fixtures.dir", backendDir.dir("../harness/fixtures/p1").asFile.absolutePath)
    // P4 수집·투영 fixture(토큰 수·Slack 데이터셋·기대 유닛, TS 생성) — ADR-0018 §7
    systemProperty("scos.fixtures.p4.dir", backendDir.dir("../harness/fixtures/p4").asFile.absolutePath)
    // F5 Jira fixture(가짜 Jira 데이터셋·기대 원본·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.jira.dir", backendDir.dir("../harness/fixtures/jira").asFile.absolutePath)
    // F8 Tiro 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.tiro.dir", backendDir.dir("../harness/fixtures/tiro").asFile.absolutePath)
    // F7 Google Drive 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.google-drive.dir", backendDir.dir("../harness/fixtures/google-drive").asFile.absolutePath)
    // F6 Notion 수집 fixture(가짜 Notion 데이터셋·기대 원본·장애·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.notion.dir", backendDir.dir("../harness/fixtures/notion").asFile.absolutePath)
    // F10 운영 로그 형식 fixture(질의 로그·JSON 로그 줄, TS 생성)
    systemProperty("scos.fixtures.ops.dir", backendDir.dir("../harness/fixtures/ops").asFile.absolutePath)
    // fixture를 다시 생성하면 테스트도 다시 돈다(시스템 속성은 경로만이라 내용 변경을 보지 못한다)
    inputs.dir(backendDir.dir("../harness/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    // F10 T10.4 운영 래퍼 스크립트·launchd 템플릿(ScriptHarness가 실제 /bin/bash로 실행) — 스크립트를 바꾸면 테스트도 다시 돈다
    systemProperty("scos.deploy.dir", backendDir.dir("../deploy/java").asFile.absolutePath)
    inputs.dir(backendDir.dir("../deploy/java")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("deployScripts")
    // 사내 운영 문서(OperationsDocCommandsTest·OperationsDocPinTest, 사내 오버레이) — 문서를 바꾸면 테스트도 다시 돈다.
    // 공개 내보내기에는 문서도 테스트도 없다(D11). inputs.dir은 optional()이어도 없는 디렉터리를 검증 오류로 보므로 fileTree로 둔다(없으면 빈 입력)
    inputs.files(fileTree(backendDir.dir("../docs/operations"))).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("operationsDocs")
    // 공개 데모(DemoCorpusTest가 마크다운 원본과 파생 파일을, PostgresVersionPinTest가 compose.yaml의 이미지 digest를 대조한다) — 바꾸면 테스트도 다시 돈다
    systemProperty("scos.demo.dir", backendDir.dir("../demo").asFile.absolutePath)
    inputs.dir(backendDir.dir("../demo")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoCorpus")
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
    usesService(postgresContainers)
    // 순서 힌트일 뿐이다 — 구성 캐시(gradle.properties)에서는 test와 동시에 돈다(Gradle은 병렬 실행에서 shouldRunAfter를 무시한다)
    shouldRunAfter(tasks.test)
    // 테스트 하나가 멈추면 스위트가 아니라 그 테스트가 실패한다(TESTING §7). 경합·kill 테스트의 상한보다 넉넉하게.
    systemProperty("junit.jupiter.execution.timeout.default", "240 s")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("scos.java.home", javaLauncher.get().metadata.installationPath.asFile.absolutePath)
    systemProperty("scos.fixtures.p4.dir", backendDir.dir("../harness/fixtures/p4").asFile.absolutePath)
    systemProperty("scos.fixtures.jira.dir", backendDir.dir("../harness/fixtures/jira").asFile.absolutePath)
    // F8 Tiro 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.tiro.dir", backendDir.dir("../harness/fixtures/tiro").asFile.absolutePath)
    // F7 Google Drive 수집·투영 fixture(TS 생성)
    systemProperty("scos.fixtures.google-drive.dir", backendDir.dir("../harness/fixtures/google-drive").asFile.absolutePath)
    // F6 Notion 수집 fixture(가짜 Notion 데이터셋·기대 원본·장애·클라이언트 표, TS 생성)
    systemProperty("scos.fixtures.notion.dir", backendDir.dir("../harness/fixtures/notion").asFile.absolutePath)
    inputs.dir(backendDir.dir("../harness/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    // 공개 데모 코퍼스 적재 테스트(DemoCorpusLoadTest)
    systemProperty("scos.demo.dir", backendDir.dir("../demo").asFile.absolutePath)
    inputs.dir(backendDir.dir("../demo")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("demoCorpus")
    // F10 T10.1 백업·복원 리허설(BackupRestoreRehearsalTest)이 실제 스크립트를 실행한다
    systemProperty("scos.deploy.dir", backendDir.dir("../deploy/java").asFile.absolutePath)
    inputs.dir(backendDir.dir("../deploy/java")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("deployScripts")
    // P3 품질 판정 세트(P-4 TS 유닛·질의) — KeywordQualityTest
    systemProperty("scos.p4.dir", backendDir.dir("scos-store-postgres/src/test/resources/keyword-quality").asFile.absolutePath)
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

tasks.check { dependsOn(dbTest) }

// 측정 프로브(@Tag("perf"), 예: IngestThroughputProbeTest — M-11 적재 처리량). check 밖, 필요할 때만. 수치는 VERIFICATION "성능".
val perfTest by tasks.registering(Test::class) {
    description = "측정 프로브(게이트 아님) — PostgreSQL(Testcontainers) 필요"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    usesService(postgresContainers)
    // 규모 프로브(SearchScaleProbeTest) 크기: -Pscos.perf.units=N(기본 200,000 — 운영 약 20.7만 유닛, ts-replacement-gap G9)
    systemProperty("scos.perf.units", providers.gradleProperty("scos.perf.units").getOrElse("200000"))
    maxHeapSize = "2g"
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

listOf(fastTest, processTest, dbTest, perfTest).forEach { it.configure {
    // 모듈마다 테스트가 없는 층이 있다 — 층 작업은 발견한 테스트가 없어도 실패하지 않는다(Gradle 9 기본값은 실패)
    failOnNoDiscoveredTests = false
    if (name in relaxedTiers) {
        filter.isFailOnNoMatchingTests = false
        // 건너뛴 작업은 afterSuite가 불리지 않아 일치 수가 0으로 남는다 — 이렇게 부를 때는 늘 다시 돌린다
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        usesService(matchedTests)
        val matched = matchedTests
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun beforeTest(testDescriptor: TestDescriptor) {}
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.parent == null) matched.get().add(result.testCount)
            }
        })
        finalizedBy(":assertTestsMatched")
    }
} }

// 모듈을 가로지르는 검사(verification의 ArchUnit 규칙·PIT 집계)가 쓰는 test 출력: 클래스·리소스 디렉터리와 그 의존(testImplementation·testRuntimeOnly).
// 받는 쪽은 `testImplementation(project(":<p>", "testOutputElements"))`
configurations.consumable("testOutputElements") {
    extendsFrom(configurations.testImplementation.get(), configurations.testRuntimeOnly.get())
    // main(runtimeElements)과 같은 클래스패스에 함께 들어가므로 기본 capability(io.scos:<p>)와 겹치지 않게 한다
    outgoing.capability("io.scos:${project.name}-test-output:unspecified")
    outgoing.artifact(tasks.compileTestJava.flatMap { it.destinationDirectory }) { type = "directory" }
    outgoing.artifact(tasks.processTestResources.map { it.destinationDir }) { type = "directory"; builtBy(tasks.processTestResources) }
}

// 테스트는 backend 루트에서 돈다(상대 경로 `src/…`·`../harness`를 쓰는 테스트가 있다) — 모듈 위치와 무관하게 같은 값
tasks.withType<Test>().configureEach { workingDir = rootProject.projectDir }
