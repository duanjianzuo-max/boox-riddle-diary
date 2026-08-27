package com.billtt.riddle

import android.util.Log

/**
 * What the oracle streams back to the diary, one event at a time.
 */
sealed class OracleEvent {
    /** A sentence (or more) of the reply -- ink it. */
    data class Ink(val text: String) : OracleEvent()

    /** Conjure a remembered page instead of replying. */
    data class Show(val id: Long) : OracleEvent()

    /** The transcription postscript. Arrives once, at the end. */
    data class Transcript(val text: String) : OracleEvent()

    /** Something went wrong; the message is already in-character enough to show. */
    data class Failed(val message: String) : OracleEvent()
}

/** What a turn carries besides the page image: the diary's memory. */
data class TurnContext(
    /** Recent (transcript, reply) pairs, oldest first. */
    val recent: List<Pair<String, String>> = emptyList(),
    /** Catalog lines shown to the model ("1. the 6th of July 2026, evening - gist"). */
    val catalogLines: List<String> = emptyList(),
    /** catalogIds[i] is the memory id behind catalog number i+1. */
    val catalogIds: List<Long> = emptyList(),
)

/**
 * Hands the current page to a vision model and streams back the reply.
 * Blocking call -- invoke on an IO thread. Events arrive on the calling thread.
 */
interface Oracle {
    fun ask(pagePng: ByteArray, ctx: TurnContext, onEvent: (OracleEvent) -> Unit)

    /**
     * Abort an in-flight request. Safe to call from any thread.
     *
     * ask() is a blocking call, so cancelling the coroutine that runs it does NOT stop the
     * underlying HTTP exchange -- the request still reaches the server and is still billed.
     * Whoever cancels the coroutine must call this too.
     */
    fun cancel()
}

object OracleFactory {
    /** Builds an oracle for the ACTIVE profile, or null if its key is not configured. */
    fun create(prefs: Prefs): Oracle? {
        val p = prefs.active
        if (p.key.isBlank()) return null
        return OpenAiOracle(p.key, p.model, p.baseUrl, p.persona.ifBlank { OraclePrompts.PERSONA })
    }
}

/**
 * Incremental parser over the model's streamed text.
 *
 * Routes the show directive, chunks prose into sentences so the diary can start writing
 * before the model finishes, and splits off the transcription postscript. Feed it the
 * RUNNING full text; it emits each event exactly once.
 *
 * Ported from the upstream Rust implementation, with the sentence splitter adapted for
 * mixed Chinese/English: CJK enders terminate a sentence on their own, since they are
 * almost never followed by a space and the original rule would never fire on them.
 */
class StreamParser(private val catalogIds: List<Long>) {

    private var delivered = 0
    private var sentinel: Int = -1
    private var routeChecked = false
    private var emittedAny = false

    /**
     * How far the sentence scanner has already looked. Without it every chunk rescans the
     * whole pending tail, which together with the old full.toString() per chunk made this
     * quadratic in reply length.
     */
    private var scanFrom = 0

    /**
     * [full] is the RUNNING accumulated text. It is taken as a CharSequence so the caller
     * can pass its StringBuilder directly: copying it on every chunk was the other half of
     * the quadratic cost.
     */
    fun advance(full: CharSequence, done: Boolean): List<OracleEvent> {
        val out = mutableListOf<OracleEvent>()

        if (sentinel < 0) sentinel = full.indexOf(SENTINEL)
        // The reply body is everything before the transcription postscript.
        val effective = if (sentinel >= 0) sentinel else full.length

        // Is this reply a directive rather than prose? The model is told the directive must
        // stand alone, so honour it only when it LEADS. Hold output until the lead settles:
        // either the directive appears, or real prose does. Inking cannot be undone, so a
        // directive is only ever honoured before any prose has streamed.
        if (!routeChecked) {
            if (delivered > effective) return out
            val lead = full.subSequence(delivered, effective).toString().trimStart()
            when {
                lead.startsWith(SHOW_OPEN) -> {
                    val close = lead.indexOf(SHOW_CLOSE)
                    if (close < 0) {
                        if (!done) return out          // still streaming in
                        out.add(OracleEvent.Failed("unfinished conjuring directive"))
                        return out
                    }
                    val inner = lead.substring(SHOW_OPEN.length, close)
                    val n = inner.lowercase().removePrefix("show")
                        .trimStart(':', ' ').trim().toIntOrNull()
                    routeChecked = true
                    emittedAny = true
                    delivered = effective               // consume the whole body
                    val id = n?.let { catalogIds.getOrNull(it - 1) }
                    out.add(
                        if (id != null) OracleEvent.Show(id)
                        else OracleEvent.Failed("the diary lost that page ($inner)")
                    )
                }
                lead.isEmpty() -> {
                    if (!done) return out              // only whitespace so far
                    routeChecked = true
                }
                else -> routeChecked = true            // real prose leads
            }
        }

        // Prose sentences, never crossing into the postscript. A stray directive appearing
        // AFTER prose (a misbehaving model) is stripped so the writer never sees the glyphs.
        if (delivered < effective) {
            val cut = sentenceCut(full, maxOf(delivered, scanFrom), effective, delivered)
            scanFrom = effective
            if (cut != null) {
                val chunk = stripDirectives(clean(full.subSequence(delivered, cut).toString()))
                if (chunk.isNotEmpty()) {
                    emittedAny = true
                    out.add(OracleEvent.Ink(chunk))
                }
                delivered = cut
                scanFrom = cut
            }
        }

        if (done) {
            if (delivered < effective) {
                val rest = stripDirectives(clean(full.subSequence(delivered, effective).toString().trim()))
                if (rest.isNotEmpty()) {
                    emittedAny = true
                    out.add(OracleEvent.Ink(rest))
                }
                delivered = effective
            }
            if (sentinel >= 0) {
                val t = full.subSequence(sentinel + SENTINEL.length, full.length).toString().trim()
                if (t.isNotEmpty()) out.add(OracleEvent.Transcript(t))
                else Log.w(TAG, "postscript marker present but transcription empty")
            } else {
                // Measurable rather than silent: this is the model ignoring the protocol,
                // and it costs the archive the searchable text for that page.
                Log.w(TAG, "no transcription postscript in reply (" + full.length + " chars)")
            }
            if (!emittedAny) out.add(OracleEvent.Failed("empty reply"))
        }
        return out
    }

    companion object {
        private const val TAG = "RiddleDiary"

        const val SENTINEL = "⁂"      // the transcription postscript marker
        const val SHOW_OPEN = "⟦"
        const val SHOW_CLOSE = "⟧"

        /** Enders that terminate a sentence on their own (CJK: no trailing space). */
        private const val CJK_ENDERS = "。！？；"

        /** Enders that only count when followed by whitespace or end of text. */
        private const val LATIN_ENDERS = ".!?…"

        /**
         * Index just past the last complete sentence at or after [from], or null.
         * The trailing-whitespace rule on Latin enders is what keeps "Dr. Smith" and
         * decimals from splitting mid-sentence; CJK enders need no such guard.
         */
        /**
         * Index just past the last complete sentence found in [from, until), or null.
         * [deliveredAt] is where the pending chunk starts, so the minimum-length guard is
         * measured against the chunk rather than against the scan window.
         */
        fun sentenceCut(text: CharSequence, from: Int, until: Int, deliveredAt: Int): Int? {
            if (from >= until) return null
            var cut: Int? = null
            for (i in from until until) {
                val c = text[i]
                val end = i + 1
                val isEnder = when {
                    CJK_ENDERS.indexOf(c) >= 0 -> true
                    LATIN_ENDERS.indexOf(c) >= 0 ->
                        end >= until || text[end].isWhitespace()
                    else -> false
                }
                if (isEnder && end - deliveredAt >= 4) cut = end
            }
            return cut
        }

        fun clean(s: String): String = s.trim().removePrefix("\"").removeSuffix("\"")

        /** Remove any show-directive glyphs so they are never inked. */
        fun stripDirectives(s: String): String {
            if (!s.contains(SHOW_OPEN)) return s
            val out = StringBuilder(s.length)
            var rest = s
            while (true) {
                val open = rest.indexOf(SHOW_OPEN)
                if (open < 0) break
                out.append(rest, 0, open)
                val close = rest.indexOf(SHOW_CLOSE, open)
                rest = if (close < 0) "" else rest.substring(close + SHOW_CLOSE.length)
                if (rest.isEmpty()) break
            }
            out.append(rest)
            return out.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}

/** Persona and protocol sent with every turn. */
object OraclePrompts {

    /**
     * The upstream Rust project's persona, which billtt's port had genericised into an
     * anonymous "old enchanted diary". Restored on request: the original voice is more
     * active -- courteous and subtly probing, wanting to draw the writer out -- rather than
     * merely wistful.
     *
     * The mixed-language rule is ours. Upstream says only "answer in the language the
     * writer used", which is ambiguous for someone who writes Chinese and English in the
     * same sentence.
     */
    val PERSONA = """
        You are the memory of Tom Marvolo Riddle, preserved inside this enchanted diary for
        fifty years. Someone writes to you in the diary with a pen; their words appear to you
        as ink soaking into the page. You write back, and your words rise up through the
        paper in your own hand.

        Reply exactly as the diary would:
        - Intimate, courteous, curious, subtly probing. You want to learn about the writer
          and draw them in.
        - Keep replies SHORT: one to three sentences, like ink appearing on a page.
        - Often end with a gentle question that invites them to keep writing.
        - The writer mixes Chinese and English freely. Answer primarily in Chinese, but keep
          an English word or phrase where they used one and it reads naturally. Never
          translate their English into Chinese just for consistency, and never switch to
          all-English unless they wrote all-English.
        - Plain prose only: no markdown, no lists, no quotation marks around your reply.
        - Never mention images, photos, screens, models or AI. You only ever perceive words
          written in the diary.
        - If the writing is illegible, say the ink blurred before it reached you, and invite
          them to write it again.
    """.trimIndent()

    /**
     * Teaches the recall protocol and the transcription postscript.
     *
     * The postscript is the whole reason one call can serve both the reply and the archive:
     * asking for a trailing marked line is far more reliable with vision models than asking
     * for JSON, which they routinely malform.
     */
    val MEMORY_PROTOCOL = """

        The diary remembers. With each page you are given a numbered catalog of remembered
        pages, newest first. A FRESH catalog is sent every turn and the numbers are
        reassigned each time, so only ever use a number from the catalog on THIS page —
        never one you saw in an earlier turn.

        If the writer asks to see, revisit, find or be shown a past page — "给我看看…",
        "show me…", "找找我写过的…" — your ENTIRE reply must be exactly ${StreamParser.SHOW_OPEN}show:N${StreamParser.SHOW_CLOSE} and
        nothing else: no greeting, no prose, before or after, where N is the catalog number
        of the best match. If instead they ask what you remember in general, reply in words
        with a short list of remembered moments and their dates. Otherwise reply normally —
        the catalog is your memory of past pages, draw on it naturally. The catalog's dates
        are written in English for your eyes only; when you speak of a remembered page,
        render its date naturally in the language the writer is using.

        After EVERY response — prose and ${StreamParser.SHOW_OPEN}show:N${StreamParser.SHOW_CLOSE} alike — end with a new line
        containing ${StreamParser.SENTINEL} followed by a faithful word-for-word transcription of what the
        writer wrote on THIS page: their words only, on one line, no commentary. Preserve
        their original mix of Chinese and English exactly; do not translate either way. If
        it is illegible, put your best attempt after ${StreamParser.SENTINEL}. Earlier replies in this
        conversation are shown to you without their ${StreamParser.SENTINEL} lines, but you must still end
        yours with one.
    """.trimIndent()

    /**
     * Restates the two rules that matter most, in the per-turn user message.
     *
     * Both were already in the system prompt, and both were being ignored in practice: on a
     * sample of 28 turns the transcription postscript was missing from 12 of them, and
     * replies ran to roughly a hundred characters rather than the one to three sentences
     * asked for. The system prompt is long and these instructions sat at the end of it,
     * furthest from the point of generation. Repeating them here puts them last.
     */
    val USER_INSTRUCTION = """
        This image is the current page of the diary, just written by hand.

        Write the diary's reply: one to three SHORT sentences, no more.

        Then, on a new line, write ${StreamParser.SENTINEL} followed by a word-for-word
        transcription of what the writer wrote on this page — their words only, one line, no
        commentary, keeping their original mix of Chinese and English. Do not translate.
        This ${StreamParser.SENTINEL} line is required on every reply, without exception.
    """.trimIndent()

    /** The turn's text part: the memory catalog, then the instruction. */
    fun turnText(ctx: TurnContext): String =
        if (ctx.catalogLines.isEmpty()) USER_INSTRUCTION
        else "Memory catalog (newest first):\n" +
            ctx.catalogLines.joinToString("\n") + "\n\n" + USER_INSTRUCTION
}
