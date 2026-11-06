package de.voicekeyboard.correct

import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * T23b (V0.1.47, docs/PLAN_V0147.md §15): reader for the deterministic VKWL1
 * binary word lists (format documented in tools/build_wordlist.py):
 *
 *   magic 'VKWL1' | lang-length u8 | lang ASCII | entry-count u32 LE |
 *   N entries in FREQUENCY order (entry index = frequency rank, 0 = most
 *   frequent): u16 LE byte length + utf-8 word (case preserved; main form =
 *   most frequent spelling).
 *
 * Blobs over the size budget are gzip-wrapped (mtime=0, still deterministic);
 * [load] auto-detects the gzip magic. Pure Kotlin/JVM, no Android APIs, no
 * word data outside the APK assets (see assets/wordlists/NOTICE.txt).
 */
class WordList private constructor(
    val languageId: String,
    private val words: List<String>,
    private val foldIndex: Map<String, Int>,
) {

    /** Number of entries (== header entry count). */
    val size: Int get() = words.size

    /** Entry at frequency position [rank] (0-based, 0 = most frequent). */
    fun wordAt(rank: Int): String = words[rank]

    /** Case-insensitive membership; query folded with [caseLocale]. */
    fun contains(word: String, caseLocale: Locale = Locale.ROOT): Boolean =
        foldIndex.containsKey(word.lowercase(caseLocale))

    /** Frequency rank of the fold-matching entry, -1 if absent. */
    fun rank(word: String, caseLocale: Locale = Locale.ROOT): Int =
        foldIndex[word.lowercase(caseLocale)] ?: -1

    companion object {

        private const val MAGIC = "VKWL1"

        /** Decodes a VKWL1 blob (optionally gzip-wrapped) into a [WordList]. */
        fun load(bytes: ByteArray): WordList {
            val buf = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(bytes.inputStream()).readBytes()
            } else {
                bytes
            }
            check(buf.size >= 10) { "VKWL1 blob too short" }
            check(String(buf, 0, 5, Charsets.US_ASCII) == MAGIC) { "not a VKWL1 blob" }
            val langLen = buf[5].toInt() and 0xff
            check(buf.size >= 10 + langLen) { "VKWL1 header truncated" }
            val languageId = String(buf, 6, langLen, Charsets.US_ASCII)
            var p = 6 + langLen
            val count = readU32(buf, p)
            p += 4
            val words = ArrayList<String>(count)
            val foldIndex = HashMap<String, Int>(count * 2)
            repeat(count) { rank ->
                check(p + 2 <= buf.size) { "VKWL1 entry truncated" }
                val len = readU16(buf, p)
                p += 2
                check(p + len <= buf.size) { "VKWL1 entry truncated" }
                val word = String(buf, p, len, Charsets.UTF_8)
                p += len
                words.add(word)
                // builder already deduped case-insensitively; first (most
                // frequent) form wins defensively
                foldIndex.putIfAbsent(word.lowercase(Locale.ROOT), rank)
            }
            check(p == buf.size) { "VKWL1 trailing bytes" }
            return WordList(languageId, words, foldIndex)
        }

        private fun readU16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

        private fun readU32(b: ByteArray, o: Int): Int {
            var v = 0
            for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toInt() and 0xff)
            return v
        }
    }
}
