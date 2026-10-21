// backend 루트 프로젝트(코드 없음, 공개 배포 단계 4 T3): 빌드 스크립트 서식, 테스트 호출 검사, 하네스 자체 테스트.
// 루트가 build-logic을 먼저 싣게 해서 모듈들의 scos.java-module과 BuildService 타입(MatchedTests·PostgresContainers)이 같은 클래스로더에서 온다.
plugins {
    base
    id("com.diffplug.spotless")
}

spotless {
    kotlinGradle { target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts") }
    kotlin { target("build-logic/src/**/*.kt") }
}

// test는 층 작업을 묶기만 한다 — 루트 이름(`test`)·루트 경로(`:test`)·모듈 경로(`:<p>:test`) 어느 것이든 --tests를 받지 않는다
if (gradle.startParameter.taskNames.zipWithNext().any { (task, option) -> (task == "test" || task.endsWith(":test")) && option.startsWith("--tests") })
    throw GradleException("test는 fastTest·processTest를 묶기만 한다 — --tests는 fastTest·processTest·dbTest에 준다(TESTING §6.4)")

// 이름만으로 부른 층(`fastTest --tests X`)은 모듈마다 일치가 없어도 넘어가고(scos.java-module), 여기서 전체 일치 수가 0이면 실패시킨다
tasks.register("assertTestsMatched") {
    description = "이름만으로 부른 층 작업의 --tests에 일치한 테스트가 하나라도 있는지 확인한다"
    group = "verification"
    val matched = gradle.sharedServices.registerIfAbsent("matchedTests", MatchedTests::class) {}
    usesService(matched)
    doLast {
        if (matched.get().total() == 0L) throw GradleException("--tests에 일치한 테스트가 없다(모든 모듈)")
    }
}

// 하네스(별도 Gradle 빌드 ../harness)의 자체 테스트. check(통합 게이트)가 함께 돌린다 — 하네스만 돌릴 때는 `cd harness && ./gradlew test`.
// tsTest·javaTest(TS checkout·bootJar 필요)는 넣지 않는다.
val harnessTest by tasks.registering(Exec::class) {
    description = "하네스 자체 테스트(../harness의 ./gradlew test)"
    group = "verification"
    workingDir = layout.projectDirectory.dir("../harness").asFile
    commandLine("./gradlew", "test")
}

tasks.check { dependsOn(harnessTest) }
