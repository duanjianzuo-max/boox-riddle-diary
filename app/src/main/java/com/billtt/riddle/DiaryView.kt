package com.billtt.riddle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.io.ByteArrayOutputStream

/**
 * The diary page (a plain View). Holds all strokes and reply words, plus each
 * element's "ink level".
 *
 * Writing phase: pen points arrive via DiaryController and are drawn live here
 * (software) with throttled local refreshes; this class also accumulates the
 * stroke data (used by the post-writing animation and the screenshot).
 * Animation phase: onDraw composes the whole page by ink level, and DU4 fast
 * refresh renders the stepped fade in/out.
 */
class DiaryView(context: Context) : View(context) {

    // ---- strokes ----
    val strokes = mutableListOf<Stroke>()
    val strokeAlphas = mutableListOf<Float>()

    // ---- the stroke currently being written (live display) ----
    private val currentPoints = mutableListOf<StrokePoint>()
    private val liveDirty = RectF()
    private var liveDirtyValid = false
    private var lastLiveFlush = 0L

    // ---- absorb animation: offscreen cache split into bands in write order, staggered fade ----
    private class AbsorbBand(val bmp: Bitmap, val left: Float, val top: Float)
    private val absorbBands = mutableListOf<AbsorbBand>()
    var absorbBandAlphas = FloatArray(0)

    // ---- reply ----
    var replyWords: List<ReplyWord> = emptyList()
        private set
    var wordAlphas = FloatArray(0)
        private set
    private var replyTextSize = 0f
    /** Two hands: a Chinese calligraphic face and a Latin script face, chosen per word. */
    private val cjkFace: Typeface by lazy {
        runCatching { resources.getFont(R.font.ma_shan_zheng) }.getOrDefault(Typeface.SERIF)
    }
    private val latinFace: Typeface by lazy {
        runCatching { resources.getFont(R.font.dancing_script) }.getOrDefault(Typeface.SERIF)
    }

    /**
     * ~0.5 mm of ink. Upstream hardcoded 4.5 px, which is 0.5 mm on the Note X2's
     * 227 PPI panel but only 0.38 mm on this 300 PPI one, so express it physically
     * and let it come out right on both.
     */
    var baseStrokeWidth = resources.displayMetrics.xdpi * 0.5f / 25.4f

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
    }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.WHITE)
    }

    companion object {
        /** Throttle interval (ms) for the local live-ink refresh. */
        const val LIVE_THROTTLE_MS = 45L

        /** Number of bands the absorb animation splits strokes into, in write order
         *  (more = finer ordering, a few more bitmaps drawn per frame). */
        const val ABSORB_BANDS = 10

        /** Ceiling on the offscreen bitmaps the absorb animation may hold at once. */
        const val ABSORB_BUDGET_BYTES = 48L * 1024 * 1024
    }

    // ------------------------------------------------------------------ strokes

    /** Put a remembered page back on screen, redrawn from its own strokes. */
    fun showStrokes(list: List<Stroke>) {
        clearStrokes()
        for (st in list) addStroke(st)
        invalidate()
    }

    fun addStroke(stroke: Stroke) {
        if (stroke.points.size < 2) return
        strokes.add(stroke)
        strokeAlphas.add(1f)
    }

    // ---------- live ink (writing phase) ----------

    /** Pen-down: clear the live-stroke buffer. */
    fun beginLiveStroke() {
        currentPoints.clear()
        liveDirtyValid = false
    }

    /**
     * A pen point arrived: add it to the live stroke and, throttled, do a local redraw +
     * local fast refresh — only the small region around the new segment is refreshed, to
     * keep the e-ink pen-tracking latency as low as possible.
     */
    fun addLivePoint(p: StrokePoint) {
        val prev = currentPoints.lastOrNull()
        currentPoints.add(p)
        if (prev != null) {
            val pad = baseStrokeWidth * 2f
            val l = minOf(prev.x, p.x) - pad
            val t = minOf(prev.y, p.y) - pad
            val r = maxOf(prev.x, p.x) + pad
            val b = maxOf(prev.y, p.y) + pad
            if (liveDirtyValid) liveDirty.union(l, t, r, b) else { liveDirty.set(l, t, r, b); liveDirtyValid = true }
        }
        val now = SystemClock.uptimeMillis()
        if (liveDirtyValid && now - lastLiveFlush >= LIVE_THROTTLE_MS) {
            lastLiveFlush = now
            invalidate(
                liveDirty.left.toInt(), liveDirty.top.toInt(),
                liveDirty.right.toInt() + 1, liveDirty.bottom.toInt() + 1,
            )
            liveDirtyValid = false
        }
    }

    /** Pen-up: the final stroke is already merged into strokes; clear the live stroke and redraw. */
    fun endLiveStroke() {
        currentPoints.clear()
        liveDirtyValid = false
        invalidate()
    }

    // ---------- absorb animation: page-ink cache ----------

    /**
     * Before absorption: split strokes evenly into bands in write order, and render each
     * band into a small bitmap covering only its bounding box. Then each frame only draws
     * these few bitmaps and adjusts per-band level — preserving write order while keeping
     * the redraw cost minimal.
     */
    fun prepareAbsorb() {
        finishAbsorb()
        val n = strokes.size
        if (n == 0 || width <= 0 || height <= 0) return

        // Each band gets a bitmap sized to its own bounding box, and those boxes overlap
        // heavily once ink is spread across the page -- a full page of writing makes every
        // one of them nearly full-screen. Ten such bitmaps at 1860x2480 ARGB_8888 is ~184 MB,
        // which is an OOM on a device like this. Bound the total and use fewer, larger bands
        // when the ink is spread out; sparse pages still get the full count.
        val union = RectF()
        strokes.forEachIndexed { i, st -> if (i == 0) union.set(st.bounds) else union.union(st.bounds) }
        val worstBandBytes =
            (union.width().coerceIn(1f, width.toFloat()) *
                union.height().coerceIn(1f, height.toFloat()) * 4f).toLong().coerceAtLeast(1L)
        val affordable = (ABSORB_BUDGET_BYTES / worstBandBytes).toInt().coerceAtLeast(1)
        val bands = n.coerceAtMost(ABSORB_BANDS).coerceAtMost(affordable)
        val perBand = (n + bands - 1) / bands
        val paint = Paint(inkPaint).apply { alpha = 255 }
        var idx = 0
        while (idx < n) {
            val group = strokes.subList(idx, minOf(idx + perBand, n))
            val r = RectF()
            group.forEachIndexed { gi, s -> if (gi == 0) r.set(s.bounds) else r.union(s.bounds) }
            val pad = baseStrokeWidth * 1.5f
            val l = (r.left - pad).coerceAtLeast(0f)
            val t = (r.top - pad).coerceAtLeast(0f)
            val right = (r.right + pad).coerceAtMost(width.toFloat())
            val bottom = (r.bottom + pad).coerceAtMost(height.toFloat())
            val bw = (right - l).toInt().coerceAtLeast(1)
            val bh = (bottom - t).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp).apply { translate(-l, -t) }
            for (s in group) drawStroke(c, s, paint)
            absorbBands.add(AbsorbBand(bmp, l, t))
            idx += perBand
        }
        absorbBandAlphas = FloatArray(absorbBands.size) { 1f }
    }

    fun finishAbsorb() {
        for (b in absorbBands) b.bmp.recycle()
        absorbBands.clear()
        absorbBandAlphas = FloatArray(0)
    }

    fun clearStrokes() {
        strokes.clear()
        strokeAlphas.clear()
        invalidate()
    }

    /** Eraser: remove whole strokes intersecting the erase points. Returns whether anything was removed. */
    fun eraseAt(points: List<StrokePoint>, radius: Float): Boolean {
        var removed = false
        val it = strokes.listIterator()
        var idx = 0
        while (it.hasNext()) {
            val s = it.next()
            val hit = points.any { p ->
                s.bounds.left - radius <= p.x && p.x <= s.bounds.right + radius &&
                    s.bounds.top - radius <= p.y && p.y <= s.bounds.bottom + radius &&
                    s.intersectsCircle(p.x, p.y, radius)
            }
            if (hit) {
                it.remove()
                strokeAlphas.removeAt(idx)
                removed = true
            } else {
                idx++
            }
        }
        return removed
    }

    // ------------------------------------------------------------------ reply

    fun setReply(text: String) {
        val layout = ReplyTypesetter.layout(text, width, height, textPaint, cjkFace, latinFace)
        replyWords = layout.words
        replyTextSize = layout.textSizePx
        wordAlphas = FloatArray(replyWords.size)   // start from 0 (invisible)
    }

    fun clearReply() {
        replyWords = emptyList()
        wordAlphas = FloatArray(0)
        invalidate()
    }

    // ------------------------------------------------------------------ drawing

    /** Quantize a continuous level to 5 steps (0/.25/.5/.75/1) to match DU4 and cut useless frames. */
    private fun quantizeAlpha(a: Float): Float = Math.round(a * 4f) / 4f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        if (absorbBands.isNotEmpty()) {
            // Absorb phase: small band bitmaps in write order, per-band level (stepped), minimal redraw cost.
            for (i in absorbBands.indices) {
                val a = quantizeAlpha(absorbBandAlphas.getOrElse(i) { 0f })
                if (a <= 0.02f) continue
                bmpPaint.alpha = (a * 255).toInt()
                val band = absorbBands[i]
                canvas.drawBitmap(band.bmp, band.left, band.top, bmpPaint)
            }
        } else {
            drawStrokes(canvas)
            drawCurrent(canvas)
        }
        drawReply(canvas)
    }

    /** Draw the stroke currently being written (full-ink black). */
    private fun drawCurrent(canvas: Canvas) {
        if (currentPoints.size < 2) return
        inkPaint.alpha = 255
        for (j in 1 until currentPoints.size) {
            val a = currentPoints[j - 1]
            val b = currentPoints[j]
            inkPaint.strokeWidth = baseStrokeWidth * (0.6f + 0.8f * b.pressure)
            canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
        }
    }

    private fun drawStrokes(canvas: Canvas) {
        for (i in strokes.indices) {
            val alpha = quantizeAlpha(strokeAlphas[i])
            if (alpha <= 0.02f) continue
            inkPaint.alpha = (alpha * 255).toInt()
            drawStroke(canvas, strokes[i], inkPaint)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, paint: Paint) {
        val pts = stroke.points
        for (j in 1 until pts.size) {
            val a = pts[j - 1]
            val b = pts[j]
            paint.strokeWidth = baseStrokeWidth * (0.6f + 0.8f * b.pressure)
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private fun drawReply(canvas: Canvas) {
        if (replyWords.isEmpty()) return
        textPaint.textSize = replyTextSize
        for (i in replyWords.indices) {
            val alpha = quantizeAlpha(wordAlphas[i])
            if (alpha <= 0.02f) continue
            textPaint.alpha = (alpha * 255).toInt()
            val w = replyWords[i]
            textPaint.typeface = if (w.cjk) cjkFace else latinFace
            canvas.drawText(w.text, w.x, w.y, textPaint)
        }
    }

    // ------------------------------------------------------------------ screenshot

    /** Render the current strokes at full ink into a PNG (scaled so the long edge is maxEdge),
     *  for the vision model to read. */
    fun capturePagePng(maxEdge: Int = 1280): ByteArray {
        val scale = maxEdge.toFloat() / maxOf(width, height).coerceAtLeast(1)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        val paint = Paint(inkPaint).apply { alpha = 255 }
        for (s in strokes) drawStroke(canvas, s, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
