package de.voicekeyboard.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Selection of the audio input route (DESIGN §3, quoted from the German spec:
 * “routing is part of the recording”):
 *
 * - Wired/USB headset before Bluetooth communication microphone before built-in mic.
 * - Bluetooth A2DP is NOT a microphone input (only type BLUETOOTH_SCO counts).
 * - `setPreferredDevice` is a preference, not a guarantee — the actually
 *   active route is reported to the UI as [Route]; a deviation
 *   (route loss) goes via the error channel, not a silent switch.
 */
enum class Route(internal val uiLabel: String) {
    WIRED_HEADSET("Headset"),
    BLUETOOTH_SCO("Bluetooth"),
    BUILTIN_MIC("Microphone"),
}

/** Framework-free device picture for the pure selection rule (JVM-testable). */
data class InputDeviceFacts(
    val id: Int,
    val kind: Kind,
) {
    enum class Kind { WIRED, BLUETOOTH_SCO, BUILTIN, OTHER }
}

/**
 * Pure selection rule: preferred input device from the available devices.
 * Fixed order: WIRED > BLUETOOTH_SCO > BUILTIN > none (null = default).
 * Never choose OTHER (e.g. A2DP, FM, remote submix) as a microphone.
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

    /** Route that the UI should show for the chosen device. */
    fun routeFor(preferred: InputDeviceFacts?): Route = when (preferred?.kind) {
        InputDeviceFacts.Kind.WIRED -> Route.WIRED_HEADSET
        InputDeviceFacts.Kind.BLUETOOTH_SCO -> Route.BLUETOOTH_SCO
        else -> Route.BUILTIN_MIC
    }
}

/**
 * Android adapter: observes input devices ([AudioDeviceCallback]), maps them
 * onto [InputDeviceFacts] and sets the preference on the [AudioRecord].
 * The decision logic lives entirely in [RoutingPolicy] (no Android).
 */
class AudioRouter(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var errorListener: AudioErrorListener? = null
    var routeListener: ((Route) -> Unit)? = null

    private var callback: AudioDeviceCallback? = null

    /** Currently reported route (for the UI), initially derived from the device state. */
    var currentRoute: Route = Route.BUILTIN_MIC
        private set

    fun facts(): List<InputDeviceFacts> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toFacts() }

    /**
     * Registers the device callback and derives the initial route.
     * [onRouteChanged] fires on every relevant input device change.
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

    /** Recompute: change the route only if the preferred device changes. */
    private fun refresh() {
        val route = RoutingPolicy.routeFor(RoutingPolicy.preferredInput(facts()))
        if (route != currentRoute) {
            currentRoute = route
            routeListener?.invoke(route)
        }
    }

    /**
     * Sets the device preference for a recorder (NO guarantee, only a preference —
     * the confirmed route runs via [currentRoute]/[routeListener]).
     * Return: the actually preferred device (null = system default).
     */
    fun applyPreferredDevice(record: android.media.AudioRecord): InputDeviceFacts? {
        val preferred = RoutingPolicy.preferredInput(facts())
        val device = preferred?.let { findAudioDevice(it.id) }
        record.setPreferredDevice(device) // null takes back the preference
        return preferred
    }

    private fun findAudioDevice(id: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }

    companion object {
        /** Mapping Android → framework-free (pure mapping, no logic). */
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
                else -> InputDeviceFacts.Kind.OTHER // A2DP, FM, remote submix … never a microphone
            },
        )
    }
}
