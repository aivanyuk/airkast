package io.github.aivanyuk.airkast.sample.views

import android.content.ComponentName
import android.os.Bundle
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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Track
import io.github.aivanyuk.airkast.TrackKind
import io.github.aivanyuk.airkast.android.LocalNetwork
import io.github.aivanyuk.airkast.android.ReceiverDiscovery
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.sample.CastService
import io.github.aivanyuk.airkast.sample.cast
import io.github.aivanyuk.airkast.sample.views.databinding.ActivityMainBinding
import io.github.aivanyuk.airkast.sample.views.databinding.DialogPinBinding
import io.github.aivanyuk.airkast.sample.views.databinding.DialogTracksBinding
import io.github.aivanyuk.airkast.sample.views.databinding.ItemReceiverBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Finds TVs, casts a URL to the one tapped, and shows what the TV reports: the Compose flavor's
 * `CastScreen`, in views. It runs over the process's [Cast], and holds a MediaController to
 * [CastService] while shown, for media3's controls in the layout.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var connecting: ListenableFuture<MediaController>? = null
    private var scanning: Job? = null
    private var pinDialog: AlertDialog? = null

    /** Android 17's local network permission, which the app declares and asks for itself. */
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { scan() }

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

        binding.grant.setOnClickListener { permission.launch(LocalNetwork.PERMISSION) }
        binding.host.doAfterTextChanged { binding.connect.isEnabled = !it.isNullOrBlank() }
        // A receiver typed in by hand has no TXT record, so its compatibility is Unknown.
        binding.connect.setOnClickListener {
            cast.start(
                Receiver(
                    getString(R.string.typed_in),
                    binding.host.text
                        .toString()
                        .trim(),
                ),
                url(),
            )
        }
        binding.tracks.setOnClickListener { lifecycleScope.launch { cast.attempt { tracks() }?.let(::showTracks) } }
        binding.info.setOnClickListener {
            lifecycleScope.launch { cast.attempt { "${playbackInfo()}\nvolume ${volume()}" }?.let(::showInfo) }
        }
        binding.disconnect.setOnClickListener { cast.player.disconnect() }
        binding.pairWithPin.setOnClickListener {
            (cast.player.connection.value as? Connection.Idle)?.receiver?.let { cast.start(it, url(), withPin = true) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { cast.player.connection.collect(::showState) }
                launch { cast.log.collect { binding.log.text = it.joinToString("\n") } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        scan()
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
        pinDialog?.dismiss()
        pinDialog = null
        super.onDestroy()
    }

    override fun onStop() {
        scanning?.cancel()
        binding.playerView.player = null
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        super.onStop()
    }

    private fun url() =
        binding.url.text
            .toString()
            .trim()

    /**
     * Scans while started and permitted: the flow scans while it is collected. Runs again when
     * the permission is granted, and on every start, since the user may change it in Settings.
     */
    private fun scan() {
        scanning?.cancel()
        val accessible = LocalNetwork.isAccessible(this)
        binding.permissionCard.isVisible = !accessible
        binding.receivers.removeAllViews()
        binding.discovery.isVisible = accessible
        binding.discovery.setText(R.string.searching)
        if (!accessible) return
        scanning =
            lifecycleScope.launch {
                try {
                    ReceiverDiscovery(this@MainActivity).receivers.collect(::showReceivers)
                } catch (e: AirkastException) {
                    binding.discovery.text = describe(e)
                }
            }
    }

    private fun showReceivers(receivers: List<Receiver>) {
        binding.receivers.removeAllViews()
        for (receiver in receivers) {
            val item = ItemReceiverBinding.inflate(layoutInflater, binding.receivers, true)
            item.name.text = receiver.name
            item.detail.text =
                getString(R.string.receiver_detail, receiver.model ?: receiver.host, describe(receiver.compatibility))
            item.root.isEnabled = receiver.isSupported
            item.root.alpha = if (receiver.isSupported) 1f else 0.5f
            item.root.setOnClickListener { cast.start(receiver, url()) }
        }
    }

    private fun showState(state: Connection) {
        val failure = (state as? Connection.Idle)?.failure
        binding.failure.text = failure?.let(::describe)
        binding.failure.isVisible = failure != null
        // A receiver may ask for a PIN without saying so in its TXT record.
        binding.pairWithPin.isVisible =
            state is Connection.Idle &&
            state.receiver != null &&
            (failure is AirkastException.PairingFailed || failure is AirkastException.PinRejected)
        binding.status.text =
            when (state) {
                is Connection.Idle -> null
                is Connection.Connecting -> getString(R.string.connecting, state.receiver.name)
                is Connection.AwaitingPin -> getString(R.string.awaiting_pin, state.receiver.name)
                is Connection.Connected -> getString(R.string.casting, state.session.receiver.name)
            }
        binding.status.isVisible = state !is Connection.Idle
        binding.casting.isVisible = state is Connection.Connected
        if (state is Connection.AwaitingPin) {
            if (pinDialog == null) pinDialog = showPin(state.receiver)
        } else {
            pinDialog?.dismiss()
            pinDialog = null
        }
    }

    /** The PIN [receiver] shows on its screen, typed in once to pair. */
    private fun showPin(receiver: Receiver): AlertDialog {
        val view = DialogPinBinding.inflate(layoutInflater)
        return MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pin_title, receiver.name))
            .setView(view.root)
            .setPositiveButton(R.string.pair) { _, _ ->
                cast.player.enterPin(
                    view.pin.text
                        .toString()
                        .trim(),
                )
            }.setNegativeButton(android.R.string.cancel) { _, _ -> cast.player.disconnect() }
            .setOnCancelListener { cast.player.disconnect() }
            .show()
    }

    /**
     * The receiver reports the renditions it selected, with ids that follow the HLS master's order,
     * so "next" is the next id. With none of a kind selected, as on a Mac with subtitles off, it is
     * the id after the highest one selected, since a master lists audio before subtitles. A subtitle
     * selection with a null id turns subtitles off.
     */
    private fun showTracks(selected: List<Track>) {
        val view = DialogTracksBinding.inflate(layoutInflater)
        view.selected.text =
            if (selected.isEmpty()) {
                getString(R.string.tracks_none)
            } else {
                selected.joinToString(
                    "\n",
                ) { "${it.kind} ${it.id} ${it.name.orEmpty()} ${it.language.orEmpty()}".trim() }
            }
        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tracks_title)
                .setView(view.root)
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        fun next(kind: TrackKind): Long {
            val current = selected.firstOrNull { it.kind == kind } ?: selected.maxByOrNull { it.id }
            return current?.id?.plus(1) ?: 0
        }

        fun pick(
            kind: TrackKind,
            id: Long?,
        ) {
            dialog.dismiss()
            lifecycleScope.launch { cast.attempt { selectTrack(kind, id) } }
        }
        view.nextAudio.setOnClickListener { pick(TrackKind.Audio, next(TrackKind.Audio)) }
        view.nextSubtitles.setOnClickListener { pick(TrackKind.Subtitles, next(TrackKind.Subtitles)) }
        view.subtitlesOff.setOnClickListener { pick(TrackKind.Subtitles, null) }
    }

    private fun showInfo(text: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
            is AirkastException.NotPermitted -> getString(R.string.failed_not_permitted)
            is AirkastException.Unreachable -> getString(R.string.failed_unreachable)
            is AirkastException.PairingFailed -> getString(R.string.failed_pairing)
            is AirkastException.PinRejected -> getString(R.string.failed_pin)
            is AirkastException.Disconnected -> getString(R.string.failed_disconnected)
            is AirkastException.DiscoveryFailed -> getString(R.string.discovery_failed, failure.code)
            else -> getString(R.string.failed_other, failure.message ?: failure.javaClass.simpleName)
        }
}
