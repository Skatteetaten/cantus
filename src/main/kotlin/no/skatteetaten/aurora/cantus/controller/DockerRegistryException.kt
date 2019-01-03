package no.skatteetaten.aurora.cantus.controller

import java.lang.RuntimeException

class DockerRegistryException (message : String) : RuntimeException(message)