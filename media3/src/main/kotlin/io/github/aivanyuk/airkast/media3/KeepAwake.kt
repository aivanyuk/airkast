package io.github.aivanyuk.airkast.media3

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** A partial wake lock and a Wi-Fi lock, held together while a cast needs the phone awake. */
internal class KeepAwake(
    context: Context,
) {
    private val wake =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airkast:session")
            .apply { setReferenceCounted(false) }

    // Null on a device without Wi-Fi, such as an Ethernet-only box.
    private val wifi =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?)
            // HIGH_PERF is deprecated from API 34, but LOW_LATENCY holds only while the screen is on.
            // ExoPlayer's WAKE_MODE_NETWORK takes HIGH_PERF too.
            ?.createWifiLock(highPerf(), "airkast:session")
            ?.apply { setReferenceCounted(false) }

    var held: Boolean = false
        private set

    // A cast lasts as long as the episode, so no timeout fits; release() follows the session.
    @SuppressLint("WakelockTimeout")
    fun hold(hold: Boolean) {
        if (hold == held) return
        held = hold
        if (hold) {
            wake.acquire()
            wifi?.acquire()
        } else {
            wake.release()
            wifi?.release()
        }
    }

    private companion object {
        @Suppress("DEPRECATION")
        fun highPerf() = WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
