package com.aiagent.lib

import com.aiagent.lib.FakeServer.Reply
import com.aiagent.lib.agent.AgentConfig
import com.aiagent.lib.agent.AiAgent
import com.aiagent.lib.llm.AnthropicLlmClient
import com.aiagent.lib.llm.LlmNonFatalException
import com.aiagent.lib.llm.ModelTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicLlmClientTest {

    private fun message(content: String, stopReason: String, extra: String = "") =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$content],
           "stop_reason":"$stopReason","stop_sequence":null,$extra"usage":{"input_tokens":10,"output_tokens":5}}"""

    private fun agent(server: FakeServer, tool: WeatherTool) = AiAgent(
        AgentConfig(
            agentId = "test",
            llmClient = AnthropicLlmClient(apiKey = "test-key", baseUrl = server.url),
            systemPrompt = "אתה חזאי",
            tools = mapOf(tool.name to tool),
        )
    )

    @Test
    fun `thinking and tool ids come back unchanged`() = runBlocking {
        FakeServer(
            Reply(
                message(
                    """{"type":"thinking","thinking":"","signature":"sig-abc"},
                       {"type":"tool_use","id":"toolu_01","name":"get_weather","input":{"city":"תל אביב"}}""",
                    "tool_use",
                )
            ),
            Reply(message("""{"type":"text","text":"חם בתל אביב"}""", "end_turn")),
        ).use { server ->
            val tool = WeatherTool()
            assertEquals("חם בתל אביב", agent(server, tool).processInput("מה מזג האוויר?"))
            assertEquals(1, tool.calls)

            val second = server.requests[1].json
            val messages = second["messages"]!!.jsonArray.map { it.jsonObject }

            // התור של המודל חזר כמו שהוא: בלוק החשיבה עם החתימה, ואז הקריאה עם המזהה שלה.
            val turn = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals("thinking", turn[0]["type"]!!.jsonPrimitive.content)
            assertEquals("sig-abc", turn[0]["signature"]!!.jsonPrimitive.content)
            assertEquals("toolu_01", turn[1]["id"]!!.jsonPrimitive.content)

            // התוצאה מצביעה על אותו מזהה.
            val result = messages[2]["content"]!!.jsonArray[0].jsonObject
            assertEquals("toolu_01", result["tool_use_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `request carries effort and server fallback`() = runBlocking {
        FakeServer(Reply(message("""{"type":"text","text":"שלום"}""", "end_turn"))).use { server ->
            agent(server, WeatherTool()).processInput("היי")

            val request = server.requests[0]
            assertEquals("claude-sonnet-5-5", request.json["model"]!!.jsonPrimitive.content) // FAST
            assertEquals("low", request.json["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals("default", request.json["fallbacks"]!!.jsonPrimitive.content)
            assertTrue("server-side-fallback-2026-07-01" in request.headers["anthropic-beta"].orEmpty().joinToString())
        }
    }

    @Test
    fun `FAST is Sonnet and SMART is Opus`() {
        val client = AnthropicLlmClient(apiKey = "test-key")
        assertEquals("claude-sonnet-5-5", client.modelNameFor(ModelTier.FAST))
        assertEquals("claude-opus-5-5", client.modelNameFor(ModelTier.SMART))
    }

    @Test
    fun `refusal is non fatal`() = runBlocking {
        FakeServer(
            Reply(message("", "refusal", """"stop_details":{"type":"refusal","category":"cyber","explanation":"no"},"""))
        ).use { server ->
            assertFailsWith<LlmNonFatalException> { agent(server, WeatherTool()).processInput("היי") }
            assertEquals(1, server.requests.size)
        }
    }
}
