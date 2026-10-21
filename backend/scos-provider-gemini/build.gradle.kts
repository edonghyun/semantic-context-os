// Gemini 공급자(io.scos.provider.gemini): 임베딩·재정렬 호출
plugins {
    id("scos.java-module")
}

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework:spring-web")

    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
