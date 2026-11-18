package com.aiagent.lib

import com.aiagent.lib.agent.AgentCapability
import com.aiagent.lib.agent.AgentHistory
import com.aiagent.lib.llm.BaseLlmClient
import com.aiagent.lib.llm.LlmNonFatalException
import com.aiagent.lib.llm.LlmResponse
import com.aiagent.lib.llm.LlmSendException
import com.aiagent.lib.llm.ModelTier
import com.aiagent.lib.llm.RetryPolicy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BaseLlmClientTest {

    /** לקוח שכל קריאה שלו מריצה את הצעד הבא בתסריט. */
    private class ScriptedClient(vararg steps: () -> LlmResponse) :
        BaseLlmClient("fake", RetryPolicy(maxAttempts = 3, firstDelayMs = 1)) {

        private val steps = steps.toList()
        var calls = 0

        override fun modelNameFor(tier: ModelTier) = when (tier) {
            ModelTier.FAST -> "fast-model"
            ModelTier.SMART -> "smart-model"
        }

        override fun buildRequestPayload(
            system: String,
            history: List<AgentHistory>,
            capabilities: List<AgentCapability>,
            tier: ModelTier,
        ): Any = Unit

        override suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse = steps[calls++]()

        suspend fun send() = sendMessage("agent", "system", listOf(AgentHistory.UserInput("hi")), emptyList(), ModelTier.FAST)
    }

    private val ok = { LlmResponse(textReply = "ok") }

    @Test
    fun `transient error is retried`() = runTest {
        val client = ScriptedClient({ throw IOException("connection reset") }, ok)
        assertEquals("ok", client.send().textReply)
        assertEquals(2, client.calls)
    }

    @Test
    fun `permanent error fails at once`() = runTest {
        val client = ScriptedClient({ throw IllegalStateException("401 unauthorized") }, ok)
        assertFailsWith<LlmSendException> { client.send() }
        assertEquals(1, client.calls)
    }

    @Test
    fun `gives up after max attempts`() = runTest {
        val client = ScriptedClient(*Array(3) { { throw IOException("timeout") } })
        val error = assertFailsWith<LlmSendException> { client.send() }
        assertEquals(3, client.calls)
        assertEquals("fast-model", error.modelName)
    }

    @Test
    fun `non fatal error passes through without retry`() = runTest {
        val client = ScriptedClient({ throw LlmNonFatalException("agent", "refused") }, ok)
        assertFailsWith<LlmNonFatalException> { client.send() }
        assertEquals(1, client.calls)
    }

    @Test
    fun `empty response is a failure`() = runTest {
        val client = ScriptedClient({ LlmResponse() })
        assertFailsWith<LlmSendException> { client.send() }
    }

    @Test
    fun `tier picks the model`() {
        val client = ScriptedClient()
        assertEquals("fast-model", client.modelNameFor(ModelTier.FAST))
        assertEquals("smart-model", client.modelNameFor(ModelTier.SMART))
    }
}
