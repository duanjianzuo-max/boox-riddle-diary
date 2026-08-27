package com.billtt.riddle

import android.app.Application
import android.util.Log

/**
 * Installs hidden-API exemptions before any Onyx class loads.
 *
 * The SDK bootstraps its own access to non-SDK interfaces by reflecting into
 * VMRuntime.setHiddenApiExemptions. Android 12 blocks that call, so on this device the
 * bootstrap in com.onyx.android.sdk.utils.ReflectUtil.<clinit> throws, which cascades:
 *
 *   ReflectUtil.<clinit> fails
 *     -> Device.getBoardPlatform() fails
 *       -> EpdController static init fails
 *         -> EpdController.mapToRawTouchPoint cannot map view coords to the digitiser
 *           -> RawInputReader logs "Empty region detected when mapping"
 *             -> TouchHelper reports success and no callback ever fires
 *
 * Upstream omits this and gets away with it on the Note X2's Android 11. It is required
 * here. ReflectUtil's class initialiser runs once and its failure is permanent for the
 * process, so this must happen in Application.onCreate, before anything touches the SDK.
 */
class RiddleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val ok = runCatching {
            // Narrow, not blanket. An empty prefix exempts EVERY non-SDK interface for this
            // process; these three cover what the Onyx SDK actually reaches for (VMRuntime
            // for the bootstrap itself, and the framework packages its EPD/pen code uses).
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions(
                "Ldalvik/system/VMRuntime;",
                "Landroid/os/",
                "Landroid/view/",
            )
        }.getOrElse {
            Log.e("RiddleDiary", "HiddenApiBypass failed; pen input will not work", it)
            false
        }
        Log.i("RiddleDiary", "hidden api exemptions installed=$ok")
    }
}
