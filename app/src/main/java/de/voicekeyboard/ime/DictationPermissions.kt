package de.voicekeyboard.ime

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
     * F28c: from api 33 POST_NOTIFICATIONS is NO longer a pure display permission:
     * without it the system can silently stop the FGS type microphone
     * (export #2: api 37, notif=no, recording dead after 1.0 s) — hence in
     * [requiredForApi].
     */
    val OPTIONAL_IN_ORDER = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    /** Api level from which the FGS type microphone makes the notification
     *  permission a dictation condition (F28c; POST_NOTIFICATIONS exists from 33). */
    const val NOTIF_REQUIRED_SINCE_API = 33

    /** F28c: dictation conditions as a function of the api level — below 33 only
     *  the microphone (V16a), from 33 additionally the notification permission. */
    fun requiredForApi(apiLevel: Int): List<String> =
        if (apiLevel >= NOTIF_REQUIRED_SINCE_API) REQUIRED_IN_ORDER + OPTIONAL_IN_ORDER
        else REQUIRED_IN_ORDER

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
     * @param apiLevel    device api level; default 29 = V16a semantics (display
     *                    never blocks). From [NOTIF_REQUIRED_SINCE_API],
     *                    POST_NOTIFICATIONS becomes a dictation condition (F28c).
     * @return next step in the order of [requiredForApi]
     */
    fun nextStep(
        granted: Set<String>,
        permanentlyDenied: Set<String>,
        apiLevel: Int = 29,
    ): Decision {
        for (permission in requiredForApi(apiLevel)) {
            if (permission in granted) continue
            return if (permission in permanentlyDenied) {
                Decision.PermanentlyDenied(permission)
            } else {
                Decision.Ask(permission)
            }
        }
        return Decision.Ready
    }

    /** Short form: is dictation possible without further dialogs? (F28c: depends on the api level.) */
    fun isReady(granted: Set<String>, permanentlyDenied: Set<String> = emptySet(), apiLevel: Int = 29): Boolean =
        nextStep(granted, permanentlyDenied, apiLevel) is Decision.Ready
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
