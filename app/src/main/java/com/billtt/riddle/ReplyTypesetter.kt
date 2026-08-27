package com.billtt.riddle

import android.graphics.Paint
import android.graphics.Typeface

/**
 * One animatable unit after layout: a single CJK character, or one Western word.
 * [cjk] selects the typeface at draw time — the two scripts get different hands.
 */
data class ReplyWord(val text: String, val x: Float, val y: Float, val cjk: Boolean)

data class ReplyLayout(val words: List<ReplyWord>, val textSizePx: Float)

/**
 * Lays the reply out on the page: starting around the top third, each line centered, split
 * per-character for CJK and per-word for Western text so every unit can be revealed or
 * faded independently.
 *
 * Mixed Chinese/English is handled per *run*, not per reply. Upstream decided once, for the
 * whole text, whether it was CJK — so a reply containing any Chinese also went down the CJK
 * path, which walks character by character. That split English words into loose letters and,
 * because the CJK path uses a zero-width space, ran them together. Both scripts appear in
 * the same reply here, so the tokenizer switches script as it goes.
 */
object ReplyTypesetter {

    /** One laid-out token before positioning. */
    private data class Token(val text: String, val cjk: Boolean, val spaceBefore: Boolean)

    private fun isCjk(c: Char): Boolean {
        val b = Character.UnicodeBlock.of(c)
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            b == Character.UnicodeBlock.HIRAGANA ||
            b == Character.UnicodeBlock.KATAKANA
    }

    private fun isTrailingPunct(c: Char): Boolean = c in "，。！？；：、”’）》…—,.!?;:)\"'"

    /**
     * Split into animation units, switching script mid-string.
     *
     * CJK characters become their own unit, with trailing punctuation glued to the character
     * it follows. Runs of non-CJK, non-space characters stay whole as words. A word gets a
     * leading space when the source had one; between a CJK character and a Latin word we add
     * one anyway, which is the usual convention and keeps "写了 essay 之后" from colliding.
     */
    private fun tokenize(text: String): List<Token> {
        val cleaned = text.trim().replace(Regex("\\s+"), " ")
        val tokens = mutableListOf<Token>()
        var i = 0
        var pendingSpace = false
        while (i < cleaned.length) {
            val c = cleaned[i]
            when {
                c == ' ' -> { pendingSpace = true; i++ }
                isCjk(c) -> {
                    // Punctuation after a CJK char rides along, so it never starts a line.
                    if (tokens.isNotEmpty() && isTrailingPunct(c) && !pendingSpace) {
                        val last = tokens.removeAt(tokens.size - 1)
                        tokens.add(last.copy(text = last.text + c))
                    } else {
                        tokens.add(Token(c.toString(), cjk = true, spaceBefore = pendingSpace))
                    }
                    pendingSpace = false
                    i++
                }
                else -> {
                    val start = i
                    while (i < cleaned.length && cleaned[i] != ' ' && !isCjk(cleaned[i])) i++
                    val word = cleaned.substring(start, i)
                    // A Latin run touching CJK on its left still needs breathing room.
                    val needsSpace = pendingSpace || tokens.lastOrNull()?.cjk == true
                    tokens.add(Token(word, cjk = false, spaceBefore = needsSpace))
                    pendingSpace = false
                }
            }
        }
        return tokens
    }

    /**
     * @param paint measured with; its typeface is set per token, so pass the same paint the
     *   view draws with and supply both faces.
     */
    fun layout(
        text: String,
        pageWidth: Int,
        pageHeight: Int,
        paint: Paint,
        cjkFace: Typeface,
        latinFace: Typeface,
    ): ReplyLayout {
        var textSize = pageWidth / 24f
        val maxWidth = pageWidth * 0.78f
        val topStart = pageHeight * 0.30f
        val maxBottom = pageHeight * 0.85f
        val tokens = tokenize(text)

        var words: List<ReplyWord>
        while (true) {
            paint.textSize = textSize
            words = flow(tokens, maxWidth, pageWidth, topStart, paint, cjkFace, latinFace)
            val bottom = words.lastOrNull()?.y ?: topStart
            if (bottom <= maxBottom || textSize <= pageWidth / 40f) break
            textSize *= 0.88f
        }
        return ReplyLayout(words, textSize)
    }

    /** Fill line by line and center each line horizontally. */
    private fun flow(
        tokens: List<Token>,
        maxWidth: Float,
        pageWidth: Int,
        topStart: Float,
        paint: Paint,
        cjkFace: Typeface,
        latinFace: Typeface,
    ): List<ReplyWord> {
        paint.typeface = latinFace
        val spaceW = paint.measureText(" ")
        val lineHeight = paint.textSize * 1.7f

        // (token, width, gap before it on this line)
        data class Placed(val t: Token, val w: Float, val gap: Float)

        val lines = mutableListOf<MutableList<Placed>>()
        var line = mutableListOf<Placed>()
        var lineW = 0f
        for (t in tokens) {
            paint.typeface = if (t.cjk) cjkFace else latinFace
            val w = paint.measureText(t.text)
            val gap = if (line.isEmpty() || !t.spaceBefore) 0f else spaceW
            if (lineW + gap + w > maxWidth && line.isNotEmpty()) {
                lines.add(line)
                // The token starts a fresh line, so it carries no leading gap.
                line = mutableListOf(Placed(t, w, 0f))
                lineW = w
                continue
            }
            line.add(Placed(t, w, gap))
            lineW += gap + w
        }
        if (line.isNotEmpty()) lines.add(line)

        val words = mutableListOf<ReplyWord>()
        var y = topStart
        for (l in lines) {
            val totalW = l.sumOf { (it.w + it.gap).toDouble() }.toFloat()
            var x = (pageWidth - totalW) / 2f
            for (p in l) {
                x += p.gap
                words.add(ReplyWord(p.t.text, x, y, p.t.cjk))
                x += p.w
            }
            y += lineHeight
        }
        return words
    }
}
