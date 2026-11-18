package com.aiagent.lib

import com.aiagent.lib.FakeServer.Reply
import com.aiagent.lib.agent.AgentConfig
import com.aiagent.lib.agent.AiAgent
import com.aiagent.lib.llm.GoogleLlmClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleLlmClientTest {

    @Test
    fun `thought signature comes back on the same call`() = runBlocking {
        FakeServer(
            // "c2lnLTE=" = base64 של "sig-1"
            Reply(
                """{"candidates":[{"content":{"role":"model","parts":[
                     {"functionCall":{"name":"get_weather","args":{"city":"תל אביב"}},"thoughtSignature":"c2lnLTE="}]}}],
                   "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
            ),
            Reply("""{"candidates":[{"content":{"role":"model","parts":[{"text":"חם בתל אביב"}]}}]}"""),
        ).use { server ->
            val tool = WeatherTool()
            val agent = AiAgent(
                AgentConfig(
                    agentId = "test",
                    llmClient = GoogleLlmClient(apiKey = "test-key", fastModel = "gemini-test", baseUrl = server.url),
                    systemPrompt = "אתה חזאי",
                    tools = mapOf(tool.name to tool),
                )
            )

            assertEquals("חם בתל אביב", agent.processInput("מה מזג האוויר?"))
            assertEquals(1, tool.calls)
            assertTrue(server.requests[0].path.endsWith("/models/gemini-test:generateContent"))

            val contents = server.requests[1].json["contents"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })

            val call = contents[1]["parts"]!!.jsonArray[0].jsonObject
            assertEquals("c2lnLTE=", call["thoughtSignature"]!!.jsonPrimitive.content)

            val response = contents[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
            assertEquals("get_weather", response["name"]!!.jsonPrimitive.content)
        }
    }
}
