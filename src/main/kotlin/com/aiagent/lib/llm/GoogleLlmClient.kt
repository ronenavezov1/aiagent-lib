package com.aiagent.lib.llm

import com.aiagent.lib.agent.AgentCapability
import com.aiagent.lib.agent.AgentHistory
import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.Content
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions
import com.google.genai.types.HttpRetryOptions
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.genai.types.FunctionCall as GenAiFunctionCall
import com.google.genai.types.Tool as GenAiTool

/**
 * מימוש Google Gemini של [LlmClient] — מבוסס על GeminiLlmClient מפרויקט Agent.
 *
 * 1. **דרגה = מודל.** אצל Gemini כל דרגה היא מודל אחר (למשל flash ל-FAST ו-pro ל-SMART).
 *
 * 2. **חתימות מחשבה.** מודלים חושבים של Gemini מצמידים חתימה לקריאת כלי, ודורשים
 *    לקבל אותה בחזרה על אותה קריאה. היא נשמרת ב-[FunctionCall.thoughtSignature].
 *
 * 3. **בלי מזהי כלים.** Gemini מצמידה תוצאה לקריאה לפי שם הכלי.
 *
 * @param baseUrl null = השרת של Google. אחרת: proxy, או שרת מזויף בבדיקות.
 */
class GoogleLlmClient(
    apiKey: String,
    private val fastModel: String,
    private val smartModel: String = fastModel,
    retry: RetryPolicy = RetryPolicy(),
    baseUrl: String? = null,
) : BaseLlmClient("Google Gemini", retry) {

    override fun modelNameFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> fastModel
        ModelTier.SMART -> smartModel
    }

    // הניסיונות החוזרים של BaseLlmClient מספיקים — בלי שכבה שנייה בתוך ה-SDK.
    private val client = Client.builder()
        .apiKey(apiKey)
        .httpOptions(
            HttpOptions.builder()
                .retryOptions(HttpRetryOptions.builder().attempts(1))
                .apply { baseUrl?.let { baseUrl(it) } }
                .build()
        )
        .build()

    override fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>,
        tier: ModelTier,
    ): Any = GeminiRequest(modelNameFor(tier), toContents(history), buildConfig(system, capabilities))

    @Throws(LlmSendException::class)
    override suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse {
        val req = payload as GeminiRequest

        // ה-SDK חוסם, ולכן יוצאים לתהליכון IO לפני הקריאה.
        val response = withContext(Dispatchers.IO) { client.models.generateContent(req.model, req.contents, req.config) }

        val parts = response.candidates().orElse(emptyList()).firstOrNull()
            ?.content()?.orElse(null)
            ?.parts()?.orElse(emptyList())
            .orEmpty()

        val funcCalls = parts.mapNotNull { part ->
            part.functionCall().orElse(null)?.let { fc ->
                FunctionCall(
                    functionName = fc.name().orElse(""),
                    arguments = fc.args().orElse(emptyMap()),
                    thoughtSignature = part.thoughtSignature().orElse(null),
                )
            }
        }

        // חלקי "מחשבה" הם לא תשובה — רק הטקסט שהמודל אומר.
        val textReply = if (funcCalls.isEmpty()) {
            parts.filterNot { it.thought().orElse(false) }
                .mapNotNull { it.text().orElse(null) }
                .joinToString("")
                .takeIf { it.isNotBlank() }
        } else null

        val usage = response.usageMetadata().orElse(null)
        return LlmResponse(
            textReply = textReply,
            functionCalls = funcCalls,
            inputTokens = usage?.promptTokenCount()?.orElse(0) ?: 0,
            outputTokens = usage?.candidatesTokenCount()?.orElse(0) ?: 0,
        )
    }

    /** 4xx הוא בעיה בבקשה או במפתח, חוץ מ-408/409/429 שחולפים מעצמם. */
    override fun isPermanent(e: Exception): Boolean =
        if (e is ApiException) e.code() in 400..499 && e.code() !in setOf(408, 409, 429)
        else super.isPermanent(e)

    // ------------------------------------------------------------
    //  היסטוריה → Content
    //
    //  Gemini לא מקבלת שני תורים רצופים של המודל: קריאות כלי רצופות
    //  מתמזגות ל-Content אחד, וכך גם התוצאות.
    // ------------------------------------------------------------
    private fun toContents(history: List<AgentHistory>): List<Content> {
        val result = mutableListOf<Content>()

        fun text(role: String, value: String) {
            result += Content.builder().role(role).parts(listOf(Part.builder().text(value).build())).build()
        }

        var i = 0
        while (i < history.size) {
            when (val action = history[i]) {

                is AgentHistory.UserInput -> {
                    text("user", action.text); i++
                }

                is AgentHistory.AiResponse -> {
                    text("model", action.text); i++
                }

                is AgentHistory.ToolCallRequest -> {
                    val parts = mutableListOf<Part>()
                    while (i < history.size && history[i] is AgentHistory.ToolCallRequest) {
                        val call = history[i] as AgentHistory.ToolCallRequest
                        val fc = GenAiFunctionCall.builder().name(call.toolName).args(call.arguments).build()
                        parts += Part.builder().functionCall(fc)
                            .apply { call.thoughtSignature?.let { thoughtSignature(it) } }
                            .build()
                        i++
                    }
                    result += Content.builder().role("model").parts(parts).build()
                }

                is AgentHistory.ToolExecutionResult -> {
                    val parts = mutableListOf<Part>()
                    while (i < history.size && history[i] is AgentHistory.ToolExecutionResult) {
                        val res = history[i] as AgentHistory.ToolExecutionResult
                        val key = if (res.isSuccess) "result" else "error"
                        val fr = FunctionResponse.builder().name(res.toolName).response(mapOf(key to res.resultData)).build()
                        parts += Part.builder().functionResponse(fr).build()
                        i++
                    }
                    result += Content.builder().role("user").parts(parts).build()
                }

                // אירוע מערכת — פנימי, לא נשלח למודל.
                is AgentHistory.SystemEvent -> i++

                // סיכום — נשלח כתור user, כדי שהמודל יבין שזה הקשר דחוס ולא שאלה חיה.
                is AgentHistory.Summary -> {
                    text("user", "סיכום השיחה עד כה (הקשר דחוס):\n${action.text}")
                    i++
                }
            }
        }
        return result
    }

    private fun buildConfig(systemPrompt: String, capabilities: List<AgentCapability>): GenerateContentConfig {
        val config = GenerateContentConfig.builder()
            .systemInstruction(Content.builder().parts(listOf(Part.builder().text(systemPrompt).build())).build())

        if (capabilities.isNotEmpty()) {
            val declarations = capabilities.map { cap ->
                val properties = cap.parameters.mapValues { (_, description) ->
                    Schema.builder().type(Type("STRING")).description(description).build()
                }
                FunctionDeclaration.builder()
                    .name(cap.name)
                    .description(cap.description)
                    .parameters(
                        Schema.builder()
                            .type(Type("OBJECT"))
                            .properties(properties)
                            .required(cap.parameters.keys.toList())
                            .build()
                    )
                    .build()
            }
            config.tools(listOf(GenAiTool.builder().functionDeclarations(declarations).build()))
        }

        return config.build()
    }

    private data class GeminiRequest(
        val model: String,
        val contents: List<Content>,
        val config: GenerateContentConfig,
    )
}
