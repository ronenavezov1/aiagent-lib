package com.aiagent.lib

import com.aiagent.lib.agent.Tool
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress

/**
 * שרת HTTP מקומי שעונה תשובות מוכנות לפי הסדר, ושומר כל בקשה שקיבל.
 * ככה בודקים לקוח אמיתי — כולל ה-SDK — בלי רשת ובלי מפתח.
 */
class FakeServer(vararg replies: Reply) : AutoCloseable {

    data class Reply(val body: String, val status: Int = 200)

    data class Request(val path: String, val headers: Headers, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    val requests = mutableListOf<Request>()
    private val replies = ArrayDeque(replies.toList())

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            synchronized(requests) { requests += Request(exchange.requestURI.path, exchange.requestHeaders, body) }

            val reply = synchronized(this@FakeServer.replies) { this@FakeServer.replies.removeFirstOrNull() }
                ?: Reply("""{"error":"no more replies"}""", 500)
            val bytes = reply.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

/** כלי אחד, משותף לכל הבדיקות. */
class WeatherTool : Tool {
    var calls = 0
    override val name = "get_weather"
    override val description = "מחזיר את מזג האוויר בעיר"
    override val parameters = mapOf("city" to "שם העיר")

    override suspend fun execute(args: Map<String, Any>): String {
        calls++
        return "32 מעלות ב${args["city"]}"
    }
}
