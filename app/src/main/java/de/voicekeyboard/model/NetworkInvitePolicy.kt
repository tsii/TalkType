package de.voicekeyboard.model

/**
 * Framework-free policy for the ONE-TIME system-level network-revoke invite
 * after a model download (docs/PLAN_V0145.md T20a; T20b wires the UI).
 *
 * Rules:
 * (a) State.Ready AND shown_for != modelSetId -> show the invite card.
 *     Covers both the post-download run and the cold start READY case (e):
 *     never-shown (null) shows exactly once, a new modelSetId shows again (c).
 * (b) State.Ready AND shown_for == modelSetId -> no (never nag, one-time only).
 * (d) Every other controller state -> no.
 * (b2) Both buttons ("Later" and the settings deep link) persist the same
 *     value via [markShown]; the caller (T20b) writes it to prefs.
 * Intent: ACTION_APPLICATION_DETAILS_SETTINGS with "package:<pkg>" so the user
 * can revoke INTERNET at the SYSTEM level (GrapheneOS: App-Info ->
 * Permissions -> Network); [fallbackIntentAction] for devices without an
 * app-details activity. Returned as pure strings (JVM-testable, no Context).
 * modelSetId is the stable sha256-of-manifest hash (ModelStore); persistence
 * is NOT part of this file — only bool/String in, decision out, side-effect
 * free, deterministic (same input -> same output).
 */
object NetworkInvitePolicy {

    /** ACTION_APPLICATION_DETAILS_SETTINGS as a plain string (no android.content import). */
    const val INVITE_INTENT_ACTION: String = "android.settings.APPLICATION_DETAILS_SETTINGS"

    /** Fallback for devices without an app-details activity: ACTION_SETTINGS. */
    const val FALLBACK_INTENT_ACTION: String = "android.settings.SETTINGS"

    /** (a)/(b)/(c)/(d)/(e): show the invite exactly when READY and not yet shown for this model set. */
    fun shouldShowInvite(state: DownloadController.State, modelSetId: String, shownForId: String?): Boolean =
        state is DownloadController.State.Ready && shownForId != modelSetId

    /** (b2) Value to persist after "Later" or a settings-button click. Pure: in == out. */
    fun markShown(modelSetId: String): String = modelSetId

    /** Intent action for the app-details deep link (see [INVITE_INTENT_ACTION]). */
    fun inviteIntentAction(): String = INVITE_INTENT_ACTION

    /** Intent data URI for the app-details deep link: "package:<pkg>". */
    fun inviteIntentData(pkg: String): String = "package:$pkg"

    /** Intent action if no app-details activity resolves (see [FALLBACK_INTENT_ACTION]). */
    fun fallbackIntentAction(): String = FALLBACK_INTENT_ACTION
}
