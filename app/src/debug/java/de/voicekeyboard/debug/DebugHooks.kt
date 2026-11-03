package de.voicekeyboard.debug

import android.util.Log
import de.voicekeyboard.asr.LatencyStubEngine
import de.voicekeyboard.audio.FakeWavAudioSource
import de.voicekeyboard.ime.VoiceKeyboardService
import java.io.File

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): Debug-Hooks für die C2-Simulation —
 * LIVE gelesene System-Properties (adb shell setprop; NICHT persistiert,
 * der Prozess startet je Lauf frisch). Debug-Sourceset NUR — im Release wird
 * die identische Signatur aus dem release-Sourceset geliefert (leerer Stub,
 * Paarung wie LiteRtBridgeSetup — KEINE Reflection: main ruft direkt auf).
 *
 *  - debug.vk.fakewav=<pfad>   → debugAudioSourceFactory = FakeWavAudioSource(file)
 *    (Volldurchlauf-Defaults = „throughput"-Szenario; der Fake liest die WAV
 *    vollständig ein und liefert sie zeitgetreu).
 *  - debug.vk.engine=real | stub:<alpha_ms_pro_s>:<base_ms> → debugEngineFactory
 *    (real/leer = echte ParakeetAsrEngine, wie bisher).
 *  - debug.vk.export_file=1   → jeder 🐞-Export wird ZUSÄTZLICH nach
 *    files/debug_export.txt geschrieben (identischer Inhalt; Pull per
 *    adb exec-out run-as de.voicekeyboard cat files/debug_export.txt).
 *
 * Regel 9: Properties tragen nur Pfade/Zahlen; Export-Inhalt bleibt der
 * sanitisierte 🐞-Text unverändert.
 */
object DebugHooks {

    private const val TAG = "VK-DBGHOOK"

    /** debug.vk.export_file=1 — beim apply gelesen; Exporte schreiben mit. */
    @Volatile private var exportFileEnabled = false

    /**
     * Wird vom Service VOR jedem Coordinator-Bau aufgerufen (onMicDown) —
     * die Properties gelten damit je SESSION (force-stop zwischen Läufen,
     * Skript §1.3 Schritt 4).
     */
    fun apply(service: VoiceKeyboardService) {
        try {
            val fakewav = prop("debug.vk.fakewav")
            if (fakewav != null) {
                Log.i(TAG, "fakewav hook active") // kein Pfadinhalt im Log
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
            // Debug-Hook darf den Start NIEMALS brechen — Produkt bleibt unberührt.
            Log.w(TAG, "hook apply failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Export-Hook (Aufrufer: VoiceKeyboardService.onDebugInfoRequested, NACH
     * dem regulären Collector-Export) — schreibt den IDENTISCHEN 🐞-Text
     * zusätzlich in files/debug_export.txt (überschreibt je Export; das Skript
     * pullt nach jedem Lauf).
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

    /** stub:<alpha_ms_pro_s>:<base_ms> — z. B. stub:38.5:0, stub:80:50, stub:150:0. */
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
     * SystemProperties.get über die Greylist-Reflexion (hidden API, für
     * adb-setprop lesbare Properties; Debug-BuildsOnly). Fehlgeschlagene
     * Lesung = Property abwesend — kein Fehler nach außen.
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
