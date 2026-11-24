import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("scos.java-module")
    `java-library`
}
dependencies {
    api(project(":scos-board-archive"))
    api(platform(SpringBootPlugin.BOM_COORDINATES))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.junit.jupiter:junit-jupiter-params")
    api("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
