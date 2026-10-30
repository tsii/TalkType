package de.voicekeyboard.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Auswahl der Audio-Eingangsroute (DESIGN §3 „Routing ist Bestandteil der Aufnahme"):
 *
 * - Kabel-/USB-Headset vor Bluetooth-Kommunikationsmikrofon vor eingebautes Mikro.
 * - Bluetooth-A2DP ist KEIN Mikrofoneingang (nur Typ BLUETOOTH_SCO zählt).
 * - `setPreferredDevice` ist eine Präferenz, keine Garantie — die tatsächlich
 *   aktive Route wird als [Route] an die UI gemeldet, einbstellige Abweichung
 *   (Routenverlust) geht über den Fehlerkanal, nicht still umgeschaltet.
 */
enum class Route(internal val uiLabel: String) {
    WIRED_HEADSET("Headset"),
    BLUETOOTH_SCO("Bluetooth"),
    BUILTIN_MIC("Mikrofon"),
}

/** Framework-freies Gerätebild für die reine Auswahlregel (JVM-testbar). */
data class InputDeviceFacts(
    val id: Int,
    val kind: Kind,
) {
    enum class Kind { WIRED, BLUETOOTH_SCO, BUILTIN, OTHER }
}

/**
 * Reine Auswahlregel: bevorzugtes Eingabegerät aus den verfügbaren Geräten.
 * Reihenfolge fix: WIRED > BLUETOOTH_SCO > BUILTIN > keins (null = Default).
 * Niemals OTHER (z. B. A2DP, FM, Remote-Submix) als Mikrofon wählen.
 */
object RoutingPolicy {
    fun preferredInput(devices: List<InputDeviceFacts>): InputDeviceFacts? =
        devices.minByOrNull { device ->
            when (device.kind) {
                InputDeviceFacts.Kind.WIRED -> 0
                InputDeviceFacts.Kind.BLUETOOTH_SCO -> 1
                InputDeviceFacts.Kind.BUILTIN -> 2
                InputDeviceFacts.Kind.OTHER -> 3
            }
        }?.takeIf { it.kind != InputDeviceFacts.Kind.OTHER }

    /** Route, die die UI für das gewählte Gerät anzeigen soll. */
    fun routeFor(preferred: InputDeviceFacts?): Route = when (preferred?.kind) {
        InputDeviceFacts.Kind.WIRED -> Route.WIRED_HEADSET
        InputDeviceFacts.Kind.BLUETOOTH_SCO -> Route.BLUETOOTH_SCO
        else -> Route.BUILTIN_MIC
    }
}

/**
 * Android-Adapter: beobachtet Eingabegeräte ([AudioDeviceCallback]), bildet sie
 * auf [InputDeviceFacts] ab und setzt die Präferenz auf dem [AudioRecord].
 * Die Entscheidungslogik steckt komplett in [RoutingPolicy] (ohne Android).
 */
class AudioRouter(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var errorListener: AudioErrorListener? = null
    var routeListener: ((Route) -> Unit)? = null

    private var callback: AudioDeviceCallback? = null

    /** Aktuell gemeldete Route (für die UI), initial aus dem Gerätezustand abgeleitet. */
    var currentRoute: Route = Route.BUILTIN_MIC
        private set

    fun facts(): List<InputDeviceFacts> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toFacts() }

    /**
     * Registriert den Geräte-Callback und leitet die Anfangsroute ab.
     * [onRouteChanged] feuert bei jedem relevanten Eingabegerätwechsel.
     */
    fun startObserving() {
        if (callback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        callback = cb
        refresh()
    }

    fun stopObserving() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
    }

    /** Neuberechnung: Route nur ändern, wenn das bevorzugte Gerät wechselt. */
    private fun refresh() {
        val route = RoutingPolicy.routeFor(RoutingPolicy.preferredInput(facts()))
        if (route != currentRoute) {
            currentRoute = route
            routeListener?.invoke(route)
        }
    }

    /**
     * Setzt die Gerätepräferenz für einen Recorder (KEINE Garantie, nur Präferenz —
     * die bestätigte Route läuft über [currentRoute]/[routeListener]).
     * Rückgabe: das tatsächlich bevorzugte Gerät (null = Systemdefault).
     */
    fun applyPreferredDevice(record: android.media.AudioRecord): InputDeviceFacts? {
        val preferred = RoutingPolicy.preferredInput(facts())
        val device = preferred?.let { findAudioDevice(it.id) }
        record.setPreferredDevice(device) // null nimmt die Präferenz zurück
        return preferred
    }

    private fun findAudioDevice(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }

    companion object {
        /** Mapping Android → framework-frei (reine Abbildung, keine Logik). */
        fun AudioDeviceInfo.toFacts(): InputDeviceFacts = InputDeviceFacts(
            id = id,
            kind = when (type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                -> InputDeviceFacts.Kind.WIRED
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDeviceFacts.Kind.BLUETOOTH_SCO
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceFacts.Kind.BUILTIN
                else -> InputDeviceFacts.Kind.OTHER // A2DP, FM, Remote-Submix … nie Mikrofon
            },
        )
    }
}
