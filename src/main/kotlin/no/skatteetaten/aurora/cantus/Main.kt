package no.skatteetaten.aurora.cantus

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties

@SpringBootApplication
@EnableConfigurationProperties
class Main

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    SpringApplication.run(Main::class.java, *args)
}
