package io.github.aivanyuk.airkast.sample.views

import android.content.ComponentName
import android.os.Bundle
import android.text.InputType
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.android.LocalNetworkPermission
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.sample.CastService
import io.github.aivanyuk.airkast.sample.SampleMedia
import io.github.aivanyuk.airkast.sample.cast
import io.github.aivanyuk.airkast.sample.trackName
import io.github.aivanyuk.airkast.sample.views.databinding.ActivityMainBinding
import io.github.aivanyuk.airkast.sample.views.databinding.DialogSecretBinding
import io.github.aivanyuk.airkast.sample.views.databinding.ItemReceiverBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Plays a URL on the phone or casts it to the TV tapped, and shows what the TV reports: the Compose
 * flavor's `CastScreen`, in views. It runs over the process's [Cast], and holds a MediaController
 * to [CastService] while shown, for media3's player in the layout.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var connecting: ListenableFuture<MediaController>? = null
    private var secretDialog: AlertDialog? = null

    /** Android 17's local network permission, which the app declares and asks for itself. */
    private val localNetwork by lazy { LocalNetworkPermission(this) }
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            lifecycleScope.launch { localNetwork.refresh() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) binding.url.setText(SampleMedia.Stream.url)
        binding.url.doAfterTextChanged { showAutoFormat() }
        showAutoFormat()
        binding.sampleStream.setOnClickListener { pick(SampleMedia.Stream) }
        binding.sampleVideo.setOnClickListener { pick(SampleMedia.Video) }
        binding.sampleAudio.setOnClickListener { pick(SampleMedia.Audio) }
        binding.sampleMusic.setOnClickListener { pick(SampleMedia.Music) }

        binding.grant.setOnClickListener { permission.launch(localNetwork.permission) }
        binding.host.doAfterTextChanged { binding.connect.isEnabled = !it.isNullOrBlank() }
        binding.connect.setOnClickListener {
            cast.startAt(
                getString(R.string.typed_in),
                binding.host.text
                    .toString()
                    .trim(),
                url(),
                format(),
            )
        }
        binding.playHere.setOnClickListener { cast.playHere(url(), format()) }
        binding.tracks.setOnClickListener { showTracks(cast.tracks) }
        binding.info.setOnClickListener {
            lifecycleScope.launch { cast.attempt { "${playbackInfo()}\nvolume ${volume()}" }?.let(::showInfo) }
        }
        binding.disconnect.setOnClickListener { cast.player.disconnect() }
        binding.pairWithPin.setOnClickListener { pairWith(Secret.Pin) }
        binding.pairWithPassword.setOnClickListener { pairWith(Secret.Password) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { cast.player.connection.collect(::showState) }
                launch { localNetwork.state.collectLatest(::scan) }
                launch { cast.log.collect { binding.log.text = it.joinToString("\n") } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The user may change it in Settings and come back.
        lifecycleScope.launch { localNetwork.refresh() }
        // Connecting a controller starts CastService, whose MediaSession puts up the notification.
        val token = SessionToken(this, ComponentName(this, CastService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        connecting = future
        future.addListener(
            { if (connecting === future) binding.playerView.player = future.get() },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onDestroy() {
        // A recreated activity shows it again from the state.
        secretDialog?.dismiss()
        secretDialog = null
        super.onDestroy()
    }

    override fun onStop() {
        binding.playerView.player = null
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        super.onStop()
    }

    private fun url() =
        binding.url.text
            .toString()
            .trim()

    /** The format picked by hand, or null to read it from the URL. */
    private fun format(): MediaFormat? =
        when (binding.formats.checkedChipId) {
            R.id.format_hls -> MediaFormat.Hls
            R.id.format_file -> MediaFormat.File
            else -> null
        }

    private fun pick(sample: SampleMedia) {
        binding.url.setText(sample.url)
        binding.formats.check(R.id.format_auto)
    }

    private fun showAutoFormat() {
        binding.formatAuto.text = getString(R.string.format_auto, describe(MediaFormat.of(url())))
    }

    /**
     * Scans while started and permitted: the flow scans while it is collected, until the stop or
     * the next [state].
     */
    private suspend fun scan(state: LocalNetworkPermission.State) {
        val accessible = state != LocalNetworkPermission.State.Denied
        binding.permissionCard.isVisible = !accessible
        binding.receivers.removeAllViews()
        binding.discovery.isVisible = accessible
        binding.discovery.setText(R.string.searching)
        if (!accessible) return
        try {
            cast.discovery.receivers.collect(::showReceivers)
        } catch (e: AirkastException) {
            binding.discovery.text = describe(e)
        }
    }

    private fun showReceivers(receivers: List<Receiver>) {
        binding.receivers.removeAllViews()
        for (receiver in receivers) {
            val item = ItemReceiverBinding.inflate(layoutInflater, binding.receivers, true)
            item.name.text = receiver.name
            val plays =
                if (cast.streamsAudio(
                        receiver,
                    )
                ) {
                    getString(R.string.compat_speaker)
                } else {
                    describe(receiver.compatibility)
                }
            item.detail.text = getString(R.string.receiver_detail, receiver.model ?: receiver.host, plays)
            item.root.isEnabled = cast.canCast(receiver)
            item.root.alpha = if (cast.canCast(receiver)) 1f else 0.5f
            item.root.setOnClickListener { cast.start(receiver, url(), format()) }
        }
    }

    private fun showState(state: Connection) {
        val failure = (state as? Connection.Idle)?.failure
        binding.failure.text = failure?.let(::describe)
        binding.failure.isVisible = failure != null
        // A receiver may ask for a PIN or password without saying so in its TXT record.
        binding.pair.isVisible =
            state is Connection.Idle &&
            state.receiver != null &&
            (failure is AirkastException.PairingFailed || failure is AirkastException.SecretRejected)
        binding.status.text =
            when (state) {
                is Connection.Idle -> null
                is Connection.Connecting -> getString(R.string.connecting, state.receiver.name)
                is Connection.AwaitingSecret -> getString(R.string.awaiting_secret, state.receiver.name)
                is Connection.Connected -> getString(R.string.casting, state.session.receiver.name)
                is Connection.Streaming -> getString(R.string.streaming, state.stream.receiver.name)
            }
        binding.status.isVisible = state !is Connection.Idle
        binding.casting.isVisible = state is Connection.Connected || state is Connection.Streaming
        // A speaker has no renditions and no playback info: the phone's player plays.
        binding.tracks.isVisible = state is Connection.Connected
        binding.info.isVisible = state is Connection.Connected
        if (state is Connection.AwaitingSecret) {
            if (secretDialog == null) secretDialog = showSecret(state.receiver, state.secret, state.rejected)
        } else {
            secretDialog?.dismiss()
            secretDialog = null
        }
    }

    /** Connects again to the receiver that refused, pairing first with [secret]. */
    private fun pairWith(secret: Secret) {
        (cast.player.connection.value as? Connection.Idle)?.receiver?.let { cast.start(it, url(), format(), secret) }
    }

    /** The PIN [receiver] shows on its screen, or the password set on it, typed in once to pair. */
    private fun showSecret(
        receiver: Receiver,
        secret: Secret,
        rejected: Int,
    ): AlertDialog {
        val view = DialogSecretBinding.inflate(layoutInflater)
        val pin = secret == Secret.Pin
        if (!pin) {
            view.root.hint = getString(R.string.password_hint)
            view.code.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return MaterialAlertDialogBuilder(this)
            .setTitle(getString(if (pin) R.string.pin_title else R.string.password_title, receiver.name))
            .apply {
                // The player asks again after a refusal; a PIN receiver shows a new code.
                if (rejected > 0) {
                    val refused = if (pin) R.plurals.pin_rejected else R.plurals.password_rejected
                    setMessage(resources.getQuantityString(refused, rejected, rejected))
                }
            }.setView(view.root)
            .setPositiveButton(R.string.pair) { _, _ ->
                val code = view.code.text.toString()
                // A password may start or end with a space; a PIN is digits.
                cast.player.enterSecret(if (pin) code.trim() else code)
            }.setNegativeButton(android.R.string.cancel) { _, _ -> cast.player.disconnect() }
            .setOnCancelListener { cast.player.disconnect() }
            .show()
    }

    /**
     * The item's audio and subtitle tracks, from the player's `Tracks`. A tap selects one through
     * the player's track selection parameters, which the player sends to the TV.
     */
    private fun showTracks(tracks: List<Tracks.Group>) {
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tracks_title)
                .setNegativeButton(android.R.string.cancel, null)
        if (tracks.isEmpty()) {
            dialog.setMessage(R.string.tracks_none)
        } else {
            val labels = tracks.map(::describe) + getString(R.string.subtitles_off)
            dialog.setItems(labels.toTypedArray()) { _, which ->
                val group = tracks.getOrNull(which)
                if (group != null) cast.select(group) else cast.subtitlesOff()
            }
        }
        dialog.show()
    }

    private fun describe(group: Tracks.Group): String {
        val name = trackName(group.getTrackFormat(0))
        val track =
            getString(
                if (group.type ==
                    C.TRACK_TYPE_AUDIO
                ) {
                    R.string.track_audio
                } else {
                    R.string.track_subtitles
                },
                name,
            )
        return if (group.isSelected) getString(R.string.track_selected, track) else track
    }

    private fun showInfo(text: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun describe(format: MediaFormat): String =
        when (format) {
            MediaFormat.Hls -> getString(R.string.format_hls)
            MediaFormat.File -> getString(R.string.format_file)
        }

    private fun describe(compatibility: Compatibility): String =
        when (compatibility) {
            Compatibility.Supported -> getString(R.string.compat_supported)

            Compatibility.Unknown -> getString(R.string.compat_unknown)

            Compatibility.NoVideo -> getString(R.string.compat_no_video)

            Compatibility.VideoV1Only -> getString(R.string.compat_video_v1)

            Compatibility.NeedsPin -> getString(R.string.compat_needs_pin)

            Compatibility.NeedsPassword -> getString(R.string.compat_needs_password)

            Compatibility.AccessRestricted -> getString(R.string.compat_restricted)

            // New cases may join in a minor release.
            else -> getString(R.string.compat_other, compatibility.name)
        }

    /** Every failure is an [AirkastException]; new subclasses may join, so there is an `else`. */
    private fun describe(failure: AirkastException): String =
        when (failure) {
            is AirkastException.NotPermitted -> {
                getString(R.string.failed_not_permitted)
            }

            is AirkastException.Unreachable -> {
                getString(R.string.failed_unreachable)
            }

            is AirkastException.PairingFailed -> {
                getString(R.string.failed_pairing)
            }

            is AirkastException.SecretRejected -> {
                getString(if (failure.secret == Secret.Pin) R.string.failed_pin else R.string.failed_password)
            }

            is AirkastException.Disconnected -> {
                getString(
                    if (failure.reason == DisconnectReason.ClosedByReceiver) {
                        R.string.failed_closed_by_receiver
                    } else {
                        R.string.failed_disconnected
                    },
                )
            }

            is AirkastException.DiscoveryFailed -> {
                getString(R.string.discovery_failed, failure.code)
            }

            else -> {
                getString(R.string.failed_other, failure.message ?: failure.javaClass.simpleName)
            }
        }
}
