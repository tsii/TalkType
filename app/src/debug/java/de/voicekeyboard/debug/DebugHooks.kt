package de.voicekeyboard.debug

import android.util.Log
import de.voicekeyboard.asr.LatencyStubEngine
import de.voicekeyboard.audio.FakeWavAudioSource
import de.voicekeyboard.ime.VoiceKeyboardService
import java.io.File

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): debug hooks for the C2 simulation —
 * LIVE-read system properties (adb shell setprop; NOT persisted,
 * the process starts fresh per run). Debug source set ONLY — in the release
 * the identical signature is provided from the release source set (empty stub,
 * pairing like LiteRtBridgeSetup — NO reflection: main calls directly).
 *
 *  - debug.vk.fakewav=<path>   → debugAudioSourceFactory = FakeWavAudioSource(file)
 *    (full-run defaults = „throughput“ scenario; the fake reads the WAV
 *    completely and delivers it time-faithfully).
 *  - debug.vk.engine=real | stub:<alpha_ms_pro_s>:<base_ms> → debugEngineFactory
 *    (real/empty = real ParakeetAsrEngine, as before).
 *  - debug.vk.export_file=1   → every 🐞 export is ADDITIONALLY written to
 *    files/debug_export.txt (identical content; pull via
 *    adb exec-out run-as de.voicekeyboard cat files/debug_export.txt).
 *
 * Rule 9: properties carry only paths/numbers; the export content stays the
 * sanitized 🐞 text, unchanged.
 */
object DebugHooks {

    private const val TAG = "VK-DBGHOOK"

    /** debug.vk.export_file=1 — read at apply; exports write along. */
    @Volatile private var exportFileEnabled = false

    /**
     * Called by the service BEFORE every coordinator build (onMicDown) —
     * the properties thus apply per SESSION (force-stop between runs,
     * script §1.3 step 4).
     */
    fun apply(service: VoiceKeyboardService) {
        try {
            val fakewav = prop("debug.vk.fakewav")
            if (fakewav != null) {
                Log.i(TAG, "fakewav hook active") // no path content in the log
                val factory: () -> de.voicekeyboard.audio.AudioSource =
                    { FakeWavAudioSource(File(fakewav)) }
                service.debugAudioSourceFactory = factory
            } else {
                service.debugAudioSourceFactory = null
            }
            when (val engine = prop("debug.vk.engine")) {
                null, "real" -> service.debugEngineFactory = null
                else -> {
                    val stub = parseStub(engine)
                    if (stub != null) {
                        service.debugEngineFactory = { stub }
                        Log.i(TAG, "engine hook active alpha_ms_per_s=${stub.alphaMsPerSecond} base_ms=${stub.baseMs}")
                    } else {
                        Log.w(TAG, "engine hook unparseable — using real engine")
                        service.debugEngineFactory = null
                    }
                }
            }
            exportFileEnabled = prop("debug.vk.export_file") == "1"
            if (exportFileEnabled) Log.i(TAG, "export_file hook active")
        } catch (t: Throwable) {
            // The debug hook must NEVER break startup — the product stays untouched.
            Log.w(TAG, "hook apply failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Export hook (caller: VoiceKeyboardService.onDebugInfoRequested, AFTER
     * the regular collector export) — writes the IDENTICAL 🐞 text
     * additionally to files/debug_export.txt (overwrites per export; the script
     * pulls after every run).
     */
    fun onExport(service: VoiceKeyboardService, text: String) {
        if (!exportFileEnabled) return
        try {
            File(service.filesDir, "debug_export.txt").writeText(text)
            Log.i(TAG, "export written to file (chars=${text.length})")
        } catch (t: Throwable) {
            Log.w(TAG, "export to file failed: ${t.javaClass.simpleName}")
        }
    }

    /** stub:<alpha_ms_pro_s>:<base_ms> — e.g. stub:38.5:0, stub:80:50, stub:150:0. */
    private fun parseStub(spec: String): LatencyStubEngine? {
        if (!spec.startsWith("stub:")) return null
        val rest = spec.removePrefix("stub:")
        val parts = rest.split(':')
        if (parts.size !in 1..2) return null
        val alpha = parts[0].toDoubleOrNull() ?: return null
        val base = parts.getOrElse(1) { "0" }.toLongOrNull() ?: return null
        return LatencyStubEngine(alphaMsPerSecond = alpha, baseMs = base)
    }

    /**
     * SystemProperties.get via the greylist reflection (hidden API, for
     * properties readable by adb setprop; debug builds only). A failed
     * read = property absent — no error to the outside.
     */
    private fun prop(name: String): String? = try {
        @Suppress("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }
}
