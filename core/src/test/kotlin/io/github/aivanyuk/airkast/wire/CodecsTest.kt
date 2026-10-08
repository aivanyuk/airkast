package io.github.aivanyuk.airkast.wire

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodecsTest {
    @Test
    fun tlvSplitsLongValuesAndJoinsThemBack() {
        val publicKey = ByteArray(384) { it.toByte() }
        val encoded = Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(3), Tlv8.PUBLIC_KEY to publicKey)
        assertThat(encoded.size).isEqualTo(3 + 2 + 255 + 2 + 129)
        val decoded = Tlv8.decode(encoded)
        assertThat(decoded.getValue(Tlv8.SEQUENCE)).isEqualTo(byteArrayOf(3))
        assertThat(decoded.getValue(Tlv8.PUBLIC_KEY)).isEqualTo(publicKey)
    }

    @Test
    fun tlvKeepsAnEmptyValue() {
        assertThat(Tlv8.decode(Tlv8.encode(Tlv8.METHOD to ByteArray(0))).getValue(Tlv8.METHOD)).isEmpty()
    }

    @Test
    fun plistReadsAReceiverEnvelopeWrittenByPlistlib() {
        val outer = BinaryPlist.decode(PLISTLIB_EVENT.unhex()) as Map<*, *>
        val data = (outer["params"] as Map<*, *>)["data"] as ByteArray
        val event = BinaryPlist.decode(data) as Map<*, *>
        assertThat(event["type"]).isEqualTo("playbackState")
        assertThat((event["item"] as Map<*, *>)["uuid"]).isEqualTo("A4271638-F960-41C7-A11A-AC1D0E6C2960")
        val params = event["params"] as Map<*, *>
        assertThat(params["rate"]).isEqualTo(1L)
        assertThat(params["readyToPlay"]).isEqualTo(true)
        assertThat(params["seekableTimeRanges"]).isEqualTo(emptyList<Any?>())
        assertThat(params["big"]).isEqualTo(5_000_000_000L)
        assertThat(params["neg"]).isEqualTo(-3L)
        assertThat(params["real"]).isEqualTo(1.5)
        assertThat(params["name"]).isEqualTo("Многоголосый")
        assertThat((params["position"] as Map<*, *>)["timescale"]).isEqualTo(1000L)
    }

    @Test
    fun plistRoundTripsEverythingASenderWrites() {
        val command = mapOf(
            "type" to "insertPlayQueueItem",
            "item" to mapOf(
                "uuid" to "30BFEC7B-E49B-47E9-8839-E009D7F9CD7F",
                "Content-Location" to "https://example.com/" + "a".repeat(300) + "/master.m3u8",
                "Start-Position" to mapOf("value" to 600_000L, "timescale" to 1000L, "flags" to 1L, "epoch" to 0L),
                "mediaType" to "streaming",
            ),
            "rate" to 1.0,
            "flags" to listOf(true, false, null),
            "big" to 70_000L,
            "neg" to -1L,
            "unicode" to "café",
            "data" to byteArrayOf(1, 2, 3),
        )
        val decoded = BinaryPlist.decode(BinaryPlist.encode(command)) as Map<*, *>
        assertThat(decoded.keys).containsExactlyElementsIn(command.keys).inOrder()
        assertThat(decoded["item"]).isEqualTo(command["item"])
        assertThat(decoded["rate"]).isEqualTo(1.0)
        assertThat(decoded["flags"]).isEqualTo(listOf(true, false, null))
        assertThat(decoded["big"]).isEqualTo(70_000L)
        assertThat(decoded["neg"]).isEqualTo(-1L)
        assertThat(decoded["unicode"]).isEqualTo("café")
        assertThat(decoded["data"] as ByteArray).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun plistNestsAPlistInData() {
        val inner = BinaryPlist.encode(mapOf("type" to "setRate", "rate" to 0.0))
        val outer = BinaryPlist.decode(BinaryPlist.encode(mapOf("params" to mapOf("data" to inner)))) as Map<*, *>
        val data = (outer["params"] as Map<*, *>)["data"] as ByteArray
        assertThat(BinaryPlist.decode(data)).isEqualTo(mapOf("type" to "setRate", "rate" to 0.0))
    }

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        /** `plistlib.dumps(..., fmt=FMT_BINARY)` of an event shaped like the LG's. */
        const val PLISTLIB_EVENT =
            "62706c6973743030d1010256706172616d73d1030454646174614f11019962706c6973743030d4010203040508092154" +
            "6974656d546e616d6556706172616d735474797065d1060754757569645f102441343237313633382d463936302d3431" +
            "43372d413131412d41433144304536433239363057706c6179696e67d90a0b020c0d0e0f101112131415161b1e1f2053" +
            "6269675f101264726f70706564566964656f4672616d6573536e656758706f736974696f6e54726174655b7265616479" +
            "546f506c6179547265616c5f10127365656b61626c6554696d6552616e67657313000000012a05f20010006c041c043d" +
            "043e0433043e0433043e043b043e0441044b043913fffffffffffffffdd41718191a131b1c1d5565706f636855666c61" +
            "67735974696d657363616c655576616c756510011103e811396009233ff8000000000000a05d706c61796261636b5374" +
            "617465000800110016001b00220027002a002f0056005e00710075008a008e0097009c00a800ad00c200cb00cd00e600" +
            "ef00f800fe0104010e011401160119011c011d0126012700000000000002010000000000000022000000000000000000" +
            "000000000001350008000b00120015001a00000000000002010000000000000005000000000000000000000000000001" +
            "b7"
    }
}
