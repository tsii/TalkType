package de.voicekeyboard.correct

/**
 * T24b/B3b (PLAN_V0147 §17.2, Gleis 3): the REAL [BartTokenizer] over the
 * shipped tokenizer.json — ByteLevel-BPE (en, RobertaTokenizer) and
 * SentencePiece-Unigram (de, T5Tokenizer), reproducing the HF encode
 * semantics of the GATE runner exactly (BART_CORRECT_CONTRACT §5):
 *  - en: RobertaProcessing wrapper [<s>=0 … </s>=2], GPT-2 pre-token regex,
 *    byte-level unicode mapping, merges by rank; decode byte-level, specials skipped.
 *  - de: WhitespaceSplit -> Metaspace("▁", prepend always) -> Unigram Viterbi
 *    over [piece, score]; unk via the minimum-score penalty (tokenizers
 *    kUnkPenalty); T5 appends </s>=1; decode Metaspace join, specials skipped.
 *
 * Parity pinned in BartTokenizerTest against the GATE report
 * (per-sentence n_input_tokens; totals 165->147 en / 226->161 de).
 * The de PRECOMPILED charsmap normalizer is NOT reimplemented (documented
 * deviation candidate): the goldens are plain German text — any divergence
 * would surface in the parity test and be escalated, not patched silently.
 * No logging (rule 9).
 */
object BartTokenizers {

    fun load(tokenizerJson: String, meta: BartCorrectEngine.VocabMeta): BartTokenizer {
        val root = MiniJson.parseObject(tokenizerJson)
        @Suppress("UNCHECKED_CAST")
        val model = root["model"] as Map<String, Any?>
        return when (model["type"] as String) {
            "BPE" -> ByteLevelBpe(model, meta)
            "Unigram" -> SentencePieceUnigram(model, meta)
            else -> error("unsupported tokenizer model type")
        }
    }

    /** From the ModelStore set tree (tokenizer/vocab_meta.json + tokenizer.json). */
    fun fromModelDir(modelDir: java.io.File): BartTokenizer {
        val tokDir = java.io.File(modelDir, "tokenizer")
        val meta = BartCorrectEngine.VocabMeta.load(java.io.File(tokDir, "vocab_meta.json"))
        return load(java.io.File(tokDir, "tokenizer.json").readText(), meta)
    }

    // ---------------------------------------------------- ByteLevel BPE (en)

    class ByteLevelBpe(
        model: Map<String, Any?>,
        private val meta: BartCorrectEngine.VocabMeta,
    ) : BartTokenizer {
        init {
            require(model["type"] == "BPE") { "BPE model expected" }
        }

        @Suppress("UNCHECKED_CAST")
        private val vocab: Map<String, Long> =
            (model["vocab"] as Map<String, Any?>).mapValues { it.value as Long }

        @Suppress("UNCHECKED_CAST")
        private val mergeRank: Map<String, Long> =
            (model["merges"] as List<Any?>).mapIndexed { i, m ->
                val pair = m as List<Any?>
                "${pair[0]} ${pair[1]}" to i.toLong()
            }.toMap()

        private val idToToken: Map<Long, String> = vocab.entries.associate { it.value to it.key }

        /** <s> … </s> wrapper (RobertaProcessing), specials never inside. */
        override fun encode(text: String): LongArray {
            val cls = vocab["<s>"] ?: 0L
            val sep = meta.eosId
            val body = ArrayList<Long>()
            for (pre in PRE_TOKEN_REGEX.findAll(text)) {
                val mapped = bytesToByteLevel(pre.value)
                for (tok in bpe(mapped)) {
                    body.add(vocab.getValue(tok))
                }
            }
            val out = LongArray(body.size + 2)
            out[0] = cls
            for (i in body.indices) out[i + 1] = body[i]
            out[out.size - 1] = sep
            return out
        }

        override fun decode(ids: LongArray): String {
            val cls = vocab["<s>"] ?: 0L
            val unk = vocab["<unk>"] ?: 3L
            val mask = vocab["<mask>"]
            val sb = StringBuilder()
            for (id in ids) {
                if (id == cls || id == meta.eosId || id == meta.padId || id == unk) continue
                if (mask != null && id == mask) continue
                sb.append(idToToken[id] ?: continue)
            }
            return byteLevelToString(sb.toString())
        }

        /** classic BPE: merge the lowest-rank pair until none left. */
        private fun bpe(word: String): List<String> {
            if (word.isEmpty()) return emptyList()
            var symbols = word.map { it.toString() }.toMutableList()
            while (symbols.size > 1) {
                var bestKey: String? = null
                var bestRank = Long.MAX_VALUE
                for (i in 0 until symbols.size - 1) {
                    val key = "${symbols[i]} ${symbols[i + 1]}"
                    val rank = mergeRank[key] ?: continue
                    if (rank < bestRank) {
                        bestRank = rank
                        bestKey = key
                    }
                }
                if (bestKey == null) break
                val parts = bestKey.split(' ')
                val a = parts[0]
                val b = parts[1]
                val out = ArrayList<String>(symbols.size)
                var i = 0
                while (i < symbols.size) {
                    if (i < symbols.size - 1 && symbols[i] == a && symbols[i + 1] == b) {
                        out.add(a + b)
                        i += 2
                    } else {
                        out.add(symbols[i])
                        i++
                    }
                }
                symbols = out
            }
            return symbols
        }

        companion object {
            /** GPT-2 pre-token regex (ByteLevel use_regex=true). */
            private val PRE_TOKEN_REGEX =
                Regex("(?:'s|'t|'re|'ve|'m|'ll|'d)| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")

            internal val BYTE_TABLE: Map<Int, String> = buildByteTable()

            /** canonical GPT-2 bytes_to_unicode: printable ASCII + two latin
             *  blocks map to themselves; every other byte to 256+n. */
            private fun buildByteTable(): Map<Int, String> {
                val out = HashMap<Int, String>(256)
                var extra = 0
                for (b in 0 until 256) {
                    if (b in 33..126 || b in 161..172 || b in 174..255) {
                        out[b] = b.toChar().toString()
                    } else {
                        out[b] = (256 + extra).toChar().toString()
                        extra++
                    }
                }
                return out
            }

            fun bytesToByteLevel(text: String): String {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val sb = StringBuilder(bytes.size)
                for (b in bytes) {
                    sb.append(BYTE_TABLE.getValue(b.toInt() and 0xFF))
                }
                return sb.toString()
            }

            fun byteLevelToString(mapped: String): String {
                val rev = HashMap<String, Int>(256)
                for ((b, s) in BYTE_TABLE) rev[s] = b
                val bytes = ByteArray(mapped.length)
                for (i in mapped.indices) {
                    val v = rev[mapped[i].toString()]
                    if (v == null) return mapped // not byte-level data: as-is
                    bytes[i] = v.toByte()
                }
                return String(bytes, Charsets.UTF_8)
            }
        }
    }

    // -------------------------------------------- SentencePiece Unigram (de)

    class SentencePieceUnigram(
        model: Map<String, Any?>,
        private val meta: BartCorrectEngine.VocabMeta,
    ) : BartTokenizer {
        init {
            require(model["type"] == "Unigram") { "Unigram model expected" }
        }

        @Suppress("UNCHECKED_CAST")
        private val pieces: List<Pair<String, Double>> =
            (model["vocab"] as List<Any?>).map { row ->
                (row as List<Any?>).let { p -> p[0] as String to (p[1] as Double) }
            }

        private val pieceIndex: Map<String, Int> =
            pieces.withIndex().associate { it.value.first to it.index }

        private val unkId: Long = (model["unk_id"] as? Long) ?: 2L
        private val unkScore: Double = pieces.minOf { it.second } - 10.0

        /** T5 added tokens (<extra_id_*> at 32000..32099) + pad/eos/unk. */
        private val specials: Set<Long> =
            (setOf(meta.padId, meta.eosId, unkId) + (32000L..32099L)).toSet()

        /** T5: [pieces…, </s>] (no BOS); Metaspace prepend "▁" per word. */
        override fun encode(text: String): LongArray {
            val ids = ArrayList<Long>()
            for (word in text.split(' ').filter { it.isNotEmpty() }) {
                for (idx in viterbi("▁$word")) {
                    ids.add(idx.toLong())
                }
            }
            ids.add(meta.eosId)
            return ids.toLongArray()
        }

        override fun decode(ids: LongArray): String {
            val sb = StringBuilder()
            for (id in ids) {
                if (id in specials) continue
                sb.append(pieces.getOrNull(id.toInt())?.first ?: continue)
            }
            // Metaspace decode: ▁ -> space; prepend_scheme "always" added ONE
            // leading ▁ that becomes the leading space to strip.
            return sb.toString().replace("▁", " ").removePrefix(" ")
        }

        /** Viterbi over the piece lattice (tokenizers-rs Unigram semantics). */
        internal fun viterbi(word: String): IntArray {
            val n = word.length
            if (n == 0) return IntArray(0)
            val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
            val bestStart = IntArray(n + 1) { -1 }
            val bestPiece = IntArray(n + 1) { -1 }
            bestScore[0] = 0.0
            for (end in 1..n) {
                for (start in 0 until end) {
                    if (bestScore[start] == Double.NEGATIVE_INFINITY) continue
                    val idx = pieceIndex[word.substring(start, end)]
                    if (idx == null) continue
                    val score = bestScore[start] + pieces[idx].second
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        bestStart[end] = start
                        bestPiece[end] = idx
                    }
                }
                // single unknown char fallback (tokenizers kUnkPenalty)
                val start = end - 1
                if (bestScore[start] != Double.NEGATIVE_INFINITY) {
                    val score = bestScore[start] + unkScore
                    if (score > bestScore[end]) {
                        bestScore[end] = score
                        bestStart[end] = start
                        bestPiece[end] = -1
                    }
                }
            }
            val out = ArrayList<Int>(n)
            var pos = n
            while (pos > 0) {
                val piece = bestPiece[pos]
                if (piece == -1) out.add(unkId.toInt()) else out.add(piece)
                pos = bestStart[pos]
            }
            out.reverse()
            return out.toIntArray()
        }
    }
}
