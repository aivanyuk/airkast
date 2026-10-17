package io.github.aivanyuk.airkast.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.github.aivanyuk.airkast.Rendition
import io.github.aivanyuk.airkast.Track
import io.github.aivanyuk.airkast.TrackKind

/**
 * The loaded item's [renditions] as media3 [tracks]: a group each, selected as the receiver last
 * [reported], or as [ask] asked it to until the receiver reports that.
 */
@OptIn(UnstableApi::class)
internal class ReceiverTracks(
    private val renditions: List<Rendition>,
    private var reported: List<Track>,
) {
    private val groups = renditions.map(::trackGroup)

    /** What was asked of the receiver and is not reported yet. */
    private var asked: List<Pick> = emptyList()

    var tracks: Tracks = build()
        private set

    /** Whether the receiver reports everything [ask] asked for. */
    val settled: Boolean get() = asked.isEmpty()

    /**
     * The selections [parameters] make that the receiver does not report yet. The tracks show them
     * from now on, until [report] says what the receiver did.
     */
    fun ask(parameters: TrackSelectionParameters): List<Pick> {
        asked = picks(parameters).filterNot { it.isIn(reported) }
        tracks = build()
        return asked
    }

    /**
     * What the receiver reports selected. Once it reports all that was asked, or after [giveUp],
     * the tracks show it alone.
     */
    fun report(selected: List<Track>) {
        reported = selected
        if (asked.all { it.isIn(selected) }) asked = emptyList()
        tracks = build()
    }

    fun giveUp() {
        asked = emptyList()
        tracks = build()
    }

    private fun build(): Tracks {
        val shown = asked.fold(reported) { selected, pick -> pick.over(selected) }
        return Tracks(
            renditions.mapIndexed { i, rendition ->
                Tracks.Group(
                    groups[i],
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(shown.any { it.kind == rendition.kind && it.id == rendition.id }),
                )
            },
        )
    }

    /**
     * At most one selection per kind: an override of one of the groups, by its id, or else the
     * first rendition in a preferred language. Audio cannot be turned off; subtitles are, by
     * disabling the text type, by an override that selects none of its group, or as media3's
     * controls do it, by ignoring the flag that marks a default track.
     */
    private fun picks(parameters: TrackSelectionParameters): List<Pick> {
        fun overridden(kind: TrackKind): Pick? =
            parameters.overrides.values.firstNotNullOfOrNull { override ->
                renditions
                    .firstOrNull { it.kind == kind && it.id.toString() == override.mediaTrackGroup.id }
                    ?.let { Pick(kind, it.id.takeIf { override.trackIndices.isNotEmpty() }) }
            }

        fun preferred(
            kind: TrackKind,
            languages: List<String>,
        ): Pick? = matching(renditions.filter { it.kind == kind && !it.forced }, languages)?.let { Pick(kind, it.id) }

        val audio =
            overridden(TrackKind.Audio)?.takeIf { it.id != null }
                ?: preferred(TrackKind.Audio, parameters.preferredAudioLanguages)
        val off = Pick(TrackKind.Subtitles, null)
        val subtitles =
            when {
                C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes -> {
                    off
                }

                else -> {
                    overridden(TrackKind.Subtitles)
                        ?: preferred(TrackKind.Subtitles, parameters.preferredTextLanguages)
                        ?: off.takeIf { parameters.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
                }
            }
        return listOfNotNull(audio, subtitles)
    }

    /**
     * The first rendition with the first of [languages] that any has, by media3's canonical
     * language code, or else by its primary subtag, as media3's default track selector matches them.
     */
    private fun matching(
        of: List<Rendition>,
        languages: List<String>,
    ): Rendition? {
        val codes: List<String?> = of.map { it.language?.let(Util::normalizeLanguageCode) }
        for (language in languages) {
            val wanted: String? = Util.normalizeLanguageCode(language)
            if (wanted.isNullOrEmpty() || wanted == C.LANGUAGE_UNDETERMINED) continue
            val primary = wanted.substringBefore('-')
            val at =
                codes.indexOf(wanted).takeIf { it >= 0 } ?: codes.indexOfFirst { it?.substringBefore('-') == primary }
            if (at >= 0) return of[at]
        }
        return null
    }

    private companion object {
        /** How media3's HLS parser reads `CHARACTERISTICS` into role flags. */
        val ROLES =
            mapOf(
                "public.accessibility.describes-video" to C.ROLE_FLAG_DESCRIBES_VIDEO,
                "public.accessibility.transcribes-spoken-dialog" to C.ROLE_FLAG_TRANSCRIBES_DIALOG,
                "public.accessibility.describes-music-and-sound" to C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND,
                "public.easy-to-read" to C.ROLE_FLAG_EASY_TO_READ,
            )

        /**
         * One group with one format for [rendition], with its id as the receiver numbers it. The
         * codec is the receiver's business, so the MIME type says only audio or text.
         */
        fun trackGroup(rendition: Rendition): TrackGroup {
            val id = rendition.id.toString()
            val format =
                Format
                    .Builder()
                    .setId(id)
                    .setLabel(rendition.name)
                    .setLanguage(rendition.language)
                    .setSampleMimeType(
                        if (rendition.kind == TrackKind.Audio) MimeTypes.AUDIO_UNKNOWN else MimeTypes.TEXT_UNKNOWN,
                    ).setSelectionFlags(if (rendition.forced) C.SELECTION_FLAG_FORCED else 0)
                    .setRoleFlags(rendition.characteristics.fold(0) { flags, it -> flags or (ROLES[it] ?: 0) })
                    .build()
            return TrackGroup(id, format)
        }
    }
}

/**
 * A [selectTrack][io.github.aivanyuk.airkast.AirkastSession.selectTrack] call: a null [id] turns
 * subtitles off.
 */
internal data class Pick(
    val kind: TrackKind,
    val id: Long?,
) {
    /**
     * Whether the receiver, reporting [selected], has done it. A forced subtitle may stay with
     * subtitles off.
     */
    fun isIn(selected: List<Track>): Boolean =
        if (id != null) {
            selected.any { it.kind == kind && it.id == id }
        } else {
            selected.none { it.kind == kind && !it.forced }
        }

    /** [selected] once the receiver has done it. */
    fun over(selected: List<Track>): List<Track> =
        if (id != null) {
            selected.filter { it.kind != kind } + Track(kind, id, null, null, false)
        } else {
            selected.filter { it.kind != kind || it.forced }
        }
}
