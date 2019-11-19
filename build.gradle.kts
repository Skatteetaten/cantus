repositories {
    maven("https://repo.spring.io/snapshot")
    mavenCentral()
}
plugins {
    id("org.springframework.cloud.contract")
    id("org.jetbrains.kotlin.jvm") version "1.3.50"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.50"
    id("org.jlleitschuh.gradle.ktlint") version "9.1.1"
    id("org.sonarqube") version "2.8"

    id("org.springframework.boot") version "2.2.1.RELEASE"
    id("org.asciidoctor.convert") version "2.3.0"

    id("com.gorylenko.gradle-git-properties") version "2.2.0"
    id("com.github.ben-manes.versions") version "0.27.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.13"

    id("no.skatteetaten.gradle.aurora") version "3.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.1.1"
}

dependencies {
    implementation("uk.q3c.rest:hal-kotlin:0.5.4.0.db32476")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.2.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.2.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test:1.3.60")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("io.projectreactor.addons:reactor-extra:3.3.0.RELEASE")
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.19")
    testImplementation("com.nhaarman:mockito-kotlin:1.6.0")
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.0.2")
}

sonarqube {
    properties {
        property("sonar.kotlin.detekt.reportPaths", "build/reports/detekt/detekt.xml")
    }
}
