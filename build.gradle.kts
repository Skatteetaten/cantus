plugins {
    id("java")
    id("no.skatteetaten.gradle.aurora") version "4.4.22"
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

    // Spring
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Testing
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.7")
    testImplementation("no.skatteetaten.aurora:mockwebserver-extensions-kotlin:1.3.1")
    testImplementation("io.mockk:mockk:1.12.4")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("com.ninja-squad:springmockk:3.1.0")
    testImplementation("io.projectreactor:reactor-test:3.4.23")

    // Spring testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
