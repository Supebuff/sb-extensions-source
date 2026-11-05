package eu.kanade.tachiyomi.animeextension.all.x1hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestPolicyTest {
    @Test
    fun spacesRequestsByHostAndAppliesTheSelectedInterval() {
        var now = 0L
        var interval = 500L
        val policy = RequestPolicy({ interval }, { now })
        assertEquals(0L, policy.beforeRequest("site.test"))
        assertEquals(500L, policy.beforeRequest("site.test"))
        assertEquals(0L, policy.beforeRequest("other.test"))
        now = 500
        interval = 2000
        assertEquals(0L, policy.beforeRequest("site.test"))
        assertEquals(2000L, policy.beforeRequest("site.test"))
    }

    @Test
    fun honorsRetryAfterAndKeepsTheLongerExistingDeadline() {
        var now = 0L
        val policy = RequestPolicy(nowMillis = { now })
        assertEquals(120_000L, policy.response("site.test", 429, "120"))
        assertEquals(120_000L, policy.response("site.test", 503, "5"))
        assertThrows(CooldownException::class.java) { policy.beforeRequest("site.test") }
        assertEquals(0L, policy.beforeRequest("other.test"))
        now = 120_000
        assertEquals(0L, policy.beforeRequest("site.test"))
    }

    @Test
    fun missingRetryAfterBacksOffAndSuccessfulResponsesResetTheBackoff() {
        var now = 0L
        val policy = RequestPolicy(nowMillis = { now })
        assertEquals(30_000L, policy.response("site.test", 429, null))
        now += 30_000
        assertEquals(60_000L, policy.response("site.test", 429, "bad"))
        now += 60_000
        assertEquals(0L, policy.response("site.test", 200, null))
        assertEquals(30_000L, policy.response("site.test", 429, null))
        assertEquals(0L, policy.response("other.test", 503, null))
    }

    @Test
    fun parsesSecondsAndHttpDatesAndRejectsTrailingGarbage() {
        assertEquals(12_000L, RequestPolicy.retryAfterMillis("12", 0))
        assertEquals(60_000L, RequestPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertNull(RequestPolicy.retryAfterMillis("Thu, 01 Jan 1970 00:01:00 GMT invalid", 0))
        assertNull(RequestPolicy.retryAfterMillis("-1", 0))
        assertEquals(Long.MAX_VALUE, RequestPolicy.retryAfterMillis("99999999999999999999", 0))
    }

    @Test
    fun hugeServerDelaysCannotOverflowAndClearTheCooldown() {
        val policy = RequestPolicy(nowMillis = { 10L })
        assertEquals(Long.MAX_VALUE - 10, policy.response("site.test", 429, "99999999999999999999"))
        assertThrows(CooldownException::class.java) { policy.beforeRequest("site.test") }
    }
}
