package de.voicekeyboard.correct

import java.util.Locale

/**
 * T23c (V0.1.47, docs/PLAN_V0147.md §3, §14.3, §14.6): lexicon-based word
 * corrector. Pure Kotlin/JVM, no Android APIs, wand-only usage (rule 9).
 *
 * Semantics (calibrated over testdata/typing_goldens, see
 * tools/wordlists/SWEEP_de.md + precheck_t23c.py):
 *  - correct only if the word is NOT in the affected list(s) (casefold);
 *    CURRENT affects the list of the current layout language, ALL_ACTIVE all
 *    given lists. No list -> rules only -> null.
 *  - weighted edit distance max 2 (threshold 1.6): substitution = neighbour
 *    cost (0.4/0.7/1.0), insertion/deletion 1.0, transposition 0.8x the pair
 *    cost, German umlaut mappings ae/oe/ue/ss -> ä/ö/ü/ß (0.8) for de
 *    candidates only. Depth-2 expansion only when no depth-1 hit <= 0.8 exists
 *    (calibrated: keeps e.g. Gruse->Grüße (0.4+0.4) ahead of the 1.0 depth-1
 *    hit Grube; latency regions without such words stay depth-1-fast).
 *  - candidates merged by case-folded surface BEFORE ranking (§14.6a);
 *    ranked by (cost, frequency rank).
 *  - case from the input token (§14.6b); capitalised input -> capitalised fix.
 *  - no anglicising: cheaper candidate wins across languages (Projetk ->
 *    Projekt, not project).
 *  - brakes (no suggestion):
 *    (a) frequent-runner-up (§14.6e, e.g. Mial: Mail|mal): runner-up >= 10x
 *        more frequent, cost gap < 0.45 and the runner-up comes from an INNER
 *        deletion/insertion (phantom-char reading); edge deletions like
 *        Bich->ich do NOT block adjacent-key fixes;
 *    (b) cross-language (§14.3): cost gap < 0.5 AND rank ratio < 3.
 *  - must-not-touch (§2): digits, URLs/e-mails, ALLCAPS <= 5, inner capitals,
 *    length < 3; compounds: acceptance only via CompoundSplitter (never a
 *    candidate source).
 */
object WordCorrector {

    /** §14 correction language mode. */
    enum class Mode { CURRENT, ALL_ACTIVE }

    /** Weighted edit-distance acceptance threshold (calibration: 2 x 0.8). */
    const val COST_THRESHOLD = 1.6

    /** Depth-1 hits <= this skip the depth-2 expansion (latency). */
    private const val CHEAP_HIT = 0.8

    /** German umlaut mappings (de candidates only, §14.3). */
    private val UMLAUTS = listOf("ae" to 'ä', "oe" to 'ö', "ue" to 'ü', "ss" to 'ß')

    data class Correction(
        val text: String,
        val cost: Double,
        val lang: String,
        val ambiguous: Boolean,
    )

    /** Sealed op tags for the position-aware brake. */
    private sealed class Tag {
        object Sub : Tag()
        object Trp : Tag()
        object Uml : Tag()
        data class Del(val pos: Int) : Tag()
        data class Ins(val pos: Int) : Tag()
    }

    private data class Op(val out: String, val cost: Double, val tag: Tag)

    private data class Hit(
        val surface: String,
        val cost: Double,
        val rank: Double,
        val lang: String,
        val tag: Tag,
    )

    fun correct(word: String, layoutId: String, lists: Map<String, WordList>, mode: Mode): Correction? {
        if (word.length < 3) return null
        if (word.contains("://") || word.contains('@')) return null
        if (!word.all { it.isLetter() || it == '-' }) return null
        val letters = word.filter { it.isLetter() }
        // §2 must-not-touch: short ALLCAPS and inner capitals (iPhone-shaped);
        // capitals directly after a hyphen are legitimate (Team-Meeting)
        if (letters.isNotEmpty() && letters.length <= 5 && letters.all { it.isUpperCase() }) return null
        if (word.split('-').any { seg -> seg.drop(1).any { it.isUpperCase() } }) return null
        val affected = when (mode) {
            Mode.CURRENT -> {
                val l = lists[layoutId] ?: return null
                listOf(layoutId to l)
            }
            Mode.ALL_ACTIVE -> lists.entries.map { it.key to it.value }.ifEmpty { return null }
        }
        if (word.contains('-')) return correctHyphenated(word, layoutId, affected, mode)
        // in list -> no correction needed
        val folded = word.lowercase(Locale.ROOT)
        affected.forEach { (_, list) -> if (list.contains(word, Locale.ROOT)) return null }
        // compound acceptance (never a candidate source)
        if (CompoundSplitter.split(word, affected.toMap(), mode)) return null
        return search(word, folded, layoutId, affected)
    }

    /** Hyphenated tokens: accept fully valid compounds, fix a single bad part (§14.6d). */
    private fun correctHyphenated(
        word: String,
        layoutId: String,
        affected: List<Pair<String, WordList>>,
        mode: Mode,
    ): Correction? {
        val map = affected.toMap()
        val segments = word.split('-')
        if (segments.size < 2) return null
        val bad = segments.filter { seg ->
            seg.length < 3 || !seg.all { it.isLetter() } ||
                !(map.any { (_, l) -> l.contains(seg, Locale.ROOT) } ||
                    CompoundSplitter.split(seg, map, mode))
        }
        if (bad.isEmpty()) return null // accepted compound (e.g. Fahrrad-Schloss)
        if (bad.size > 1) return null
        val fix = correct(bad[0], layoutId, map, mode) ?: return null
        val fixed = segments.joinToString("-") { if (it == bad[0]) fix.text else it }
        return Correction(fixed, fix.cost, fix.lang, fix.ambiguous)
    }

    private fun search(
        word: String,
        folded: String,
        layoutId: String,
        affected: List<Pair<String, WordList>>,
    ): Correction? {
        val model = NeighbourModel.load(layoutId)
        val withUmlauts = affected.any { it.first == "de-de" }
        val step1 = genOps(folded, model, withUmlauts)

        val candidates = HashMap<String, MutableList<Hit>>() // folded surface -> hits (pre-merge)
        fun probe(cand: String, cost: Double, tag: Tag) {
            val surface = cand.lowercase(Locale.ROOT)
            for ((lang, list) in affected) {
                if (lang != "de-de" && surface.any { it in "äöüß" }) continue
                val rank = list.rank(surface, Locale.ROOT)
                if (rank >= 0) {
                    candidates.getOrPut(surface) { ArrayList() }.add(Hit(surface, cost, rank.toDouble(), lang, tag))
                }
            }
        }

        var bestDepth1 = Double.MAX_VALUE
        for ((cand, cost, tag) in step1) {
            if (cost > COST_THRESHOLD) continue
            probe(cand, cost, tag)
        }
        candidates.values.flatten().minOfOrNull { it.cost }?.let { bestDepth1 = it }

        if (bestDepth1 > CHEAP_HIT) { // no cheap depth-1 hit -> depth 2 (pruned by threshold)
            for ((c1, cost1, _) in step1) {
                if (cost1 > COST_THRESHOLD) continue
                for ((c2, cost2, tag2) in genOps(c1, model, withUmlauts)) {
                    val tot = cost1 + cost2
                    if (tot > COST_THRESHOLD) continue
                    probe(c2, tot, tag2)
                }
            }
        }
        if (candidates.isEmpty()) return null

        // merge casefold surface across languages (§14.6a): best (cost, rank) wins
        val merged = HashMap<String, Hit>()
        for (hits in candidates.values) {
            for (h in hits) {
                val cur = merged[h.surface]
                if (cur == null || h.cost < cur.cost || (h.cost == cur.cost && h.rank < cur.rank)) {
                    merged[h.surface] = h
                }
            }
        }
        val ranked = merged.values.sortedWith(compareBy({ it.cost }, { it.rank }))
        val best = ranked[0]

        if (ranked.size > 1) {
            val second = ranked[1]
            // (a) frequent-runner-up brake (§14.6e): inner phantom-char reading
            val gap = second.cost - best.cost
            val innerEdit = (second.tag is Tag.Del && second.tag.pos in 1 until folded.length - 1) ||
                (second.tag is Tag.Ins && second.tag.pos in 1 until folded.length)
            if (gap < 0.45 && second.rank * 10 <= best.rank && innerEdit) return null
            // (b) cross-language brake (§14.3)
            if (best.lang != second.lang && gap < 0.5 && second.rank < 3 * best.rank) return null
        }

        val form = best.surface
        val text = if (word[0].isUpperCase()) {
            form.replaceFirstChar { it.uppercase() }
        } else {
            form.lowercase(Locale.ROOT)
        }
        val ambiguous = ranked.size > 1 && (ranked[1].cost - best.cost) < 0.5
        return Correction(text, best.cost, best.lang, ambiguous)
    }

    private fun genOps(w: String, model: NeighbourModel, withUmlauts: Boolean): List<Op> {
        val out = ArrayList<Op>(w.length * 64)
        // folded reachable chars: shift-layer capitals fold onto the base keys —
        // candidates stay lowercase, so surfaces are unique per fold (§14.6a)
        val chars = model.foldedReachable
        for (i in w.indices) {
            for (ch in chars) { // substitution
                if (ch != w[i]) out.add(Op(w.substring(0, i) + ch + w.substring(i + 1), model.cost(w[i], ch), Tag.Sub))
            }
            out.add(Op(w.substring(0, i) + w.substring(i + 1), 1.0, Tag.Del(i))) // deletion
        }
        for (i in 0..w.length) { // insertion
            for (ch in chars) out.add(Op(w.substring(0, i) + ch + w.substring(i), 1.0, Tag.Ins(i)))
        }
        for (i in 0 until w.length - 1) { // transposition = 0.8x pair cost
            if (w[i] != w[i + 1]) {
                out.add(
                    Op(
                        w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2),
                        0.8 * model.cost(w[i], w[i + 1]),
                        Tag.Trp,
                    ),
                )
            }
        }
        if (withUmlauts) {
            for ((from, to) in UMLAUTS) {
                var j = w.indexOf(from)
                while (j >= 0) {
                    out.add(Op(w.substring(0, j) + to + w.substring(j + from.length), 0.8, Tag.Uml))
                    j = w.indexOf(from, j + 1)
                }
            }
        }
        return out
    }
}
