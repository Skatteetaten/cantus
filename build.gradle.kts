plugins {
    id("java")
    id("no.skatteetaten.gradle.aurora") version "4.4.9"
}

aurora {
    useKotlinDefaults
    useSpringBootDefaults
    useSpringBoot {
        useCloudContract
    }
}

dependencies {

    implementation("uk.q3c.rest:hal-kotlin:0.5.4.0.db32476")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.1.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")

    // Testing
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.7")
    testImplementation("no.skatteetaten.aurora:mockwebserver-extensions-kotlin:1.2.0")
    testImplementation("io.mockk:mockk:1.12.1")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("com.ninja-squad:springmockk:3.1.0")

    // Spring
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
