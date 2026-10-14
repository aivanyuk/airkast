package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

class SocketTimeoutTest {
    @Test
    fun aTimeoutBecomesWholeMilliseconds() {
        assertThat(5.seconds.toSocketTimeout()).isEqualTo(5_000)
    }

    @Test
    fun anInfiniteOrVeryLongTimeoutStopsAtTheLargestASocketTakes() {
        assertThat(Duration.INFINITE.toSocketTimeout()).isEqualTo(Int.MAX_VALUE)
        assertThat(30.days.toSocketTimeout()).isEqualTo(Int.MAX_VALUE)
    }

    /** A socket reads 0 as no timeout at all, so a timeout under a millisecond becomes 1. */
    @Test
    fun aTimeoutUnderAMillisecondStaysATimeout() {
        assertThat(500.microseconds.toSocketTimeout()).isEqualTo(1)
        assertThat(Duration.ZERO.toSocketTimeout()).isEqualTo(1)
    }
}
