// 모듈을 가로지르는 검사(배포하지 않는다): ArchUnit 규칙(ArchitectureRulesTest·PublicBoundaryRulesTest·TestCodeRulesTest)과 테스트 소스 규칙
// (TestSourceRulesTest), 전 모듈 집계 변이 테스트(PIT). 다른 모듈 테스트가 처음 죽이는 변이가 있어 PIT는 모듈별로 나누지 않는다(단계 4 Review Focus 4).
plugins {
    id("scos.java-module")
    // TESTING §12 변이 테스트: pitest-junit5-plugin 1.2.3(Maven Central). 별도 실행(`check`에 넣지 않음) — 정책 클래스만 대상이라 PR 라벨·수동 실행으로 돌린다(.github/workflows/backend.yml).
    id("scos.pitest")
}

// 규칙과 PIT가 보는 모듈(제품 14개 + scos-test-support). testFixtures가 있는 모듈은 그 출력도 본다
val products = listOf(
    "scos-spi", "scos-engine", "scos-store-memory", "scos-store-postgres", "scos-collection",
    "scos-connector-slack", "scos-connector-notion", "scos-connector-jira", "scos-connector-google-drive", "scos-connector-tiro",
    "scos-provider-gemini", "scos-mcp", "scos-spring-boot-starter", "scos-server",
)
val withFixtures = setOf(
    "scos-spi", "scos-engine", "scos-store-postgres", "scos-collection",
    "scos-connector-slack", "scos-connector-notion", "scos-connector-jira", "scos-connector-google-drive", "scos-connector-tiro",
)

dependencies {
    // 클래스패스: 전 모듈 main(jar)·testFixtures(jar)·test 출력(디렉터리, testOutputElements — scos-server는 하네스 출력까지)·test-support
    products.forEach {
        testImplementation(project(":$it"))
        testImplementation(project(":$it", "testOutputElements"))
        if (it in withFixtures) testImplementation(testFixtures(project(":$it")))
    }
    testImplementation(project(":scos-test-support"))
    testImplementation(project(":scos-test-support", "testOutputElements"))
    // ArchUnit 1.5.1(archunit.org getting-started) — 모듈 경계 규칙(PublicBoundaryRulesTest)과 제품 의존 규칙(ArchitectureRulesTest)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.springframework:spring-tx")
}

// TestSourceRulesTest는 모든 모듈의 테스트 소스를 직접 읽는다 — 주석·문자열만 바뀌어도 다시 돌게 소스 트리를 입력으로 둔다
tasks.named<Test>("fastTest") {
    val backend = rootProject.layout.projectDirectory
    inputs.files(
        products.flatMap { listOf(fileTree(backend.dir("$it/src/test/java")), fileTree(backend.dir("$it/src/testFixtures/java"))) } +
            listOf(fileTree(backend.dir("scos-test-support/src/main/java")), fileTree(backend.dir("scos-test-support/src/test/java")))
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("moduleTestSources")
}

// PIT가 변이하는 코드: 전 모듈 main 클래스 디렉터리(jar가 아니라 classes 변형)와 그 소스
val mutableCode = configurations.dependencyScope("mutableCode")
fun mainOutput(name: String, elements: String) = configurations.resolvable(name) {
    extendsFrom(mutableCode.get())
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(elements))
    }
}
val mutableClasses = mainOutput("mutableClasses", LibraryElements.CLASSES)
val mutableResources = mainOutput("mutableResources", LibraryElements.RESOURCES)
dependencies { products.forEach { mutableCode(project(":$it")) } }

// 같은 main을 담은 제품 jar(<모듈>/build/libs/<모듈>.jar). testFixtures jar(<모듈>-test-fixtures.jar)와 scos-test-support.jar는 남긴다
val productJars = products.map { rootProject.layout.projectDirectory.file("$it/build/libs/$it.jar").asFile }.toSet()

tasks.pitest {
    mutableCodePaths.setFrom(mutableClasses)
    sourceDirs.setFrom(products.map { rootProject.layout.projectDirectory.dir("$it/src/main/java") })
    // PIT는 classPath 가운데 mutableCodePaths와 같은 루트만 변이 대상으로 고르고, 테스트는 디렉터리 루트에서 찾는다. 플러그인 기본값(test
    // runtimeClasspath + 이 프로젝트 main)에는 다른 모듈 main이 jar로만 있어 대상이 0개가 된다 — main 클래스·리소스 디렉터리를 앞에 두고,
    // 같은 클래스가 두 번 들어오지 않게 제품 jar는 뺀다. test 출력은 testOutputElements로 이미 디렉터리다
    val jars = productJars // 스크립트 객체를 붙잡지 않게 지역 값으로(구성 캐시)
    val testRuntime = sourceSets.test.map { set -> set.runtimeClasspath.filter { it !in jars } }
    additionalClasspath.setFrom(mutableClasses, mutableResources, testRuntime)
}

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
    // 증분 기록 — 바뀐 클래스의 변이만 다시 돈다(verification/build/pitest/history.bin)
    historyInputLocation = layout.buildDirectory.file("pitest/history.bin")
    historyOutputLocation = layout.buildDirectory.file("pitest/history.bin")
    jvmArgs = listOf(
        // 워커 힙 상한: 기본(물리 메모리의 1/4, 이 기기 12GB)이면 메모리를 끝없이 쓰는 변이 하나가 워커를 8GB까지 키우고, 전체 빌드(test·dbTest와 겹침)
        // 중 다른 워커가 제때 뜨지 못해 RUN_ERROR가 난다(MUTATION.md "워커 메모리"). 평소 워커는 RSS p90 약 1.2GB.
        "-Xmx1536m",
        // 언어 중립 fixture(TextDeterminismTest 등)가 읽는 경로 — test 태스크와 같은 값
        "-Dscos.fixtures.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/p1").asFile.absolutePath,
        "-Dscos.fixtures.p4.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/p4").asFile.absolutePath,
        "-Dscos.fixtures.jira.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/jira").asFile.absolutePath,
        "-Dscos.fixtures.tiro.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/tiro").asFile.absolutePath,
        "-Dscos.fixtures.notion.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/notion").asFile.absolutePath,
        "-Dscos.fixtures.ops.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/ops").asFile.absolutePath,
        "-Dscos.fixtures.google-drive.dir=" + rootProject.layout.projectDirectory.dir("../harness/fixtures/google-drive").asFile.absolutePath
    )
}

// 비단언 감지 게이트(MUTATION.md "F2 리뷰 반영"): TIMED_OUT·MEMORY_ERROR는 PIT가 "검출"로 세지만 단언이 죽인 것이 아니다. 임계값은 이것을
// 구분하지 못하므로(테스트 집합이 커져 정상 변이가 MEMORY_ERROR가 되면 생존이 조용히 검출로 바뀐다) 0이 아니면 실패한다. RUN_ERROR는 minion
// 이상 종료(환경 요인)라 경고만 한다.
tasks.named("pitest") {
    // fixture를 다시 생성하면 PIT도 다시 돈다(test·dbTest와 같은 선언). history는 클래스 기준이라 fixture만 바꿨으면 history.bin을 지운다(MUTATION.md)
    inputs.dir(rootProject.layout.projectDirectory.dir("../harness/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    val report = layout.buildDirectory.file("reports/pitest/mutations.xml")
    doLast {
        val statuses = Regex("status='([A-Z_]+)'").findAll(report.get().asFile.readText())
            .map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val notByAssertion = statuses.filterKeys { it == "TIMED_OUT" || it == "MEMORY_ERROR" }
        statuses["RUN_ERROR"]?.let { logger.warn("PIT RUN_ERROR ${it}건 — minion 이상 종료(MUTATION.md), 다시 실행해 확인한다") }
        if (notByAssertion.isNotEmpty()) {
            throw GradleException("PIT 비단언 감지 $notByAssertion — 단언으로 죽이거나 무한 루프 변이를 없앤다. 테스트만 고쳤다면 history는 TIMED_OUT을 재사용하므로 verification/build/pitest/history.bin을 지우고 다시 돌린다(MUTATION.md)")
        }
    }
}

