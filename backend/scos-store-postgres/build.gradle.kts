// PostgreSQL 저장 어댑터(io.scos.store.postgres..)와 스키마 마이그레이션(db/migration V1–V17, Flyway가 읽는다)
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("org.springframework:spring-jdbc")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation(testFixtures(project(":scos-collection")))
    testImplementation(project(":scos-connector-google-drive"))
    testImplementation(project(":scos-spring-boot-starter"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testFixturesImplementation(project(":scos-spi"))
}
