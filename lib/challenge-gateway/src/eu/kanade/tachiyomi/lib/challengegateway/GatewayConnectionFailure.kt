package eu.kanade.tachiyomi.lib.challengegateway

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Stable, sanitized diagnostics; raw exception text can contain private URLs. */
internal class GatewayConnectionFailure(error: IOException) {
    private val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    private fun mentions(vararg values: String): Boolean = causes.any { cause ->
        values.any { cause.message.orEmpty().contains(it, ignoreCase = true) }
    }

    val code: String = when {
        mentions("CLEARTEXT") -> "HTTP_BLOCKED"
        mentions("EACCES", "EPERM", "Permission denied") -> "ACCESS_DENIED"
        causes.any { it is UnknownHostException } -> "DNS_FAILED"
        causes.any { it is SSLException } -> "TLS_FAILED"
        causes.any { it is NoRouteToHostException } -> "NO_ROUTE"
        causes.any { it is ProtocolException } -> "HTTP_PROTOCOL"
        causes.any { it is SocketTimeoutException || it is InterruptedIOException } -> "TIMEOUT"
        causes.any { it is ConnectException } -> "CONNECT_FAILED"
        causes.any { it is EOFException } || mentions("unexpected end of stream") -> "CONNECTION_CLOSED"
        causes.any { it is SocketException } && mentions("reset", "broken pipe", "socket closed") -> "CONNECTION_RESET"
        else -> "IO_FAILED"
    }

    // Retry only recognizable TCP/connection failures. Never replay a slow browser fetch,
    // certificate/policy failure, DNS failure, or a coroutine cancellation.
    val recoverable: Boolean = code in setOf("CONNECT_FAILED", "CONNECTION_CLOSED", "CONNECTION_RESET")

    fun exception(error: IOException): IOException {
        val hint = when (code) {
            "HTTP_BLOCKED" -> "App 拒絕 HTTP 明文連線，請使用受信任的 HTTPS 網關。"
            "ACCESS_DENIED" -> "系統拒絕此 App 連線，請檢查 App 的網路權限及 VPN／過濾設定。"
            "DNS_FAILED" -> "無法解析網關或代理主機，請檢查服務位址與網路設定。"
            "TLS_FAILED" -> "TLS 驗證失敗，請核對 http／https 與服務憑證。"
            "NO_ROUTE" -> "找不到通往網關的網路路徑，請確認目前網路可連到電腦。"
            "HTTP_PROTOCOL" -> "服務回應不符合 HTTP 協定，請核對服務位址、連接埠及代理設定。"
            "TIMEOUT" -> "網關請求逾時，請稍後刷新並確認電腦與服務仍在線。"
            "CONNECT_FAILED" -> "無法建立網關連線，請確認電腦在線、服務位址及連接埠。"
            "CONNECTION_CLOSED", "CONNECTION_RESET" -> "網關連線中斷，請稍後刷新。"
            else -> "網關通訊失敗，請確認服務狀態後重試。"
        }
        return IOException("網關連線失敗 [$code]：$hint", error)
    }
}
