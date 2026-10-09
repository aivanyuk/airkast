package io.github.aivanyuk.airkast.sample

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * A MediaSession over [Cast.player]: the notification, the lock screen and other controllers drive
 * the TV through it. media3 makes the service a foreground service of type `mediaPlayback` while
 * the cast plays, which keeps the session's network access with the app in the background, and
 * [Cast.player] holds the wake and Wi-Fi locks that keep its keepalive going with the screen off.
 */
class CastService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session = MediaSession.Builder(this, cast.player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The player is the Cast's, and outlives the service: release the MediaSession only.
        session?.release()
        session = null
        super.onDestroy()
    }
}
