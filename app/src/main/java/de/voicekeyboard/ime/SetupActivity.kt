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
            val box = android.widget.CheckBox(this).apply {
                text = "$endonym · $tag"
                isChecked = id in active
                minHeight = (48 * density).toInt()
                contentDescription = "$endonym $tag"
            }
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
            container.addView(box)
        }
        // insert the section behind the version line (T19) and before the
        // model status: setup_title(0), setup_version(1), language(2)
        layoutRoot.addView(container, 2)
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
        val store = ModelStore(File(filesDir, MODEL_DIR_NAME))
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
        return if (ModelStore(File(filesDir, MODEL_DIR_NAME)).status() == ModelStatus.READY) {
            DownloadController.State.Ready
        } else null
    }

    /** Card visibility from [NetworkInvitePolicy.shouldShowInvite] — exactly
     *  once per model set; the passed render state takes precedence. */
    private fun updateNetInviteCard(state: DownloadController.State? = null) {
        val s = state ?: inviteState()
        val show = s != null && NetworkInvitePolicy.shouldShowInvite(
            s,
            ModelStore(File(filesDir, MODEL_DIR_NAME)).manifestSetId(),
            prefs.getString(NET_INVITE_SHOWN_FOR_KEY, null),
        )
        netInviteCard.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** (b2) Both buttons persist shown_for = modelSetId (pure [markShown]). */
    private fun markNetInviteShown() {
        prefs.edit()
            .putString(
                NET_INVITE_SHOWN_FOR_KEY,
                NetworkInvitePolicy.markShown(ModelStore(File(filesDir, MODEL_DIR_NAME)).manifestSetId()),
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
