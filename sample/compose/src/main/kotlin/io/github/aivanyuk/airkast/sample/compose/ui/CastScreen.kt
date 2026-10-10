package io.github.aivanyuk.airkast.sample.compose.ui

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Track
import io.github.aivanyuk.airkast.TrackKind
import io.github.aivanyuk.airkast.android.LocalNetwork
import io.github.aivanyuk.airkast.android.ReceiverDiscovery
import io.github.aivanyuk.airkast.sample.Cast
import io.github.aivanyuk.airkast.sample.CastState
import io.github.aivanyuk.airkast.sample.compose.R
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Finds TVs, casts a URL to the one tapped, and shows what the TV reports. [player] is the
 * MediaController to [io.github.aivanyuk.airkast.sample.CastService], while one is connected.
 */
@Composable
fun CastScreen(
    cast: Cast,
    player: Player?,
    modifier: Modifier = Modifier,
) {
    val state by cast.state.collectAsStateWithLifecycle()
    val log by cast.log.collectAsStateWithLifecycle()
    val defaultUrl = stringResource(R.string.default_url)
    var url by rememberSaveable { mutableStateOf(defaultUrl) }
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
        Receivers(enabled = access.granted, onPick = { cast.start(it, url) })
        // A receiver typed in by hand has no TXT record, so its compatibility is Unknown.
        AddressEntry(onConnect = { host -> cast.start(Receiver(typedIn, host), url) })
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.url_title)) },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        CastStatus(cast, state, player, onPair = { cast.start(it, url, withPin = true) })
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
    var granted by remember { mutableStateOf(LocalNetwork.isAccessible(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = LocalNetwork.isAccessible(context)
        }
    // The user may change it in Settings and come back.
    LifecycleResumeEffect(Unit) {
        granted = LocalNetwork.isAccessible(context)
        onPauseOrDispose { }
    }
    return LocalNetworkAccess(granted) { launcher.launch(LocalNetwork.PERMISSION) }
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
private fun rememberDiscovery(enabled: Boolean): Discovery {
    val context = LocalContext.current
    val discovery =
        remember(enabled) {
            if (enabled) {
                ReceiverDiscovery(context)
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
    enabled: Boolean,
    onPick: (Receiver) -> Unit,
) {
    val discovery = rememberDiscovery(enabled)
    Column {
        Text(stringResource(R.string.receivers_title), style = MaterialTheme.typography.titleMedium)
        when (discovery) {
            is Discovery.Found -> {
                for (receiver in discovery.receivers) {
                    val usable = receiver.isSupported
                    ListItem(
                        headlineContent = { Text(receiver.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.receiver_detail,
                                    receiver.model ?: receiver.host,
                                    describe(receiver.compatibility),
                                ),
                            )
                        },
                        modifier =
                            Modifier
                                .clickable(enabled = usable) { onPick(receiver) }
                                .alpha(if (usable) 1f else 0.5f),
                    )
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
private fun CastStatus(
    cast: Cast,
    state: CastState,
    player: Player?,
    onPair: (Receiver) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<List<Track>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is CastState.Idle -> {
                state.failure?.let { Text(describe(it), color = MaterialTheme.colorScheme.error) }
                // A receiver may ask for a PIN without saying so in its TXT record.
                val receiver = state.receiver
                val refused =
                    state.failure is AirkastException.PairingFailed || state.failure is AirkastException.PinRejected
                if (receiver != null && refused) {
                    Button(onClick = { onPair(receiver) }) { Text(stringResource(R.string.pair_with_pin)) }
                }
            }

            is CastState.Connecting -> {
                Text(stringResource(R.string.connecting, state.receiver.name))
            }

            is CastState.AwaitingPin -> {
                Text(stringResource(R.string.awaiting_pin, state.receiver.name))
                PinDialog(state.receiver, onEnter = cast::enterPin, onDismiss = cast::cancelPin)
            }

            is CastState.Casting -> {
                Text(
                    stringResource(R.string.casting, state.session.receiver.name),
                    style = MaterialTheme.typography.titleMedium,
                )
                PlayerControls(player)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { selected = cast.attempt { tracks() } } }) {
                        Text(stringResource(R.string.renditions))
                    }
                    Button(
                        onClick = { scope.launch { info = cast.attempt { "${playbackInfo()}\nvolume ${volume()}" } } },
                    ) {
                        Text(stringResource(R.string.info))
                    }
                    Button(onClick = cast::stop) { Text(stringResource(R.string.disconnect)) }
                }
            }
        }
    }
    selected?.let { tracks ->
        TracksDialog(
            selected = tracks,
            onSelect = { kind, id ->
                selected = null
                scope.launch { cast.attempt { selectTrack(kind, id) } }
            },
            onDismiss = { selected = null },
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

/** The PIN [receiver] shows on its screen, typed in once to pair. */
@Composable
private fun PinDialog(
    receiver: Receiver,
    onEnter: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_title, receiver.name)) },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.pin_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = {
            TextButton(onClick = { onEnter(code) }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.pair)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** media3's controls over the MediaController, with no surface: the picture is on the TV. */
@Composable
private fun PlayerControls(player: Player?) {
    AndroidView(
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.player_view, FrameLayout(context), false) as PlayerView
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
        modifier = Modifier.fillMaxWidth().height(140.dp),
    )
}

/**
 * The receiver reports the renditions it selected, with ids that follow the HLS master's order,
 * so "next" is the next id. With none of a kind selected, as on a Mac with subtitles off, it is
 * the id after the highest one selected, since a master lists audio before subtitles. A subtitle
 * selection with a null id turns subtitles off.
 */
@Composable
private fun TracksDialog(
    selected: List<Track>,
    onSelect: (TrackKind, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    fun next(kind: TrackKind): Long {
        val current = selected.firstOrNull { it.kind == kind } ?: selected.maxByOrNull { it.id }
        return current?.id?.plus(1) ?: 0
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_title)) },
        text = {
            Column {
                Text(
                    if (selected.isEmpty()) {
                        stringResource(R.string.tracks_none)
                    } else {
                        selected.joinToString(
                            "\n",
                        ) { "${it.kind} ${it.id} ${it.name.orEmpty()} ${it.language.orEmpty()}".trim() }
                    },
                )
                TextButton(onClick = { onSelect(TrackKind.Audio, next(TrackKind.Audio)) }) {
                    Text(stringResource(R.string.next_audio))
                }
                TextButton(onClick = { onSelect(TrackKind.Subtitles, next(TrackKind.Subtitles)) }) {
                    Text(stringResource(R.string.next_subtitles))
                }
                TextButton(onClick = { onSelect(TrackKind.Subtitles, null) }) {
                    Text(stringResource(R.string.subtitles_off))
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
        is AirkastException.NotPermitted -> stringResource(R.string.failed_not_permitted)
        is AirkastException.Unreachable -> stringResource(R.string.failed_unreachable)
        is AirkastException.PairingFailed -> stringResource(R.string.failed_pairing)
        is AirkastException.PinRejected -> stringResource(R.string.failed_pin)
        is AirkastException.Disconnected -> stringResource(R.string.failed_disconnected)
        is AirkastException.DiscoveryFailed -> stringResource(R.string.discovery_failed, failure.code)
        else -> stringResource(R.string.failed_other, failure.message ?: failure.javaClass.simpleName)
    }
