// 실행 애플리케이션(io.scos.server): 명령·데몬·MCP 실행 조립과 설정(application*.yml·logback-spring.xml). 실행 JAR(bootJar)과 하네스 소스셋을 만든다
plugins {
    id("scos.java-module")
    id("scos.boot-app")
}

dependencies {
    // starter는 import하지 않고 런타임 스캔(scanBasePackages = "io.scos")으로 쓴다
    implementation(project(":scos-spring-boot-starter"))
    implementation(project(":scos-collection"))
    implementation(project(":scos-engine"))
    implementation(project(":scos-mcp"))
    implementation(project(":scos-spi"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Jackson 3(tools.jackson) — 유닛 JSON 적재·MCP 응답 직렬화
    implementation("org.springframework.boot:spring-boot-starter-json")
    // 설정 검증(@ConfigurationProperties + @Validated, ADR-0009, 리뷰 I-7)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // P5: 지표(ADR-0009·0020 §3) — Micrometer 코어(BOM 버전). HTTP 노출은 daemon 프로필의 Actuator(아래)
    implementation("io.micrometer:micrometer-core")
    // P5(ADR-0020 §3): daemon 프로필만 Actuator health/readiness/metrics를 로컬 HTTP로 노출 — 다른 명령은 WebApplicationType.NONE
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":scos-spi")))
    testImplementation(testFixtures(project(":scos-engine")))
    testImplementation(project(":scos-store-memory"))
    testImplementation(testFixtures(project(":scos-store-postgres")))
    testImplementation(testFixtures(project(":scos-collection")))
    testImplementation(testFixtures(project(":scos-connector-slack")))
    testImplementation(testFixtures(project(":scos-connector-notion")))
    testImplementation(testFixtures(project(":scos-connector-jira")))
    testImplementation(testFixtures(project(":scos-connector-google-drive")))
    testImplementation(testFixtures(project(":scos-connector-tiro")))
    testImplementation(project(":scos-provider-gemini"))
    testImplementation(project(":scos-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

// 하네스 대역(카세트 임베더·고정 시각, ADR-0017·리뷰 I-6)은 제품 JAR 밖의 별도 소스셋·JAR이다. 하네스가 `-Dloader.path`로만 얹는다.
sourceSets {
    create("harness") {
        java.srcDir("src/harness/java")
        compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}
val harnessJar by tasks.registering(Jar::class) {
    description = "하네스 전용 어댑터 JAR(scos-harness-adapters.jar) — 제품 bootJar에 포함되지 않는다"
    group = "build"
    archiveFileName = "scos-harness-adapters.jar"
    destinationDirectory = rootProject.layout.buildDirectory.dir("libs")
    from(sourceSets["harness"].output)
}
dependencies {
    // 하네스 대역의 슬라이스 테스트(ConfigurationValidationTest)만 테스트 클래스패스에 얹는다 — 제품 main 클래스패스에는 없다
    testImplementation(sourceSets["harness"].output)
}

// P5 프로세스 테스트(DaemonProcessTest)는 실제 bootJar를 `java -jar`로 띄운다(ADR-0020 §7)
tasks.named<Test>("dbTest") {
    dependsOn(tasks.bootJar)
    systemProperty("scos.boot.jar", tasks.bootJar.get().archiveFile.get().asFile.absolutePath)
}

// 실행 JAR: `java -jar build/libs/scos-backend.jar import|mcp …`(backend 루트 기준, 하네스 JavaServerLauncher가 쓴다)
// 제품 bootJar는 JarLauncher 그대로(재검토 N-3: PropertiesLauncher는 LOADER_PATH·loader.properties로 외부 클래스패스 주입 통로를 연다).
// 하네스만 `java -cp scos-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher`를 명시 호출해 어댑터 JAR을 얹는다.
tasks.bootJar {
    archiveFileName = "scos-backend.jar"
    destinationDirectory = rootProject.layout.buildDirectory.dir("libs")
}
springBoot { mainClass = "io.scos.server.ScosApplication" }
// plain jar는 classifier 없이 scos-server.jar(scos-server/build/libs) — 다른 모듈 jar와 같은 이름 규칙이라 ArchUnit 가져오기(TestLocations.ProductCode)가 같이 잡는다
tasks.jar { archiveClassifier = "" }

// 데모 코퍼스 생성(demo/README.md "Editing the corpus")·가짜 공급자 프로세스(io.scos.demo, 테스트 소스셋)의 클래스패스와 Java 21 실행 파일 경로를 파일로
val writeDemoClasspath by tasks.registering {
    description = "데모 가짜 공급자 프로세스의 클래스패스를 build/demo/classpath.txt에 쓴다"
    group = "application"
    val cp = sourceSets.test.get().runtimeClasspath
    // 다른 모듈의 main·testFixtures는 jar로 들어온다 — 클래스패스의 jar까지 만든다
    dependsOn(tasks.testClasses, cp)
    val out = rootProject.layout.buildDirectory.file("demo/classpath.txt")
    // 데모는 빌드와 같은 Java 21 toolchain으로 실행한다(PATH의 java가 다른 버전일 수 있다)
    val java = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val javaOut = rootProject.layout.buildDirectory.file("demo/java.txt")
    outputs.files(out, javaOut)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText(cp.asPath) }
        javaOut.get().asFile.writeText(java.get().executablePath.asFile.absolutePath)
    }
}
