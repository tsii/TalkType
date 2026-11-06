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
    private lateinit var qwenProgress: ProgressBar

    /** T22h test seam: store injection (null -> filesDir/models). */
    internal var modelStoreOverride: ModelStore? = null

    /** T22h test seam: total device RAM (null -> ActivityManager.MemoryInfo). */
    internal var totalRamOverride: Long? = null

    /** T22h test seam: manifest for the offer check (null -> pinned list of
     *  the variant; emptyList simulates a not-unlocked set). */
    internal var manifestOverride: List<de.voicekeyboard.model.ModelFile>? = null

    private val prefs by lazy { getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.setup_activity)
        stateView = findViewById(R.id.setup_state)
        // T19 (v0.1.45): version label via the ONE shared read path
        // (AppVersion.read — same source as the debug export).
        versionView = findViewById(R.id.setup_version)
        versionView.text = AppVersion.versionLabel(AppVersion.read(this), isDebugBuild())
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
        qwenProgress = findViewById(R.id.setup_qwen_progress)
        parakeetSelect.setOnClickListener { onSelectModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        parakeetDownload.setOnClickListener { onDownloadModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        parakeetDelete.setOnClickListener { onDeleteModel(de.voicekeyboard.model.ModelVariant.ONNX_CPU) }
        qwenSelect.setOnClickListener { onSelectModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }
        qwenDownload.setOnClickListener { onDownloadModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }
        qwenDelete.setOnClickListener { onDeleteModel(de.voicekeyboard.model.ModelVariant.QWEN3_ASR) }

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

        buildLanguageSection()
        // T23h (PLAN_V0147 §6): typing suggestions section BELOW the dictation
        // model section (appended at the end of the layout root).
        buildTypingSection()

        // observer AFTER layout registration: the service informs us state-based
        // (no polling, no own thread); UI-thread delivery via runOnUiThread.
        ModelDownloadService.observer = { state -> runOnUiThread { render(state) } }
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
    private fun wordCorrectionAvailabilityText(): String {
        val wordCorrLangs = assets.list("wordlists")
            ?.filter { it.endsWith(".bin") }
            ?.map { de.voicekeyboard.ime.LanguageCycle.endonym(it.removeSuffix(".bin")) }
            ?.joinToString(", ")
            ?: ""
        return getString(R.string.setup_lang_word_correction, wordCorrLangs)
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
        super.onDestroy()
    }

    // ---- Download start path (L1: catch FGS block, never crash) ----

    private fun requestStartDownload() {
        val store = modelStore()
        val status = store.status()
        val decision = DownloadPolicy.decide(
            // T20b: the E2 in-app switch is removed — the app never gates the
            // network itself. If the user revoked INTERNET at the system level,
            // the download fails with the known "netz" error (D1 taxonomy).
            networkEnabled = true,
            freeBytes = filesDir.usableSpace,
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
    private fun modelStore(): ModelStore =
        modelStoreOverride ?: ModelStore(File(filesDir, MODEL_DIR_NAME))

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
        if (modelStore().statusQuick(variant) != ModelStatus.READY) return // never on not-ready
        getSharedPreferences(de.voicekeyboard.ime.VoiceKeyboardService.LAYOUT_PREFS, MODE_PRIVATE)
            .edit()
            .putString(de.voicekeyboard.model.ModelVariant.PREFS_KEY, variant.id)
            .apply()
        renderModelCards()
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
        val active = activeVariantFromPrefs()
        val ok = modelStore().deleteSet(variant, active)
        android.util.Log.i("VK-MODEL", "delete_set variant=${variant.id} ok=$ok") // codes only
        renderModelCards()
    }

    /** Card states: Ready+active / Ready+other / downloadable / locked. */
    private fun renderModelCards() {
        val store = modelStore()
        val totalRam = totalRamOverride ?: deviceTotalRamBytes()
        val freeBytes = filesDir.usableSpace
        val active = activeVariantFromPrefs()
        val controller = ModelDownloadService.controller
        renderModelCard(
            variant = de.voicekeyboard.model.ModelVariant.ONNX_CPU,
            store = store, active = active, totalRam = totalRam, freeBytes = freeBytes,
            statusView = parakeetStatus, selectButton = parakeetSelect,
            downloadButton = parakeetDownload, deleteButton = parakeetDelete,
            progressBar = parakeetProgress,
            downloading = controller?.variant == de.voicekeyboard.model.ModelVariant.ONNX_CPU &&
                controller.state is DownloadController.State.Downloading,
            fraction = (controller?.state as? DownloadController.State.Downloading)?.fraction ?: 0f,
        )
        renderModelCard(
            variant = de.voicekeyboard.model.ModelVariant.QWEN3_ASR,
            store = store, active = active, totalRam = totalRam, freeBytes = freeBytes,
            statusView = qwenStatus, selectButton = qwenSelect,
            downloadButton = qwenDownload, deleteButton = qwenDelete,
            progressBar = qwenProgress,
            downloading = controller?.variant == de.voicekeyboard.model.ModelVariant.QWEN3_ASR &&
                controller.state is DownloadController.State.Downloading,
            fraction = (controller?.state as? DownloadController.State.Downloading)?.fraction ?: 0f,
        )
    }

    private fun renderModelCard(
        variant: de.voicekeyboard.model.ModelVariant,
        store: ModelStore,
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
            return
        }
        progressBar.visibility = gone
        selectButton.isEnabled = true
        downloadButton.isEnabled = true
        deleteButton.isEnabled = true
        val status = store.statusQuick(variant)
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
                statusView.text = getString(R.string.model_not_downloaded)
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
        if (missing.isEmpty()) return
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun evaluatePermissions(results: Map<String, Boolean>) {
        for ((permission, granted) in results) {
            if (granted) {
                // granted: possibly delete a former marker
                prefs.edit().remove(deniedKey(permission)).apply()
            } else if (!shouldShowRequestPermissionRationale(permission)) {
                // denied again + no more rationale ⇒ permanently denied:
                // marker into the SAME file that VoiceKeyboardService reads (C-3).
                prefs.edit().putBoolean(deniedKey(permission), true).apply()
            }
        }
        refreshUi()
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
        render(ModelDownloadService.controller?.state)
        // T20b: re-evaluate the invite card for the CURRENT state — covers the
        // cold start (controller null, store READY ⇒ invite once) and the
        // returning user. render(null) itself must NOT re-evaluate (its
        // Idle recursion would wrongly hide the card again).
        updateNetInviteCard()
        // T22h: re-render the model cards (status/active/delete per set).
        renderModelCards()
        openSettingsButton.visibility =
            if (anyPermissionDeniedForever()) android.view.View.VISIBLE else android.view.View.GONE
        if (anyPermissionDeniedForever()) {
            permissionsHint.text = getString(R.string.setup_permissions_denied_forever)
        } else {
            permissionsHint.text = getString(R.string.setup_permissions_hint)
        }
    }

    // ---- T20b: one-time network-revoke invite (PLAN_V0145, binding) ----

    /** Observed controller state for the invite decision: the override (test
     *  seam), else the live controller, else the cold-start derivation
     *  (process gone: store READY counts as Ready — invite shows once). */
    private fun inviteState(): DownloadController.State? {
        inviteStateOverride?.let { return it }
        ModelDownloadService.controller?.state?.let { return it }
        return if (modelStore().status(activeVariantFromPrefs()) == ModelStatus.READY) {
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
            modelStore().manifestSetId(activeVariantFromPrefs()),
            prefs.getString(NET_INVITE_SHOWN_FOR_KEY, null),
        )
        netInviteCard.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** (b2) Both buttons persist shown_for = modelSetId of the ACTIVE set
     *  (pure [markShown]). */
    private fun markNetInviteShown() {
        prefs.edit()
            .putString(
                NET_INVITE_SHOWN_FOR_KEY,
                NetworkInvitePolicy.markShown(
                    modelStore().manifestSetId(activeVariantFromPrefs()),
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
                val status = ModelStore(File(filesDir, MODEL_DIR_NAME)).status()
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
        /** SAME file as VoiceKeyboardService (there PERMISSION_PREFS). */
        private const val PERMISSION_PREFS = "vk_permissions"
        private const val DENIED_FOREVER_PREFIX = "denied_forever_"
        private const val MODEL_DIR_NAME = "models"

        /** T20b: persistence key of the one-time network-revoke invite
         *  (value = modelSetId; "Later" and settings click both set it). */
        private const val NET_INVITE_SHOWN_FOR_KEY = "net_invite_shown_for"
    }
}

/** Buttons a model card can offer (pure render matrix, no views). */
internal enum class ButtonKind { SELECT, DOWNLOAD, DELETE }


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
