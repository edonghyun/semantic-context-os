// 모듈을 가로지르는 검사(배포하지 않는다): ArchUnit 규칙(ArchitectureRulesTest·PublicBoundaryRulesTest·TestCodeRulesTest·ConventionRulesTest)과
// 소스 규칙(TestSourceRulesTest·MethodLengthRulesTest), 전 모듈 집계 변이 테스트(PIT). 다른 모듈 테스트가 처음 죽이는 변이가 있어 PIT는 모듈별로 나누지 않는다(단계 4 Review Focus 4).
plugins {
    id("scos.java-module")
    // TESTING §12 변이 테스트: pitest-junit5-plugin 1.2.3(Maven Central). `check` 밖의 명시 태스크 `:verification:pitest`다(파일 끝). CI는 별도 job으로 돈다(.github/workflows/backend.yml).
    id("scos.pitest")
}

// 규칙과 PIT가 보는 모듈(제품 전부 — 목록은 build-logic의 ScosModules + scos-test-support). testFixtures가 있는 모듈은 그 출력도 본다
val products = ScosModules.products
val withFixtures = products.filter { rootProject.layout.projectDirectory.dir("$it/src/testFixtures/java").asFile.isDirectory }.toSet()

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
    // 메서드 길이 규칙(MethodLengthRulesTest)은 제품 main 소스를 직접 읽는다 — 주석·빈 줄만 바뀌어도 다시 돈다
    inputs.files(products.map { fileTree(backend.dir("$it/src/main/java")) }).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("productMainSources")
    // 카탈로그 대조 게이트(EventCatalogTest)는 로깅 규약 문서의 §9 표를 읽는다 — 표만 바뀌어도 다시 돈다
    inputs.file(backend.file("../docs/conventions/logging.md")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("loggingConventions")
    // 고정 목록 갱신(TESTING §13.2): -Pscos.freeze.update=true일 때만 ArchUnit 고정 저장소에서 고친 위반을 지우고 메서드 길이 목록의 줄어든 항목을 낮춘다.
    // 새 위반은 이 모드에서도 실패한다(refreeze는 늘 꺼져 있다)
    val freezeUpdate = providers.gradleProperty("scos.freeze.update").getOrElse("false")
    inputs.property("freezeUpdate", freezeUpdate)
    systemProperty("archunit.freeze.store.default.allowStoreUpdate", freezeUpdate)
    systemProperty("scos.freeze.update", freezeUpdate)
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

// 같은 main을 담은 제품 jar: test 런타임 클래스패스의 해석 결과 가운데 owner가 제품이고 capability가 기본(io.scos:<모듈>)이고 jar인 것.
// 경로(<모듈>/build/libs/<모듈>.jar)가 아니라 해석 결과로 고른다 — jar 이름이 바뀌어도 빠지지 않는다. testFixtures jar(capability
// io.scos:<모듈>-test-fixtures)와 scos-test-support.jar는 남는다
// (해석 결과의 파일은 그 jar 작업이 끝난 뒤에만 읽을 수 있다 — 읽는 작업은 클래스패스를 입력으로 둔다)
val productMainJars: Provider<Map<File, String>> = configurations.testRuntimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
    val products = ScosModules.products // 스크립트 객체를 붙잡지 않게(구성 캐시)
    artifacts.mapNotNull { a ->
        val owner = (a.id.componentIdentifier as? ProjectComponentIdentifier)?.projectName ?: return@mapNotNull null
        val elements = a.variant.attributes.keySet().firstOrNull { it.name == LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name }
            ?.let { a.variant.attributes.getAttribute(it)?.toString() }
        val defaultCapability = a.variant.capabilities.all { it.name == owner }
        if (owner in products && defaultCapability && elements == LibraryElements.JAR) a.file to owner else null
    }.toMap()
}

tasks.pitest {
    mutableCodePaths.setFrom(mutableClasses)
    sourceDirs.setFrom(products.map { rootProject.layout.projectDirectory.dir("$it/src/main/java") })
    // PIT는 classPath 가운데 mutableCodePaths와 같은 루트만 변이 대상으로 고르고, 테스트는 디렉터리 루트에서 찾는다. 플러그인 기본값(test
    // runtimeClasspath + 이 프로젝트 main)에는 다른 모듈 main이 jar로만 있어 대상이 0개가 된다 — main 클래스·리소스 디렉터리를 앞에 두고,
    // 같은 클래스가 두 번 들어오지 않게 제품 jar는 뺀다. test 출력은 testOutputElements로 이미 디렉터리다
    val jars = productMainJars // 스크립트 객체를 붙잡지 않게 지역 값으로(구성 캐시)
    val testRuntime = sourceSets.test.map { set -> set.runtimeClasspath.filter { it !in jars.get() } }
    additionalClasspath.setFrom(mutableClasses, mutableResources, testRuntime)
}

// PIT 클래스패스 검사(단계 5 T1): 제품 main은 클래스 디렉터리로만 들어가야 한다 — jar가 남으면 같은 클래스가 두 번 들어오고, 디렉터리가 빠지면
// 그 모듈의 변이가 조용히 0개가 된다. 어긋난 모듈 이름을 담아 실패한다
val verifyPitClasspath = tasks.register("verifyPitClasspath") {
    description = "PIT 클래스패스에 제품 main jar가 없고 모든 제품 main 클래스 디렉터리가 변이 대상에 있는지 확인한다"
    group = "verification"
    val pit = tasks.pitest.get()
    val additional: FileCollection = pit.additionalClasspath
    val mutable: FileCollection = pit.mutableCodePaths
    val classOwners = mutableClasses.flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
        artifacts.mapNotNull { a -> (a.id.componentIdentifier as? ProjectComponentIdentifier)?.let { a.file to it.projectName } }.toMap()
    }
    val mainJars = productMainJars
    val expected = products
    inputs.files(additional, mutable).withPropertyName("pitClasspath")
    doLast {
        val problems = mutableListOf<String>()
        val jarsOnClasspath = additional.files.mapNotNull { mainJars.get()[it] }.toSortedSet()
        jarsOnClasspath.forEach { problems += "$it: PIT additionalClasspath에 제품 main jar가 있다" }
        val mutableFiles = mutable.files
        val covered = classOwners.get().filterKeys { it in mutableFiles }.values.toSet()
        (expected - covered).sorted().forEach { problems += "$it: main 클래스 디렉터리가 PIT mutableCodePaths에 없다" }
        if (problems.isNotEmpty()) throw GradleException("PIT 클래스패스 검사 실패:\n" + problems.joinToString("\n") { "  - $it" })
    }
}
tasks.check { dependsOn(verifyPitClasspath) }

// PIT 대상 검사(MUTATION.md "대상과 제외"): 제품 main 클래스(중첩 포함)는 모두 targetClasses glob에 걸리거나 excludedClasses로 명시적으로 빠져야
// 한다 — 새 클래스·패키지가 변이 대상에서 조용히 빠지지 않게. 아무 클래스에도 걸리지 않는 glob(이름이 바뀌어 낡은 항목)도 실패한다.
// glob은 PIT 규칙대로 맞춘다(`*` = 임의 문자열, `?` = 한 글자, 중첩 클래스는 `바깥$안` 이름)
val verifyPitTargets = tasks.register("verifyPitTargets") {
    description = "제품 main 클래스가 모두 PIT targetClasses나 excludedClasses에 걸리고, 두 목록의 glob이 모두 클래스에 걸리는지 확인한다"
    group = "verification"
    val classDirs: FileCollection = files(mutableClasses)
    val targets = pitest.targetClasses
    val excluded = pitest.excludedClasses
    inputs.files(classDirs).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("productMainClasses")
    inputs.property("targetClasses", targets)
    inputs.property("excludedClasses", excluded)
    doLast {
        fun glob(pattern: String) = Regex(pattern.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape("$it") } }.joinToString(""))
        val classes = classDirs.files.flatMap { dir ->
            dir.walk().filter { it.isFile && it.extension == "class" && it.nameWithoutExtension !in setOf("package-info", "module-info") }
                .map { it.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }.toList()
        }.sorted()
        val targetGlobs = targets.get().associateWith(::glob)
        val excludedGlobs = excluded.get().associateWith(::glob)
        val problems = mutableListOf<String>()
        classes.filter { c -> (targetGlobs.values + excludedGlobs.values).none { it.matches(c) } }
            .forEach { problems += "$it: targetClasses에도 excludedClasses에도 걸리지 않는다" }
        targetGlobs.filterValues { r -> classes.none(r::matches) }.keys.forEach { problems += "targetClasses \"$it\": 걸리는 제품 main 클래스가 없다" }
        excludedGlobs.filterValues { r -> classes.none(r::matches) }.keys.forEach { problems += "excludedClasses \"$it\": 걸리는 제품 main 클래스가 없다" }
        if (problems.isNotEmpty()) {
            throw GradleException("PIT 대상 검사 실패 — 새 클래스는 targetClasses glob에 들게 하거나 excludedClasses에 이유 주석과 함께 둔다(MUTATION.md):\n" +
                problems.joinToString("\n") { "  - $it" })
        }
    }
}
tasks.check { dependsOn(verifyPitTargets) }

// 변이 테스트(TESTING §12): `test` 태스크의 테스트만(db 태그 제외 — Docker·공용 DB를 PIT 워커마다 띄울 수 없고 어댑터 SQL은 PIT가 변형하지
// 못한다). 대상은 제품 main 전부(`io.scos.*`)이고, 빼는 것은 아래 excludedClasses에 줄마다 이유와 함께 둔다 — 새 클래스는 기본으로 대상이 된다
// (verifyPitTargets가 두 목록과 제품 클래스를 대조한다). ratchet 임계값은 mutationThreshold·testStrengthThreshold다.
pitest {
    pitestVersion = "1.22.1"
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("io.scos.*")
    excludedClasses = setOf(
        // SQL 어댑터: 본체가 SQL 문자열이라 PIT가 의미 있게 변이하지 못하고, db 태그 테스트(실제 Postgres)만 덮는다
        "io.scos.store.postgres.Postgres*", "io.scos.collection.internal.postgres.Postgres*",
        // 어댑터의 배열 바인드·내용 열·blob 쓰기 보조: JDBC 문장·결과 행을 다루며 db 태그 테스트만 덮는다
        "io.scos.store.postgres.UnitTextBatch*", "io.scos.collection.internal.postgres.ContentColumns*", "io.scos.collection.internal.postgres.BlobWriter*",
        // Spring 조립(@Configuration 빈 배선): 슬라이스 테스트가 없으면 변이가 컨텍스트 기동 실패나 커버리지 없음이 된다. 같은 조립이라도
        // 슬라이스 테스트로 모든 변이를 죽이는 Jira*Configuration·ProvidersConfiguration은 대상에 둔다
        "io.scos.boot.CollectionConfiguration*", "io.scos.boot.PostgresCollectionConfiguration*", "io.scos.boot.PostgresIndexConfiguration*",
        "io.scos.boot.ProjectionConfiguration*", "io.scos.boot.McpConfiguration*", "io.scos.boot.MetricsConfiguration*",
        "io.scos.boot.NotionCollectionConfiguration*", "io.scos.boot.NotionProjectionConfiguration*",
        "io.scos.boot.GoogleDriveCollectionConfiguration*", "io.scos.boot.GoogleDriveProjectionConfiguration*",
        "io.scos.boot.TiroCollectionConfiguration*", "io.scos.boot.TiroProjectionConfiguration*",
        "io.scos.server.McpHttpConfiguration*", "io.scos.server.McpRuntimeConfiguration*", "io.scos.server.OpsConfiguration*",
        // record 구성요소에 TYPE_USE 어노테이션(@Nullable·@NotBlank·@Pattern 등)이 붙은 record: JDK 21의 RedefineClasses 결함
        // JDK-8376185(구성요소 type annotation을 일반 annotation 파서로 상수 풀 재작성 — 25.0.4·26.0.2·27 수정, 21u 미수정) 때문에
        // PIT가 같은 minion에서 이 클래스를 두 번째로 hot-swap하면 "class redefinition failed: attempted to change the class
        // NestHost, NestMembers, Record, or PermittedSubclasses attribute"로 minion이 끝나거나(RUN_ERROR), 망가진 필드 메타데이터를
        // Spring 바인딩이 반사로 읽다 SIGSEGV(Reflection::new_field → Symbol::as_unicode)로 죽는다. 같은 대상을 JDK 27 minion으로
        // 돌리면 RUN_ERROR 0이다(2026-10-27, MUTATION.md "대상과 제외"). PIT minion을 수정된 JDK로 돌리게 되면 이 묶음을 대상으로 되돌린다.
        // 검증 규칙은 ConfigurationValidationTest·DaemonPoolSizeTest·ProvidersConfigurationTest·AccessPropertiesTest가, 필터는
        // VectorIndexContract·KeywordIndexContract(메모리 어댑터의 archived·partition 필터)가 본다
        "io.scos.server.DaemonConfiguration*", "io.scos.boot.RerankProperties*", "io.scos.boot.AccessProperties*",
        "io.scos.boot.EmbedderProperties*", "io.scos.boot.ProvidersConfiguration\$GeminiProperties*", "io.scos.spi.store.SearchFilter",
        // 진입점(main): 프로세스 테스트가 본다
        "io.scos.server.ScosApplication*",
        // 운영 CLI 실행·출력(명령 분기·DB 조회·TS 바이트 비교 JSON·단위 가져오기·버전 검사·출력 스트림·실행 모드): process·db 태그 테스트만 덮는다.
        // 인자 해석(CommandArguments)·소스 설정 입력(SourceConfigInput)은 순수라 대상이다
        "io.scos.server.Commands*", "io.scos.server.QueryCommands*", "io.scos.server.SourceQueryJson*", "io.scos.server.UnitJsonImport*",
        "io.scos.server.DatabaseVersionCheck*", "io.scos.server.CommandOutput*", "io.scos.server.LaunchMode*",
        // 런타임 조립(수집 run·투영 run·적재·단위 가져오기·투영 공간): 저장 포트를 엮는 흐름이라 Postgres 통합 테스트(db 태그)가 증명한다
        // (메모리 포트 단위 테스트가 생기면 대상에 넣는다)
        "io.scos.collection.internal.CollectionRuntime*", "io.scos.collection.internal.projection.ProjectionRun*",
        "io.scos.collection.internal.projection.ProjectionSpaces*", "io.scos.engine.internal.projection.DocumentIngestor*",
        "io.scos.engine.internal.projection.UnitImporter*"
    )
    targetTests = setOf("io.scos.*")
    excludedGroups = setOf("db")
    threads = 4
    // 테스트의 hang 방지 상한(게이트·합류 대기 10s)이 PIT 감시(15s + 평소 시간 × 1.25)보다 먼저 실패하게 — 변이를 TIMED_OUT이 아니라
    // 단언으로 죽인다(MUTATION.md). 진짜 무한 루프 변이만 이 상한에 걸린다.
    timeoutConstInMillis = 15000
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    failWhenNoMutations = true
    // ratchet(TESTING §12): 대상을 io.scos.*로 넓힌 2026-10-25 측정(점수 96.25%·test strength 97.43%)의 내림값(MUTATION.md).
    // PIT는 원시 백분율을 Math.round한 정수와 임계를 비교한다. 올라가면 올리고 내리지 않는다(낮추려면 ADR).
    mutationThreshold = 96
    testStrengthThreshold = 97
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
// 이상 종료(환경 요인)인데 PIT는 이것도 검출로 센다 — 임계 여유(약 11변이)를 몰래 메우지 못하게 pitRunErrorLimit건을 넘으면 실패한다
// (2026-10-26 리뷰 R1 M12. 관찰값 0–3건, MUTATION.md "비단언 감지").
tasks.named("pitest") {
    // 지역 값으로 둔다 — 스크립트 수준 값을 doLast가 붙잡으면 구성 캐시가 스크립트 객체를 직렬화하지 못한다
    val pitRunErrorLimit = 5
    // fixture를 다시 생성하면 PIT도 다시 돈다(test·dbTest와 같은 선언). history는 클래스 기준이라 fixture만 바꿨으면 history.bin을 지운다(MUTATION.md)
    inputs.dir(rootProject.layout.projectDirectory.dir("../harness/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("harnessFixtures")
    val report = layout.buildDirectory.file("reports/pitest/mutations.xml")
    doLast {
        val statuses = Regex("status='([A-Z_]+)'").findAll(report.get().asFile.readText())
            .map { it.groupValues[1] }.groupingBy { it }.eachCount()
        val notByAssertion = statuses.filterKeys { it == "TIMED_OUT" || it == "MEMORY_ERROR" }
        val runErrors = statuses["RUN_ERROR"] ?: 0
        if (runErrors > pitRunErrorLimit) {
            throw GradleException("PIT RUN_ERROR ${runErrors}건 > 상한 $pitRunErrorLimit — minion 이상 종료가 점수에 검출로 섞였다. 다른 빌드 없이 history.bin을 지우고 다시 돌린다(MUTATION.md)")
        }
        if (runErrors > 0) logger.warn("PIT RUN_ERROR ${runErrors}건(상한 $pitRunErrorLimit) — minion 이상 종료(MUTATION.md), 다시 실행해 확인한다")
        if (notByAssertion.isNotEmpty()) {
            throw GradleException("PIT 비단언 감지 $notByAssertion — 단언으로 죽이거나 무한 루프 변이를 없앤다. 테스트만 고쳤다면 history는 TIMED_OUT을 재사용하므로 verification/build/pitest/history.bin을 지우고 다시 돌린다(MUTATION.md)")
        }
    }
}


// check 밖(2026-10-26 통합 결정, 리뷰 R1 I1 — MUTATION.md "check 편입"): history 없는 첫 실행(새 worktree·clean 뒤)은 전체(18–50분)이고,
// history가 있어도 커버리지 분석이 매번 전부 돌아 7–8분이며, history가 TIMED_OUT·낡은 KILLED를 재사용해 게이트를 고착시키거나 낡은 결과로
// 통과시킬 수 있다. 그래서 `check`는 pitest에 의존하지 않는다. 변이 테스트는 명시 태스크 `./gradlew :verification:pitest`(임계 96·97 그대로)이고
// PIT 대상 클래스를 바꾼 커밋·통합 전에 돌린다(TESTING §6.4·§12). CI는 별도 job(.github/workflows/backend.yml)
