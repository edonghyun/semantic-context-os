// 공개 SPI(io.scos.spi..): 저장·임베딩·투영·커넥터 계약. JDK만 쓴다. testFixtures는 저장 계약 스위트(단계 5에서 scos-testkit으로)
plugins {
    id("scos.java-module")
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
}
