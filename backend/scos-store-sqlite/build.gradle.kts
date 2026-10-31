// SQLite 저장 어댑터(io.scos.store.sqlite..) — 클라이언트 단독 실행 경로. 스키마는 db/sqlite/index
plugins {
    id("scos.java-module")
}

scosNullAway { enabled = true }

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.xerial:sqlite-jdbc")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
