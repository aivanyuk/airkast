package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ReceiverTest {
    private val lgFeatures = "0x7F8AD0,0x38BCB46"

    private fun receiver(vararg properties: Pair<String, String>) =
        Receiver("tv", "10.0.0.2", properties = mapOf(*properties))

    @Test
    fun theLgCxIsSupported() {
        val lg = receiver("features" to lgFeatures, "flags" to "0x244", "srcvers" to "377.25.06", "model" to "OLED55CX")
        assertThat(lg.compatibility).isEqualTo(Compatibility.Supported)
        assertThat(lg.sourceVersion).isEqualTo("377.25.06")
        assertThat(receiver("ft" to lgFeatures, "sf" to "0x244").compatibility).isEqualTo(Compatibility.Supported)
    }

    @Test
    fun aReceiverTypedInByHandIsUnknown() {
        assertThat(Receiver("tv", "10.0.0.2").compatibility).isEqualTo(Compatibility.Unknown)
    }

    @Test
    fun aCodeOnTheScreenOrAPasswordPairs() {
        val pin = receiver("features" to lgFeatures, "flags" to "0x8")
        assertThat(pin.compatibility).isEqualTo(Compatibility.NeedsPin)
        assertThat(pin.secret).isEqualTo(Secret.Pin)
        assertThat(pin.isSupported).isTrue()
        assertThat(receiver("features" to lgFeatures, "flags" to "0x80").compatibility)
            .isEqualTo(Compatibility.NeedsPassword)
        val password = receiver("features" to lgFeatures, "pw" to "TRUE")
        assertThat(password.compatibility).isEqualTo(Compatibility.NeedsPassword)
        assertThat(password.secret).isEqualTo(Secret.Password)
        assertThat(password.isSupported).isTrue()
        assertThat(receiver("features" to lgFeatures, "flags" to "0x244").secret).isNull()
    }

    @Test
    fun aMacThatLetsInOnlyItsOwnersDevicesIsRestricted() {
        val mac = receiver("features" to lgFeatures, "flags" to "0x4", "act" to "2")
        assertThat(mac.compatibility).isEqualTo(Compatibility.AccessRestricted)
        assertThat(mac.isSupported).isFalse()
        assertThat(receiver("features" to lgFeatures, "flags" to "0x4", "act" to "0").compatibility)
            .isEqualTo(Compatibility.Supported)
    }

    @Test
    fun theFeatureBitsSayWhetherVideoPlaysAndHowItPairs() {
        // A soundbar: AirPlay audio, no video bit.
        assertThat(receiver("features" to "0x445F8A00,0x1C340").compatibility).isEqualTo(Compatibility.NoVideo)
        assertThat(receiver("features" to "0x5A7FFFF7,0x1E").compatibility).isEqualTo(Compatibility.VideoV1Only)
        // Bit 49 alone: video v2 without system or CoreUtils pairing.
        assertThat(receiver("features" to "0x0,0x20000").compatibility).isEqualTo(Compatibility.NoTransientPairing)
    }

    @Test
    fun theSamsungSoundbarTakesAudioOnly() {
        // Its `_airplay._tcp` record, read on 2026-10-14.
        val soundbar =
            receiver(
                "features" to "0x445F8A00,0x1C340",
                "flags" to "0x4",
                "model" to "HW-Q990B",
                "srcvers" to "366.0",
                "acl" to "0",
            )
        assertThat(soundbar.compatibility).isEqualTo(Compatibility.NoVideo)
        assertThat(soundbar.isSupported).isFalse()
        assertThat(soundbar.audioCompatibility).isEqualTo(Compatibility.Supported)
        assertThat(soundbar.isAudioSupported).isTrue()
    }

    @Test
    fun audioPairsAsVideoDoes() {
        val lg = receiver("features" to lgFeatures, "flags" to "0x244")
        assertThat(lg.audioCompatibility).isEqualTo(Compatibility.Supported)
        val soundbar = "0x445F8A00,0x1C340"
        assertThat(receiver("features" to soundbar, "flags" to "0x8").audioCompatibility)
            .isEqualTo(Compatibility.NeedsPin)
        val password = receiver("features" to soundbar, "pw" to "true")
        assertThat(password.audioCompatibility).isEqualTo(Compatibility.NeedsPassword)
        assertThat(password.isAudioSupported).isTrue()
        assertThat(password.secret).isEqualTo(Secret.Password)
        assertThat(receiver("features" to soundbar, "act" to "2").audioCompatibility)
            .isEqualTo(Compatibility.AccessRestricted)
        // Bit 9 alone: AirPlay audio without system or CoreUtils pairing, as an AirPlay 1 speaker.
        assertThat(receiver("features" to "0x200").audioCompatibility).isEqualTo(Compatibility.NoTransientPairing)
        // Video bits without bit 9.
        assertThat(receiver("features" to "0x0,0x30000").audioCompatibility).isEqualTo(Compatibility.NoAudio)
        assertThat(receiver().audioCompatibility).isEqualTo(Compatibility.Unknown)
    }

    @Test
    fun theIdIsTheDeviceIdOrElseTheAddress() {
        assertThat(receiver("deviceid" to "AA:BB:CC:DD:EE:FF").id).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(Receiver("renamed", "10.0.0.3", 7100, mapOf("deviceid" to "AA:BB:CC:DD:EE:FF")).id)
            .isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(Receiver("tv", "10.0.0.2").id).isEqualTo("10.0.0.2:7000")
        assertThat(Receiver("tv", "10.0.0.2", 7100).id).isEqualTo("10.0.0.2:7100")
    }

    @Test
    fun infoReadsAsTheTxtRecordWould() {
        // GET /info as the LG CX (377.25.06), the Samsung HW-Q990B (366.0) and a Mac (960.13.25)
        // set to "Require password" answered it on 2026-10-17, without txtAirPlay.
        fun described(vararg info: Pair<String, Any?>) =
            Receiver("typed in", "10.0.0.2", properties = Receiver.propertiesFromInfo(mapOf(*info)))

        val lg = described("features" to 0x38BCB46007F8AD0L, "statusFlags" to 580L, "model" to "OLED65CX6LA")
        assertThat(lg.properties).containsAtLeast("features", "0x7F8AD0,0x38BCB46", "flags", "0x244")
        assertThat(lg.compatibility).isEqualTo(Compatibility.Supported)
        assertThat(lg.model).isEqualTo("OLED65CX6LA")

        val soundbar = described("features" to 0x1C340445F8A00L, "statusFlags" to 4L, "sourceVersion" to "366.0")
        assertThat(soundbar.compatibility).isEqualTo(Compatibility.NoVideo)
        assertThat(soundbar.audioCompatibility).isEqualTo(Compatibility.Supported)
        assertThat(soundbar.sourceVersion).isEqualTo("366.0")

        val mac = described("features" to 0x38174FDE4A7FCFD5L, "statusFlags" to 132L, "deviceID" to "02:00:00:00:00:02")
        assertThat(mac.compatibility).isEqualTo(Compatibility.NeedsPassword)
        assertThat(mac.deviceId).isEqualTo("02:00:00:00:00:02")
    }

    @Test
    fun infoWithTheTxtRecordReadsAsThatRecord() {
        // The spec's Apple TV 4K sample carries its record, as length-prefixed strings.
        val record = listOf("act=2", "features=0x4A7FFFF7,0x4155FDE", "flags=0x10644").map { it.toByteArray() }
        val txt = record.flatMap { listOf(it.size.toByte()) + it.toList() }.toByteArray()
        val properties = Receiver.propertiesFromInfo(mapOf("txtAirPlay" to txt, "features" to 0L))
        assertThat(properties).containsExactly("act", "2", "features", "0x4A7FFFF7,0x4155FDE", "flags", "0x10644")
    }

    @Test
    fun theMetadataBitsSayWhatNowPlayingSends() {
        val soundbar = receiver("features" to "0x445F8A00,0x1C340")
        assertThat(listOf(soundbar.takesTrackInfo, soundbar.takesArtwork, soundbar.takesProgress))
            .containsExactly(true, true, true)
        val textOnly = receiver("features" to "0x20200")
        assertThat(listOf(textOnly.takesTrackInfo, textOnly.takesArtwork, textOnly.takesProgress))
            .containsExactly(true, false, false)
            .inOrder()
        val typedIn = Receiver("tv", "10.0.0.2")
        assertThat(listOf(typedIn.takesTrackInfo, typedIn.takesArtwork, typedIn.takesProgress))
            .containsExactly(true, true, true)
    }

    @Test
    fun valuesCompareByContent() {
        val tv = Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures))
        assertThat(tv).isEqualTo(Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures)))
        assertThat(
            tv.hashCode(),
        ).isEqualTo(Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures)).hashCode())
        assertThat(tv.toString()).contains("host=10.0.0.2")
        assertThat(Media("a", 1.seconds)).isNotEqualTo(Media("a", 2.seconds))
    }
}
