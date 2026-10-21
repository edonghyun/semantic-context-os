// 수집 런타임(io.scos.collection.internal..): 실행·체크포인트·원본 저장·투영 대기열과 그 PostgreSQL 어댑터
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-context")
    implementation("io.micrometer:micrometer-core")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    // blob GC 경합 번역에 서버 오류의 제약 이름이 필요하다(collection.postgres.BlobReferences) — 어댑터 패키지만 쓴다
    compileOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation(testFixtures(project(":scos-engine")))
    testImplementation(testFixtures(project(":scos-store-postgres")))
    testImplementation(testFixtures(project(":scos-connector-slack")))
    testImplementation(testFixtures(project(":scos-connector-notion")))
    testImplementation(testFixtures(project(":scos-connector-jira")))
    testImplementation(testFixtures(project(":scos-connector-google-drive")))
    testImplementation(testFixtures(project(":scos-connector-tiro")))
    testImplementation(project(":scos-spring-boot-starter"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    // BlobReferences 단위 테스트가 PSQLException·ServerErrorMessage를 만든다
    testImplementation("org.postgresql:postgresql")

    // 수집 테스트 지원(메모리 포트·PostgreSQL 런타임·Testcontainers): 커넥터·엔진·서버 테스트가 쓴다
    testFixturesApi(project(":scos-spi"))
    testFixturesApi(project(":scos-engine"))
    testFixturesApi(testFixtures(project(":scos-engine")))
    testFixturesImplementation(project(":scos-store-memory"))
    testFixturesImplementation(project(":scos-connector-slack"))
    testFixturesImplementation(project(":scos-spring-boot-starter"))
    testFixturesImplementation(project(":scos-test-support"))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesApi("org.postgresql:postgresql")
    testFixturesApi("org.flywaydb:flyway-core")
    testFixturesApi("org.flywaydb:flyway-database-postgresql")
    // PostgresSupport가 마이그레이션(db/migration)을 클래스패스에서 읽는다
    testFixturesRuntimeOnly(project(":scos-store-postgres"))
}
