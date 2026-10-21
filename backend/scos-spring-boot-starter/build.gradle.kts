// Spring 조립(io.scos.boot): 저장·수집·커넥터·공급자·MCP 구성. 자동 구성은 단계 5에서 붙인다
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation(project(":scos-store-postgres"))
    implementation(project(":scos-collection"))
    implementation(project(":scos-connector-slack"))
    implementation(project(":scos-connector-notion"))
    implementation(project(":scos-connector-jira"))
    implementation(project(":scos-connector-google-drive"))
    implementation(project(":scos-connector-tiro"))
    implementation(project(":scos-provider-gemini"))
    implementation(project(":scos-mcp"))
    // import는 없지만 운영 jar에 들어 있고(단계 3에서 main으로 승격) 단계 5 임베드 체험 경로가 starter로 받는다
    runtimeOnly(project(":scos-store-memory"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Jackson 3(tools.jackson)
    implementation("org.springframework.boot:spring-boot-starter-json")
    // 설정 검증(@ConfigurationProperties + @Validated, ADR-0009, 리뷰 I-7)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
