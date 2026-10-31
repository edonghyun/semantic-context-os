// 공개 SPI(io.scos.spi..): 저장·임베딩·투영·커넥터 계약. JDK만 쓴다. testFixtures는 저장 계약 스위트(단계 5에서 scos-testkit으로)
plugins {
    id("scos.java-module")
}

scosNullAway { enabled = true }

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 다른 스레드의 대기·합류(BackgroundThread·Eventually) — 취소 토큰 테스트
    testImplementation(project(":scos-test-support"))
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
}
