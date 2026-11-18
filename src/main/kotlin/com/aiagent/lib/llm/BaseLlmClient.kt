package com.aiagent.lib.llm

import com.aiagent.lib.agent.AgentHistory
import com.aiagent.lib.agent.AgentCapability
import com.aiagent.lib.debug.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** How many times to try, and how long to wait between tries (doubles each time). */
data class RetryPolicy(
    val maxAttempts: Int = 5,
    val firstDelayMs: Long = 2_500L,
    val maxDelayMs: Long = 10_000L,
)

/**
 * Base LLM client — handles retry logic and the empty-response guard.
 * Concrete subclasses implement [modelNameFor] (which model each tier runs on),
 * [buildRequestPayload] and [executeNetworkCall].
 */
abstract class BaseLlmClient(
    final override val providerName: String,
    private val retry: RetryPolicy = RetryPolicy(),
) : LlmClient {

    @Throws(LlmSendException::class)
    override suspend fun sendMessage(
        agentId: String,
        systemPrompt: String,
        history: List<AgentHistory>,
        availableCapabilities: List<AgentCapability>,
        tier: ModelTier
    ): LlmResponse {
        val modelName = modelNameFor(tier)
        val requestPayload = buildRequestPayload(systemPrompt, history, availableCapabilities, tier)
        val response = executeWithRetry(agentId, modelName, requestPayload)

        // An empty response is a failure too — surface it as LlmSendException, like any other send error.
        if (response.textReply.isNullOrBlank() && response.functionCalls.isEmpty()) {
            throw LlmSendException(agentId, providerName, modelName, "$providerName returned an empty response")
        }
        return response
    }

    /**
     * Exponential backoff retry for transient errors (429, 503, network IOException).
     * Fails fast on permanent errors (see [isPermanent]) — no point retrying those.
     */
    @Throws(LlmSendException::class)
    private suspend fun executeWithRetry(agentId: String, modelName: String, payload: Any): LlmResponse {
        var currentDelay = retry.firstDelayMs

        repeat(retry.maxAttempts) { attempt ->
            try {
                return executeNetworkCall(agentId, payload)
            } catch (e: CancellationException) {
                throw e // the caller gave up (e.g. withTimeout) — never retry or wrap that
            } catch (e: LlmNonFatalException) {
                throw e // the provider answered; retrying won't change its answer
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName

                if (isPermanent(e) || attempt == retry.maxAttempts - 1) {
                    throw LlmSendException(agentId, providerName, modelName, message)
                }

                val delayMs = minOf(currentDelay, retry.maxDelayMs)
                DebugLogger.llmRetry(providerName, message, delayMs, attempt + 1, retry.maxAttempts)
                delay(delayMs.milliseconds)
                currentDelay *= 2
            }
        }
        throw LlmSendException(agentId, providerName, modelName, "failed after ${retry.maxAttempts} attempts")
    }

    /**
     * Errors that will fail the same way on every retry (bad request, bad key, no permission).
     *
     * The default reads the message, for SDKs that only give us text.
     * Providers whose SDK has typed errors override this with an exact check.
     */
    protected open fun isPermanent(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase()
        // Word boundaries so "1400" doesn't match "400".
        return Regex("\\b(400|401|403)\\b").containsMatchIn(msg) ||
            msg.contains("unauthorized") ||
            msg.contains("bad request") ||
            msg.contains("invalid_argument") ||
            msg.contains("permission_denied")
    }

    /** Each concrete client maps the shared history/capabilities into its own wire format. */
    protected abstract fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>,
        tier: ModelTier
    ): Any

    @Throws(LlmSendException::class)
    protected abstract suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse
}
