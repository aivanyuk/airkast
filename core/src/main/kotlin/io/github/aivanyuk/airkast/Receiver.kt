package io.github.aivanyuk.airkast

/**
 * A receiver as its `_airplay._tcp` service record describes it. [properties] is the TXT record;
 * discovery fills it, and a receiver typed in by hand can leave it empty.
 */
public data class Receiver(
    val name: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    val properties: Map<String, String> = emptyMap(),
) {
    /** The feature bits from `features` (or `ft`): `0xLOW,0xHIGH`. */
    val features: Long get() = parseFeatures(properties["features"] ?: properties["ft"])

    /** Whether the receiver takes a URL to play itself: AirPlay video v2, which this library speaks. */
    val playsUrls: Boolean get() = features and (1L shl VIDEO_V2_BIT) != 0L

    /** Whether the receiver asks for an on-screen PIN, which this version does not support. */
    val requiresPin: Boolean get() = statusFlags and PIN_REQUIRED != 0L

    val model: String? get() = properties["model"]
    val deviceId: String? get() = properties["deviceid"]

    private val statusFlags: Long get() = (properties["flags"] ?: properties["sf"])?.let(::parseHex) ?: 0L

    public companion object {
        public const val DEFAULT_PORT: Int = 7000
        public const val SERVICE_TYPE: String = "_airplay._tcp"
        private const val VIDEO_V2_BIT = 49
        private const val PIN_REQUIRED = 0x8L

        internal fun parseFeatures(value: String?): Long {
            if (value.isNullOrBlank()) return 0
            val parts = value.split(',')
            val low = parseHex(parts[0])
            val high = parts.getOrNull(1)?.let(::parseHex) ?: 0
            return (high shl 32) or (low and 0xffffffffL)
        }

        private fun parseHex(value: String): Long = value.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0
    }
}
