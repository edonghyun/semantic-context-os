// OpenAI 호환 공급자(io.scos.provider.openai): /embeddings·/chat/completions 호출(공개 배포 단계 5 T2) — 의존은 Gemini 공급자와 같다
plugins {
    id("scos.java-module")
}

scosNullAway { enabled = true }

dependencies {
    implementation(project(":scos-spi"))
    implementation(project(":scos-engine"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.springframework:spring-web")

    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
