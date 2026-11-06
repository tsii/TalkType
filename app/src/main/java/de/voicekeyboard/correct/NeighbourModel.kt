package de.voicekeyboard.correct

/**
 * T23c (V0.1.47, docs/PLAN_V0147.md §14.3): neighbour graph of the DISPLAYED
 * layout. Key rects from assets/layouts/<id>.json (layers base + shift; rows
 * with cumulative x by key width, row pitch = 1.5x column pitch, so vertical
 * neighbours like q-a are 'weit'). Long-press alternates share the host key
 * rect (same key, e.g. u/ü).
 *
 * Substitution cost by rect-centre distance d:
 *   d < 0.25 (same key)   -> 0.4
 *   d < 1.5  (benachbart) -> 0.7
 *   d >= 1.5 (weit)       -> 1.0
 *   unreachable chars     -> 1.0 (flat)
 *
 * Pure Kotlin/JVM — own minimal JSON reader, no Android APIs (production wiring
 * uses [parse] with the asset JSON; [load] resolves the asset file from the
 * working directory for JVM tests/tools).
 */
class NeighbourModel private constructor(
    val layoutId: String,
    private val positions: Map<Char, Pair<Double, Double>>,
) {

    fun isReachable(c: Char): Boolean = c in positions

    fun reachableChars(): Set<Char> = positions.keys

    /** Folded (lowercase) reachable chars for candidate generation — cached. */
    val foldedReachable: Set<Char> by lazy {
        val s = LinkedHashSet<Char>()
        for (c in positions.keys) s.add(c.lowercaseChar())
        s
    }

    fun cost(a: Char, b: Char): Double {
        if (a == b) return 0.0
        val pa = positions[a] ?: return 1.0
        val pb = positions[b] ?: return 1.0
        val d = kotlin.math.hypot(pa.first - pb.first, pa.second - pb.second)
        return when {
            d < 0.25 -> SAME_KEY_COST
            d < 1.5 -> NEIGHBOUR_COST
            else -> 1.0
        }
    }

    companion object {
        const val SAME_KEY_COST = 0.4
        const val NEIGHBOUR_COST = 0.7
        const val FAR_COST = 1.0
        private const val ROW_PITCH = 1.5 // 1.5x column pitch: q-a is 'weit'

        private val cache = HashMap<String, NeighbourModel>()

        /** REVIEW_V0147 VK-1 test observability: how often the JVM-only walk-up
         *  loader ran — production must ALWAYS come over [seed] (the walk-up
         *  crashes on Android); tests assert walkUpLoads == 0. */
        internal var walkUpLoads: Int = 0
            private set

        /** JVM test/tool loader: resolves assets/layouts/<layoutId>.json by walk-up. */
        fun load(layoutId: String): NeighbourModel =
            cache.getOrPut(layoutId) {
                walkUpLoads++
                parse(layoutId, readLayoutJson(layoutId))
            }

        /** REVIEW_V0147 VK-1: is the layout seeded (production path)? */
        internal fun isSeeded(layoutId: String): Boolean = cache.containsKey(layoutId)

        /** REVIEW_V0147 VK-1: test reset — clears the seed cache + walk-up counter. */
        internal fun resetSeedsForTest() {
            cache.clear()
            walkUpLoads = 0
        }

        /** T23g production seam: seed the cache from the ALREADY-READ layout
         *  asset JSON — Android has no walk-up file system (readLayoutJson is
         *  JVM-only); the service calls this once per language before the
         *  first compute. Idempotent, cheap on repeats. */
        fun seed(layoutId: String, json: String): NeighbourModel =
            cache.getOrPut(layoutId) { parse(layoutId, json) }

        /** Production seam: parse the layout asset JSON (already read by the caller). */
        fun parse(layoutId: String, json: String): NeighbourModel {
            val root = MiniJson.parseObject(json)
            @Suppress("UNCHECKED_CAST")
            val layers = root["layers"] as Map<String, Any?>
            val positions = LinkedHashMap<Char, Pair<Double, Double>>()
            for (layerName in listOf("base", "shift")) {
                @Suppress("UNCHECKED_CAST")
                val layer = layers[layerName] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val rows = layer["rows"] as List<Any?>
                for ((rowIdx, rowAny) in rows.withIndex()) {
                    @Suppress("UNCHECKED_CAST")
                    val row = rowAny as List<Any?>
                    var x = 0.0
                    for (keyAny in row) {
                        @Suppress("UNCHECKED_CAST")
                        val key = keyAny as Map<String, Any?>
                        val width = (key["width"] as? Double) ?: 1.0
                        if (key["kind"] == "text") {
                            val value = key["value"] as String
                            val pos = Pair(x + width / 2.0, ROW_PITCH * rowIdx)
                            if (value.isNotEmpty()) positions.putIfAbsent(value[0], pos)
                            @Suppress("UNCHECKED_CAST")
                            val alts = key["alternates"] as? List<Any?>
                            alts?.forEach { a ->
                                val s = a as String
                                if (s.isNotEmpty()) positions.putIfAbsent(s[0], pos)
                            }
                        }
                        x += width
                    }
                }
            }
            return NeighbourModel(layoutId, positions)
        }

        private fun readLayoutJson(layoutId: String): String {
            var base = java.io.File(System.getProperty("user.dir") ?: ".")
            repeat(6) {
                for (rel in listOf("src/main/assets/layouts", "app/src/main/assets/layouts")) {
                    val f = java.io.File(base, "$rel/$layoutId.json")
                    if (f.isFile) return f.readText()
                }
                base = base.parentFile ?: return@repeat
            }
            throw IllegalStateException("assets/layouts/$layoutId.json not found")
        }
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, bools, null). */
private object MiniJson {

    fun parseObject(text: String): Map<String, Any?> {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        require(p.atEnd()) { "trailing characters in json" }
        @Suppress("UNCHECKED_CAST")
        return v as Map<String, Any?>
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun atEnd() = i >= s.length

        fun parseValue(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end of json" }
            return when (s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        fun expect(w: String) {
            require(s.regionMatches(i, w, 0, w.length)) { "bad json literal at $i" }
            i += w.length
        }

        fun parseObj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val k = parseString()
                skipWs()
                require(s[i] == ':') { "expected ':' at $i" }
                i++
                out[k] = parseValue()
                skipWs()
                if (s[i] == ',') i++ else { require(s[i] == '}'); i++; return out }
            }
        }

        fun parseArr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (s[i] == ',') i++ else { require(s[i] == ']'); i++; return out }
            }
        }

        fun parseString(): String {
            require(s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException("bad escape at $i")
                    }
                    i++
                } else {
                    sb.append(s[i])
                    i++
                }
            }
            i++
            return sb.toString()
        }

        fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            return if (t.contains('.') || t.contains('e') || t.contains('E')) t.toDouble() else t.toLong()
        }
    }
}
