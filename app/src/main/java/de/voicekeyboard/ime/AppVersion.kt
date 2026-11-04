package de.voicekeyboard.ime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * T19 (v0.1.45): ONE shared version read path — used by the Setup header
 * (version label) AND [de.voicekeyboard.debug.DebugInfoCollector] (export
 * field). Version via PackageManager only (BuildConfig does not exist in
 * this module — no buildFeatures block in app/build.gradle.kts).
 */
internal object AppVersion {

    data class Info(val name: String?, val code: Long)

    /** PackageManager read; unavailable -> [Info](null, 0), never throws. */
    fun read(context: Context): Info = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        Info(
            name = info.versionName,
            code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
        )
    } catch (e: PackageManager.NameNotFoundException) {
        Info(name = null, code = 0)
    }

    /** Pure label: "TalkType 0.1.45 (46)"; debug appends " · debug";
     *  unknown name -> "TalkType ? (46)". Debug flag is a PARAMETER —
     *  no BuildConfig reference (rule: format testable without Android). */
    fun versionLabel(info: Info, debug: Boolean): String {
        val name = info.name?.takeIf { it.isNotEmpty() } ?: "?"
        return "TalkType $name (${info.code})" + if (debug) " · debug" else ""
    }
}
