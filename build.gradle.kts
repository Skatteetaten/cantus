plugins {
    id("java")
    id("no.skatteetaten.gradle.aurora") version "4.3.21"
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

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.0.2.RELEASE")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")

    // Testing
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.8")
    testImplementation("io.mockk:mockk:1.12.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.25")
    testImplementation("com.ninja-squad:springmockk:3.0.1")

    // Spring
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
