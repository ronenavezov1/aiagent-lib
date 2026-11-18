package com.aiagent.lib

import com.aiagent.lib.FakeServer.Reply
import com.aiagent.lib.agent.AgentConfig
import com.aiagent.lib.agent.AiAgent
import com.aiagent.lib.llm.LlmSendException
import com.aiagent.lib.llm.LocalLlmClient
import com.aiagent.lib.llm.ModelTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalLlmClientTest {

    @Test
    fun `agent calls a tool and answers - full round trip`() = runBlocking {
        FakeServer(
            Reply("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"get_weather","arguments":{"city":"תל אביב"}}}]},"prompt_eval_count":10,"eval_count":5}"""),
            Reply("""{"message":{"role":"assistant","content":"חם בתל אביב"}}"""),
        ).use { server ->
            val tool = WeatherTool()
            val agent = AiAgent(
                AgentConfig(
                    agentId = "test",
                    llmClient = LocalLlmClient(baseUrl = server.url),
                    systemPrompt = "אתה חזאי",
                    tools = mapOf(tool.name to tool),
                )
            )

            assertEquals("חם בתל אביב", agent.processInput("מה מזג האוויר?"))
            assertEquals(1, tool.calls)

            val first = server.requests[0].json
            assertEquals("gemma2:27b", first["model"]!!.jsonPrimitive.content)
            assertEquals("false", first["stream"]!!.jsonPrimitive.content)
            assertEquals("get_weather", first["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

            // הסבב השני: system, user, הקריאה של המודל, ותוצאת הכלי לפי שמו.
            val messages = server.requests[1].json["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it["role"]!!.jsonPrimitive.content })
            assertEquals("get_weather", messages[3]["tool_name"]!!.jsonPrimitive.content)
            assertEquals("32 מעלות בתל אביב", messages[3]["content"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `local model has no SMART tier`() {
        val client = LocalLlmClient()
        assertEquals("gemma2:27b", client.modelNameFor(ModelTier.FAST))
        assertFailsWith<IllegalArgumentException> { client.modelNameFor(ModelTier.SMART) }
    }

    @Test
    fun `model without tool support fails at once`() = runBlocking {
        FakeServer(Reply("""{"error":"gemma2:27b does not support tools"}""", status = 400)).use { server ->
            val tool = WeatherTool()
            val agent = AiAgent(
                AgentConfig(
                    agentId = "test",
                    llmClient = LocalLlmClient(baseUrl = server.url),
                    systemPrompt = "x",
                    tools = mapOf(tool.name to tool),
                )
            )
            val error = assertFailsWith<LlmSendException> { agent.processInput("hi") }
            assertEquals("Ollama 400: gemma2:27b does not support tools", error.errorMessage)
            assertEquals(1, server.requests.size) // 4xx לא מנסים שוב
        }
    }
}
