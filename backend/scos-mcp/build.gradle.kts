// MCP stdio 서버(io.scos.mcp..): 읽기 도구와 질의 로그
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    // MCP Java SDK 직접(ADR-0010 M2, E-7). 버전 확인 2026-10-13: mcp-bom 2.0.1
    implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.1"))
    implementation("io.modelcontextprotocol.sdk:mcp")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation(testFixtures(project(":scos-engine")))
    testImplementation(testFixtures(project(":scos-collection")))
    testImplementation(project(":scos-store-memory"))
    testImplementation(project(":scos-spring-boot-starter"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("org.springframework.boot:spring-boot-starter-json")
}
