package com.aiagent.lib.llm

import com.aiagent.lib.agent.AgentCapability
import com.aiagent.lib.agent.AgentHistory
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * מימוש Anthropic של [LlmClient].
 *
 * 1. **דרגה = מודל + עומק חשיבה.** FAST → Sonnet 5.5 עם effort נמוך,
 *    SMART → Opus 5.5 עם effort גבוה.
 *
 * 2. **מזהי כלים.** כל `tool_result` מצביע ל-`tool_use_id` של הקריאה שלו.
 *    [AgentHistory.ToolExecutionResult] מחזיק רק שם כלי, ולכן ההצמדה היא לפי
 *    סדר: קריאה ותוצאה מאותו שם מזדווגות לפי התור שבו הופיעו.
 *
 * 3. **החשיבה חוזרת כמו שהיא.** ב-Claude Opus 5.5 החשיבה תמיד פעילה, וכשהמודל
 *    קורא לכלים הוא מצפה לקבל את התור שלו בחזרה בלי שינוי, כולל בלוקי החשיבה.
 *    לכן התור כולו נשמר ב-[FunctionCall.thoughtSignature] של הקריאה הראשונה,
 *    ונשלח בחזרה כמו שהוא.
 *
 * @param workspaceId    נדרש רק כשהמפתח מקושר-זהות.
 * @param serverFallback אם מסווג הבטיחות מסרב, הבקשה רצה שוב על מודל אחר בצד
 *                       השרת. לכבות למודלים שלא תומכים בזה (למשל Haiku).
 * @param baseUrl        null = השרת של Anthropic. אחרת: proxy, או שרת מזויף בבדיקות.
 */
class AnthropicLlmClient(
    apiKey: String,
    workspaceId: String? = null,
    private val maxTokens: Long = 16_000,
    private val serverFallback: Boolean = true,
    retry: RetryPolicy = RetryPolicy(),
    baseUrl: String? = null,
) : BaseLlmClient("Anthropic", retry) {

    // הניסיונות החוזרים של BaseLlmClient מספיקים — בלי שכבה שנייה בתוך ה-SDK.
    private val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .maxRetries(0)
        .apply { workspaceId?.takeIf { it.isNotBlank() }?.let { putHeader("anthropic-workspace-id", it) } }
        .apply { baseUrl?.let { baseUrl(it) } }
        .build()

    override fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>,
        tier: ModelTier,
    ): Any = MessageCreateParams.builder()
        .model(modelNameFor(tier))
        .maxTokens(maxTokens)
        .system(system)
        .messages(toMessages(history))
        // בלי thinking: ב-Opus 5.5 ההשמטה היא חשיבה אדפטיבית, ו-disabled נדחה.
        .outputConfig(
            OutputConfig.builder()
                .effort(if (tier == ModelTier.SMART) OutputConfig.Effort.HIGH else OutputConfig.Effort.LOW)
                .build()
        )
        .apply { toTools(capabilities).forEach { addTool(it) } }
        .apply {
            if (serverFallback) {
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }
        .build()

    @Throws(LlmSendException::class)
    override suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse {
        val params = payload as MessageCreateParams

        // ה-SDK חוסם, ולכן יוצאים לתהליכון IO לפני הקריאה.
        val response = withContext(Dispatchers.IO) { client.messages().create(params) }

        if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
            val category = response.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("unknown")
            throw LlmNonFatalException(agentId, "Claude refused the request ($category)")
        }

        val funcCalls = response.content().mapNotNull { block ->
            block.toolUse().orElse(null)?.let { use ->
                @Suppress("UNCHECKED_CAST")
                FunctionCall(
                    functionName = use.name(),
                    arguments = use._input().convert(Map::class.java) as? Map<String, Any> ?: emptyMap(),
                )
            }
        }.mapIndexed { i, call -> if (i == 0) call.copy(thoughtSignature = encodeTurn(response)) else call }

        val textReply = if (funcCalls.isEmpty()) {
            response.content()
                .mapNotNull { it.text().orElse(null)?.text() }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        } else null

        val usage = response.usage()
        return LlmResponse(
            textReply = textReply,
            functionCalls = funcCalls,
            inputTokens = usage.inputTokens().toInt(),
            outputTokens = usage.outputTokens().toInt(),
        )
    }

    /** 4xx הוא בעיה בבקשה או במפתח, חוץ מ-408/409/429 שחולפים מעצמם. */
    override fun isPermanent(e: Exception): Boolean =
        if (e is AnthropicServiceException) e.statusCode() in 400..499 && e.statusCode() !in setOf(408, 409, 429)
        else super.isPermanent(e)

    // ------------------------------------------------------------
    //  היסטוריה → הודעות
    //
    //  קריאות כלי רצופות מתמזגות להודעה אחת, וכך גם התוצאות:
    //  Anthropic לא מקבלת שני תורים רצופים של אותו תפקיד.
    // ------------------------------------------------------------
    private fun toMessages(history: List<AgentHistory>): List<MessageParam> {
        val result = mutableListOf<MessageParam>()

        // תור מזהים לכל שם כלי: הקריאה נותנת מזהה, והתוצאה הבאה עם אותו שם שולפת אותו.
        val pendingIds = mutableMapOf<String, ArrayDeque<String>>()

        fun text(role: MessageParam.Role, value: String) {
            result += MessageParam.builder()
                .role(role)
                .contentOfBlockParams(listOf(ContentBlockParam.ofText(TextBlockParam.builder().text(value).build())))
                .build()
        }

        var i = 0
        while (i < history.size) {
            when (val action = history[i]) {

                is AgentHistory.UserInput -> {
                    text(MessageParam.Role.USER, action.text); i++
                }

                is AgentHistory.AiResponse -> {
                    text(MessageParam.Role.ASSISTANT, action.text); i++
                }

                is AgentHistory.ToolCallRequest -> {
                    val group = mutableListOf<AgentHistory.ToolCallRequest>()
                    while (i < history.size && history[i] is AgentHistory.ToolCallRequest) {
                        group += history[i] as AgentHistory.ToolCallRequest
                        i++
                    }
                    val turn = group.first().thoughtSignature?.let(::decodeTurn) ?: rebuildTurn(group)
                    turn.forEachToolUse { name, id -> pendingIds.getOrPut(name) { ArrayDeque() }.addLast(id) }
                    result += turn
                }

                is AgentHistory.ToolExecutionResult -> {
                    val blocks = mutableListOf<ContentBlockParam>()
                    while (i < history.size && history[i] is AgentHistory.ToolExecutionResult) {
                        val res = history[i] as AgentHistory.ToolExecutionResult
                        val id = pendingIds[res.toolName]?.removeFirstOrNull() ?: "call_${res.id}"
                        blocks += ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(id)
                                .content(res.resultData)
                                .isError(!res.isSuccess)
                                .build()
                        )
                        i++
                    }
                    result += MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(blocks)
                        .build()
                }

                // אירוע מערכת — פנימי, לא נשלח למודל.
                is AgentHistory.SystemEvent -> i++

                // סיכום — נשלח כתור user, כדי שהמודל יבין שזה הקשר דחוס ולא שאלה חיה.
                is AgentHistory.Summary -> {
                    text(MessageParam.Role.USER, "סיכום השיחה עד כה (הקשר דחוס):\n${action.text}")
                    i++
                }
            }
        }
        return result
    }

    /** כשאין תור מקורי (היסטוריה שנבנתה ביד, או מספק אחר) — בונים אותו מהקריאות. */
    private fun rebuildTurn(group: List<AgentHistory.ToolCallRequest>): MessageParam =
        MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(group.map { call ->
                ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder()
                        .id("call_${call.id}")
                        .name(call.toolName)
                        .input(ToolUseBlockParam.Input.builder().apply {
                            call.arguments.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                        }.build())
                        .build()
                )
            })
            .build()

    private fun MessageParam.forEachToolUse(action: (name: String, id: String) -> Unit) {
        content().blockParams().orElse(emptyList())
            .mapNotNull { it.toolUse().orElse(null) }
            .forEach { action(it.name(), it.id()) }
    }

    // ------------------------------------------------------------
    //  יכולות → כלים
    // ------------------------------------------------------------
    private fun toTools(capabilities: List<AgentCapability>): List<Tool> =
        capabilities.map { cap ->
            val properties = Tool.InputSchema.Properties.builder().apply {
                cap.parameters.forEach { (name, description) ->
                    putAdditionalProperty(name, JsonValue.from(mapOf("type" to "string", "description" to description)))
                }
            }.build()

            Tool.builder()
                .name(cap.name)
                .description(cap.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(properties)
                        .required(cap.parameters.keys.toList())
                        .build()
                )
                .build()
        }

    override fun modelNameFor(tier: ModelTier): String = when (tier) {
        ModelTier.FAST -> "claude-sonnet-5-5"
        ModelTier.SMART -> "claude-opus-5-5"
    }

    private companion object {
        fun encodeTurn(response: Message): ByteArray = jsonMapper().writeValueAsBytes(response)

        fun decodeTurn(bytes: ByteArray): MessageParam =
            jsonMapper().readValue(bytes, Message::class.java).toParam()
    }
}
