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
    fun aCodeOnTheScreenOrAPasswordIsNotSupportedYet() {
        assertThat(receiver("features" to lgFeatures, "flags" to "0x8").compatibility).isEqualTo(Compatibility.NeedsPin)
        assertThat(receiver("features" to lgFeatures, "flags" to "0x80").compatibility)
            .isEqualTo(Compatibility.NeedsPassword)
        assertThat(receiver("features" to lgFeatures, "pw" to "TRUE").compatibility)
            .isEqualTo(Compatibility.NeedsPassword)
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
    fun valuesCompareByContent() {
        val tv = Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures))
        assertThat(tv).isEqualTo(Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures)))
        assertThat(
            tv.hashCode(),
        ).isEqualTo(Receiver("tv", "10.0.0.2", properties = mapOf("features" to lgFeatures)).hashCode())
        assertThat(tv.toString()).contains("host=10.0.0.2")
        assertThat(VideoItem("a", 1.seconds)).isNotEqualTo(VideoItem("a", 2.seconds))
    }
}
