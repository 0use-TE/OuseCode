package io.github.ouse.ousecode

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Stateless MCP Streamable HTTP. Only the app's local OpenCode receives its bearer token. */
class AndroidMcpServer(private val access: AndroidAccess) : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8), { runnable -> Thread(runnable, "android-mcp-request").apply { isDaemon = true } })
    val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val url = "http://127.0.0.1:${server.localPort}/mcp"
    init {
        thread(name = "android-mcp", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    try { workers.execute { socket.use(::serve) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { socket.close() }
                }
                catch (error: java.io.IOException) { if (!server.isClosed) throw error }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) { val value = input.read(); check(value >= 0) { "INCOMPLETE_HTTP" }; if (value == 10) break; check(bytes.size() < 8192) { "HEADER_TOO_LARGE" }; if (value != 13) bytes.write(value) }
            return bytes.toString("US-ASCII")
        }
        fun respond(status: Int, body: JSONObject? = null) {
            val bytes = body?.toString()?.toByteArray() ?: ByteArray(0)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Response"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes); output.flush()
        }
        try {
            val request = line().split(' ')
            val headers = mutableMapOf<String, String>()
            var total = 0
            while (true) { val header = line(); if (header.isEmpty()) break; total += header.length; check(total <= 16384); headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
            val expected = "Bearer $token".toByteArray()
            if (headers.containsKey("origin") || !MessageDigest.isEqual(expected, headers["authorization"].orEmpty().toByteArray())) { respond(403); return }
            if (request.size != 3 || request[1] != "/mcp") { respond(404); return }
            if (request[0] == "DELETE") { respond(204); return }
            if (request[0] != "POST") { respond(405); return }
            val length = headers["content-length"]?.toIntOrNull()
            if (length == null || length !in 1..262144 || headers.containsKey("transfer-encoding")) { respond(400); return }
            val data = ByteArray(length); var offset = 0
            while (offset < length) { val count = input.read(data, offset, length - offset); check(count > 0); offset += count }
            val message = JSONObject(String(data, Charsets.UTF_8))
            if (!message.has("id")) { respond(202); return }
            val reply = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"))
            try { reply.put("result", dispatch(message)) }
            catch (error: IllegalArgumentException) { reply.put("error", JSONObject().put("code", -32602).put("message", error.message ?: "INVALID_PARAMS")) }
            respond(200, reply)
        } catch (_: java.io.IOException) { /* Disconnected client; no command output or credentials are logged. */ }
        catch (_: org.json.JSONException) { respond(400) }
        catch (_: IllegalStateException) { respond(400) }
    }
    internal fun dispatch(message: JSONObject): JSONObject = when (message.getString("method")) {
        "initialize" -> JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", "OpenCode Android").put("version", "1.0"))
            .put("instructions", "Use android_exec for Android system commands. Ubuntu commands belong in the normal shell. In Smart mode known status queries run automatically; other commands wait up to 30 seconds for approval in the Android app. Supply purpose to explain the action. Do not retry a denial or work around a required approval. Android access and approval mode are controlled in the Android app settings.")
        "ping" -> JSONObject()
        "tools/list" -> JSONObject().put("tools", JSONArray().apply {
            if (access.mode != AndroidMode.OFF) put(JSONObject().put("name", "exec").put("description", "Execute an Android /system/bin/sh command using the user's authorized Shizuku identity. Working directory: /data/local/tmp. Returns stdout, stderr and exitCode. This is the Android host, not Ubuntu.")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("command", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 32768))
                    .put("purpose", JSONObject().put("type", "string").put("description", "Explain the requested Android action to the user."))
                    .put("timeoutSeconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 120).put("default", 30)))
                    .put("required", JSONArray().put("command")).put("additionalProperties", false)))
        })
        "tools/call" -> {
            val params = message.getJSONObject("params")
            require(params.getString("name") == "exec") { "UNKNOWN_TOOL" }
            val args = params.getJSONObject("arguments")
            try {
                val result = access.execute(args.getString("command"), args.optInt("timeoutSeconds", 30), args.optString("purpose"))
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
                    .put("isError", result.getInt("exitCode") != 0)
            } catch (error: Exception) {
                JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", error.message ?: error.javaClass.simpleName)))
            }
        }
        else -> throw IllegalArgumentException("METHOD_NOT_SUPPORTED")
    }
    override fun close() { server.close(); workers.shutdownNow() }
}
