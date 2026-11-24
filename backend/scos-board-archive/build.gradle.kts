plugins { id("scos.java-module") }
scosNullAway { enabled = true }
dependencies {
    implementation(project(":scos-spi"))
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("org.xerial:sqlite-jdbc")
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(project(":scos-board-archive-testkit"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
