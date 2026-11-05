package eu.kanade.tachiyomi.animeextension.all.x1hub

import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class CooldownException(val remainingMillis: Long) :
    IOException(
        "網站要求暫停請求，請約 ${remainingMillis / 1000 + if (remainingMillis % 1000 > 0) 1 else 0} 秒後再試。",
    )

internal class RequestPolicy(
    private val intervalMillis: () -> Long = { 500L },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private data class HostState(
        var nextRequest: Long = Long.MIN_VALUE,
        var blockedUntil: Long = Long.MIN_VALUE,
        var blocks: Int = 0,
    )

    private val hosts = mutableMapOf<String, HostState>()

    @Synchronized
    fun beforeRequest(host: String): Long {
        val state = hosts.getOrPut(host) { HostState() }
        val now = nowMillis()
        val cooldown = remaining(state.blockedUntil, now)
        if (cooldown > 0) throw CooldownException(cooldown)
        val wait = remaining(state.nextRequest, now)
        if (wait == 0L) state.nextRequest = deadline(now, intervalMillis().coerceAtLeast(500))
        return wait
    }

    @Synchronized
    fun response(host: String, status: Int, retryAfter: String?): Long {
        val state = hosts.getOrPut(host) { HostState() }
        val now = nowMillis()
        if (status == 429 || (status == 503 && retryAfter != null)) {
            val fallback = minOf(30_000L * (1L shl state.blocks.coerceAtMost(5)), 900_000L)
            val wait = (retryAfterMillis(retryAfter, wallMillis()) ?: fallback).coerceAtLeast(1000)
            state.blockedUntil = maxOf(state.blockedUntil, deadline(now, wait))
            state.blocks = (state.blocks + 1).coerceAtMost(5)
            return remaining(state.blockedUntil, now)
        }
        if (status in 200..299 && state.blockedUntil <= now) state.blocks = 0
        return 0
    }

    companion object {
        private fun deadline(now: Long, duration: Long): Long = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration

        private fun remaining(until: Long, now: Long): Long = if (until <= now) 0 else (until - now).takeIf { it >= 0 } ?: Long.MAX_VALUE

        internal fun retryAfterMillis(value: String?, wallMillis: Long): Long? {
            val header = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (header.all { it in '0'..'9' }) {
                val seconds = header.toLongOrNull() ?: return Long.MAX_VALUE
                return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
            }
            for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy")) {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }
                val position = ParsePosition(0)
                val date = parser.parse(header, position) ?: continue
                if (position.index == header.length) return remaining(date.time, wallMillis)
            }
            return null
        }
    }
}
