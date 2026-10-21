// backend 루트(코드 없음). 제품 모듈 14개와 scos-test-support·verification은 settings.gradle.kts의 include, 모듈 공통 설정은
// convention plugin scos.java-module(build-logic), 실행 JAR·하네스 소스셋은 scos-server, PIT 집계는 verification에 있다.
// 버전(공식 문서 확인 2026-10-13): Spring Boot 4.1.1, Gradle 9.8.1, Java 21. 플러그인 버전은 build-logic/build.gradle.kts 한 곳에 있다.
plugins {
    id("scos.root")
}

// 모듈 사이 의존 방향(공개 배포 단계 4 T6): 선언한 프로젝트 의존(api·implementation·compileOnly·runtimeOnly)이 이 표와 정확히 같아야 한다.
// 표는 계획의 모듈 표(main import 그대로)다. 방향: spi ← engine ← {store·collection·connector·provider·mcp} ← starter ← server.
// starter의 store-memory는 runtimeOnly다(운영 jar에 남기고, 임베드 체험 경로의 자리) — 표는 구성을 가리지 않는다.
val allowedMainDependencies: Map<String, Set<String>> = mapOf(
    "scos-spi" to setOf(),
    "scos-engine" to setOf("scos-spi"),
    "scos-store-memory" to setOf("scos-spi", "scos-engine"),
    "scos-store-postgres" to setOf("scos-spi", "scos-engine"),
    "scos-collection" to setOf("scos-spi", "scos-engine"),
    "scos-connector-slack" to setOf("scos-spi", "scos-engine"),
    "scos-connector-notion" to setOf("scos-spi", "scos-engine"),
    "scos-connector-jira" to setOf("scos-spi", "scos-engine"),
    "scos-connector-google-drive" to setOf("scos-spi", "scos-engine"),
    "scos-connector-tiro" to setOf("scos-spi", "scos-engine"),
    "scos-provider-gemini" to setOf("scos-spi", "scos-engine"),
    "scos-mcp" to setOf("scos-spi", "scos-engine"),
    "scos-spring-boot-starter" to setOf(
        "scos-spi", "scos-engine", "scos-store-postgres", "scos-collection",
        "scos-connector-slack", "scos-connector-notion", "scos-connector-jira", "scos-connector-google-drive", "scos-connector-tiro",
        "scos-provider-gemini", "scos-mcp", "scos-store-memory",
    ),
    "scos-server" to setOf("scos-spring-boot-starter", "scos-collection", "scos-engine", "scos-mcp", "scos-spi"),
    "scos-test-support" to setOf("scos-collection", "scos-engine"),
    "verification" to setOf(),
)
val bootAssemblers = setOf("scos-spring-boot-starter", "scos-server")
val mainConfigurations = listOf("api", "implementation", "compileOnly", "runtimeOnly")

val checkModuleGraph = tasks.register<CheckModuleGraph>("checkModuleGraph") {
    description = "모듈 사이 의존이 허용 표와 같고, spi는 JDK만, starter·server 밖 제품 모듈은 Spring Boot 없이 컴파일되는지 확인한다"
    group = "verification"
    allowed.set(allowedMainDependencies.mapValues { it.value.toList() })
}
tasks.check { dependsOn(checkModuleGraph) }

// 선언은 모든 프로젝트를 평가한 뒤 모으고(작업 입력 값으로 구성 캐시에 들어간다), 클래스패스는 resolutionResult provider로 지연 해석한다.
// BOM(platform·enforcedPlatform)은 의존이 아니라 버전 표라 세지 않는다.
gradle.projectsEvaluated {
    checkModuleGraph.configure {
        subprojects.forEach { p ->
            val deps = mainConfigurations.mapNotNull { p.configurations.findByName(it) }.flatMap { it.dependencies }
            declared.put(p.name, deps.filterIsInstance<ProjectDependency>().map { it.path.removePrefix(":") }.distinct())
            if (p.name == "scos-spi") {
                val isPlatform = { d: Dependency ->
                    (d as? ModuleDependency)?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name.let {
                        it == Category.REGULAR_PLATFORM || it == Category.ENFORCED_PLATFORM
                    }
                }
                externalOfSpi.put(p.name, deps.filter { it !is ProjectDependency && !isPlatform(it) }.map { "${it.group}:${it.name}" })
            }
            if (p.name in allowedMainDependencies.keys - bootAssemblers - setOf("scos-test-support", "verification")) {
                val root = p.configurations.getByName("compileClasspath").incoming.resolutionResult.rootComponent
                bootOnCompileClasspath.put(
                    p.name,
                    root.map { r ->
                        val seen = mutableSetOf<ResolvedComponentResult>()
                        val queue = ArrayDeque(listOf(r))
                        while (queue.isNotEmpty()) {
                            val c = queue.removeFirst()
                            if (seen.add(c)) c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
                        }
                        seen.mapNotNull { it.moduleVersion }
                            .filter { it.group == "org.springframework.boot" && it.name != "spring-boot-dependencies" }
                            .map { "${it.group}:${it.name}" }.sorted()
                    },
                )
            }
        }
    }
}
