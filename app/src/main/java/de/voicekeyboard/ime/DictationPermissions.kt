package de.voicekeyboard.ime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings

/**
 * Pure decision logic for dictation permissions (framework-free, JVM-testable):
 * which permissions are missing, in which order they are asked for and what to do
 * on permanently denied permission ("do not ask again").
 *
 * Android thin layer: [androidRequester] starts the system dialogs — in
 * production code via activity starter/PendingIntent (here a lambda, so the
 * logic is testable without Robolectric).
 */
object DictationPermissions {

    /**
     * Core permission for dictation (V16a): ONLY the microphone.
     * POST_NOTIFICATIONS previously blocked the dictation wrongly (live defect:
     * the FGS runs without it according to CaptureForegroundService — the
     * notification just stays invisible). The display question belongs on the
     * setup page.
     */
    val REQUIRED_IN_ORDER = listOf(
        android.Manifest.permission.RECORD_AUDIO,
    )

    /**
     * Optional permissions (V16a): ask once in the SetupActivity.
     * T21b (measurement 2026-11-04, PLAN_V0145 §T21b): 3x60 s revoked + 2x60 s
     * granted clean on api36 — the OS does NOT stop the FGS mic without
     * POST_NOTIFICATIONS; the F28c cause was the app-side silence guard
     * (audio_error:silence), not the OS. The notification is PURELY optional
     * (download-progress UX + recording notice), NEVER a dictation condition.
     */
    val OPTIONAL_IN_ORDER = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    /** T21a (v0.1.45, measurement bridge): operator switch in Settings.Global —
     *  set on the device via `adb shell settings put global vk_skip_notif_gate 1`. */
    const val NOTIF_GATE_BYPASS_SETTING = "vk_skip_notif_gate"

    /** T21a: PURE bypass decision — debug build (FLAG_DEBUGGABLE) AND the
     *  operator switch exactly 1. Release is NEVER bypassed. Test target
     *  for the matrix; the Android facts come from [bypassNotifGate]. */
    fun notifGateBypassed(debuggable: Boolean, setting: Int): Boolean =
        debuggable && setting == 1

    /** T21a measurement bridge, part 2 (silent guard): the SAME operator switch
     *  ([NOTIF_GATE_BYPASS_SETTING]) also arms the bypass of the app's own
     *  ERR_SILENT start watchdog (DictationCoordinator) — on a dead mic source
     *  (emulator -no-audio) it aborted after ~3 s, BEFORE the OS-FGS layer was
     *  observable. Same semantics: debug build AND setting exactly 1; release
     *  NEVER. Android facts: [bypassSilentGuard]. */
    fun silentGuardBypassed(debuggable: Boolean, setting: Int): Boolean =
        debuggable && setting == 1

    /** T21b (measurement 2026-11-04, binding): dictation condition is the
     *  microphone ONLY at EVERY api level (V16a semantics restored). The
     *  apiLevel parameter stays for call-site stability; POST_NOTIFICATIONS
     *  never enters the required set (optional — [OPTIONAL_IN_ORDER]). */
    fun requiredForApi(apiLevel: Int): List<String> = REQUIRED_IN_ORDER

    sealed class Decision {
        /** Everything present — the dictation may start. */
        data object Ready : Decision()

        /** This permission is missing and must (still) be asked for. */
        data class Ask(val permission: String) : Decision()

        /** Permanently denied: the system dialog would not come anymore — the
         *  user must go to the app settings; dictation stays blocked. */
        data class PermanentlyDenied(val permission: String) : Decision()
    }

    /**
     * Decides the next step. Parameters are deliberately injected facts
     * (no framework):
     *
     * @param granted     currently granted permissions (set)
     * @param permanentlyDenied permissions the user has denied "never again"
     * @param apiLevel    device api level; T21b: does NOT change the required
     *                    set (mic only at every level) — kept for call-site
     *                    stability.
     * @param bypassNotif T21a measurement bridge (debug-only, see
     *                    [bypassNotifGate]): historical parameter — the
     *                    notification is never required, so it cannot change
     *                    the decision; it stays as measurement technique.
     * @return next step in the order of [requiredForApi]
     */
    fun nextStep(
        granted: Set<String>,
        permanentlyDenied: Set<String>,
        apiLevel: Int = 29,
        bypassNotif: Boolean = false,
    ): Decision {
        val required =
            if (bypassNotif) requiredForApi(apiLevel).filterNot { it in OPTIONAL_IN_ORDER }
            else requiredForApi(apiLevel)
        for (permission in required) {
            if (permission in granted) continue
            return if (permission in permanentlyDenied) {
                Decision.PermanentlyDenied(permission)
            } else {
                Decision.Ask(permission)
            }
        }
        return Decision.Ready
    }

    /** Short form: is dictation possible without further dialogs? (T21b: mic only
     *  at every api level; T21a: bypassNotif cannot change the decision.) */
    fun isReady(
        granted: Set<String>,
        permanentlyDenied: Set<String> = emptySet(),
        apiLevel: Int = 29,
        bypassNotif: Boolean = false,
    ): Boolean =
        nextStep(granted, permanentlyDenied, apiLevel, bypassNotif) is Decision.Ready
}

/** T21a (v0.1.45, measurement bridge, PLAN_V0145 §T21a): Android thin layer of
 *  the debug-only bypass — TRUE only when the app is debuggable
 *  (ApplicationInfo.FLAG_DEBUGGABLE, same seam as the T9 status band) AND
 *  the operator switch [DictationPermissions.NOTIF_GATE_BYPASS_SETTING] in
 *  Settings.Global is exactly 1. Lets the OS behavior (FGS mic alive/dead
 *  without POST_NOTIFICATIONS) be measured on a debug build; production is
 *  never bypassed. */
fun bypassNotifGate(context: Context): Boolean {
    val debuggable =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return false // release: never, before any Settings read
    val setting = Settings.Global.getInt(
        context.contentResolver,
        DictationPermissions.NOTIF_GATE_BYPASS_SETTING,
        0,
    )
    return DictationPermissions.notifGateBypassed(debuggable, setting)
}

/** T21a measurement bridge, part 2: Android thin layer of the SILENT guard
 *  bypass — same pattern as [bypassNotifGate] (debug build via
 *  ApplicationInfo.FLAG_DEBUGGABLE + the operator switch
 *  [DictationPermissions.NOTIF_GATE_BYPASS_SETTING] == 1). Wires the
 *  DictationCoordinator ERR_SILENT watchdog bypass; production is never
 *  bypassed. */
fun bypassSilentGuard(context: Context): Boolean {
    val debuggable =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (!debuggable) return false // release: never, before any Settings read
    val setting = Settings.Global.getInt(
        context.contentResolver,
        DictationPermissions.NOTIF_GATE_BYPASS_SETTING,
        0,
    )
    return DictationPermissions.silentGuardBypassed(debuggable, setting)
}

/** Android thin layer: issues the request on behalf of the visible activity/IME. */
class AndroidPermissionsRequester(
    private val checkPermission: (String) -> Boolean,
    private val shouldShowRationale: (String) -> Boolean,
) {
    /**
     * Computes the facts for the pure logic:
     * after a denial, `shouldShowRequestPermissionRationale` reports false
     * if the user chose "do not ask again" (or the policy refuses it) —
     * then there is no way around the app settings.
     * F28c: NOW all known dictation permissions (incl. POST_NOTIFICATIONS)
     * — previously only REQUIRED_IN_ORDER, which made the 🐞 field `permPostNotifications`
     * structurally always false (export #2: notif=no despite possible grant).
     */
    fun grantedSet(): Set<String> =
        (DictationPermissions.REQUIRED_IN_ORDER + DictationPermissions.OPTIONAL_IN_ORDER)
            .filter { checkPermission(it) }.toSet()

    fun permanentlyDeniedSet(): Set<String> =
        (DictationPermissions.REQUIRED_IN_ORDER + DictationPermissions.OPTIONAL_IN_ORDER)
            .filter { !checkPermission(it) && !shouldShowRationale(it) }
            .toSet()
}
