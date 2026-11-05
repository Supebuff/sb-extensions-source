package eu.kanade.tachiyomi.animeextension.zh.mugua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuguaRequestPolicyTest {
    private var now = 0L
    private val wall = 1_445_412_480_000L // Wed, 21 Oct 2015 07:28:00 GMT
    private val policy = MuguaRequestPolicy({ 500L }, { now }, { wall })

    @Test
    fun spacesStartsPerHostWithoutBlockingOtherHosts() {
        assertEquals(0L, policy.beforeRequest("site.test"))
        assertEquals(500L, policy.beforeRequest("site.test"))
        assertEquals(0L, policy.beforeRequest("images.test"))
        now = 200
        assertEquals(300L, policy.beforeRequest("site.test"))
        now = 500
        assertEquals(0L, policy.beforeRequest("site.test"))
        assertEquals(500L, policy.beforeRequest("site.test"))
    }

    @Test
    fun numericRetryAfterBlocksOnlyItsHostUntilTheDeadline() {
        assertEquals(60_000L, policy.response("site.test", 429, "60"))
        assertEquals(60_000L, cooldown("site.test"))
        assertEquals(0L, policy.beforeRequest("images.test"))
        now = 59_999
        assertEquals(1L, cooldown("site.test"))
        now++
        assertEquals(0L, policy.beforeRequest("site.test"))
    }

    @Test
    fun honorsHttpDateAndLongServerDelays() {
        assertEquals(120_000L, policy.response("site.test", 503, "Wed, 21 Oct 2015 07:30:00 GMT"))
        assertEquals(3_600_000L, policy.response("site.test", 429, "3600"))
        assertEquals(3_600_000L, cooldown("site.test"))
    }

    @Test
    fun overlappingResponsesCannotShortenCooldown() {
        policy.response("site.test", 429, "120")
        now = 1000
        policy.response("site.test", 429, "1")
        policy.response("site.test", 200, null)
        assertEquals(119_000L, cooldown("site.test"))
    }

    @Test
    fun missingAndInvalid429DelaysUseBoundedBackoff() {
        assertEquals(30_000L, policy.response("site.test", 429, null))
        now += 30_000
        assertEquals(60_000L, policy.response("site.test", 429, "invalid"))
        now += 60_000
        assertEquals(120_000L, policy.response("site.test", 429, "-5"))
        repeat(10) {
            now += 900_000
            assertTrue(policy.response("site.test", 429, null) <= 900_000L)
        }
    }

    @Test
    fun aSuccessfulRecoveryResetsFallbackBackoff() {
        policy.response("site.test", 429, null)
        now = 30_000
        policy.response("site.test", 200, null)
        assertEquals(30_000L, policy.response("site.test", 429, null))
    }

    @Test
    fun plain403And503DoNotBecomeIpBanOrHostCooldown() {
        assertEquals(0L, policy.response("site.test", 403, null))
        assertEquals(0L, policy.response("site.test", 503, null))
        assertEquals(0L, policy.beforeRequest("site.test"))
    }

    @Test
    fun parsesHttpDatesAndRejectsTrailingGarbage() {
        assertEquals(120_000L, MuguaRequestPolicy.retryAfterMillis("Wednesday, 21-Oct-15 07:30:00 GMT", wall))
        assertEquals(120_000L, MuguaRequestPolicy.retryAfterMillis("Wed Oct 21 07:30:00 2015", wall))
        assertEquals(0L, MuguaRequestPolicy.retryAfterMillis("Wed, 21 Oct 2015 07:27:00 GMT", wall))
        assertNull(MuguaRequestPolicy.retryAfterMillis("Wed, 21 Oct 2015 07:30:00 GMT extra", wall))
    }

    @Test
    fun overflowingNumericDelaysStayBlockedInsteadOfWrapping() {
        val huge = "99999999999999999999999999999999999"
        assertEquals(Long.MAX_VALUE, MuguaRequestPolicy.retryAfterMillis(huge, wall))
        assertEquals(Long.MAX_VALUE, policy.response("site.test", 429, huge))
        assertEquals(Long.MAX_VALUE, cooldown("site.test"))
    }

    @Test
    fun zeroRetryAfterStillPausesRapidRetries() {
        assertEquals(1000L, policy.response("site.test", 429, "0"))
    }

    @Test
    fun failedCoverRetriesAfterFiveMinutesAndDoesNotBlockOtherImages() {
        policy.coverFailed("a")
        assertFalse(policy.canFetchCover("a"))
        assertTrue(policy.canFetchCover("b"))
        now = MuguaRequestPolicy.COVER_FAILURE_MILLIS - 1
        assertFalse(policy.canFetchCover("a"))
        now++
        assertTrue(policy.canFetchCover("a"))
    }

    @Test
    fun successClearsFailureAndFailureStorageIsBounded() {
        policy.coverFailed("a")
        policy.coverSucceeded("a")
        assertTrue(policy.canFetchCover("a"))
        repeat(513) { policy.coverFailed("$it") }
        assertTrue(policy.canFetchCover("0"))
        assertFalse(policy.canFetchCover("512"))
    }

    private fun cooldown(host: String): Long = try {
        policy.beforeRequest(host)
        throw AssertionError("Expected host cooldown")
    } catch (error: MuguaCooldownException) {
        error.remainingMillis
    }
}
