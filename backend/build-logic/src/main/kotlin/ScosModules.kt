/**
 * 모듈 목록 한 곳(공개 배포 단계 5 T1). 의존 검사(checkModuleGraph)와 PIT 집계(verification)가 여기서 목록을 가져와서, 새 모듈이 검사와
 * PIT에서 조용히 빠지지 않는다. settings.gradle.kts의 include는 settings 단계라 이 객체를 쓸 수 없다 — 같은 집합인지는 checkModuleGraph가 확인한다.
 */
object ScosModules {
    /** 제품 모듈(공개 배포 설계 §5.1). 모듈 사이 방향: spi ← engine ← {store·collection·connector·provider·mcp} ← starter ← server, {mcp·store-sqlite} ← client(단독 실행) */
    val products: List<String> = listOf(
        "scos-spi", "scos-engine", "scos-store-memory", "scos-store-postgres", "scos-store-sqlite", "scos-client", "scos-collection",
        "scos-connector-slack", "scos-connector-notion", "scos-connector-jira", "scos-connector-google-drive", "scos-connector-tiro",
        "scos-provider-gemini", "scos-provider-openai-compatible", "scos-mcp", "scos-spring-boot-starter", "scos-server",
    )

    /** 배포하지 않는 보조 프로젝트. */
    val support = listOf("scos-test-support", "verification")
}
