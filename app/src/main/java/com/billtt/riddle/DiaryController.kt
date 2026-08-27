package com.billtt.riddle

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

/**
 * State machine: writing -> ink absorption -> awaiting reply -> reply reveal ->
 * linger -> reply fade -> writing.
 *
 * During writing the pen chip paints the stroke itself (hardware raw render; see
 * attach()) and we only persist points, committing the stroke on pen-up. When idle is
 * detected the raw pen is disabled and the fade animations run via DiaryView + DU4.
 */
class DiaryController(
    private val activity: Activity,
    private val view: DiaryView,
    private val prefs: Prefs,
) {

    enum class State { WRITING, ABSORBING, AWAITING_REPLY, REVEALING, LINGERING, FADING_REPLY }

    @Volatile var state = State.WRITING
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var touchHelper: TouchHelper? = null

    /** TouchHelper configuration must not run on the main thread; see attach(). */
    private val penSetupThread = Executors.newSingleThreadExecutor { r -> Thread(r, "pen-setup") }
    private var cycleJob: Job? = null
    @Volatile private var skipLingerRequested = false

    // The stroke currently being written (accumulated from move callbacks; used as a
    // fallback on pen-up if the full point list wasn't delivered).
    private val pendingPoints = ArrayList<StrokePoint>()

    /** True between onBeginRawDrawing and onEndRawDrawing: the nib is down, writing. */
    @Volatile private var strokeInFlight = false

    /** True between onBeginRawErasing and onEndRawErasing. Diagnostic only for now. */
    @Volatile private var erasingSincePenDown = false

    /**
     * Whether the SDK delivered the authoritative point list for the CURRENT stroke.
     *
     * Upstream gated its pen-up fallback on `view.strokes.isEmpty()`, which is only true for
     * the very first stroke on a page. From the second stroke onward, any stroke whose point
     * list failed to arrive was silently dropped. Tracking it per stroke fixes that.
     */
    @Volatile private var listDeliveredThisStroke = false

    private val idleRunnable = Runnable { onIdle() }

    /** The oracle serving the current cycle, so an in-flight HTTP call can be aborted. */
    @Volatile private var activeOracle: Oracle? = null

    /** Strokes, transcript and reply of every finished turn; also the recall catalog. */
    private val memory = MemoryStore.open(activity)

    // ------------------------------------------------------------- lifecycle

    /** Call after the view is laid out; if the size is still 0, defer until layout completes. */
    fun attach() {
        // Any later resize invalidates the chip's mapped region, so re-bind on it.
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0 && (v.width != boundW || v.height != boundH)) {
                Log.i(TAG, "layout changed to ${v.width}x${v.height}, re-binding pen")
                bindPen()
            }
        }
        if (view.width == 0 || view.height == 0) {
            Log.i(TAG, "attach: view not laid out yet (${view.width}x${view.height}), deferring")
            view.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or_: Int, ob: Int,
                ) {
                    if (v.width > 0 && v.height > 0) {
                        v.removeOnLayoutChangeListener(this)
                        attach()
                    }
                }
            })
            return
        }
        bindPen()
        EInk.beginAnimation(view)
    }

    private var boundW = 0
    private var boundH = 0

    /** Create and configure the TouchHelper for the view's CURRENT geometry. */
    private fun bindPen() {
        if (view.width <= 0 || view.height <= 0) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        boundW = view.width
        boundH = view.height
        runCatching { touchHelper?.closeRawDrawing() }
        val limit = Rect(0, 0, view.width, view.height)
        val strokeWidth = view.baseStrokeWidth
        val ok = runCatching {
            // Upstream used FEATURE_APP_TOUCH_RENDER with setRawDrawingRenderEnabled(true),
            // i.e. the app draws live ink in software, because on the author's Note X2
            // firmware the hardware-draw mode delivered no callbacks at all.
            //
            // That is NOT true here. Measured on this Note X3 Pro (A12/API 32, firmware
            // 2026-05-20) with a standalone probe: FEATURE_ALL_TOUCH_RENDER combined with
            // setRawDrawingRenderEnabled(false) gives hardware live ink AND full callbacks
            // (491 Hz, 4095-step pressure, tilt populated). So the pen chip paints the
            // stroke and we only persist it on pen-up -- no per-move software redraw.
            //
            // Note openRawDrawing() resets the chip, so stroke params are applied after it.
            touchHelper = TouchHelper.create(
                view, TouchHelper.FEATURE_ALL_TOUCH_RENDER, rawCallback, false
            )
            // EVERY configuration call runs on the pen thread, in exactly this order.
            // Splitting the chain -- openRawDrawing() on the main thread, the rest off it --
            // makes create() report success while no callback ever fires. That is the
            // failure mode this ordering exists to avoid, so do not "tidy" it back inline.
            penSetupThread.execute {
                runCatching {
                    touchHelper?.apply {
                        setStrokeWidth(strokeWidth)
                        enableFingerTouch(false)
                        onlyEnableFingerTouch(false)
                        setStrokeColor(Color.BLACK)
                        setLimitRect(limit, ArrayList())
                        openRawDrawing()                    // resets the chip
                        setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                        setStrokeWidth(strokeWidth)         // re-applied: openRawDrawing reset it
                        setStrokeColor(Color.BLACK)
                        setRawDrawingRenderEnabled(false)   // false = the chip renders
                        setRawDrawingEnabled(true)
                    }
                    Log.i(TAG, "pen configured on ${Thread.currentThread().name}")
                }.onFailure { Log.e(TAG, "pen config failed", it) }
            }
            true
        }.isSuccess
        Log.i(TAG, "bindPen: limit=$limit onScreen=${loc[0]},${loc[1]} " +
            "touchHelper=${if (touchHelper != null) "ok" else "null"} ok=$ok")
        if (!ok) {
            touchHelper = null
            Toast.makeText(activity, R.string.toast_not_boox, Toast.LENGTH_LONG).show()
        }
    }

    /** Run a TouchHelper call on the pen thread; never touch the chip from the main thread. */
    private fun onPen(block: TouchHelper.() -> Unit) {
        val h = touchHelper ?: return
        penSetupThread.execute {
            runCatching { h.block() }.onFailure { Log.e(TAG, "pen op failed", it) }
        }
    }

    /** Resume writing: re-enable raw pen input, only in the writing state. Called when the
     *  window regains focus / a dialog is dismissed. */
    fun onResume() {
        Log.i(TAG, "onResume: state=$state touchHelper=${touchHelper != null}")
        if (state == State.WRITING) {
            // false keeps the chip rendering; true would silently drop us back to the
            // software path this port exists to get rid of.
            onPen {
                setRawDrawingRenderEnabled(false)
                setRawDrawingEnabled(true)
            }
        }
    }

    fun onPause() {
        onPen { setRawDrawingEnabled(false) }
        view.removeCallbacks(idleRunnable)
    }

    fun onDestroy() {
        activeOracle?.cancel()
        runCatching { touchHelper?.closeRawDrawing() }
        penSetupThread.shutdown()
        scope.cancel()
    }

    // ------------------------------------------- debug touch input (non-BOOX)

    /** True when the raw pen driver is unavailable (emulator); fall back to touch events. */
    val debugTouchFallback: Boolean get() = touchHelper == null

    fun debugAddPoint(x: Float, y: Float, pressure: Float, up: Boolean) {
        if (state != State.WRITING) return
        pendingPoints.add(StrokePoint(x, y, pressure))
        if (up) {
            view.addStroke(Stroke(ArrayList(pendingPoints)))
            pendingPoints.clear()
            EInk.animateFrame(view)
            scheduleIdleCheck()
        }
    }

    /**
     * Send the page now, without waiting out the idle delay. Bound to a two-finger tap.
     * Ignored unless we are writing and there is something on the page.
     */
    fun triggerNow(): Boolean {
        if (state != State.WRITING || view.strokes.isEmpty()) return false
        view.removeCallbacks(idleRunnable)
        Log.i(TAG, "manual trigger: strokes=${view.strokes.size}")
        startCycle()
        return true
    }

    /**
     * Collects one turn's streamed events. Written on the oracle's IO thread and read on
     * the main thread, so every access is guarded: cancelling a turn does not stop the
     * blocking HTTP call instantly, and an unguarded read could observe a torn state.
     */
    private class TurnResult {
        private val ink = StringBuilder()
        private var transcript = ""
        private var recallId: Long? = null
        private var failure: String? = null

        data class Snapshot(
            val reply: String,
            val transcript: String,
            val recallId: Long?,
            val failure: String?,
        )

        @Synchronized
        fun accept(event: OracleEvent) {
            when (event) {
                is OracleEvent.Ink -> {
                    if (ink.isNotEmpty()) ink.append(' ')
                    ink.append(event.text)
                }
                is OracleEvent.Transcript -> transcript = event.text
                is OracleEvent.Show -> recallId = event.id
                is OracleEvent.Failed -> failure = event.message
            }
        }

        @Synchronized
        fun snapshot() = Snapshot(ink.toString(), transcript, recallId, failure)
    }

    /** Delete every remembered page. See MemoryStore.forgetAll. */
    fun forgetAllMemories(): Boolean = memory.forgetAll()

    /** Any touch during the linger phase makes the reply fade early. */
    fun requestSkipLinger() {
        if (state == State.LINGERING) skipLingerRequested = true
    }

    // --------------------------------------------------------- raw pen callbacks
    // Note: these fire on Onyx SDK background threads and are all posted to the main thread.

    private val rawCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {
            strokeInFlight = true
            listDeliveredThisStroke = false
            Log.i(TAG, "onBeginRawDrawing (${point.x.toInt()},${point.y.toInt()}) p=${point.pressure}")
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                view.removeCallbacks(idleRunnable)
                pendingPoints.clear()
                view.beginLiveStroke()
                pendingPoints.add(p)
                // No live draw: the pen chip paints this stroke itself.
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            val p = StrokePoint(point.x, point.y, normalizePressure(point.pressure))
            view.post {
                pendingPoints.add(p)
                // Collect only: the chip already shows this stroke, so the old throttled
                // partial-invalidate path (~22 software redraws/s) is gone.
            }
        }

        override fun onRawDrawingTouchPointListReceived(pointList: TouchPointList) {
            listDeliveredThisStroke = true
            val pts = pointList.points.map {
                StrokePoint(it.x, it.y, normalizePressure(it.pressure))
            }
            view.post {
                view.addStroke(Stroke(pts))
                pendingPoints.clear()
            }
        }

        override fun onEndRawDrawing(outLimitRegion: Boolean, point: TouchPoint) {
            strokeInFlight = false
            Log.i(TAG, "onEndRawDrawing strokes=${view.strokes.size} pending=${pendingPoints.size}")
            view.post {
                if (!listDeliveredThisStroke && pendingPoints.size >= 2) {
                    view.addStroke(Stroke(ArrayList(pendingPoints)))
                }
                pendingPoints.clear()
                view.endLiveStroke()   // clear live stroke and redraw (final stroke is in strokes)
                scheduleIdleCheck()
            }
        }

        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {
            erasingSincePenDown = true
            view.post { view.removeCallbacks(idleRunnable) }
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {}

        override fun onRawErasingTouchPointListReceived(pointList: TouchPointList) {
            val pts = pointList.points.map { StrokePoint(it.x, it.y, 1f) }

            // This firmware fires erase callbacks spuriously in the middle of ordinary
            // writing -- the probe logged 598 of them in one short session, with begin
            // pressures of 220-361, interleaved with normal draw strokes. Since
            // TouchPoint.getToolType is not exposed here, the callback family is the ONLY
            // signal available, so an unguarded eraseAt() silently deletes what was just
            // written. Two cheap guards, both erring towards keeping ink:
            //   - a real erase sweeps: require enough points to be a gesture
            //   - never erase while a drawing stroke is in flight
            val looksDeliberate = pts.size >= MIN_ERASE_POINTS && !strokeInFlight
            if (!looksDeliberate) {
                Log.i(TAG, "ignored erase burst: pts=${pts.size} strokeInFlight=$strokeInFlight")
                view.post { scheduleIdleCheck() }
                return
            }
            view.post {
                if (view.eraseAt(pts, eraserRadius)) refreshAfterErase()
                scheduleIdleCheck()
            }
        }

        override fun onEndRawErasing(outLimitRegion: Boolean, point: TouchPoint) {
            erasingSincePenDown = false
            view.post { scheduleIdleCheck() }
        }
    }

    /** The probe measured 4095 on this panel, not 4096. Ask the device instead of guessing. */
    private val maxPressure: Float by lazy {
        runCatching { EpdController.getMaxTouchPressure() }.getOrNull()
            ?.takeIf { it > 0f } ?: MAX_PRESSURE
    }

    /** ~2.7 mm, which is what upstream's 24 px came to on the Note X2's 227 PPI panel. */
    private val eraserRadius: Float by lazy {
        view.resources.displayMetrics.xdpi * 2.7f / 25.4f
    }

    private fun normalizePressure(raw: Float): Float =
        (raw / maxPressure).coerceIn(0.05f, 1f)

    /** After erasing, briefly leave raw pen mode to redraw the remaining strokes and full-refresh. */
    private fun refreshAfterErase() {
        onPen { setRawDrawingEnabled(false) }
        view.invalidate()          // redraw remaining strokes (erased ones are gone)
        EInk.fullRefresh(view)     // GC full refresh to clear ghosting of erased ink
        view.postDelayed({
            if (state == State.WRITING) onPen { setRawDrawingEnabled(true) }
        }, 300)
    }

    // ------------------------------------------------------------- idle detection

    private fun scheduleIdleCheck() {
        view.removeCallbacks(idleRunnable)
        if (state == State.WRITING && view.strokes.isNotEmpty()) {
            view.postDelayed(idleRunnable, prefs.idleMs)
        }
    }

    private fun onIdle() {
        if (state != State.WRITING || view.strokes.isEmpty()) return
        startCycle()
    }

    // --------------------------------------------------------- main cycle (one round)

    private fun startCycle() {
        state = State.ABSORBING
        skipLingerRequested = false
        onPen { setRawDrawingEnabled(false) }

        cycleJob = scope.launch {
            EInk.animateFrame(view)
            delay(FRAME_MS)

            // Snapshot the page before absorption wipes it: these strokes are what gets
            // archived, and what a later recall redraws in the writer's own hand.
            val written = view.strokes.toList()
            val png = withContext(Dispatchers.Default) { view.capturePagePng() }

            // Fire the request first so recognition overlaps the absorb animation.
            val oracle = OracleFactory.create(prefs)
            activeOracle = oracle
            val remember = prefs.memoryEnabled
            val ctx = if (remember) {
                val (lines, ids) = memory.catalog(CATALOG_MAX)
                TurnContext(memory.recentDialogue(RECENT_TURNS), lines, ids)
            } else {
                TurnContext()
            }

            val turn = TurnResult()

            val turnDeferred = oracle?.let {
                async(Dispatchers.IO) {
                    runCatching { it.ask(png, ctx, turn::accept) }
                }
            }

            animateAbsorb()
            view.clearStrokes()
            EInk.fullRefresh(view)

            state = State.AWAITING_REPLY
            val unconfigured = turnDeferred == null
            if (turnDeferred != null) {
                val result = runCatching {
                    withTimeout(REPLY_TIMEOUT_MS) { turnDeferred.await() }
                }.onFailure {
                    // ask() blocks, so cancelling the coroutine leaves the HTTP exchange
                    // running: it still reaches the server and is still billed. Abort the
                    // call itself, and say so rather than swallowing the timeout.
                    oracle?.cancel()
                    turnDeferred.cancel()
                    reportError(it)
                }.getOrNull()
                result?.exceptionOrNull()?.let { reportError(it) }
            }
            // With no key configured the message is produced below, so that the ellipsis
            // fallback cannot overwrite it.

            // One consistent snapshot. On the timeout path the HTTP thread can still be
            // running for a moment after cancel(), so reading the fields one by one could
            // mix a half-written turn.
            val snap = turn.snapshot()
            val recalled = snap.recallId?.let { if (remember) memory.strokes(it) else null }
            if (recalled != null) {
                // A recall: instead of a reply, the page itself comes back, in the writer's
                // own hand. Nothing new is archived -- this turn produced no new page.
                state = State.REVEALING
                view.showStrokes(recalled)
                EInk.fullRefresh(view)
                state = State.LINGERING
                lingerInterruptibly(RECALL_LINGER_MS)
                view.clearStrokes()
                EInk.fullRefresh(view)
            } else {
                snap.failure?.let { Log.w(TAG, "oracle reported: $it") }
                // Ellipsis in character: the diary heard nothing, rather than an error box.
                // With no key configured, say so instead -- upstream set that message and
                // then immediately overwrote it with the ellipsis.
                val reply = when {
                    unconfigured -> activity.getString(R.string.toast_need_key)
                    else -> snap.reply.ifBlank { "……" }
                }
                state = State.REVEALING
                view.setReply(reply)
                animateReveal()

                if (remember && written.isNotEmpty()) {
                    val id = System.currentTimeMillis() / 1000L
                    withContext(Dispatchers.IO) {
                        memory.append(id, snap.transcript, reply, written)
                    }
                }

                state = State.LINGERING
                lingerInterruptibly(lingerMillisFor(view.replyWords.size))

                state = State.FADING_REPLY
                animateReplyFade()
                view.clearReply()
                EInk.fullRefresh(view)
            }

            state = State.WRITING
            onPen { setRawDrawingEnabled(true) }
        }
    }

    private fun reportError(cause: Throwable) {
        Log.e(TAG, "oracle failed", cause)
        Toast.makeText(activity, "API error: ${cause.message}", Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------- animations
    //
    // E-ink refresh is slow, so a continuous per-frame gradient stutters. Ink level is
    // quantized to 5 steps; we scan the timeline with a fine sample step and only issue
    // a real DU4 fast refresh when some element crosses a step — every refresh is a
    // visible grayscale jump, dead frames are skipped, and the result is a crisp,
    // stepped absorb / reveal.

    /** Quantize to steps 0..4, consistent with DiaryView's quantization. */
    private fun level(a: Float): Int = Math.round(a.coerceIn(0f, 1f) * 4f)

    /**
     * Generic stepped-fade driver.
     * @param count   number of elements
     * @param totalMs total curve duration
     * @param setA    write element i's ink level in place
     * @param curve   given element i and time t, return its target level in [0,1]
     */
    private suspend fun runStagedFade(
        count: Int, totalMs: Long, setA: (Int, Float) -> Unit, curve: (Int, Long) -> Float,
    ) {
        if (count == 0) return
        val prev = IntArray(count) { Int.MIN_VALUE }
        var t = 0L
        while (t <= totalMs) {
            var changed = false
            for (i in 0 until count) {
                val a = curve(i, t)
                setA(i, a)
                val lv = level(a)
                if (lv != prev[i]) { prev[i] = lv; changed = true }
            }
            if (changed) {
                EInk.animateFrame(view)
                delay(FRAME_MS)
            }
            t += SAMPLE_MS
        }
    }

    /**
     * Ink absorption: the whole page fades out together, slowly and evenly.
     *
     * Upstream staggered the bands so the page drained head-to-tail, which reads as the ink
     * being eaten line by line. With ABSORB_BAND_STAGGER_MS at 0 every band shares one alpha,
     * so the banding now only serves its other purpose -- drawing a handful of cached bitmaps
     * per frame instead of hundreds of stroke segments.
     */
    private suspend fun animateAbsorb() {
        if (view.strokes.isEmpty()) return
        view.prepareAbsorb()
        val k = view.absorbBandAlphas.size
        if (k == 0) return
        val totalMs = ABSORB_BAND_STAGGER_MS * (k - 1) + ABSORB_BAND_FADE_MS
        runStagedFade(k, totalMs, { i, a -> view.absorbBandAlphas[i] = a }) { i, t ->
            val local = (t - i * ABSORB_BAND_STAGGER_MS).coerceAtLeast(0L)
            (1f - local.toFloat() / ABSORB_BAND_FADE_MS).coerceIn(0f, 1f)
        }
        view.finishAbsorb()
    }

    /** Reply reveal: words rise in order from faint gray to full ink. */
    private suspend fun animateReveal() {
        val n = view.replyWords.size
        runStagedFade(n, REVEAL_WORD_MS * (n - 1) + FADE_MS, { i, a -> view.wordAlphas[i] = a }) { i, t ->
            val local = (t - i * REVEAL_WORD_MS).coerceAtLeast(0L)
            (local.toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
        for (i in 0 until n) view.wordAlphas[i] = 1f
        EInk.animateFrame(view)
    }

    /** Reply fade: the reverse of reveal — words sink back into the page in order. */
    private suspend fun animateReplyFade() {
        val n = view.replyWords.size
        val stagger = if (n > 0) (ABSORB_TOTAL_STAGGER_MS / n).coerceIn(50L, REVEAL_WORD_MS) else 0L
        runStagedFade(n, stagger * (n - 1) + FADE_MS, { i, a -> view.wordAlphas[i] = a }) { i, t ->
            val local = (t - i * stagger).coerceAtLeast(0L)
            (1f - local.toFloat() / FADE_MS).coerceIn(0f, 1f)
        }
    }

    private fun lingerMillisFor(wordCount: Int): Long =
        (1000L + wordCount * 110L).coerceIn(1800L, 9000L)

    private suspend fun lingerInterruptibly(millis: Long) {
        var waited = 0L
        while (waited < millis && !skipLingerRequested) {
            delay(120)
            waited += 120
        }
    }

    companion object {
        const val TAG = "RiddleDiary"

        /** Minimum interval after each real refresh — DU4 fast refresh is ~150ms. */
        const val FRAME_MS = 130L

        /** Timeline sampling step (does not refresh; only used to detect step crossings). */
        const val SAMPLE_MS = 30L

        const val FADE_MS = 420L                  // reply: one word from full to 0 (or reverse)
        /** Absorb duration. Quantisation gives 5 visible steps, so ~320 ms per step here. */
        const val ABSORB_BAND_FADE_MS = 1600L
        /** 0 = the whole page fades as one. Non-zero drains it head-to-tail instead. */
        const val ABSORB_BAND_STAGGER_MS = 0L
        const val ABSORB_TOTAL_STAGGER_MS = 1100L // reply fade: total stagger budget
        const val ABSORB_STAGGER_MAX_MS = 90L

        const val REVEAL_WORD_MS = 55L            // gap between adjacent words starting to reveal

        const val REPLY_TIMEOUT_MS = 150_000L

        /** Past pages offered to the model as a numbered recall catalog. */
        const val CATALOG_MAX = 40

        /** Earlier turns replayed as conversation, so the diary remembers what was just said. */
        const val RECENT_TURNS = 6

        /** How long a conjured page stays on screen before the diary lets it go. */
        const val RECALL_LINGER_MS = 12_000L

        /**
         * Minimum points in an erase burst before it is believed. Tuned conservatively:
         * losing an intended erase costs one repeated swipe, losing written ink costs the
         * page. Revisit once real usage shows how big genuine erase bursts are.
         */
        const val MIN_ERASE_POINTS = 8
        const val MAX_PRESSURE = 4096f
    }
}
