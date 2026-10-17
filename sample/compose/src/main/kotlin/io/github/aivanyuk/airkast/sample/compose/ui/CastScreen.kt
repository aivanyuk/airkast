package io.github.aivanyuk.airkast.sample.compose.ui

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.ui.PlayerView
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.android.LocalNetworkPermission
import io.github.aivanyuk.airkast.android.ReceiverDiscovery
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.sample.Cast
import io.github.aivanyuk.airkast.sample.SampleMedia
import io.github.aivanyuk.airkast.sample.compose.R
import io.github.aivanyuk.airkast.sample.trackName
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Plays a URL on the phone or casts it to the TV tapped, and shows what the TV reports. [player] is
 * the MediaController to [io.github.aivanyuk.airkast.sample.CastService], while one is connected.
 */
@Composable
fun CastScreen(
    cast: Cast,
    player: Player?,
    modifier: Modifier = Modifier,
) {
    val connection by cast.player.connection.collectAsStateWithLifecycle()
    val log by cast.log.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(SampleMedia.Stream.url) }
    // Null reads it from the URL.
    var format by rememberSaveable { mutableStateOf<MediaFormat?>(null) }
    val typedIn = stringResource(R.string.typed_in)
    val access = rememberLocalNetworkAccess()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!access.granted) PermissionCard(onGrant = access.request)
        Receivers(cast, enabled = access.granted, onPick = { cast.start(it, url, format) })
        // A receiver typed in by hand has no TXT record, so its compatibility is Unknown.
        AddressEntry(onConnect = { host -> cast.start(Receiver(typedIn, host), url, format) })
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.url_title)) },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Samples(
            onPick = {
                url = it.url
                format = null
            },
        )
        FormatPicker(url, format, onPick = { format = it })
        Button(onClick = { cast.playHere(url, format) }) { Text(stringResource(R.string.play_here)) }
        PlayerControls(player)
        CastStatus(cast, connection, onPair = { receiver, secret -> cast.start(receiver, url, format, secret) })
        EventLog(log)
    }
}

private class LocalNetworkAccess(
    val granted: Boolean,
    val request: () -> Unit,
)

/** Android 17's local network permission, which the app declares and asks for itself. */
@Composable
private fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val localNetwork = remember { LocalNetworkPermission(context) }
    val state by localNetwork.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            scope.launch { localNetwork.refresh() }
        }
    // The user may change it in Settings and come back.
    LifecycleStartEffect(localNetwork) {
        scope.launch { localNetwork.refresh() }
        onStopOrDispose { }
    }
    return LocalNetworkAccess(state != LocalNetworkPermission.State.Denied) {
        launcher.launch(localNetwork.permission)
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.no_local_network))
            Button(onClick = onGrant) { Text(stringResource(R.string.grant)) }
        }
    }
}

private sealed interface Discovery {
    data class Found(
        val receivers: List<Receiver>,
    ) : Discovery

    data class Failed(
        val error: AirkastException,
    ) : Discovery
}

/** Scans while this is composed, started, and [enabled]: the flow scans while it is collected. */
@Composable
private fun rememberDiscovery(
    receivers: ReceiverDiscovery,
    enabled: Boolean,
): Discovery {
    val discovery =
        remember(receivers, enabled) {
            if (enabled) {
                receivers
                    .receivers
                    .map<List<Receiver>, Discovery> { Discovery.Found(it) }
                    .catch { e -> if (e is AirkastException) emit(Discovery.Failed(e)) else throw e }
            } else {
                flowOf(Discovery.Found(emptyList()))
            }
        }
    return discovery.collectAsStateWithLifecycle(Discovery.Found(emptyList())).value
}

@Composable
private fun Receivers(
    cast: Cast,
    enabled: Boolean,
    onPick: (Receiver) -> Unit,
) {
    val discovery = rememberDiscovery(cast.discovery, enabled)
    Column {
        Text(stringResource(R.string.receivers_title), style = MaterialTheme.typography.titleMedium)
        when (discovery) {
            is Discovery.Found -> {
                for (receiver in discovery.receivers) {
                    key(receiver.id) {
                        val usable = cast.canCast(receiver)
                        ListItem(
                            headlineContent = { Text(receiver.name) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.receiver_detail,
                                        receiver.model ?: receiver.host,
                                        if (cast.streamsAudio(receiver)) {
                                            stringResource(R.string.compat_speaker)
                                        } else {
                                            describe(receiver.compatibility)
                                        },
                                    ),
                                )
                            },
                            modifier =
                                Modifier
                                    .clickable(enabled = usable) { onPick(receiver) }
                                    .alpha(if (usable) 1f else 0.5f),
                        )
                    }
                }
                if (enabled) Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall)
            }

            is Discovery.Failed -> {
                Text(describe(discovery.error), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddressEntry(onConnect: (String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.by_address_title)) },
            placeholder = { Text(stringResource(R.string.host_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onConnect(host.trim()) }, enabled = host.isNotBlank()) {
            Text(stringResource(R.string.connect))
        }
    }
}

@Composable
private fun Samples(onPick: (SampleMedia) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sample in SampleMedia.entries) {
            AssistChip(onClick = { onPick(sample) }, label = { Text(describe(sample)) })
        }
    }
}

/** How the item loads: as its URL says, the default, or as picked for a URL that says nothing. */
@Composable
private fun FormatPicker(
    url: String,
    format: MediaFormat?,
    onPick: (MediaFormat?) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = format == null,
            onClick = { onPick(null) },
            label = { Text(stringResource(R.string.format_auto, describe(MediaFormat.of(url.trim())))) },
        )
        for (each in MediaFormat.entries) {
            FilterChip(selected = format == each, onClick = { onPick(each) }, label = { Text(describe(each)) })
        }
    }
}

@Composable
private fun CastStatus(
    cast: Cast,
    state: Connection,
    onPair: (Receiver, Secret) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<Tracks.Group>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is Connection.Idle -> {
                state.failure?.let { Text(describe(it), color = MaterialTheme.colorScheme.error) }
                // A receiver may ask for a PIN or password without saying so in its TXT record.
                val receiver = state.receiver
                val refused =
                    state.failure is AirkastException.PairingFailed || state.failure is AirkastException.SecretRejected
                if (receiver != null && refused) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPair(receiver, Secret.Pin) }) {
                            Text(stringResource(R.string.pair_with_pin))
                        }
                        Button(onClick = { onPair(receiver, Secret.Password) }) {
                            Text(stringResource(R.string.pair_with_password))
                        }
                    }
                }
            }

            is Connection.Connecting -> {
                Text(stringResource(R.string.connecting, state.receiver.name))
            }

            is Connection.AwaitingSecret -> {
                Text(stringResource(R.string.awaiting_secret, state.receiver.name))
                SecretDialog(
                    state.receiver,
                    state.secret,
                    state.rejected,
                    onEnter = cast.player::enterSecret,
                    onDismiss = cast.player::disconnect,
                )
            }

            is Connection.Connected -> {
                Text(
                    stringResource(R.string.casting, state.session.receiver.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { tracks = cast.tracks }) {
                        Text(stringResource(R.string.renditions))
                    }
                    Button(
                        onClick = { scope.launch { info = cast.attempt { "${playbackInfo()}\nvolume ${volume()}" } } },
                    ) {
                        Text(stringResource(R.string.info))
                    }
                    Button(onClick = cast.player::disconnect) { Text(stringResource(R.string.disconnect)) }
                }
            }

            is Connection.Streaming -> {
                Text(
                    stringResource(R.string.streaming, state.stream.receiver.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = cast.player::disconnect) { Text(stringResource(R.string.disconnect)) }
            }
        }
    }
    tracks?.let { groups ->
        TracksDialog(
            tracks = groups,
            onSelect = { group ->
                tracks = null
                cast.select(group)
            },
            onSubtitlesOff = {
                tracks = null
                cast.subtitlesOff()
            },
            onDismiss = { tracks = null },
        )
    }
    info?.let { text ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(text) },
        )
    }
}

/** The PIN [receiver] shows on its screen, or the password set on it, typed in once to pair. */
@Composable
private fun SecretDialog(
    receiver: Receiver,
    secret: Secret,
    rejected: Int,
    onEnter: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val pin = secret == Secret.Pin
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (pin) R.string.pin_title else R.string.password_title, receiver.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The player asks again after a refusal; a PIN receiver shows a new code.
                if (rejected > 0) {
                    Text(
                        pluralStringResource(
                            if (pin) R.plurals.pin_rejected else R.plurals.password_rejected,
                            rejected,
                            rejected,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = if (pin) it.filter(Char::isDigit) else it },
                    label = { Text(stringResource(if (pin) R.string.pin_hint else R.string.password_hint)) },
                    singleLine = true,
                    visualTransformation = if (pin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEnter(code) }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.pair)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** media3's controls over the MediaController, on the phone's picture, which a cast moves to the TV. */
@Composable
private fun PlayerControls(player: Player?) {
    AndroidView(
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.player_view, FrameLayout(context), false) as PlayerView
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9),
    )
}

/**
 * The item's audio and subtitle tracks, from the player's `Tracks`. A tap selects one through the
 * player's track selection parameters, which the player sends to the TV.
 */
@Composable
private fun TracksDialog(
    tracks: List<Tracks.Group>,
    onSelect: (Tracks.Group) -> Unit,
    onSubtitlesOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_title)) },
        text = {
            Column {
                if (tracks.isEmpty()) Text(stringResource(R.string.tracks_none))
                for (group in tracks) {
                    TextButton(onClick = { onSelect(group) }) { Text(describe(group)) }
                }
                if (tracks.isNotEmpty()) {
                    TextButton(onClick = onSubtitlesOff) { Text(stringResource(R.string.subtitles_off)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun EventLog(lines: List<String>) {
    Column {
        Text(stringResource(R.string.events_title), style = MaterialTheme.typography.titleMedium)
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun describe(group: Tracks.Group): String {
    val name = trackName(group.getTrackFormat(0))
    val track =
        stringResource(
            if (group.type ==
                C.TRACK_TYPE_AUDIO
            ) {
                R.string.track_audio
            } else {
                R.string.track_subtitles
            },
            name,
        )
    return if (group.isSelected) stringResource(R.string.track_selected, track) else track
}

@Composable
private fun describe(sample: SampleMedia): String =
    when (sample) {
        SampleMedia.Stream -> stringResource(R.string.sample_stream)
        SampleMedia.Video -> stringResource(R.string.sample_video)
        SampleMedia.Audio -> stringResource(R.string.sample_audio)
        SampleMedia.Music -> stringResource(R.string.sample_music)
    }

@Composable
private fun describe(format: MediaFormat): String =
    when (format) {
        MediaFormat.Hls -> stringResource(R.string.format_hls)
        MediaFormat.File -> stringResource(R.string.format_file)
    }

@Composable
private fun describe(compatibility: Compatibility): String =
    when (compatibility) {
        Compatibility.Supported -> stringResource(R.string.compat_supported)

        Compatibility.Unknown -> stringResource(R.string.compat_unknown)

        Compatibility.NoVideo -> stringResource(R.string.compat_no_video)

        Compatibility.VideoV1Only -> stringResource(R.string.compat_video_v1)

        Compatibility.NeedsPin -> stringResource(R.string.compat_needs_pin)

        Compatibility.NeedsPassword -> stringResource(R.string.compat_needs_password)

        Compatibility.AccessRestricted -> stringResource(R.string.compat_restricted)

        // New cases may join in a minor release.
        else -> stringResource(R.string.compat_other, compatibility.name)
    }

/** Every failure is an [AirkastException]; new subclasses may join, so there is an `else`. */
@Composable
private fun describe(failure: AirkastException): String =
    when (failure) {
        is AirkastException.NotPermitted -> {
            stringResource(R.string.failed_not_permitted)
        }

        is AirkastException.Unreachable -> {
            stringResource(R.string.failed_unreachable)
        }

        is AirkastException.PairingFailed -> {
            stringResource(R.string.failed_pairing)
        }

        is AirkastException.SecretRejected -> {
            stringResource(if (failure.secret == Secret.Pin) R.string.failed_pin else R.string.failed_password)
        }

        is AirkastException.Disconnected -> {
            stringResource(
                if (failure.reason == DisconnectReason.ClosedByReceiver) {
                    R.string.failed_closed_by_receiver
                } else {
                    R.string.failed_disconnected
                },
            )
        }

        is AirkastException.DiscoveryFailed -> {
            stringResource(R.string.discovery_failed, failure.code)
        }

        else -> {
            stringResource(R.string.failed_other, failure.message ?: failure.javaClass.simpleName)
        }
    }
