package de.voicekeyboard.model

/**
 * Framework-free start/display decisions for the model download
 * (docs/SETUP_UI_DECISIONS.md E2/E3, L rules). Pure logic, JVM-testable.
 *
 * Rules:
 * (a) Start only with the network switch turned on (E2: app switch,
 *     default on until setup is complete, then off).
 * (b) Free space >= [MIN_FREE_BYTES] (E3: 2.5 GB — 671 MB model
 *     + reserve; replaces the 3-GB plausibility from DESIGN with the
 *     binding value from SETUP_UI_DECISIONS.md E3).
 * (c) Already READY → no start (L6).
 * (e) Error classes → short user text keys (netz/hash/platz/abgebrochen).
 *
 * T20b (PLAN_V0145): the E2 in-app network switch is GONE — the app never
 * gates or revokes the network itself. Callers pass networkEnabled = true;
 * a system-level INTERNET revoke surfaces as the "netz" error class (D1).
 */
object DownloadPolicy {

    /** E3: at least 2.5 GB free before the first byte (model + reserve). */
    const val MIN_FREE_BYTES: Long = 2_500_000_000L

    /** T22d (PLAN_V0146 §2): reserve ON TOP of the model bytes per set — the
     *  parts→staging move is a rename on the same file system, but the parts
     *  coexist with the active version while downloading. */
    const val SET_RESERVE_BYTES: Long = 1_500_000_000L

    /** T22d (PLAN_V0146 §2): total device RAM required to OFFER the QWEN
     *  download (RAM gate is Qwen-only; A/B have no RAM gate). */
    const val QWEN_MIN_TOTAL_RAM_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Start decision for the setup UI. */
    sealed class Decision {
        data object Start : Decision()
        data object AlreadyReady : Decision()          // (c) no start
        data object NetworkDisabled : Decision()       // (a) switch off
        data class InsufficientSpace(val freeBytes: Long) : Decision() // (b)
    }

    /** Complete start decision against the store/switch/space state. */
    fun decide(
        networkEnabled: Boolean,
        freeBytes: Long,
        status: ModelStatus,
    ): Decision = when {
        status == ModelStatus.READY -> Decision.AlreadyReady
        !networkEnabled -> Decision.NetworkDisabled
        freeBytes < MIN_FREE_BYTES -> Decision.InsufficientSpace(freeBytes)
        else -> Decision.Start
    }

    /** T22d (PLAN_V0146 §2): space requirement of a model set. Unsupported =
     *  set not usable: empty manifest ("modell nicht freigeschaltet") or —
     *  VK-2 — no pinned base URL yet ("not enabled yet", server host is
     *  pinned at upload time). Structurally no download possible. */
    sealed class SpaceNeed {
        data class Required(val bytes: Long) : SpaceNeed()
        data class Unsupported(val reason: String = REASON_NOT_UNLOCKED) : SpaceNeed()

        companion object {
            /** empty manifest list (no contract yet). */
            const val REASON_NOT_UNLOCKED = "modell nicht freigeschaltet"

            /** VK-2: base URL empty — server host not pinned yet. */
            const val REASON_NOT_ENABLED = "not enabled yet"
        }
    }

    /** T22d: model bytes + [SET_RESERVE_BYTES]; [manifest] injectable so tests
     *  can pin a future QWEN list; [baseUrl] injectable (VK-2: empty = not
     *  enabled — production default = the pinned URL of the variant). */
    fun spaceNeedFor(
        variant: ModelVariant,
        manifest: List<ModelFile> = ModelManifest.forVariant(variant),
        baseUrl: String = ModelManifest.baseUrlFor(variant),
    ): SpaceNeed = when {
        manifest.isEmpty() -> SpaceNeed.Unsupported()
        baseUrl.isEmpty() -> SpaceNeed.Unsupported(SpaceNeed.REASON_NOT_ENABLED)
        else -> SpaceNeed.Required(manifest.sumOf { it.sizeBytes } + SET_RESERVE_BYTES)
    }

    /** T22d: effective free-space floor per set = max(MIN_FREE_BYTES, need).
     *  A stays NUMERICALLY UNCHANGED: 670.6 MB + 1.5 GB < 2.5 GB ⇒ the E3
     *  floor of [decide] keeps its exact old numbers. null = set unsupported. */
    fun requiredFreeBytes(
        variant: ModelVariant,
        manifest: List<ModelFile> = ModelManifest.forVariant(variant),
        baseUrl: String = ModelManifest.baseUrlFor(variant),
    ): Long? =
        when (val need = spaceNeedFor(variant, manifest, baseUrl)) {
            is SpaceNeed.Unsupported -> null
            is SpaceNeed.Required -> maxOf(MIN_FREE_BYTES, need.bytes)
        }

    /** T22d (PLAN_V0146 §2): may the UI OFFER the download of [variant]?
     *  A (and debug-only B): always — no RAM gate, only the space floor.
     *  QWEN: only with a pinned manifest (not empty) AND a pinned base URL
     *  (VK-2) AND total RAM >= [QWEN_MIN_TOTAL_RAM_BYTES], plus the space floor. */
    fun canOfferSet(
        variant: ModelVariant,
        totalRamBytes: Long,
        freeBytes: Long,
        manifest: List<ModelFile> = ModelManifest.forVariant(variant),
        baseUrl: String = ModelManifest.baseUrlFor(variant),
    ): Boolean {
        val floor = requiredFreeBytes(variant, manifest, baseUrl) ?: return false // not unlocked/not enabled
        if (freeBytes < floor) return false
        return when (variant) {
            ModelVariant.QWEN3_ASR -> totalRamBytes >= QWEN_MIN_TOTAL_RAM_BYTES
            else -> true // plan §2: "RAM gate (Qwen only)"
        }
    }

    /** (e) Error kind → short user text key (never HTTP details, never URLs).
     *  D1 real taxonomy:
     *  - "netz" only for connection problems (timeout/connect/DNS/abort),
     *  - "server" for final 4xx (403/404/410 … — server refuses, connection ok),
     *  - "warte" for 408/429 (server overloaded — retry later),
     *  - "hash"/"platz"/"abgebrochen" as before.
     *  5xx stays "netz" (transient, retry already runs in the downloader). */
    fun userMessageKey(error: ModelError): String = when (error) {
        is ModelError.Cancelled -> "abgebrochen"
        is ModelError.HashMismatch -> "hash"
        is ModelError.Storage -> "platz"
        is ModelError.Http -> when (error.statusCode) {
            416 -> "fortsetzung" // D1-02: resume problem, not a server rejection
            408, 429 -> "warte"
            in 400..499 -> "server"
            else -> "netz" // 5xx: transient — see ModelDownloader for retries
        }
        is ModelError.Network -> "netz"
    }
}
