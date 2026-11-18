package com.aiagent.lib.debug

import com.aiagent.lib.agent.AgentHistory
import org.slf4j.LoggerFactory

// ============================================================
//  לוג מפורט של מה שהסוכן ראה והחליט.
//
//  כבוי כברירת מחדל, כי הקלט והפלט מכילים את תוכן השיחה.
//  מדליקים אותו מהפרויקט שמשתמש בספרייה, ברמת DEBUG:
//    <logger name="com.aiagent.lib" level="DEBUG"/>
// ============================================================

private val log = LoggerFactory.getLogger("com.aiagent.lib.Agent")

object DebugLogger {

    val enabled: Boolean get() = log.isDebugEnabled

    fun agentStart(agentId: String, workOnId: String, modelName: String, input: String) {
        if (!enabled) return
        log.debug("▶ {} [{}] מודל={} \n  קלט: {}", agentId, workOnId, modelName, input)
    }

    fun agentDone(
        agentId: String,
        workOnId: String,
        modelName: String,
        history: List<AgentHistory>,
        result: String,
    ) {
        if (!enabled) return
        log.debug(
            "◀ {} [{}] מודל={} · {} פריטי היסטוריה\n  פלט: {}",
            agentId, workOnId, modelName, history.size, result,
        )
    }

    fun historySummarize(agentId: String, size: Int) {
        if (!enabled) return
        log.debug("⇲ {} מדחיס היסטוריה של {} פריטים", agentId, size)
    }

    fun historySummarizeError(agentId: String, e: Exception) {
        log.warn("דחיסת היסטוריה נכשלה עבור {}: {}", agentId, e.message)
    }

    fun llmRetry(provider: String, message: String, delayMs: Long, attempt: Int, maxRetries: Int) {
        log.warn("{} נכשל (ניסיון {}/{}), ממתין {}ms: {}", provider, attempt, maxRetries, delayMs, message)
    }

    fun llmClientFailure(provider: String, message: String) {
        log.error("{}: {}", provider, message)
    }
}
