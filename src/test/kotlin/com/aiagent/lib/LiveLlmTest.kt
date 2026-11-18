package com.aiagent.lib

import com.aiagent.lib.agent.AgentConfig
import com.aiagent.lib.agent.AiAgent
import com.aiagent.lib.agent.AgentHistory
import com.aiagent.lib.llm.AnthropicLlmClient
import com.aiagent.lib.llm.GoogleLlmClient
import com.aiagent.lib.llm.LlmClient
import com.aiagent.lib.llm.LocalLlmClient
import com.aiagent.lib.llm.ModelTier
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * מול ספקים אמיתיים. רץ רק עם LIVE_LLM=1 — ואז כל ספק שיש לו הגדרות בסביבה:
 *
 *   ANTHROPIC_API_KEY                  Claude, עם כלי
 *   GEMINI_API_KEY + GEMINI_MODEL      Gemini, עם כלי
 *   OLLAMA=1                           המודל המקומי, בלי כלים (לא כל מודל תומך)
 */
class LiveLlmTest {

    private val live = System.getenv("LIVE_LLM") == "1"
    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun withTool(client: LlmClient) = runBlocking {
        val tool = WeatherTool()
        val agent = AiAgent(
            AgentConfig(
                agentId = "live",
                llmClient = client,
                systemPrompt = "אתה חזאי. השתמש בכלי כדי לדעת מה מזג האוויר. ענה במשפט אחד.",
                tools = mapOf(tool.name to tool),
            )
        )
        val answer = agent.processInput("מה מזג האוויר בתל אביב?")
        println("=== ${client.providerName}: $answer")
        assertEquals(1, tool.calls)
        assertFalse(answer.isBlank())
    }

    @Test
    fun anthropic() {
        if (!live) return
        withTool(AnthropicLlmClient(apiKey = env("ANTHROPIC_API_KEY") ?: return))
    }

    @Test
    fun google() {
        if (!live) return
        withTool(GoogleLlmClient(apiKey = env("GEMINI_API_KEY") ?: return, fastModel = env("GEMINI_MODEL") ?: return))
    }

    @Test
    fun local() = runBlocking {
        if (!live) return@runBlocking
        if (env("OLLAMA") == null) return@runBlocking
        val client = LocalLlmClient()
        val response = client.sendMessage("live", "ענה במילה אחת.", listOf(AgentHistory.UserInput("איזה צבע השמיים?")), emptyList(), ModelTier.FAST)
        println("=== ${client.providerName}: ${response.textReply}")
        assertFalse(response.textReply.isNullOrBlank())
    }
}
