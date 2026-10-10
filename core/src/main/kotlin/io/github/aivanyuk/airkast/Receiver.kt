package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko

/**
 * A receiver as its `_airplay._tcp` service record describes it. [properties] is the TXT record;
 * discovery fills it, and a receiver typed in by hand can leave it empty, which reads as
 * [Compatibility.Unknown].
 */
@Poko
public class Receiver(
    public val name: String,
    public val host: String,
    public val port: Int = DEFAULT_PORT,
    public val properties: Map<String, String> = emptyMap(),
) {
    /** The feature bits from `features` (or `ft`): `0xLOW,0xHIGH`. */
    public val features: Long get() = parseFeatures(properties["features"] ?: properties["ft"])

    /** Whether this library can play on the receiver, and if not, why. */
    public val compatibility: Compatibility get() = compatibilityOf(features, statusFlags, properties)

    /**
     * Whether airkast can play on it, so a picker lists it: [Compatibility.Supported], or
     * [Compatibility.NeedsPin]. A `NeedsPin` receiver plays once paired: [Airkast.connect] pairs
     * it when given a way to ask for the PIN, and fails with [AirkastException.PairingFailed]
     * without one. A receiver typed in by hand is [Compatibility.Unknown], not supported.
     */
    public val isSupported: Boolean
        get() = compatibility == Compatibility.Supported || compatibility == Compatibility.NeedsPin

    public val model: String? get() = properties["model"]
    public val deviceId: String? get() = properties["deviceid"]

    /** The receiver's AirPlay version, `srcvers`. Compatibility reports quote it. */
    public val sourceVersion: String? get() = properties["srcvers"]

    private val statusFlags: Long get() = (properties["flags"] ?: properties["sf"])?.let(::parseHex) ?: 0L

    public companion object {
        public const val DEFAULT_PORT: Int = 7000
        public const val SERVICE_TYPE: String = "_airplay._tcp"

        // Feature bits and status flags as pyatv names them (AirPlayFlags, utils.py).
        private const val VIDEO_V1 = 0
        private const val SYSTEM_PAIRING = 43
        private const val CORE_UTILS_PAIRING = 48
        private const val VIDEO_V2 = 49
        private const val PIN_REQUIRED = 0x8L
        private const val PASSWORD_REQUIRED = 0x80L

        /** `act`, the access control type: pyatv reads 2 as a Mac's "Current User". */
        private const val ACCESS_CURRENT_USER = "2"

        internal fun compatibilityOf(
            features: Long,
            flags: Long,
            properties: Map<String, String>,
        ): Compatibility {
            fun has(bit: Int) = features and (1L shl bit) != 0L
            return when {
                properties.isEmpty() -> {
                    Compatibility.Unknown
                }

                !has(VIDEO_V2) -> {
                    if (has(VIDEO_V1)) Compatibility.VideoV1Only else Compatibility.NoVideo
                }

                properties["act"] == ACCESS_CURRENT_USER -> {
                    Compatibility.AccessRestricted
                }

                properties["pw"].equals("true", ignoreCase = true) || flags and PASSWORD_REQUIRED != 0L -> {
                    Compatibility.NeedsPassword
                }

                // pyatv also reads 0x200 as "pairing mandatory", but the LG CX sets it (flags 0x244)
                // and pairs transiently without a PIN.
                flags and PIN_REQUIRED != 0L -> {
                    Compatibility.NeedsPin
                }

                !has(SYSTEM_PAIRING) && !has(CORE_UTILS_PAIRING) -> {
                    Compatibility.NoTransientPairing
                }

                else -> {
                    Compatibility.Supported
                }
            }
        }

        internal fun parseFeatures(value: String?): Long {
            if (value.isNullOrBlank()) return 0
            val parts = value.split(',')
            val low = parseHex(parts[0])
            val high = parts.getOrNull(1)?.let(::parseHex) ?: 0
            return (high shl 32) or (low and 0xffffffffL)
        }

        private fun parseHex(value: String): Long =
            value
                .trim()
                .removePrefix("0x")
                .removePrefix("0X")
                .toLongOrNull(16) ?: 0
    }
}

/**
 * Whether airkast can play on a receiver, read from its service record. A picker lists only
 * [Supported] receivers, and [Unknown] ones the user typed in. docs/compatibility.md has the
 * receivers behind each case. New cases may join in a minor release, as support grows.
 */
public enum class Compatibility {
    /** Speaks AirPlay video v2 and pairs without a code. */
    Supported,

    /** No TXT record to read, as for a receiver typed in by hand. Connecting is the only test. */
    Unknown,

    /**
     * Asks for a PIN shown on its screen, once: [Airkast.connect] pairs it, given a way to ask
     * the user, and keeps the [Credentials] for every connect after.
     */
    NeedsPin,

    /** Asks for a password set on the receiver, which this version cannot send. */
    NeedsPassword,

    /**
     * Lets in only the devices of its owner's Apple Account, which no third-party sender can be:
     * a Mac's AirPlay Receiver set to "Current User", its default. Setting it to "Anyone on the
     * same network" or "Everyone" changes that.
     */
    AccessRestricted,

    /** Speaks AirPlay video v2 but advertises no transient pairing, the only kind this version does. */
    NoTransientPairing,

    /** Takes URLs only over AirPlay video v1 (`POST /play`), which this version does not speak. */
    VideoV1Only,

    /** Plays no video from a URL: a speaker, or a receiver that only mirrors a screen. */
    NoVideo,
}
