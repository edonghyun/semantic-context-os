plugins {
    // Java 21 toolchain 자동 프로비저닝(ADR-0001) — backend·experiments와 같은 JDK
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "scos-harness"
