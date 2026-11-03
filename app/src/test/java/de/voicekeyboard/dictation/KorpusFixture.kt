package de.voicekeyboard.dictation

import java.io.File

/**
 * F35 („Weg D", docs/KORPUS_DIKTAT_TESTPLAN.md §2): Korpus-Fixture zur LAUFZEIT —
 * KEINE neuen Binär-Assets, kein Netz. Konkateniert die vier vorhandenen
 * 16-kHz/s16/mono-Assets (de_smoke + de_part1 + de_part2 + de_pause) und
 * streckt die Folge mit 0,3-s-Nullblöcken zwischen Wiederholungen auf die
 * exakte Ziellänge (30 s / 60 s). Regel 9: Samples verlassen nie den Test —
 * Assertionen/Logs sehen nur Zähler und Dauern.
 */
object KorpusFixture {
    const val SAMPLE_RATE = 16_000

    /** Speise-Blockgröße der Test-Quelle: 320 Samples = 20 ms (Testplan §3). */
    const val BLOCK_SAMPLES = 320

    /** 0,3 s synthetische Stille (Nullblöcke) zwischen Wiederholungen (§2). */
    const val GAP_SAMPLES = 4_800

    /** F43: interne Stille-Sequenzen der Korpus-Teile auf 2,0 s kürzen — der neue
     *  Auto-Stopp (DictationCoordinator.TRAILING_SILENCE_MS = 2500 ms) beendet
     *  sonst den Takt-Lauf mitten in der Fixture (de_pause.wav enthält 4,38 s
     *  Stille bei 2,4 s). Nur Test-Fixture-Anpassung, kein Produktcode; Grenze
     *  bewusst UNTER 2500 ms. Schwelle = Produktions-SILENCE_RMS (0,01). */
    private const val MAX_SILENT_RUN_BLOCKS = 100 // 2,0 s @ 320 Samples / 16 kHz

    private val PART_NAMES = listOf("de_smoke.wav", "de_part1.wav", "de_part2.wav", "de_pause.wav")

    /** Die vier Korpus-Teile (lazy, einmal je Test-JVM gelesen). */
    val parts: List<ShortArray> by lazy { PART_NAMES.map { loadWavS16Mono(resolve(it)) } }

    /** Real-Dauer der Teile in s (Samples/16000) — für die Ist-Meldung. */
    fun partSeconds(): List<Double> = parts.map { it.size.toDouble() / SAMPLE_RATE }

    /**
     * Fixture mit EXAKT [totalSamples] Samples: die vier Teile in Reihenfolge,
     * je Zyklus 0,3 s Nullblöcke, Wiederholung, am Ziel abgeschnitten
     * (480 000 Samples = 30,0 s / 960 000 = 60,0 s — beide durch 32 000
     * teilbar, d. h. ohne Rest im Coordinator-Tick).
     */
    fun build(totalSamples: Int): ShortArray {
        check(totalSamples > 0)
        // F43: Pausen-geklammerte Kopien der Teile (Originale in [parts] bleiben
        // für die Ist-Meldung unverändert — partSeconds() meldet die echten Dauern).
        val src = parts.map { clampSilence(it) }
        val units = src.size + 1 // 4 Teile + 1 Silence-Gap je Zyklus
        val out = ShortArray(totalSamples)
        var pos = 0
        var idx = 0
        while (pos < totalSamples) {
            val unit = if (idx % units == src.size) ShortArray(GAP_SAMPLES) else src[idx % units]
            val n = minOf(unit.size, totalSamples - pos)
            System.arraycopy(unit, 0, out, pos, n)
            pos += n
            idx++
        }
        return out
    }

    /** F43: kürzt Stille-Sequenzen (Block-RMS < Produktions-SILENCE_RMS) auf
     *  [MAX_SILENT_RUN_BLOCKS] Blöcke; laut bleibt unangetastet. Reine
     *  Fixture-Logik (kein Produktverhalten). */
    private fun clampSilence(samples: ShortArray): ShortArray {
        var run = 0
        var w = 0
        val out = ShortArray(samples.size)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + BLOCK_SAMPLES, samples.size)
            var acc = 0L
            for (j in i until end) acc += samples[j].toLong() * samples[j]
            val silent = kotlin.math.sqrt(acc.toDouble() / (end - i)) / 32_768.0 < 0.01
            if (silent && run >= MAX_SILENT_RUN_BLOCKS) {
                i = end // stiller Block über der Grenze: verwerfen (Pause gekürzt)
                continue
            }
            if (silent) run++ else run = 0
            for (j in i until end) {
                out[w] = samples[j]
                w++
            }
            i = end
        }
        return if (w == samples.size) samples else out.copyOf(w)
    }

    /** Findet testdata/ vom Test-Arbeitsverzeichnis aus (walk-up, max. 6 Ebenen). */
    private fun resolve(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cand = File(dir, "testdata")
            if (File(cand, name).isFile) return cand.resolve(name)
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("testdata/$name nicht gefunden")
    }

    /** Minimaler s16le-mono-Reader: sucht das data-Chunk (Header-Layout unabhängig). */
    private fun loadWavS16Mono(file: File): ShortArray {
        val b = file.readBytes()
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") {
            "Kein RIFF/WAVE: " + file.name
        }
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val size = ((b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or
                ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24))
            if (id == "data") {
                val n = minOf(size, b.size - i - 8)
                require(n > 1) { "data-Chunk leer: " + file.name }
                val out = ShortArray(n / 2)
                for (j in out.indices) {
                    val off = i + 8 + j * 2
                    out[j] = (b[off].toInt() or (b[off + 1].toInt() shl 8)).toShort()
                }
                return out
            }
            i += 8 + size + if (size and 1 == 1) 1 else 0
        }
        throw IllegalStateException("data-Chunk nicht gefunden: " + file.name)
    }
}
