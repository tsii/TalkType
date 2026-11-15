package de.voicekeyboard.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import de.voicekeyboard.R
import de.voicekeyboard.model.DownloadController
import de.voicekeyboard.model.DownloadPolicy
import de.voicekeyboard.model.ImportResult
import de.voicekeyboard.model.ModelDownloadService
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelStatus
import de.voicekeyboard.model.NetworkInvitePolicy
import java.io.File

/**
 * Setup page (M5b-2b): drive the model download, request permissions,
 * network switch persistence (E2: default on until READY, then off automatically).
 *
 * C-3 write path: if a permission is denied again, the marker
 * `denied_forever_<permission>` is written to the same SharedPreferences file
 * that [VoiceKeyboardService] reads ("vk_permissions" —
 * same name/prefix, nothing invented anew).
 *
 * E3 rule "the keyboard never blocks": without model/without permissions the
 * keyboard stays fully usable — the setup page only shows hints.
 */
class SetupActivity : ComponentActivity() {

    private lateinit var stateView: TextView
    private lateinit var versionView: TextView
    private lateinit var fileInfoView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var netInviteCard: android.widget.LinearLayout
    private lateinit var openSettingsButton: Button
    private lateinit var permissionsHint: TextView

    // T22h: card views of the dictation model selection (Parakeet / Qwen).
    private lateinit var parakeetStatus: TextView
    private lateinit var parakeetSelect: Button
    private lateinit var parakeetDownload: Button
    private lateinit var parakeetDelete: Button
    private lateinit var parakeetProgress: ProgressBar
    private lateinit var qwenStatus: TextView
    private lateinit var qwenSelect: Button
    private lateinit var qwenDownload: Button
    private lateinit var qwenDelete: Button
    private lateinit var qwenImport: Button
    private lateinit var qwenProgress: ProgressBar

    /** T22h test seam: store injection (null -> filesDir/models). */
    internal var modelStoreOverride: ModelStore? = null

    /** T22h test seam: total device RAM (null -> ActivityManager.MemoryInfo). */
    internal var totalRamOverride: Long? = null

    /** T22h test seam: manifest for the offer check (null -> pinned list of
     *  the variant; emptyList simulates a not-unlocked set). */
    internal var manifestOverride: List<de.voicekeyboard.model.ModelFile>? = null

    /** V0.1.51 (PLAN_V0151 §D, P3): variant whose folder picker is currently
     *  open (survives activity recreation — the picker returns into a NEW
     *  instance after a rotation). */
    private var pendingImportVariant: de.voicekeyboard.model.ModelVariant? = null
    private var pendingImportUri: Uri? = null
    private var deletingVariant: de.voicekeyboard.model.ModelVariant? = null

    /** V0.1.51: variant whose import is RUNNING (background thread) — hides
     *  the import buttons, shows the running line. */

    private val prefs by lazy { getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE) }
    private val setupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "vk-setup-io").apply { isDaemon = true }
    }
    internal var ioExecutor: java.util.concurrent.Executor = setupExecutor
    internal var storageLoaded = false
        private set
    private var sectionsBuilt = false
    private var refreshPending = false
    private var refreshAgain = false
    private data class StorageSnapshot(
        val statuses: Map<de.voicekeyboard.model.ModelVariant, ModelStatus>,
        val setIds: Map<de.voicekeyboard.model.ModelVariant, String>,
        val freeSpace: Long, val ramBytes: Long,
        val wordAvailability: String, val versionLabel: String,
    )
    private var cachedStore: ModelStore? = null
    private var statuses = emptyMap<de.voicekeyboard.model.ModelVariant, ModelStatus>()
    private var setIds = emptyMap<de.voicekeyboard.model.ModelVariant, String>()
    private var freeSpace = 0L
    private var ramBytes = 0L
    private var wordAvailability = ""
    private var versionLabel = ""

    private fun loadStorage(): StorageSnapshot {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        prefs.all
        getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE).all
        cachedStore = modelStoreOverride ?: cachedStore ?: ModelStore(File(filesDir, MODEL_DIR_NAME))
        val store = cachedStore!!
        return StorageSnapshot(
            de.voicekeyboard.model.ModelVariant.entries.associateWith { store.statusQuick(it) },
            de.voicekeyboard.model.ModelVariant.entries.associateWith { store.manifestSetId(it) },
            filesDir.usableSpace, totalRamOverride ?: deviceTotalRamBytes(),
            getString(R.string.setup_lang_word_correction,
                assets.list("wordlists")?.filter { it.endsWith(".bin") }
                    ?.joinToString(", ") { LanguageCycle.endonym(it.removeSuffix(".bin")) } ?: ""),
            AppVersion.versionLabel(AppVersion.read(this), isDebugBuild()),
        )
    }

    private fun cachedStatus(variant: de.voicekeyboard.model.ModelVariant =
        de.voicekeyboard.model.ModelVariant.ONNX_CPU): ModelStatus = statuses[variant] ?: ModelStatus.ABSENT

    /** T19 (v0.1.45): debug flag as an injectable seam (T9-style
     *  ApplicationInfo.FLAG_DEBUGGABLE read via the application info);
     *  null -> read the flag from the package. */
    internal var debugBuildOverride: Boolean? = null

    private fun isDebugBuild(): Boolean = debugBuildOverride
        ?: ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)

    /** T20b test seam: controller state for the invite-card decision;
     *  null -> the real state (live controller or cold-start store READY). */
    internal var inviteStateOverride: DownloadController.State? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> evaluatePermissions(results) }

    private val startLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* return from system settings: re-evaluate the state */ refreshUi() }

    /** V0.1.51 (PLAN_V0151 §D, P3): system folder picker (SAF tree) for the
     *  local model import — no network, no permission; the picked folder is
     *  only read while the import runs. */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onImportFolderPicked(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setup_activity)
        // V0.1.51: a rotation while the folder picker is open must not lose
        // the import target — restored BEFORE the result can arrive.
        pendingImportVariant = savedInstanceState
            ?.getString(KEY_PENDING_IMPORT)
            ?.let { id -> de.voicekeyboard.model.ModelVariant.entries.firstOrNull { it.id == id } }
        pendingImportUri = savedInstanceState?.getString(KEY_PENDING_IMPORT_URI)?.let(Uri::parse)
        stateView = findViewById(R.id.setup_state)
        // T19 (v0.1.45): version label via the ONE shared read path
        // (AppVersion.read — same source as the debug export).
        versionView = findViewById(R.id.setup_version)
        versionView.text = "…"
        fileInfoView = findViewById(R.id.setup_file_info)
        progress = findViewById(R.id.setup_progress)
        startButton = findViewById(R.id.setup_download_start)
        cancelButton = findViewById(R.id.setup_download_cancel)
        netInviteCard = findViewById(R.id.setup_net_invite)
        openSettingsButton = findViewById(R.id.setup_open_settings)
        permissionsHint = findViewById(R.id.setup_permissions_hint)

        // T22h (PLAN_V0146 §3): dictation model cards — one per set (Parakeet
        // recommended, Qwen experimental; LITERT_CPU debug-only and hidden).
        parakeetStatus = findViewById(R.id.setup_parakeet_status)
        parakeetSelect = findViewById(R.id.setup_parakeet_select)
        parakeetDownload = findViewById(R.id.setup_parakeet_download)
        parakeetDelete = findViewById(R.id.setup_parakeet_delete)
        parakeetProgress = findViewById(R.id.setup_parakeet_progress)
        qwenStatus = findViewById(R.id.setup_qwen_status)
        qwenSelect = findViewById(R.id.setup_qwen_select)
        qwenDownload = findViewById(R.id.setup_qwen_download)
        qwenDelete = findViewById(R.id.setup_qwen_delete)
        qwenImport = findViewById(R.id.setup_qwen_import)
        qwenProgress = findViewById(R.id.setup_qwen_progress)
        qwenDownload.visibility = android.view.View.GONE
        qwenImport.isEnabled = false
        qwenStatus.setText(R.string.setup_import_only)
        parakeetSelect.setOnClickListener { onSelectModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        parakeetDownload.setOnClickListener { onDownloadModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        parakeetDelete.setOnClickListener { onDeleteModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        qwenSelect.setOnClickListener { onSelectModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }
        qwenDownload.setOnClickListener { onDownloadModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }
        qwenDelete.setOnClickListener { onDeleteModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }
        // V0.1.51 (PLAN_V0151 §D): Qwen has NO download host (BASE_URL_QWEN
        // empty) — the local folder import is the offline way to the set.
        qwenImport.setOnClickListener { onImportFolder(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }

        startButton.setOnClickListener { requestStartDownload() }
        cancelButton.setOnClickListener { sendServiceAction(ModelDownloadService.ACTION_CANCEL) }
        findViewById<Button>(R.id.setup_request_permissions).setOnClickListener { requestPermissionsNow() }
        openSettingsButton.setOnClickListener { openSystemSettings() }

        // T20b (PLAN_V0145, binding): the app NEVER revokes/touches the network
        // itself — the E2 in-app switch is GONE. ONE invite card offers the
        // SYSTEM-level revoke, exactly once per model set (NetworkInvitePolicy).
        findViewById<Button>(R.id.setup_net_invite_button).setOnClickListener {
            markNetInviteShown()
            openNetworkRevokeSettings()
        }
        findViewById<Button>(R.id.setup_net_invite_later).setOnClickListener { markNetInviteShown() }

        // T23h (PLAN_V0147 §6): typing suggestions section BELOW the dictation
        // model section (appended at the end of the layout root).
        // T24b (PLAN_V0147 §17.2, Gleis 3): the U2 text-correction card — third
        // model card, programmatic (no XML change), honest inert state while
        // BASE_URL_U2_EN/_DE are empty; the set follows the CURRENT typing
        // language. V0.1.53 (PLAN_QWEN17B §B): the LOCKED 1.7B card follows —
        // visible with an honest explanation, but inert (no buttons at all).

        // observer AFTER layout registration: the service informs us state-based
        // (no polling, no own thread); UI-thread delivery via runOnUiThread.
        ModelDownloadService.observer = { _ -> runOnUiThread { refreshUi() } }
        importObserver = { refreshUi() }
    }

    // ---- Language selection (E9.1 rule 1/2, M8b-1) ----

    /** Checkboxes for all 15 layouts in canonical order; selection in
     *  vk_layout/active_languages (ordered, canonical, comma-separated — no StringSet).
     *  At least one enforced: deselecting the last one is refused. */
    private fun buildLanguageSection() {
        val layoutRoot = findViewById<TextView>(R.id.setup_title).parent as android.widget.LinearLayout
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.setup_languages_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.setup_languages_hint)
            textSize = 13f
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        })
        val layoutPrefs = getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
        val active = de.voicekeyboard.ime.LanguageCycle.parseActive(
            layoutPrefs.getString(de.voicekeyboard.ime.VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, null),
        )
        for (id in de.voicekeyboard.ime.LanguageCycle.CANONICAL) {
            val (endonym, tag) = de.voicekeyboard.ime.LanguageCycle.ENDONYMS.getValue(id)
            // T23m (PLAN_V0147 §16): only de+en ship as word keyboards — the other
            // 13 stay stored/toggleable history but are GREYED with the reason
            // line; clicking shows the hint instead of a dead click and does NOT
            // toggle or persist (the saved choice survives for later availability).
            val available = de.voicekeyboard.keyboard.LanguageAvailability.isAvailable(id)
            val box = android.widget.CheckBox(this).apply {
                text = if (available) {
                    "$endonym · $tag"
                } else {
                    "$endonym · $tag · ${getString(R.string.setup_lang_coming_soon)}"
                }
                isChecked = id in active
                minHeight = (48 * density).toInt()
                contentDescription = "$endonym $tag"
                if (!available) {
                    alpha = 0.45f // greyed (§16)
                    val savedState = id in active
                    setOnClickListener {
                        isChecked = savedState // no toggle — the hint explains
                        android.widget.Toast.makeText(
                            this@SetupActivity,
                            R.string.setup_lang_coming_soon,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            if (available) {
                box.setOnCheckedChangeListener { _, checked ->
                    val now = if (checked) {
                        savedLanguages() + id
                    } else {
                        val remaining = savedLanguages() - id
                        if (remaining.isEmpty()) {
                            // last one deselected: refuse, checkbox stays set
                            box.isChecked = true
                            return@setOnCheckedChangeListener
                        }
                        remaining
                    }
                    layoutPrefs.edit()
                        .putString(de.voicekeyboard.ime.VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, de.voicekeyboard.ime.LanguageCycle.serializeActive(now))
                        .apply()
                }
            } // unavailable rows: no change listener — click shows ONLY the hint
            container.addView(box)
        }
        // T23m (§16) doc lines: the keyboard shows only AVAILABLE languages; the
        // word-correction line derives from ASSET DISCOVERY (assets/wordlists
        // *.bin — §14.6(3)) — not from the LanguageAvailability constant.
        container.addView(TextView(this).apply {
            text = getString(R.string.setup_lang_only_available)
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, 0)
        })
        container.addView(TextView(this).apply {
            text = wordCorrectionAvailabilityText()
            textSize = 13f
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        })
        // insert the section behind the version line (T19) and before the
        // model status: setup_title(0), setup_version(1), language(2)
        layoutRoot.addView(container, 2)
    }

    /** T23h/T23m: the ONE availability text — asset discovery over the
     *  word-list bin assets (§14.6(3)), rendered endonyms; used by the
     *  language section AND the typing section (string reused, not duplicated). */
    private fun wordCorrectionAvailabilityText(): String = wordAvailability

    // ---- T24b (Gleis 3): U2 text-correction card ----

    /** U2 views (programmatic card — no XML layout change). */
    private var u2Status: TextView? = null
    private var u2Delete: Button? = null
    private var u2SetLine: TextView? = null
    private var u2Import: Button? = null

    /**
     * T24b: the U2 set follows the CURRENT typing language (de layout ->
     * BART_SPELLING_DE, en layout -> BART_SPELLING_EN; the preheat of T24d
     * decides per session — the card only mirrors the store truth).
     */
    private fun u2VariantForTypingLanguage(): de.voicekeyboard.model.ModelVariant =
        if (activeTypingLanguage().startsWith("en")) {
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_EN
        } else {
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_DE
        }

    /** Current typing language (vk_layout, filtered like the service cycle). */
    private fun activeTypingLanguage(): String {
        val prefs = getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
        val active = de.voicekeyboard.keyboard.LanguageAvailability.filterActive(
            de.voicekeyboard.ime.LanguageCycle.parseActive(
                prefs.getString(de.voicekeyboard.ime.VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, null),
            ),
        )
        return de.voicekeyboard.ime.LanguageCycle.initialLanguage(
            active,
            prefs.getString(de.voicekeyboard.ime.VoiceKeyboardService.KEY_CURRENT_LANGUAGE, null),
        )
    }

    private fun buildU2Card() {
        val layoutRoot = findViewById<TextView>(R.id.setup_title).parent as android.widget.LinearLayout
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.u2_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.u2_body)
            textSize = 13f
            setPadding(0, (4 * density).toInt(), 0, 0)
        })
        u2SetLine = TextView(this).apply { textSize = 13f }
        container.addView(u2SetLine!!)
        u2Status = TextView(this).apply { textSize = 13f }
        container.addView(u2Status!!)
        // V0.1.51 (PLAN_V0151 §D): local folder import — the U2 sets have no
        // download host (BASE_URL_U2_EN/_DE empty); the card follows the CURRENT
        // typing language set (en -> U2-en, de -> U2-de).
        u2Import = Button(this).apply {
            text = getString(R.string.setup_import_folder)
            setOnClickListener { onImportFolder(u2VariantForTypingLanguage()) }
        }
        container.addView(u2Import!!)
        u2Delete = Button(this).apply {
            text = getString(R.string.model_delete)
            visibility = android.view.View.GONE
            setOnClickListener { onDeleteModel(u2VariantForTypingLanguage()) }
        }
        container.addView(u2Delete)
        layoutRoot.addView(container)
        renderU2Card()
    }

    /**
     * T24b: the honest card state — RAM gate (8 GB floor, Qwen class:
     * U2 session ~0.4-0.5 GB NEXT TO the resident ASR engine), empty
     * BASE_URL_U2_EN/_DE = not offered (Qwen VK-2 pattern: inert, fail-fast,
     * never a dead download promise); Delete only for a READY set (frees
     * the ~0.25 GB; C2 orphan rule applies like the ASR sets).
     */
    private fun renderU2Card() {
        val variant = u2VariantForTypingLanguage()
        val status = cachedStatus(variant)
        val totalRam = ramBytes
        u2SetLine?.text = getString(
            R.string.u2_set,
            de.voicekeyboard.ime.LanguageCycle.endonym(
                if (variant == de.voicekeyboard.model.ModelVariant.BART_SPELLING_EN) "en-us" else "de-de",
            ),
        )
        u2Delete?.visibility = if (status == de.voicekeyboard.model.ModelStatus.READY) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        val importing = importingVariant == variant
        u2Import?.isEnabled = importingVariant == null
        u2Import?.visibility = if (!importing && status != de.voicekeyboard.model.ModelStatus.READY) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        when {
            importing ->
                u2Status?.text = getString(R.string.setup_import_running)
            status == de.voicekeyboard.model.ModelStatus.READY ->
                u2Status?.text = getString(R.string.u2_ready)
            totalRam < de.voicekeyboard.model.DownloadPolicy.QWEN_MIN_TOTAL_RAM_BYTES ->
                u2Status?.text = getString(R.string.model_ram_locked)
            else ->
                u2Status?.text = getString(R.string.setup_import_only)
        }
    }

    /**
     * V0.1.53 (PLAN_QWEN17B §B): the LOCKED 1.7B card — title + honest
     * explanation + state line, and NOTHING else. While the set is inert
     * (empty pinned manifest [ModelManifest.QWEN_17B], no sha256 pins,
     * [ModelManifest.BASE_URL_QWEN_17B] empty) there is deliberately NO
     * button: no download, no import, no selection — the card can only
     * EXPLAIN. States (offer/delete) come with the unlock slice (plan S4).
     */
    private fun buildQwen17bCard() {
        val layoutRoot = findViewById<TextView>(R.id.setup_title).parent as android.widget.LinearLayout
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.model_qwen17b_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.model_qwen17b_body)
            textSize = 13f
            setPadding(0, (4 * density).toInt(), 0, 0)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.model_qwen17b_not_available)
            textSize = 13f
        })
        layoutRoot.addView(container)
    }

    /**
     * T23h (PLAN_V0147 §6): "Typing suggestions" — switch A corr_row_enabled
     * (default ON) + switch B corr_lang_mode (checked = all, default current)
     * with the §14.2 discoverability hint + the word-correction availability
     * line. Both switches write the SAME keys the view/service read
     * (live effect via the suppliers — no extra wiring).
     */
    private fun buildTypingSection() {
        val layoutRoot = findViewById<TextView>(R.id.setup_title).parent as android.widget.LinearLayout
        val density = resources.displayMetrics.density
        val layoutPrefs = getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.setup_typing_title)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(android.widget.Switch(this).apply {
            setText(R.string.setup_typing_show)
            minHeight = (48 * density).toInt()
            isChecked = layoutPrefs.getBoolean(
                de.voicekeyboard.ime.VoiceKeyboardService.PREF_CORR_ROW_ENABLED, true,
            )
            setOnCheckedChangeListener { _, checked ->
                layoutPrefs.edit()
                    .putBoolean(de.voicekeyboard.ime.VoiceKeyboardService.PREF_CORR_ROW_ENABLED, checked)
                    .apply()
            }
        })
        container.addView(android.widget.Switch(this).apply {
            setText(R.string.setup_typing_all_langs)
            minHeight = (48 * density).toInt()
            isChecked = layoutPrefs.getString(
                de.voicekeyboard.ime.VoiceKeyboardService.PREF_CORR_LANG_MODE, null,
            ) == de.voicekeyboard.correct.LanguageModePolicy.VALUE_ALL
            setOnCheckedChangeListener { _, checked ->
                layoutPrefs.edit()
                    .putString(
                        de.voicekeyboard.ime.VoiceKeyboardService.PREF_CORR_LANG_MODE,
                        if (checked) {
                            de.voicekeyboard.correct.LanguageModePolicy.VALUE_ALL
                        } else {
                            de.voicekeyboard.correct.LanguageModePolicy.VALUE_CURRENT
                        },
                    )
                    .apply()
            }
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.setup_typing_lang_hint)
            textSize = 13f
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        })
        container.addView(TextView(this).apply {
            text = wordCorrectionAvailabilityText()
            textSize = 13f
            setPadding(0, 0, 0, (2 * density).toInt())
        })
        layoutRoot.addView(container)
    }

    override fun onResume() {
        super.onResume()
        // returning user: read the status fresh (the E2 network switch is gone, T20b)
        refreshUi()
    }

    override fun onDestroy() {
        ModelDownloadService.observer = null
        importObserver = null
        setupExecutor.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImportVariant?.let { outState.putString(KEY_PENDING_IMPORT, it.id) }
        pendingImportUri?.let { outState.putString(KEY_PENDING_IMPORT_URI, it.toString()) }
    }

    // ---- Download start path (L1: catch FGS block, never crash) ----

    private fun requestStartDownload() {
        if (!storageLoaded) return
        val status = cachedStatus()
        val decision = DownloadPolicy.decide(
            // T20b: the E2 in-app switch is removed — the app never gates the
            // network itself. If the user revoked INTERNET at the system level,
            // the download fails with the known "netz" error (D1 taxonomy).
            networkEnabled = true,
            freeBytes = freeSpace,
            status = status,
        )
        when (decision) {
            is DownloadPolicy.Decision.AlreadyReady -> render(null)
            // T20b: unreachable in the Setup (networkEnabled always true — the
            // E2 switch is gone). If it ever fires, the generic network error
            // is the honest fallback: a system-level INTERNET revoke shows up
            // here as "network unavailable", not as a switch hint.
            is DownloadPolicy.Decision.NetworkDisabled ->
                stateView.text = getString(R.string.setup_error_network)
            is DownloadPolicy.Decision.InsufficientSpace -> {
                stateView.text = getString(R.string.setup_error_space)
            }
            is DownloadPolicy.Decision.Start -> sendServiceAction(ModelDownloadService.ACTION_START)
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, ModelDownloadService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            stateView.text = getString(R.string.setup_error_fgs_blocked)
        }
    }

    // ---- T22h: dictation model selection (one card per set) ----

    /** The ONE store of the Setup (injectable for tests). */
    private fun modelStore(): ModelStore = checkNotNull(cachedStore) { "Storage not loaded" }

    /** Total device RAM (injectable for tests; production: ActivityManager). */
    private fun deviceTotalRamBytes(): Long {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /** Active model set from vk_layout/model_variant (fromId fallback A). */
    private fun activeVariantFromPrefs(): de.voicekeyboard.model.ModelVariant =
        de.voicekeyboard.model.ModelVariant.fromId(
            getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
                .getString(de.voicekeyboard.model.ModelVariant.PREFS_KEY, null),
        )

    /** Select: writes the pref ONLY on a READY set (plan §3: “Setup writes the
     *  pref only on READY”); the service listener does the rest (incl. Deferred
     *  while a dictation runs) — the UI never switches an engine itself. */
    private fun onSelectModel(variant: de.voicekeyboard.model.ModelVariant) {
        if (!storageLoaded || deletingVariant == variant || cachedStatus(variant) != ModelStatus.READY) return
        getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
            .edit()
            .putString(de.voicekeyboard.model.ModelVariant.PREFS_KEY, variant.id)
            .apply()
        renderLoadedUi()
    }

    /** Download: starts the service with the card's EXTRA_VARIANT (T22d). */
    private fun onDownloadModel(variant: de.voicekeyboard.model.ModelVariant) {
        val intent = Intent(this, ModelDownloadService::class.java)
            .setAction(ModelDownloadService.ACTION_START)
            .putExtra(ModelDownloadService.EXTRA_VARIANT, variant.id)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            stateView.text = getString(R.string.setup_error_fgs_blocked)
        }
    }

    /** Delete: ONLY non-active sets — the running engine's model dir must never
     *  be ripped away ([ModelStore.deleteSet] refuses the active one anyway). */
    private fun onDeleteModel(variant: de.voicekeyboard.model.ModelVariant) {
        if (!storageLoaded || deletingVariant != null) return
        deletingVariant = variant
        renderModelCards()
        ioExecutor.execute {
            val ok = modelStore().deleteSet(variant, activeVariantFromPrefs())
            android.util.Log.i("VK-MODEL", "delete_set variant=${variant.id} ok=$ok")
            runOnUiThread {
                deletingVariant = null
                if (!isDestroyed) refreshUi()
            }
        }
    }

    // ---- V0.1.51 (PLAN_V0151 §D, P3): local import (folder on the device) ----

    /** Opens the system folder picker for [variant] (ACTION_OPEN_DOCUMENT_TREE).
     *  The chosen folder must contain the pinned manifest files with their
     *  exact relative paths — see docs/MODEL_IMPORT.md. */
    private fun onImportFolder(variant: de.voicekeyboard.model.ModelVariant) {
        if (!storageLoaded || importingVariant != null) return
        pendingImportVariant = variant
        importLauncher.launch(null)
    }

    /** Picker result: null = the user closed the picker (no import started, no
     *  error); a tree URI = import on the background executor, then a visible
     *  result message + refreshUi — never a silent failure. */
    internal fun onImportFolderPicked(uri: Uri?) {
        val variant = pendingImportVariant ?: return
        if (uri == null) {
            pendingImportVariant = null
            pendingImportUri = null
            return
        }
        pendingImportUri = uri
        if (!storageLoaded || importingVariant != null) return
        pendingImportVariant = null
        pendingImportUri = null
        val store = modelStore()
        val source = SafTreeImportSource(contentResolver, uri,
            de.voicekeyboard.model.ModelManifest.forVariant(variant).map { it.storeName })
        importingVariant = variant
        renderModelCards()
        importExecutor.execute {
            val result = store.importSet(variant, source) // typed result, no throw
            importingVariant = null
            runOnUiThread {
                importObserver?.invoke()
                if (isDestroyed) return@runOnUiThread
                showImportResult(result)
            }
        }
    }

    /** Result as a VISIBLE toast — every rejection names its cause (missing/
     *  corrupt/wrong-size file carries the relative path). */
    private fun showImportResult(result: ImportResult) {
        val (resId, fileName) = importMessage(result)
        val text = if (fileName != null) getString(resId, fileName) else getString(resId)
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
        android.util.Log.i("VK-MODEL", "import result=${result.javaClass.simpleName}") // codes only
    }

    /** Card states: Ready+active / Ready+other / downloadable / locked. */
    private fun renderModelCards() {
        if (!storageLoaded) return
        val totalRam = ramBytes
        val freeBytes = freeSpace
        val active = activeVariantFromPrefs()
        val controller = ModelDownloadService.controller
        renderModelCard(
            variant = de.voicekeyboard.model.ModelVariant.ONNX_CPU,
            active = active, totalRam = totalRam, freeBytes = freeBytes,
            statusView = parakeetStatus, selectButton = parakeetSelect,
            downloadButton = parakeetDownload, deleteButton = parakeetDelete,
            progressBar = parakeetProgress,
            downloading = controller?.variant == de.voicekeyboard.model.ModelVariant.ONNX_CPU &&
                controller.state is DownloadController.State.Downloading,
            fraction = (controller?.state as? DownloadController.State.Downloading)?.fraction ?: 0f,
        )
        renderModelCard(
            variant = de.voicekeyboard.model.ModelVariant.QWEN3_ASR,
            active = active, totalRam = totalRam, freeBytes = freeBytes,
            statusView = qwenStatus, selectButton = qwenSelect,
            downloadButton = qwenDownload, deleteButton = qwenDelete,
            progressBar = qwenProgress,
            downloading = controller?.variant == de.voicekeyboard.model.ModelVariant.QWEN3_ASR &&
                controller.state is DownloadController.State.Downloading,
            fraction = (controller?.state as? DownloadController.State.Downloading)?.fraction ?: 0f,
            importButton = qwenImport,
            importing = importingVariant == de.voicekeyboard.model.ModelVariant.QWEN3_ASR,
        )
        // T24b (Gleis 3): the U2 card follows every model-card refresh.
        renderU2Card()
    }

    private fun renderModelCard(
        variant: de.voicekeyboard.model.ModelVariant,
        active: de.voicekeyboard.model.ModelVariant,
        totalRam: Long,
        freeBytes: Long,
        statusView: TextView,
        selectButton: Button,
        downloadButton: Button,
        deleteButton: Button,
        progressBar: ProgressBar,
        downloading: Boolean,
        fraction: Float,
        importButton: Button? = null, // V0.1.51: sets without a download host
        importing: Boolean = false, // V0.1.51: a local import is running
    ) {
        val gone = android.view.View.GONE
        val visible = android.view.View.VISIBLE
        if (downloading) {
            statusView.text = getString(R.string.setup_state_downloading)
            progressBar.visibility = visible
            progressBar.isIndeterminate = false
            progressBar.progress = (fraction * 1000).toInt()
            selectButton.visibility = gone
            downloadButton.visibility = gone
            deleteButton.visibility = gone
            importButton?.visibility = gone
            return
        }
        if (importing) {
            // V0.1.51: honest running state — the copy of ~1.7 GB can take a
            // while; nothing is clickable meanwhile (no parallel import).
            statusView.text = getString(R.string.setup_import_running)
            progressBar.visibility = gone
            selectButton.visibility = gone
            downloadButton.visibility = gone
            deleteButton.visibility = gone
            importButton?.visibility = gone
            return
        }
        progressBar.visibility = gone
        selectButton.isEnabled = deletingVariant != variant
        downloadButton.isEnabled = deletingVariant != variant
        deleteButton.isEnabled = deletingVariant == null
        val status = cachedStatus(variant)
        val manifest = manifestOverride ?: de.voicekeyboard.model.ModelManifest.forVariant(variant)
        // T22d/VK-2: locked = no download will EVER be offered right now —
        // RAM gate (Qwen only) or not-unlocked/not-enabled (empty base URL).
        val ramLocked = variant == de.voicekeyboard.model.ModelVariant.QWEN3_ASR &&
            totalRam < de.voicekeyboard.model.DownloadPolicy.QWEN_MIN_TOTAL_RAM_BYTES
        val locked = ramLocked ||
            DownloadPolicy.requiredFreeBytes(variant, manifest) == null
        val offerable = DownloadPolicy.canOfferSet(variant, totalRam, freeBytes, manifest)
        // button matrix — pure, pinned in SetupCardActionsTest
        val actions = cardActions(
            locked = locked,
            ready = status == ModelStatus.READY,
            active = variant == active,
            offerable = offerable,
        )
        selectButton.visibility = if (ButtonKind.SELECT in actions) visible else gone
        downloadButton.visibility = if (ButtonKind.DOWNLOAD in actions) visible else gone
        deleteButton.visibility = if (ButtonKind.DELETE in actions) visible else gone
        // V0.1.51 (PLAN §D): import is the OFFLINE way to a set without a
        // download host — offered exactly while the set is NOT READY (a
        // READY set shows select/delete instead; re-import is pointless).
        importButton?.visibility = if (status == ModelStatus.READY) gone else visible
        importButton?.isEnabled = importingVariant == null
        // status text: honest and specific (semantics unchanged)
        when {
            status == ModelStatus.READY && variant == active -> {
                statusView.text = getString(R.string.model_active)
            }
            status == ModelStatus.READY -> {
                statusView.text = getString(R.string.model_ready)
            }
            ramLocked -> {
                // RAM gate (plan §2: Qwen only) — visible BEFORE the enablement
                // check: on a < 8 GB device the honest reason is the RAM gate.
                statusView.text = getString(R.string.model_ram_locked)
            }
            DownloadPolicy.requiredFreeBytes(variant, manifest) == null -> {
                // set not usable: empty manifest (not unlocked) or — VK-2 —
                // no pinned base URL yet ("not available yet" via this path)
                statusView.text = getString(if (importButton != null && manifest.isNotEmpty())
                    R.string.setup_import_only else R.string.model_not_downloaded)
            }
            !offerable -> {
                // offer blocked: RAM gate (Qwen only) or space floor
                statusView.text = if (
                    variant == de.voicekeyboard.model.ModelVariant.QWEN3_ASR &&
                    totalRam < de.voicekeyboard.model.DownloadPolicy.QWEN_MIN_TOTAL_RAM_BYTES
                ) {
                    getString(R.string.model_ram_locked)
                } else {
                    getString(R.string.setup_error_space)
                }
            }
            else -> {
                // ABSENT/BROKEN and offerable: (re)download possible
                statusView.text = getString(R.string.model_not_downloaded)
            }
        }
    }

    // ---- Permissions (C-3) ----

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun requestPermissionsNow() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        // V0.1.51 (PLAN_V0151 §C): the button reacts in EVERY state — the old
        // silent return when nothing is missing made it look dead.
        when (PermissionAction.decide(missing.size)) {
            PermissionAction.Action.REQUEST_DIALOG ->
                permissionLauncher.launch(missing.toTypedArray())
            PermissionAction.Action.ALL_GRANTED_TOAST ->
                android.widget.Toast.makeText(
                    this, R.string.setup_permissions_all_granted,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
        }
    }

    private fun evaluatePermissions(results: Map<String, Boolean>) {
        // Rationale is a framework/UI call; preference initialization and writes
        // are serialized with storage discovery on the setup worker.
        val deniedForever = results.filter { !it.value && !shouldShowRequestPermissionRationale(it.key) }.keys
        ioExecutor.execute {
        for ((permission, granted) in results) {
            if (granted) {
                // granted: possibly delete a former marker
                prefs.edit().remove(deniedKey(permission)).apply()
            } else if (permission in deniedForever) {
                // denied again + no more rationale ⇒ permanently denied:
                // marker into the SAME file that VoiceKeyboardService reads (C-3).
                prefs.edit().putBoolean(deniedKey(permission), true).apply()
            }
        }
        runOnUiThread { if (!isDestroyed) refreshUi() }
        }
    }

    private fun deniedKey(permission: String): String = DENIED_FOREVER_PREFIX + permission

    private fun anyPermissionDeniedForever(): Boolean =
        requiredPermissions().any { prefs.getBoolean(deniedKey(it), false) }

    private fun openSystemSettings() {
        startLauncher.launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        )
    }

    // ---- UI state ----

    private fun refreshUi() {
        if (isDestroyed) return
        if (refreshPending) { refreshAgain = true; return }
        refreshPending = true
        ioExecutor.execute {
            val result = runCatching { loadStorage() }
            runOnUiThread {
                refreshPending = false
                if (!isDestroyed) {
                    val snapshot = result.getOrElse {
                        stateView.text = getString(R.string.setup_import_io)
                        return@runOnUiThread
                    }
                    statuses = snapshot.statuses
                    setIds = snapshot.setIds
                    freeSpace = snapshot.freeSpace
                    ramBytes = snapshot.ramBytes
                    wordAvailability = snapshot.wordAvailability
                    versionLabel = snapshot.versionLabel
                    storageLoaded = true
                    versionView.text = versionLabel
                    if (!sectionsBuilt) {
                        sectionsBuilt = true
                        buildLanguageSection()
                        buildTypingSection()
                        buildU2Card()
                        buildQwen17bCard()
                    }
                    renderLoadedUi()
                    pendingImportUri?.let { onImportFolderPicked(it) }
                    if (refreshAgain) { refreshAgain = false; refreshUi() }
                }
            }
        }
    }

    private fun renderLoadedUi() {
        render(ModelDownloadService.controller?.state)
        // T20b: re-evaluate the invite card for the CURRENT state — covers the
        // cold start (controller null, store READY ⇒ invite once) and the
        // returning user. render(null) itself must NOT re-evaluate (its
        // Idle recursion would wrongly hide the card again).
        updateNetInviteCard()
        // T22h: re-render the model cards (status/active/delete per set).
        renderModelCards()
        // V0.1.51 (PLAN_V0151 §C): the permissions hint follows the ACTUAL
        // state — the open-settings button appears as soon as a "denied
        // forever" marker exists (the only way to a permanently denied
        // permission is the system settings).
        val missing = requiredPermissions().count {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val deniedForeverMarker = anyPermissionDeniedForever()
        openSettingsButton.visibility =
            if (deniedForeverMarker) android.view.View.VISIBLE else android.view.View.GONE
        permissionsHint.text = when (PermissionAction.hint(missing, deniedForeverMarker)) {
            PermissionAction.Hint.DENIED_FOREVER ->
                getString(R.string.setup_permissions_denied_forever)
            PermissionAction.Hint.ALL_GRANTED ->
                getString(R.string.setup_permissions_hint_ok)
            PermissionAction.Hint.NEEDS_PERMISSIONS ->
                getString(R.string.setup_permissions_hint)
        }
    }

    // ---- T20b: one-time network-revoke invite (PLAN_V0145, binding) ----

    /** Observed controller state for the invite decision: the override (test
     *  seam), else the live controller, else the cold-start derivation
     *  (process gone: store READY counts as Ready — invite shows once). */
    private fun inviteState(): DownloadController.State? {
        inviteStateOverride?.let { return it }
        ModelDownloadService.controller?.state?.let { return it }
        return if (cachedStatus(activeVariantFromPrefs()) == ModelStatus.READY) {
            DownloadController.State.Ready
        } else null
    }

    /** Card visibility from [NetworkInvitePolicy.shouldShowInvite] — exactly
     *  once per model set; the passed render state takes precedence.
     *  T22h: the set id is the one of the ACTIVE variant — after a Qwen
     *  download the invite shows again (one card per set id, plan §3). */
    private fun updateNetInviteCard(state: DownloadController.State? = null) {
        val s = state ?: inviteState()
        val show = s != null && NetworkInvitePolicy.shouldShowInvite(
            s,
            setIds.getValue(activeVariantFromPrefs()),
            prefs.getString(NET_INVITE_SHOWN_FOR_KEY, null),
        )
        netInviteCard.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** (b2) Both buttons persist shown_for = modelSetId of the ACTIVE set
     *  (pure [markShown]). */
    private fun markNetInviteShown() {
        if (!storageLoaded) return
        prefs.edit()
            .putString(
                NET_INVITE_SHOWN_FOR_KEY,
                NetworkInvitePolicy.markShown(
                    setIds.getValue(activeVariantFromPrefs()),
                ),
            )
            .apply()
        updateNetInviteCard()
    }

    /** Direct app-details deep link (fallback: plain system settings); the
     *  revoke itself happens ONLY at the system level — the app has no
     *  in-app network control (E2 switch removed). */
    private fun openNetworkRevokeSettings() {
        val intent = Intent(
            NetworkInvitePolicy.inviteIntentAction(),
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(NetworkInvitePolicy.fallbackIntentAction()))
        }
    }

    /** Renders the controller state (null = derive from the store status). */
    private fun render(state: DownloadController.State?) {
        // T20b: the invite card follows every OBSERVER state transition
        // (post-download Ready run); null goes through refreshUi above.
        if (state != null) updateNetInviteCard(state)
        when (state) {
            is DownloadController.State.Downloading -> {
                stateView.text = getString(R.string.setup_state_downloading) +
                    // V12 (D1-03): running retry visible instead of silent waiting
                    if (state.attempt > 1) " — " + getString(
                        R.string.setup_state_retry_attempt, state.attempt, state.maxAttempts,
                    ) else ""
                progress.visibility = android.view.View.VISIBLE
                progress.isIndeterminate = false
                progress.progress = (state.fraction * 1000).toInt()
                fileInfoView.visibility = android.view.View.VISIBLE
                fileInfoView.text = getString(
                    R.string.file_info_placeholder,
                    state.currentIndex + 1, state.totalFiles,
                )
                startButton.visibility = android.view.View.GONE
                cancelButton.visibility = android.view.View.VISIBLE
            }
            is DownloadController.State.Verifying -> {
                stateView.text = getString(R.string.setup_state_verifying)
                progress.isIndeterminate = true
                cancelButton.visibility = android.view.View.VISIBLE
            }
            is DownloadController.State.Ready -> {
                stateView.text = getString(R.string.setup_state_ready)
                progress.visibility = android.view.View.GONE
                fileInfoView.visibility = android.view.View.GONE
                startButton.visibility = android.view.View.GONE
                cancelButton.visibility = android.view.View.GONE
            }
            is DownloadController.State.Cancelled -> {
                stateView.text = getString(R.string.setup_state_cancelled)
                startButton.visibility = android.view.View.VISIBLE
                cancelButton.visibility = android.view.View.GONE
            }
            is DownloadController.State.Failed -> {
                // D1: real error taxonomy + dictable technical detail line
                // (ModelError.detailForUser, rule 9: no URLs/tokens).
                val message = when (DownloadPolicy.userMessageKey(state.error)) {
                    "netz" -> getString(R.string.setup_error_network)
                    "hash" -> getString(R.string.setup_error_hash)
                    "platz" -> getString(R.string.setup_error_space)
                    "server" -> getString(R.string.setup_error_server)
                    "fortsetzung" -> getString(R.string.setup_error_resume)
                    "warte" -> getString(R.string.setup_error_wait)
                    else -> getString(R.string.setup_error_cancelled)
                }
                val detail = state.error.detailForUser()
                stateView.text = if (detail.isNotEmpty()) "$message\n$detail" else message
                startButton.visibility = android.view.View.VISIBLE
                cancelButton.visibility = android.view.View.GONE
            }
            DownloadController.State.Idle -> {
                val status = cachedStatus()
                stateView.text = if (status == ModelStatus.READY) {
                    getString(R.string.setup_state_ready)
                } else {
                    getString(R.string.setup_state_absent)
                }
                startButton.visibility =
                    if (status == ModelStatus.READY) android.view.View.GONE else android.view.View.VISIBLE
                cancelButton.visibility = android.view.View.GONE
                progress.visibility = android.view.View.GONE
                fileInfoView.visibility = android.view.View.GONE
            }
            null -> render(DownloadController.State.Idle)
        }
    }

    /** Saved active languages (canonically ordered, never empty). */
    private fun savedLanguages(): List<String> =
        de.voicekeyboard.ime.LanguageCycle.parseActive(
            getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE).getString(de.voicekeyboard.ime.VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, null),
        )

    private companion object {
        // Process scoped: a rotation must not allow another import to overwrite
        // the running import's .part files. No Activity is retained after completion.
        private var importObserver: (() -> Unit)? = null
        @Volatile private var importingVariant: de.voicekeyboard.model.ModelVariant? = null
        private val importExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "vk-model-import").apply { isDaemon = true }
        }

        /** SAME file as VoiceKeyboardService (there PERMISSION_PREFS). */
        private const val PERMISSION_PREFS = "vk_permissions"
        private const val DENIED_FOREVER_PREFIX = "denied_forever_"
        private const val MODEL_DIR_NAME = "models"

        /** T20b: persistence key of the one-time network-revoke invite
         *  (value = modelSetId; "Later" and settings click both set it). */
        private const val NET_INVITE_SHOWN_FOR_KEY = "net_invite_shown_for"

        /** V0.1.51: instance-state key of the variant whose folder picker is open. */
        private const val KEY_PENDING_IMPORT = "pending_import_variant"
        private const val KEY_PENDING_IMPORT_URI = "pending_import_uri"
    }
}

/** Buttons a model card can offer (pure render matrix, no views). */
internal enum class ButtonKind { SELECT, DOWNLOAD, DELETE }

/**
 * V0.1.51 (docs/PLAN_V0151.md §C): pure decision of the "Grant permissions"
 * button and the permissions hint — no views, pinned in
 * SetupPermissionsButtonTest (state matrix missing × denied-forever).
 *
 * User finding: [SetupActivity.requestPermissionsNow] silently returned when
 * nothing was missing — the button looked dead. Now EVERY state reacts:
 * something missing ⇒ system dialog; nothing missing ⇒ confirmation toast.
 */
internal object PermissionAction {

    /** What the button does when clicked. */
    enum class Action { REQUEST_DIALOG, ALL_GRANTED_TOAST }

    /** Which hint line [SetupActivity.refreshUi] shows under the button. */
    enum class Hint { NEEDS_PERMISSIONS, DENIED_FOREVER, ALL_GRANTED }

    /** Button action: [missing] = number of not-yet-granted required permissions. */
    fun decide(missing: Int): Action =
        if (missing > 0) Action.REQUEST_DIALOG else Action.ALL_GRANTED_TOAST

    /** Hint text: all granted wins over a STALE marker (the permission was
     *  granted afterwards); otherwise the denied-forever marker points to the
     *  system settings, the default line asks for the permissions. */
    fun hint(missing: Int, deniedForeverMarker: Boolean): Hint = when {
        missing == 0 -> Hint.ALL_GRANTED
        deniedForeverMarker -> Hint.DENIED_FOREVER
        else -> Hint.NEEDS_PERMISSIONS
    }
}


/** Pure render decision of a model card's buttons — no views, pinned in
 *  SetupCardActionsTest (matrix locked x ready x active).
 *
 *  C2 (Oracle §20b, orphaned-delete fix): a LOCKED set with READY files
 *  keeps SELECT+DELETE. Rationale: the base-URL retreat (BASE_URL_QWEN =
 *  "", VK-2) must never leave the loaded ~1.7 GB orphaned on the device
 *  with no UI way to free them — deleting a non-active READY set stays
 *  possible even while the card shows "not available" for downloads. */
internal fun cardActions(
    locked: Boolean, // no download ever offerable now: RAM gate or not unlocked/enabled
    ready: Boolean, // files READY on disk (statusQuick)
    active: Boolean, // is the active set
    offerable: Boolean, // download may be offered (space floor ok)
): Set<ButtonKind> = when {
    ready && active -> emptySet() // active set: select is pointless, delete forbidden
    ready -> setOf(ButtonKind.SELECT, ButtonKind.DELETE) // C2: ALSO when locked
    locked -> emptySet()
    offerable -> setOf(ButtonKind.DOWNLOAD)
    else -> emptySet()
}

/**
 * V0.1.51 (PLAN_V0151 §D, P3): pure mapping [ImportResult] → (message string
 *  resource, offending file name or null) — pinned in SetupImportCardTest.
 *  The file-carrying failures keep the RELATIVE manifest path so the user
 *  can find the file in the folder; IoError shows the generic message only
 *  (its technical detail never reaches the UI — rule 9).
 */
internal fun importMessage(result: de.voicekeyboard.model.ImportResult): Pair<Int, String?> =
    when (result) {
        de.voicekeyboard.model.ImportResult.Ready ->
            R.string.setup_import_ok to null
        is de.voicekeyboard.model.ImportResult.Missing ->
            R.string.setup_import_missing to result.relPath
        is de.voicekeyboard.model.ImportResult.HashMismatch ->
            R.string.setup_import_hash to result.relPath
        is de.voicekeyboard.model.ImportResult.SizeMismatch ->
            R.string.setup_import_size to result.relPath
        is de.voicekeyboard.model.ImportResult.IoError ->
            if (result.relPath != null) R.string.setup_import_io_file to result.relPath
            else R.string.setup_import_io to null
    }

