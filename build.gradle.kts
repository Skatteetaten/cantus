buildscript {
    dependencies {
        // must specify this in gradle.properties since the same version must be here and in aurora plugin
        val springCloudContractVersion: String = project.property("aurora.springCloudContractVersion") as String
        classpath("org.springframework.cloud:spring-cloud-contract-gradle-plugin:$springCloudContractVersion")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.31"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.31"
    id("org.jlleitschuh.gradle.ktlint") version "8.0.0"
    id("org.sonarqube") version "2.7.1"

    id("org.springframework.boot") version "2.1.7.RELEASE"
    id("org.asciidoctor.convert") version "1.6.0"

    id("com.gorylenko.gradle-git-properties") version "2.0.0"
    id("com.github.ben-manes.versions") version "0.21.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.9"

    id("no.skatteetaten.gradle.aurora") version "2.4.0"
}

apply(plugin = "spring-cloud-contract")

dependencies {
    implementation("uk.q3c.rest:hal-kotlin:0.5.4.0.db32476")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.2.0")

    testImplementation("com.squareup.okhttp3:mockwebserver:3.13.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.3.30")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.13")
    testImplementation("com.nhaarman:mockito-kotlin:1.6.0")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:0.6.4")
}
