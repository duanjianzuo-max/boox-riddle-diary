package com.billtt.riddle

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI-compatible backend: Chat Completions + vision, streamed.
 *
 * baseUrl can point at any OpenAI-compatible endpoint -- Aliyun DashScope compatible-mode,
 * Zhipu, Moonshot, SiliconFlow, a local gateway, or OpenAI itself.
 *
 * Streaming is not a nicety here: the reply is inked sentence by sentence as it arrives, so
 * the diary starts writing seconds before the model finishes and the wait mostly disappears
 * behind the reveal animation.
 */
class OpenAiOracle(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String,
    /** The active profile's persona; OraclePrompts.PERSONA when that field was left blank. */
    private val persona: String,
) : Oracle {

    private val inFlight = AtomicReference<Call?>(null)

    override fun cancel() {
        inFlight.getAndSet(null)?.cancel()
    }

    override fun ask(pagePng: ByteArray, ctx: TurnContext, onEvent: (OracleEvent) -> Unit) {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pagePng)

        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", persona + "\n" + OraclePrompts.MEMORY_PROTOCOL)
        )
        // Earlier turns, without their transcription postscripts, so the diary remembers
        // what was just said rather than meeting the writer fresh every page.
        for ((transcript, reply) in ctx.recent) {
            messages.put(JSONObject().put("role", "user").put("content", transcript))
            if (reply.isNotBlank()) {
                messages.put(JSONObject().put("role", "assistant").put("content", reply))
            }
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUri))
                        )
                        .put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", OraclePrompts.turnText(ctx))
                        )
                )
        )

        // No token-limit parameter on purpose: max_tokens vs max_completion_tokens is not
        // portable across models and gateways. Reply length is bounded by the prompt.
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val parser = StreamParser(ctx.catalogIds)
        val full = StringBuilder()

        val call = client.newCall(request)
        inFlight.set(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // peekBody, not string(): an error body from an untrusted endpoint should
                    // not be read without bound just to put it in a message.
                    val text = response.peekBody(MAX_ERROR_BYTES).string()
                    throw IOException("HTTP ${response.code}: ${extractError(text)}")
                }
                val source = response.body?.source()
                    ?: throw IOException("empty response body")

                while (true) {
                    // Bounded: readUtf8Line() will happily buffer a single unterminated
                    // line of any size. Strict throws once the limit is passed, and also at
                    // clean end of stream -- both mean stop reading.
                    val line = try {
                        source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                    } catch (e: java.io.EOFException) {
                        break
                    }
                    if (line.isBlank()) continue
                    if (!line.startsWith(DATA_PREFIX)) continue
                    val payload = line.substring(DATA_PREFIX.length).trim()
                    if (payload == "[DONE]") break

                    val delta = runCatching {
                        JSONObject(payload)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .optJSONObject("delta")
                            ?.optString("content", "")
                            .orEmpty()
                    }.getOrDefault("")

                    if (delta.isEmpty()) continue
                    full.append(delta)
                    // A hostile or broken endpoint can stream forever. The diary asks for one
                    // to three sentences plus a transcription; past this it is not a reply.
                    if (full.length > MAX_REPLY_CHARS) {
                        Log.w(TAG, "reply exceeded $MAX_REPLY_CHARS chars, truncating")
                        break
                    }
                    parser.advance(full, done = false).forEach(onEvent)
                }
            }
        } finally {
            inFlight.compareAndSet(call, null)
        }
        parser.advance(full, done = true).forEach(onEvent)
    }

    private fun extractError(body: String): String = runCatching {
        JSONObject(body).getJSONObject("error").getString("message")
    }.getOrDefault(body.take(200))

    companion object {
        private const val TAG = "RiddleDiary"

        /** Upper bound on streamed reply text. */
        const val MAX_REPLY_CHARS = 20_000

        /** Upper bound on one SSE line. */
        const val MAX_SSE_LINE_BYTES = 1L shl 20

        /** Upper bound on an error body we only ever put in a message. */
        const val MAX_ERROR_BYTES = 4_096L

        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val DATA_PREFIX = "data:"

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Long read timeout: this is the gap between streamed chunks, not total time.
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
