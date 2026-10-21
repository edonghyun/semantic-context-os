// Jira 커넥터(io.scos.connector.jira..). main 의존은 spi·engine뿐이다 — 커넥터끼리 서로 모른다
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework:spring-web")

    testImplementation(testFixtures(project(":scos-engine")))
    testImplementation(testFixtures(project(":scos-collection")))
    testImplementation(project(":scos-spring-boot-starter"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")

    // 수집 계약·가짜 서버·조립 도우미 — 수집·엔진·서버 테스트가 쓴다
    testFixturesApi(testFixtures(project(":scos-collection")))
    testFixturesImplementation(project(":scos-spring-boot-starter"))
    testFixturesImplementation(project(":scos-test-support"))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
}
