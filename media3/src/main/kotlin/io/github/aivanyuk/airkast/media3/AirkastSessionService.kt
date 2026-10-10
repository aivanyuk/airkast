package io.github.aivanyuk.airkast.media3

import android.app.PendingIntent
import android.os.Bundle
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A `MediaSessionService` with a `MediaSession` over the app's [AirkastPlayer], so the
 * notification, the lock screen and other controllers (a watch, a car, Bluetooth) drive it, and a
 * cast goes on with the app in the background: media3 runs the service in the foreground while
 * the player plays. While a cast is on, the notification has a Stop casting button, which ends it
 * as [AirkastPlayer.disconnect] does. The player is the app's and outlives the service, which
 * releases only the session.
 *
 * Subclass it with the player, and declare the subclass in the manifest with the
 * `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions:
 *
 * ```
 * class PlaybackService : AirkastSessionService() {
 *     override val player get() = (application as App).player
 * }
 * ```
 * ```
 * <service
 *     android:name=".PlaybackService"
 *     android:exported="true"
 *     android:foregroundServiceType="mediaPlayback">
 *     <intent-filter>
 *         <action android:name="androidx.media3.session.MediaSessionService" />
 *     </intent-filter>
 * </service>
 * ```
 */
public abstract class AirkastSessionService : MediaSessionService() {
    /** The player the session drives. The service reads it once, when it starts. */
    protected abstract val player: AirkastPlayer

    private var session: MediaSession? = null
    private var scope: CoroutineScope? = null

    /** What tapping the notification opens: the app's launcher activity, by default. */
    protected open fun sessionActivity(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            PendingIntent.getActivity(this, 0, intent, flags)
        }

    /**
     * Builds the session from [builder], which holds the player, the [sessionActivity] and the
     * callback that handles Stop casting. Override to set more first, such as an id or media
     * button preferences, which Stop casting joins. A callback set here replaces the service's, and
     * Stop casting with it.
     */
    protected open fun buildSession(builder: MediaSession.Builder): MediaSession = builder.build()

    override fun onCreate() {
        super.onCreate()
        val player = player
        val stopCasting = StopCasting(player)
        val builder = MediaSession.Builder(this, player).setCallback(stopCasting)
        sessionActivity()?.let(builder::setSessionActivity)
        val session = buildSession(builder)
        this.session = session
        scope =
            CoroutineScope(SupervisorJob() + Handler(player.applicationLooper).asCoroutineDispatcher()).apply {
                launch { stopCasting.show(session, getString(R.string.airkast_stop_casting)) }
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        // The player is the app's, and goes on: release the session only.
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * Offers Stop casting to the controllers the session trusts, the notification's among them.
     * media3 marks the calls this takes unstable, so they stay in this internal class.
     */
    @OptIn(UnstableApi::class)
    internal class StopCasting(
        private val player: AirkastPlayer,
    ) : MediaSession.Callback {
        /** Shows the button on [session] while a cast is on, after the session's own buttons. Never returns. */
        suspend fun show(
            session: MediaSession,
            label: String,
        ) {
            val own = session.mediaButtonPreferences
            val stop =
                CommandButton
                    .Builder(CommandButton.ICON_STOP)
                    .setDisplayName(label)
                    .setSessionCommand(STOP_CASTING)
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build()
            player.connection.collect { connection ->
                session.setMediaButtonPreferences(if (connection is Connection.Idle) own else own + stop)
            }
        }

        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (controller.isTrusted) {
                val defaults = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                accepted.setAvailableSessionCommands(defaults.buildUpon().add(STOP_CASTING).build())
            }
            return Futures.immediateFuture(accepted.build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand != STOP_CASTING) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            player.disconnect()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    internal companion object {
        val STOP_CASTING = SessionCommand("io.github.aivanyuk.airkast.STOP_CASTING", Bundle.EMPTY)
    }
}
