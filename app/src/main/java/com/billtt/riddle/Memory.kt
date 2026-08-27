package com.billtt.riddle

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The diary's memory. Every finished turn is kept -- the writer's actual pen strokes, a
 * transcription of their words, and the diary's reply -- so a later request ("show me what
 * I wrote about the garden") can conjure the page back in the writer's own hand.
 *
 * This is a port of the upstream Rust project's memory layer, which billtt's BOOX port
 * dropped entirely. Storage is plain files under getExternalFilesDir/memories so the
 * archive can be pulled off the device without root:
 *
 *   index.tsv        one line per memory: id \t transcript \t reply
 *                    (tabs / newlines / backslashes escaped)
 *   <id>.strokes     the pen strokes, one line per stroke: "x,y,p;x,y,p;..."
 *
 * Strokes rather than a PNG: recalling a page redraws it in the writer's own hand, and the
 * files stay small. Delete the directory and the diary forgets.
 */
class MemoryStore private constructor(private val dir: File) {

    data class Entry(
        /** Unix seconds when the page was committed. Also the strokes filename. */
        val id: Long,
        val transcript: String,
        val reply: String,
    )

    val entries = mutableListOf<Entry>()

    private val indexFile get() = File(dir, "index.tsv")

    // ------------------------------------------------------------------ load / save

    private fun load() {
        entries.clear()
        val f = indexFile
        if (!f.isFile) return
        f.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('\t')
            if (parts.size < 3) return@forEachLine
            val id = parts[0].toLongOrNull() ?: return@forEachLine
            entries.add(Entry(id, unescape(parts[1]), unescape(parts[2])))
        }
    }

    /**
     * Commit one finished turn. Strokes are decimated before writing: points closer than
     * MIN_POINT_DIST px to the last kept one are dropped, which keeps the handwriting
     * faithful while shrinking the file several-fold.
     */
    fun append(id: Long, transcript: String, reply: String, strokes: List<Stroke>) {
        runCatching {
            dir.mkdirs()
            File(dir, "$id.strokes").writeText(encodeStrokes(strokes))
            indexFile.appendText("$id\t${escape(transcript)}\t${escape(reply)}\n")
        }
        entries.add(Entry(id, transcript, reply))
        if (entries.size > MAX_MEMORIES) prune()
    }

    /** Drop the oldest entries and rewrite the index; their stroke files go too. */
    private fun prune() {
        val drop = entries.size - MAX_MEMORIES
        if (drop <= 0) return
        val removed = entries.take(drop)
        entries.subList(0, drop).clear()
        runCatching {
            removed.forEach { File(dir, "${it.id}.strokes").delete() }
            indexFile.writeText(
                entries.joinToString("") { "${it.id}\t${escape(it.transcript)}\t${escape(it.reply)}\n" }
            )
        }
    }

    /**
     * Forget everything: delete every stored page and the index.
     *
     * Turning the memory setting off only stops new pages being written and sent; the pages
     * already on disk stay there. This is the only way to actually remove them.
     */
    fun forgetAll(): Boolean {
        entries.clear()
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    fun get(id: Long): Entry? = entries.firstOrNull { it.id == id }

    /** The strokes of a remembered page, ready to redraw. */
    fun strokes(id: Long): List<Stroke>? = runCatching {
        val f = File(dir, "$id.strokes")
        if (!f.isFile) return null
        decodeStrokes(f.readText())
    }.getOrNull()

    // ------------------------------------------------------------------ oracle context

    /** The last n turns as (transcript, reply), oldest first -- the running conversation. */
    fun recentDialogue(n: Int): List<Pair<String, String>> =
        entries.filter { it.transcript.isNotBlank() }
            .takeLast(n)
            .map { it.transcript to it.reply }

    /**
     * The numbered catalog the model reads so it can conjure a page back.
     *
     * Newest first, and the numbers are reassigned every turn -- the prompt tells the model
     * to only ever use numbers from the catalog on the current page, which is what stops it
     * citing a stale index. Returns the display lines and the ids they map to, so catalog
     * number i+1 resolves to ids[i].
     */
    fun catalog(max: Int): Pair<List<String>, List<Long>> {
        val lines = mutableListOf<String>()
        val ids = mutableListOf<Long>()
        entries.asReversed().take(max).forEachIndexed { i, e ->
            val gist = if (e.transcript.isBlank()) "(illegible)" else oneLine(e.transcript, 70)
            // One entry per line: a gist must never carry its own newline, or the model
            // sees a catalog line that does not start with a number.
            lines.add("${i + 1}. ${spokenDate(e.id)} - $gist")
            ids.add(e.id)
        }
        return lines to ids
    }

    // ------------------------------------------------------------------ encoding

    private fun encodeStrokes(strokes: List<Stroke>): String = buildString {
        for (s in strokes) {
            var lastX = Int.MIN_VALUE
            var lastY = Int.MIN_VALUE
            var wrote = false
            for (p in s.points) {
                val x = p.x.toInt()
                val y = p.y.toInt()
                if (lastX != Int.MIN_VALUE) {
                    val dx = (x - lastX).toLong()
                    val dy = (y - lastY).toLong()
                    if (dx * dx + dy * dy < MIN_POINT_DIST2) continue
                }
                if (wrote) append(';')
                append(x).append(',').append(y).append(',')
                    .append((p.pressure * 1000f).toInt().coerceIn(0, 1000))
                lastX = x
                lastY = y
                wrote = true
            }
            append('\n')
        }
    }

    private fun decodeStrokes(text: String): List<Stroke> =
        text.lineSequence()
            .map { line -> line.split(';').mapNotNull(::decodePoint) }
            .filter { it.size >= 2 }
            .map { Stroke(it) }
            .toList()

    /** "x,y,p" -> a point, or null if the field is malformed. */
    private fun decodePoint(field: String): StrokePoint? {
        val f = field.split(',')
        if (f.size < 3) return null
        val x = f[0].toFloatOrNull() ?: return null
        val y = f[1].toFloatOrNull() ?: return null
        val p = f[2].toFloatOrNull() ?: return null
        return StrokePoint(x, y, p / 1000f)
    }

    companion object {
        /** Newest memories the diary keeps; older pages are forgotten. */
        const val MAX_MEMORIES = 400

        /** Decimation threshold, squared: drop replay points within 3 px of the last kept one. */
        const val MIN_POINT_DIST2 = 9L

        fun open(context: Context): MemoryStore {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val store = MemoryStore(File(base, "memories"))
            runCatching { store.load() }
            return store
        }

        private fun escape(s: String): String = s
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "")

        private fun unescape(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> { out.append('\t'); i += 2; continue }
                        'n' -> { out.append('\n'); i += 2; continue }
                        '\\' -> { out.append('\\'); i += 2; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        /** Collapse to a single line and clip, so one memory is always one catalog line. */
        private fun oneLine(s: String, max: Int): String {
            val flat = s.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= max) flat else flat.take(max - 1) + "…"
        }

        /**
         * A date the model can speak about naturally. Deliberately English: the prompt tells
         * the model the catalog dates are for its eyes only, and to render them in whatever
         * language the writer is using.
         */
        fun spokenDate(idSeconds: Long): String {
            val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US)
            cal.timeInMillis = idSeconds * 1000L
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = MONTHS[cal.get(Calendar.MONTH)]
            val year = cal.get(Calendar.YEAR)
            val part = when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..22 -> "evening"
                else -> "night"
            }
            return "the ${day}${ordinal(day)} of $month $year, $part"
        }

        private fun ordinal(d: Int): String = when {
            d % 100 in 11..13 -> "th"
            d % 10 == 1 -> "st"
            d % 10 == 2 -> "nd"
            d % 10 == 3 -> "rd"
            else -> "th"
        }

        private val MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
    }
}
