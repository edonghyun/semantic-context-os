// 메모리 저장 어댑터(io.scos.store.memory) — 임베드·체험 경로
plugins {
    id("scos.java-module")
}

scosNullAway { enabled = true }

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))

    testImplementation(testFixtures(project(":scos-spi")))
    testFixturesImplementation(testFixtures(project(":scos-spi")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
