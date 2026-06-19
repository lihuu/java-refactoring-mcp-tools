package com.example.airefactoring.llm

import com.example.airefactoring.settings.AiRefactoringSettings
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class OpenAiCompatibleLlmClientTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<String>()
    private var responseStatus = 200
    private var responseBody =
        """{"choices":[{"message":{"role":"assistant","content":"{\"action\":\"no_action\"}"}}]}"""

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            received.add(body)
            received.add(ex.requestHeaders.getFirst("Authorization") ?: "")
            val out = responseBody.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(responseStatus, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"

    @Test fun returnsAssistantContent() {
        val client = OpenAiCompatibleLlmClient()
        val result = client.complete(
            "sys", "user",
            AiRefactoringSettings.State(baseUrl = base(), apiKey = "k", model = "test-model"),
        )
        assertEquals("""{"action":"no_action"}""", result)
        assertTrue(received[0].contains("\"model\":\"test-model\""))
        assertTrue(received[0].contains("\"role\":\"system\""))
        assertTrue(received[0].contains("\"role\":\"user\""))
        assertEquals("Bearer k", received[1])
    }

    @Test fun missingApiKeyThrowsBeforeNetwork() {
        val client = OpenAiCompatibleLlmClient()
        assertThrows(LlmException.MissingConfiguration::class.java) {
            client.complete("s", "u", AiRefactoringSettings.State(baseUrl = base(), apiKey = "", model = "m"))
        }
    }

    @Test fun missingModelThrowsBeforeNetwork() {
        val client = OpenAiCompatibleLlmClient()
        assertThrows(LlmException.MissingConfiguration::class.java) {
            client.complete("s", "u", AiRefactoringSettings.State(baseUrl = base(), apiKey = "k", model = ""))
        }
    }

    @Test fun nonOkStatusThrowsBadStatus() {
        responseStatus = 401
        responseBody = """{"error":"unauthorized"}"""
        val client = OpenAiCompatibleLlmClient()
        val ex = assertThrows(LlmException.BadStatus::class.java) {
            client.complete("s", "u",
                AiRefactoringSettings.State(baseUrl = base(), apiKey = "k", model = "m"))
        }
        assertEquals(401, ex.code)
    }

    @Test fun missingChoicesContentThrowsMalformed() {
        responseBody = """{"choices":[]}"""
        val client = OpenAiCompatibleLlmClient()
        assertThrows(LlmException.MalformedResponse::class.java) {
            client.complete("s", "u",
                AiRefactoringSettings.State(baseUrl = base(), apiKey = "k", model = "m"))
        }
    }
}
