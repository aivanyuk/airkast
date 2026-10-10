package io.github.aivanyuk.airkast.sample

import io.github.aivanyuk.airkast.media3.AirkastPlayer
import io.github.aivanyuk.airkast.media3.AirkastSessionService

/**
 * The MediaSession over [Cast.player], from airkast-media3: the notification, the lock screen and
 * other controllers drive the phone's player or the TV through it, and it has a Stop casting
 * button while a cast is on. media3 runs it in the foreground while the player plays, which keeps a
 * cast's network access with the app in the background.
 */
class CastService : AirkastSessionService() {
    override val player: AirkastPlayer get() = cast.player
}
