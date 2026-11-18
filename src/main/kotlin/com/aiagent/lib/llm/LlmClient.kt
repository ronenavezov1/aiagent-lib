package com.aiagent.lib.llm

import com.aiagent.lib.agent.AgentHistory
import com.aiagent.lib.agent.AgentCapability
import kotlin.jvm.Throws

/** Intelligence tier requested by an agent. */
enum class ModelTier {
    /** Fast, cheap model — binary decisions, simple text output (e.g. spam-detector, email-sanity-checker). */
    FAST,
    /** Powerful reasoning model — multi-turn analysis, high-stakes decisions (e.g. deal-decision, followup-writer). */
    SMART
}

interface LlmClient {
    val providerName: String

    /** Returns the concrete model name that will be used for [tier] — shown in debug logs. */
    fun modelNameFor(tier: ModelTier): String

    @Throws(LlmSendException::class)
    suspend fun sendMessage(
        agentId: String,
        systemPrompt: String,
        history: List<AgentHistory>,
        availableCapabilities: List<AgentCapability>,
        tier: ModelTier = ModelTier.FAST
    ): LlmResponse
}

data class LlmResponse(
    val textReply: String? = null,
    val functionCalls: List<FunctionCall> = emptyList(),
    /** Tokens the provider charged for this call. Zero when unknown. */
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

data class FunctionCall(
    val functionName: String,
    val arguments: Map<String, Any> = emptyMap(),
    /**
     * Opaque bytes returned by thinking models. Must be echoed back verbatim.
     * Gemini: the thought signature of this call. Claude: the whole assistant turn (on the first call only).
     */
    val thoughtSignature: ByteArray? = null
)

data class LlmSendException(
    val agentId: String,
    val providerName: String,
    val modelName: String,
    val errorMessage: String
) : Exception("LLM Send Error for agent '$agentId' with provider '$providerName' and model '$modelName': $errorMessage")

/**
 * A non-fatal LLM failure: the LLM client itself is healthy and responded, but the
 * output quality was insufficient (e.g. email draft rejected by the sanity checker).
 *
 * Unlike [LlmSendException], catching this should NOT mark the [LlmClient] as dead
 * in the pool — the client is still perfectly usable for other leads.
 */
data class LlmNonFatalException(
    val agentId: String,
    val errorMessage: String
) : Exception("LLM non-fatal failure from agent '$agentId': $errorMessage")
