// 단독 실행 로컬 클라이언트(io.scos.client..) — scos-local. 공개 패키지만 쓴다(PublicBoundaryRulesTest R4). stdout은 MCP 전용, 로그는 stderr
plugins {
    id("scos.java-module")
    application
}

scosNullAway { enabled = true }

application {
    mainClass = "io.scos.client.ClientMain"
    applicationName = "scos-local"
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation(project(":scos-mcp"))
    implementation(project(":scos-store-sqlite"))
    implementation("org.springframework:spring-jdbc")
    // ContextIngestorBuilder.transactions(TransactionOperations)가 공개 시그니처에 spring-tx를 드러낸다
    implementation("org.springframework:spring-tx")
    implementation("org.xerial:sqlite-jdbc")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":scos-test-support"))
    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("ch.qos.logback:logback-classic")
}

// 테스트는 운영 JSON 인코더를 쓰는 LogCapture로 관찰한다.
configurations.testRuntimeClasspath {
    exclude(group = "org.slf4j", module = "slf4j-simple")
}
