// 엔진(io.scos.engine, engine.internal.., engine.support..): 검색·조립·투영 파이프라인과 공용 지원 코드
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation("org.springframework:spring-tx")
    // P4: RestClient(공급자 호출, ADR-0018) — starter 없이 spring-web만(BOM 버전)
    implementation("org.springframework:spring-web")
    // P5: 지표(ADR-0009·0020 §3) — Micrometer 코어(BOM 버전)
    implementation("io.micrometer:micrometer-core")
    // Jackson 3(tools.jackson) — 유닛 JSON·로그 줄
    implementation("tools.jackson.core:jackson-databind")
    // 재정렬 캐시(DESIGN-PATTERNS §12, Boot BOM 관리 버전)
    implementation("com.github.ben-manes.caffeine:caffeine")
    // P4: 토큰 계산기(ADR-0019, o200k_base) — TS js-tiktoken과 같은 랭크
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.slf4j:slf4j-api")
    // JSON 로그 줄 인코더(JsonLineEncoder) 하나만 쓴다 — 임베드 사용자에게 SLF4J 바인딩을 강제하지 않는다. 운영 jar에는 starter-logging으로 들어온다
    compileOnly("ch.qos.logback:logback-classic")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation(project(":scos-store-memory"))
    testImplementation(testFixtures(project(":scos-store-postgres")))
    testImplementation(testFixtures(project(":scos-collection")))
    testImplementation(project(":scos-connector-slack"))
    testImplementation(project(":scos-connector-notion"))
    testImplementation(testFixtures(project(":scos-connector-jira")))
    testImplementation(project(":scos-connector-google-drive"))
    testImplementation(project(":scos-connector-tiro"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("ch.qos.logback:logback-classic")
    testFixturesImplementation(project(":scos-spi"))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
}
