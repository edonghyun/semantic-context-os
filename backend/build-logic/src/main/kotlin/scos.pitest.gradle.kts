import info.solidsoft.gradle.pitest.PitestTask

// 변이 테스트(TESTING §12). 버전은 build-logic/build.gradle.kts 한 곳에 둔다
plugins {
    id("info.solidsoft.pitest")
}

// 테스트와 같은 작업 디렉터리(backend 루트) — 상대 경로 `../harness`·`scos-…/src/…`를 쓰는 테스트가 있다
tasks.withType<PitestTask>().configureEach { workingDir = rootProject.projectDir }
