// 모듈 공용 테스트 지원(io.scos.support, 배포하지 않는다): 대기·가짜 HTTP·자식 JVM·지표 단언. TestMetrics가 수집·엔진 지표 이름을 안다
plugins {
    id("scos.java-module")
    // 쓰는 쪽 테스트가 TestMetrics 등의 시그니처에 나오는 수집·엔진 타입을 그대로 본다(api)
    `java-library`
}

dependencies {
    api(project(":scos-collection"))
    api(project(":scos-engine"))
    implementation("org.springframework.boot:spring-boot-starter-test")
    implementation("io.micrometer:micrometer-core")
    // 조건 대기(TESTING §7): Boot BOM이 관리하는 버전. Eventually·ChildJvm에서만 쓴다
    implementation("org.awaitility:awaitility")
    implementation("org.springframework:spring-context")
    implementation("ch.qos.logback:logback-classic")
    implementation("tools.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
