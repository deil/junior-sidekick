package com.github.uncomplexco.sidekick.application.turn

import com.github.uncomplexco.sidekick.application.conversation.ConversationId
import com.github.uncomplexco.sidekick.application.conversation.MessageAuthor
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer
import com.github.uncomplexco.sidekick.testing.QueuedOpenRouterServer.Response
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class JevParticipationClassifierTest {
    private val input =
        ParticipationClassifierInput(
            messages =
                listOf(
                    ParticipationClassifierMessage(
                        "Check the deployment",
                        MessageAuthor("U1", "Ada"),
                    ),
                    ParticipationClassifierMessage("The staging one", MessageAuthor("U2", "Bob")),
                ),
            botUser = MessageAuthor("BOT", "Sidekick"),
            messageHistory = emptyList(),
            hasAssistantHistory = true,
            conversationId = ConversationId("C1", "thread1"),
        )

    @ParameterizedTest(name = "reply probability {0} yields {1} with threshold {2}")
    @CsvSource(
        "0.95, CLASSIFIER, 0.8",
        "0.8, CLASSIFIER, 0.8",
        "0.1, SIDE_CONVERSATION, 0.8",
        "0.6, LOW_CONFIDENCE, 0.8",
        "0.85, LOW_CONFIDENCE, 0.9",
    )
    fun `reply probability controls participation`(
        probability: Double,
        reason: TurnParticipationReason,
        threshold: Double,
    ) = runBlocking {
        // Arrange
        QueuedOpenRouterServer(
                Response("""{"answers":{"should_reply":{"type":"noul","noul":$probability}}}"""),
                path = "/api/alpha/decisions",
            )
            .use { server ->
                val classifier = classifier(server, threshold)
                try {
                    // Act
                    val decision = classifier.classify(input)

                    // Assert
                    assertEquals(reason, decision.reason)
                    assertEquals(
                        reason == TurnParticipationReason.CLASSIFIER,
                        decision is TurnParticipationDecision.Respond,
                    )
                } finally {
                    classifier.destroy()
                }
            }
    }

    @Test
    fun `one decision receives the ordered batch and conversation association`() = runBlocking {
        // Arrange
        QueuedOpenRouterServer(
                Response("""{"answers":{"should_reply":{"type":"noul","noul":0.95}}}"""),
                path = "/api/alpha/decisions",
            )
            .use { server ->
                val classifier = classifier(server)
                try {
                    // Act
                    val decision = classifier.classify(input)

                    // Assert
                    assertIs<TurnParticipationDecision.Respond>(decision)
                    val request = Json.parseToJsonElement(server.requests.single()).jsonObject
                    assertEquals(
                        "typesafe/jev-1.14",
                        request.getValue("model").jsonPrimitive.content,
                    )
                    assertEquals("C1:thread1", request.getValue("session_id").jsonPrimitive.content)
                    val messages =
                        request.getValue("state").jsonObject.getValue("current_messages").jsonArray
                    assertEquals(
                        listOf("Check the deployment", "The staging one"),
                        messages.map { it.jsonObject.getValue("text").jsonPrimitive.content },
                    )
                    assertEquals(
                        listOf("U1", "U2"),
                        messages.map { it.jsonObject.getValue("author_id").jsonPrimitive.content },
                    )
                } finally {
                    classifier.destroy()
                }
            }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "not JSON",
                """{"answers":{"should_reply":{"type":"refusal"}}}""",
                """{"answers":{"should_reply":{"type":"noul","noul":1.5}}}""",
            ]
    )
    fun `unusable decisions skip with classifier error`(body: String) = runBlocking {
        // Arrange
        QueuedOpenRouterServer(Response(body), path = "/api/alpha/decisions").use { server ->
            val classifier = classifier(server)
            try {
                // Act
                val decision = classifier.classify(input)

                // Assert
                assertIs<TurnParticipationDecision.Skip>(decision)
                assertEquals(TurnParticipationReason.CLASSIFIER_ERROR, decision.reason)
            } finally {
                classifier.destroy()
            }
        }
    }

    @Test
    fun `provider failure skips without falling back to another model`() = runBlocking {
        // Arrange
        QueuedOpenRouterServer(Response("{}", 503), path = "/api/alpha/decisions").use { server ->
            val classifier = classifier(server)
            try {
                // Act
                val decision = classifier.classify(input)

                // Assert
                assertIs<TurnParticipationDecision.Skip>(decision)
                assertEquals(TurnParticipationReason.CLASSIFIER_ERROR, decision.reason)
                assertEquals(1, server.requests.size)
            } finally {
                classifier.destroy()
            }
        }
    }

    @Test
    fun `decision requests include application attribution`() = runBlocking {
        // Arrange
        QueuedOpenRouterServer(
            Response("""{"answers":{"should_reply":{"type":"noul","noul":0.95}}}"""),
            path = "/api/alpha/decisions",
        ).use { server ->
            val classifier = classifier(server)
            try {
                // Act
                val decision = classifier.classify(input)

                // Assert
                assertIs<TurnParticipationDecision.Respond>(decision)
                val headers = server.requestHeaders.single().mapKeys { it.key.lowercase() }
                assertEquals(listOf("Sidekick"), headers["x-openrouter-title"])
                assertEquals(listOf("https://sidekick.example"), headers["http-referer"])
            } finally {
                classifier.destroy()
            }
        }
    }

    private fun classifier(server: QueuedOpenRouterServer, threshold: Double = 0.8) =
        JevParticipationClassifier(
            "test-key",
            "typesafe/jev-1.14",
            threshold,
            "${server.baseUrl}/api/alpha/decisions",
            "Sidekick",
            "https://sidekick.example",
        )
}
