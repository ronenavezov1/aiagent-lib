package com.aiagent.lib.llm

import com.aiagent.lib.agent.AgentCapability
import com.aiagent.lib.agent.AgentHistory
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * מימוש מקומי של [LlmClient] — מודל שרץ על המחשב דרך Ollama (`/api/chat`).
 *
 * 1. **רק FAST.** מודל מקומי (למשל `gemma2:27b`) לא מספיק חכם ל-SMART —
 *    סוכן שמבקש SMART מקבל שגיאה ברורה, ולא מודל טיפש בשקט.
 *
 * 2. **לא כל מודל תומך בכלים.** מודל בלי תמיכה מחזיר 400 ברגע שנשלחות לו
 *    יכולות. סוכן בלי יכולות עובד עם כל מודל.
 *
 * 3. **בלי מזהי כלים.** תוצאה מוצמדת לקריאה לפי `tool_name`.
 *
 * @param timeout מודל מקומי על מעבד יכול לחשוב דקות, לא שניות.
 */
class LocalLlmClient(
    private val baseUrl: String = "http://localhost:11434",
    private val timeout: Duration = Duration.ofMinutes(5),
    retry: RetryPolicy = RetryPolicy(),
) : BaseLlmClient("Local (Ollama)", retry = retry) {

    override fun modelNameFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> "gemma2:27b"
        ModelTier.SMART -> throw IllegalArgumentException("למודל מקומי יש רק FAST. לסוכן שצריך SMART — Claude.")
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>,
        tier: ModelTier,
    ): Any = OllamaRequest(
        model = modelNameFor(tier),
        messages = listOf(OllamaMessage("system", system)) + toMessages(history),
        tools = capabilities.map(::toTool).ifEmpty { null },
    )

    @Throws(LlmSendException::class)
    override suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse {
        val request = HttpRequest.newBuilder(URI("$baseUrl/api/chat"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload as OllamaRequest)))
            .build()

        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != 200) throw LocalLlmException(response.statusCode(), errorOf(response.body()))

        val answer = json.decodeFromString<OllamaResponse>(response.body())
        val funcCalls = answer.message.toolCalls.orEmpty().map { call ->
            FunctionCall(call.function.name, call.function.arguments.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() })
        }
        return LlmResponse(
            textReply = if (funcCalls.isEmpty()) answer.message.content.takeIf { it.isNotBlank() } else null,
            functionCalls = funcCalls,
            inputTokens = answer.promptEvalCount,
            outputTokens = answer.evalCount,
        )
    }

    /** 4xx מ-Ollama: מודל שלא קיים, או מודל שלא תומך בכלים. ניסיון חוזר לא ישנה את זה. */
    override fun isPermanent(e: Exception): Boolean =
        if (e is LocalLlmException) e.status in 400..499 else super.isPermanent(e)

    // ------------------------------------------------------------
    //  היסטוריה → הודעות
    // ------------------------------------------------------------
    private fun toMessages(history: List<AgentHistory>): List<OllamaMessage> {
        val result = mutableListOf<OllamaMessage>()

        var i = 0
        while (i < history.size) {
            when (val action = history[i]) {

                is AgentHistory.UserInput -> {
                    result += OllamaMessage("user", action.text); i++
                }

                is AgentHistory.AiResponse -> {
                    result += OllamaMessage("assistant", action.text); i++
                }

                // קריאות רצופות הן תור אחד של המודל.
                is AgentHistory.ToolCallRequest -> {
                    val calls = mutableListOf<OllamaToolCall>()
                    while (i < history.size && history[i] is AgentHistory.ToolCallRequest) {
                        val call = history[i] as AgentHistory.ToolCallRequest
                        val args = JsonObject(call.arguments.mapValues { (_, v) -> JsonPrimitive(v.toString()) })
                        calls += OllamaToolCall(OllamaFunctionCall(call.toolName, args))
                        i++
                    }
                    result += OllamaMessage("assistant", toolCalls = calls)
                }

                // כל תוצאה היא הודעת tool נפרדת.
                is AgentHistory.ToolExecutionResult -> {
                    result += OllamaMessage("tool", action.resultData, toolName = action.toolName); i++
                }

                // אירוע מערכת — פנימי, לא נשלח למודל.
                is AgentHistory.SystemEvent -> i++

                // סיכום — נשלח כתור user, כדי שהמודל יבין שזה הקשר דחוס ולא שאלה חיה.
                is AgentHistory.Summary -> {
                    result += OllamaMessage("user", "סיכום השיחה עד כה (הקשר דחוס):\n${action.text}"); i++
                }
            }
        }
        return result
    }

    private fun toTool(cap: AgentCapability) = OllamaTool(
        function = OllamaFunction(
            name = cap.name,
            description = cap.description,
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    cap.parameters.forEach { (name, description) ->
                        putJsonObject(name) {
                            put("type", "string")
                            put("description", description)
                        }
                    }
                }
                putJsonArray("required") { cap.parameters.keys.forEach { add(it) } }
            },
        )
    )

    /** Ollama מחזירה שגיאות כ-{"error": "..."}. */
    private fun errorOf(body: String): String =
        runCatching { json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content }.getOrDefault(body)

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true // "stream": false ו-"type": "function" חייבים להישלח
            explicitNulls = false // שדות ריקים לא נשלחים בכלל
        }
    }
}

class LocalLlmException(val status: Int, message: String) : Exception("Ollama $status: $message")

// ------------------------------------------------------------
//  הפורמט של /api/chat
// ------------------------------------------------------------

@Serializable
private data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val tools: List<OllamaTool>? = null,
    val stream: Boolean = false,
)

@Serializable
private data class OllamaMessage(
    val role: String,
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable
private data class OllamaToolCall(val function: OllamaFunctionCall)

@Serializable
private data class OllamaFunctionCall(val name: String, val arguments: JsonObject)

@Serializable
private data class OllamaTool(val type: String = "function", val function: OllamaFunction)

@Serializable
private data class OllamaFunction(val name: String, val description: String, val parameters: JsonObject)

@Serializable
private data class OllamaResponse(
    val message: OllamaMessage,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0,
)
