package no.skatteetaten.aurora.cantus.controller.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import no.skatteetaten.aurora.cantus.ServiceTypes
import no.skatteetaten.aurora.cantus.TargetService
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

data class UserGroup(val user: String, val group: String)

data class OpenShiftGroups(private val groupUserPairs: List<UserGroup>) {

    private val groupUsers: Map<String, List<String>> by lazy {
        groupUserPairs.groupBy({ it.group }, { it.user })
    }

    private val userGroups: Map<String, List<String>> by lazy {
        groupUserPairs.groupBy({ it.user }, { it.group })
    }

    fun getGroupsForUser(user: String) = userGroups[user] ?: emptyList()

    fun groupExist(group: String) = groupUsers.containsKey(group)
}

@Service
class OpenshiftClientService(
    @TargetService(ServiceTypes.OPENSHIFT) val webClient: WebClient
) {
    @Cacheable("groups")
    fun getGroups(): OpenShiftGroups {

        fun getAllDeclaredUserGroups(): List<UserGroup> {
            val groupItems: JsonNode = getResponseBodyItems() as ArrayNode
            return groupItems
                .filter { it["users"] is ArrayNode }
                .flatMap {
                    val name = it["metadata"]["name"].asText()
                    (it["users"] as ArrayNode).map { UserGroup(it.asText(), name) }
                }
        }

        fun getAllImplicitUserGroups(): List<UserGroup> {
            val implicitGroup = "system:authenticated"
            val userItems = getResponseBodyItems() as ArrayNode

            return userItems.map { UserGroup(it["metadata"]["name"].asText(), implicitGroup) }
        }

        return OpenShiftGroups(getAllDeclaredUserGroups() + getAllImplicitUserGroups())
    }

    fun findCurrentUser(token: String): JsonNode? {
        val apiUrl = "/oapi/v1/users/~"

        return getAndReturnBody { webClient ->
            webClient.get().uri(apiUrl).headers { header ->
                header.setBearerAuth(token)
            }
        }
    }

    private fun getResponseBodyItems(): JsonNode =
        getAndReturnBody<ArrayNode> { webClient ->
            webClient.get().uri("/oapi/v1/users")
        }?.get("items")  ?:  throw SourceSystemException(
            message = "Could not get implicit user groups from openshift",
            code = "404",
            sourceSystem = "Openshift"
        )

    private final inline fun <reified T : Any> getAndReturnBody(
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T? = fn(webClient)
        .exchange()
        .flatMap { resp ->
            resp.bodyToMono(T::class.java)
        }
        .blockAndHandleError(sourceSystem = "Openshift")
}