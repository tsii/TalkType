package de.voicekeyboard.asr.qwen

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * T22f1 (PLAN_V0146 §6; QWEN_ASR_CONTRACT §4.0/§4.1/§6): pure-Kotlin INPUT
 * chain of the Qwen3-ASR engine — mel frontend, encoder attention mask and
 * prompt token ids. NO onnxruntime import (T22f2 wires the 3 graphs on top).
 *
 * - [load] reads `mel_filters.npy` (numpy v1 header + raw little-endian
 *   float32, C-order [201,128]) and `mel_config.json` — the SAME files of the
 *   model package (sha256 pinned in ModelManifest.QWEN since T22b').
 * - [frontend]: silence-pad to FULL seconds (min 0.5 s = 8000 samples), STFT
 *   n_fft 400 (periodic Hann), hop 160, center reflect-padding, 101 frames/s
 *   with the LAST frame dropped, power spectrum, mel bank, log10 clamp 1e-10,
 *   max-8 clamp, (x+4)/4 — the exact math of contract §4.0 (reference:
 *   tools/qwen_ref/run_qwen_ref.py compute_mel; goldens: QwenInputsTest).
 * - [buildAttentionMask]: [1,1,S,S] additive block mask — 0 within a block,
 *   -1e9 outside; blocks of 104 tokens (13 * n_window_infer/(2*n_window)),
 *   rest window at the end (contract §4.1).
 * - [buildPromptTokens]: contract §6 prefix ids + S x <|audio_pad|> (151676)
 *   + suffix ids — T22a verified byte-identical against the HF processor.
 */
class QwenInputs private constructor(
    private val bank: FloatArray, // [nFftBins * nMel], row = fft bin (npy C-order)
    private val nFftBins: Int,    // 201
    private val nMel: Int,        // 128
    private val nFft: Int,        // 400
    private val hop: Int,         // 160
    private val sampleRate: Int,  // 16000
    private val minSamples: Int,  // 8000 (0.5 s)
    private val logClamp: Float,  // 1e-10
    private val maxMinus: Float,  // 8.0
    private val logOffset: Float, // 4.0
    private val logDiv: Float,    // 4.0
    private val windowTokens: Int, // 104
    private val dft: BluesteinRealDft,
    private val hann: DoubleArray,
) {

    /** Log-mel [1,128,T]: [features] is band-major (band * frames + t). */
    class MelResult(val features: FloatArray, val bands: Int, val frames: Int)

    /** Contract §4.0: raw 16-kHz-mono float32 -> input_features [1,128,100*n]. */
    fun frontend(raw: FloatArray): MelResult {
        var n = maxOf(raw.size, minSamples)
        n = ((n + sampleRate - 1) / sampleRate) * sampleRate // full 1 s chunks
        val wav = FloatArray(n)
        System.arraycopy(raw, 0, wav, 0, minOf(raw.size, n))
        // torch.stft(center=True, pad_mode='reflect')
        val pad = nFft / 2
        val padded = FloatArray(n + 2 * pad)
        for (i in 0 until pad) {
            padded[i] = wav[pad - i]
            padded[pad + n + i] = wav[n - 2 - i]
        }
        System.arraycopy(wav, 0, padded, pad, n)
        val nFrames = n / hop // 1 + n/hop frames, the LAST one dropped
        // power spectrum per frame (double DFT like the numpy reference)
        val mags = FloatArray(nFrames * nFftBins) // frame-major
        for (t in 0 until nFrames) {
            val power = dft.power(padded, t * hop, hann)
            for (f in 0 until nFftBins) mags[t * nFftBins + f] = power[f].toFloat()
        }
        // mel bank: out[band,t] = sum_f bank[f,band] * mags[f,t] (float32 accumulation)
        val mel = FloatArray(nMel * nFrames) // band-major
        for (t in 0 until nFrames) {
            val off = t * nFftBins
            for (f in 0 until nFftBins) {
                val m = mags[off + f]
                if (m == 0f) continue
                var b = 0
                var bankIdx = f * nMel
                var melIdx = t
                while (b < nMel) {
                    mel[melIdx] += m * bank[bankIdx]
                    bankIdx++
                    melIdx += nFrames
                    b++
                }
            }
        }
        // log10 clamp / max-8 clamp / (x+4)/4 — global max over the whole utterance
        var globalMax = Float.NEGATIVE_INFINITY
        for (i in mel.indices) {
            val v = log10(max(mel[i], logClamp).toDouble()).toFloat()
            mel[i] = v
            if (v > globalMax) globalMax = v
        }
        val floor = globalMax - maxMinus
        for (i in mel.indices) mel[i] = (max(mel[i], floor) + logOffset) / logDiv
        return MelResult(mel, nMel, nFrames)
    }

    /** Contract §4.1: additive block mask [1,1,S,S], flattened row-major. */
    fun buildAttentionMask(s: Int): FloatArray {
        val mask = FloatArray(s * s) { NEG_INF }
        var start = 0
        while (start < s) {
            val end = minOf(start + windowTokens, s)
            for (row in start until end) {
                java.util.Arrays.fill(mask, row * s + start, row * s + end, 0f)
            }
            start = end
        }
        return mask
    }

    /** Contract §6: prompt ids [1, 18+S] — prefix + S x audio_pad + suffix. */
    fun buildPromptTokens(s: Int): LongArray {
        val ids = LongArray(PROMPT_PREFIX.size + s + PROMPT_SUFFIX.size)
        var i = 0
        for (id in PROMPT_PREFIX) ids[i++] = id
        repeat(s) { ids[i++] = AUDIO_PAD_ID }
        for (id in PROMPT_SUFFIX) ids[i++] = id
        return ids
    }

    /**
     * True N-point real DFT (N=400 is no power of 2!) via Bluestein/chirp-z over
     * power-of-2 FFTs — zero-padding alone would CHANGE the bin frequencies.
     * Host-verified against numpy rfft: rel. diff <= 1e-14 (gen script mirror).
     */
    private class BluesteinRealDft(private val n: Int, private val outBins: Int) {
        private val m = Integer.highestOneBit(2 * n - 2) shl 1 // >= 2n-1, power of 2
        private val rev = IntArray(m) { Integer.reverse(it) ushr (Integer.SIZE - log2(m)) }
        private val phiRe = DoubleArray(n)
        private val phiIm = DoubleArray(n)
        private val psiRe = DoubleArray(m) // DFT(ψ) — precomputed once
        private val psiIm = DoubleArray(m)
        private val aRe = DoubleArray(m)
        private val aIm = DoubleArray(m)
        private val cRe = DoubleArray(m)
        private val cIm = DoubleArray(m)

        init {
            for (k in 0 until n) {
                val ang = -PI * ((k * k) % (2 * n)) / n
                phiRe[k] = cos(ang)
                phiIm[k] = sin(ang)
            }
            for (j in 0 until n) {
                val ang = PI * ((j * j) % (2 * n)) / n
                psiRe[j] = cos(ang)
                psiIm[j] = sin(ang)
                if (j > 0) {
                    psiRe[m - j] = psiRe[j]
                    psiIm[m - j] = psiIm[j]
                }
            }
            transform(psiRe, psiIm)
        }

        /** Power spectrum (re²+im²) of bins 0..outBins-1 for the windowed frame. */
        fun power(x: FloatArray, offset: Int, window: DoubleArray): DoubleArray {
            java.util.Arrays.fill(aRe, 0.0)
            java.util.Arrays.fill(aIm, 0.0)
            for (j in 0 until n) {
                val v = x[offset + j].toDouble() * window[j]
                aRe[j] = v * phiRe[j]
                aIm[j] = v * phiIm[j]
            }
            transform(aRe, aIm) // a = DFT(x·φ)
            for (j in 0 until m) {
                cRe[j] = aRe[j] * psiRe[j] - aIm[j] * psiIm[j]
                cIm[j] = aRe[j] * psiIm[j] + aIm[j] * psiRe[j]
            }
            // IDFT via the conjugate trick: conj → DFT → conj → /m
            for (j in 0 until m) cIm[j] = -cIm[j]
            transform(cRe, cIm)
            val out = DoubleArray(outBins)
            for (k in 0 until outBins) {
                val yr = cRe[k] / m
                val yi = -cIm[k] / m
                val xr = yr * phiRe[k] - yi * phiIm[k]
                val xi = yr * phiIm[k] + yi * phiRe[k]
                out[k] = xr * xr + xi * xi
            }
            return out
        }

        /** In-place iterative radix-2 FFT (double), bit-reversal + butterflies. */
        private fun transform(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until m) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= m) {
                val half = len / 2
                val step = 2 * PI / len
                var base = 0
                while (base < m) {
                    for (j in 0 until half) {
                        val wr = cos(step * j)
                        val wi = -sin(step * j)
                        val even = base + j
                        val odd = even + half
                        val tr = re[odd] * wr - im[odd] * wi
                        val ti = re[odd] * wi + im[odd] * wr
                        re[odd] = re[even] - tr
                        im[odd] = im[even] - ti
                        re[even] += tr
                        im[even] += ti
                    }
                    base += len
                }
                len *= 2
            }
        }

        private fun log2(n: Int): Int = 31 - Integer.numberOfLeadingZeros(n)
    }

    companion object {
        private const val NEG_INF = -1e9f

        /** Contract §4.1: audio tokens per 1-s chunk (fixed by the model). */
        private const val AUDIO_TOKENS_PER_CHUNK = 13

        /** Contract §6: <|audio_pad|> — replaced S times by the audio embeddings. */
        private const val AUDIO_PAD_ID = 151676L

        /** HF-processor-verified (T22a): prefix before the S pad tokens. */
        private val PROMPT_PREFIX = longArrayOf(
            151644, 8948, 198, 151645, 198, 151644, 872, 198, 151669,
        ) // <|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>

        /** HF-processor-verified (T22a): suffix after the S pad tokens. */
        private val PROMPT_SUFFIX = longArrayOf(
            151670, 151645, 198, 151644, 77091, 198, 11528, 5938, 151704,
        ) // <|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage German<asr_text>

        /** Loads the model package files mel_filters.npy + mel_config.json from [dir]. */
        fun load(dir: File): QwenInputs {
            val (bank, nFftBins, nMel) = loadNpyBank(File(dir, "mel_filters.npy"))
            val cfg = JSONObject(File(dir, "mel_config.json").readText())
            val nFft = cfg.getInt("n_fft")
            val hop = cfg.getInt("hop_length")
            require(nFftBins == nFft / 2 + 1) { "bank rows $nFftBins != n_fft/2+1" }
            val nWindow = cfg.getInt("n_window")
            // not in every mel_config.json — contract §4.1 default (0.6B: 800)
            val nWindowInfer = cfg.optInt("n_window_infer", 800)
            val windowTokens = AUDIO_TOKENS_PER_CHUNK * nWindowInfer / (2 * nWindow)
            val hann = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * PI * it / nFft) }
            return QwenInputs(
                bank, nFftBins, nMel, nFft, hop,
                cfg.getInt("sampling_rate"), cfg.getInt("min_length"),
                cfg.getDouble("log10_clamp").toFloat(),
                cfg.getDouble("norm_max_minus").toFloat(),
                cfg.getDouble("log_offset").toFloat(),
                cfg.getDouble("log_div").toFloat(),
                windowTokens, BluesteinRealDft(nFft, nFftBins), hann,
            )
        }

        /** numpy v1 .npy: magic, version, header (json-ish), then raw C-order data. */
        private fun loadNpyBank(file: File): Triple<FloatArray, Int, Int> {
            val bytes = file.readBytes()
            require(bytes.size > 10 && bytes[0] == 0x93.toByte() &&
                String(bytes, 1, 5, Charsets.ISO_8859_1) == "NUMPY") { "not a .npy file" }
            val major = bytes[6].toInt()
            var pos = 8
            var headerLen = 0
            if (major >= 2) {
                headerLen = (bytes[pos].toInt() and 0xff) or ((bytes[pos + 1].toInt() and 0xff) shl 8) or
                    ((bytes[pos + 2].toInt() and 0xff) shl 16) or ((bytes[pos + 3].toInt() and 0xff) shl 24)
                pos += 4
            } else {
                headerLen = (bytes[pos].toInt() and 0xff) or ((bytes[pos + 1].toInt() and 0xff) shl 8)
                pos += 2
            }
            val header = String(bytes, pos, headerLen, Charsets.ISO_8859_1)
            val compact = header.replace(" ", "")
            require("'descr':'<f4'" in compact) { "unsupported npy dtype: $header" }
            require("'fortran_order':False" in compact) { "npy must be C-order" }
            val shape = Regex("'shape':\\s*\\((\\d+),\\s*(\\d+)").find(header)
                ?: error("no shape in npy header")
            val rows = shape.groupValues[1].toInt()
            val cols = shape.groupValues[2].toInt()
            val dataPos = pos + headerLen
            val count = rows * cols
            require(bytes.size - dataPos >= count * 4) { "npy truncated" }
            val bank = FloatArray(count)
            val buf = java.nio.ByteBuffer.wrap(bytes, dataPos, count * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().get(bank)
            return Triple(bank, rows, cols)
        }
    }
}
