package app.pact.tablet

import android.content.Context
import android.net.ConnectivityManager
import app.pact.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import tsship.Tsship

/** Pact's own userspace Tailscale node. It never touches Android's VPN slot. */
internal class TailnetBridge(private val context: Context) {
    class NeedsLogin(val url: String) : Exception("Tailnet login needed")

    private val token = File(context.filesDir, "ship/token").readText().trim()
    private val endpoint = URL(BuildConfig.SHIP_UPLOAD_URL).also {
        // Only a tailnet address in 100.64.0.0/10, reached through the node below, may receive the token.
        val bytes = InetAddress.getByName(it.host).address
        require(it.host.first().isDigit() && bytes.size == 4 && bytes[0].toInt() == 100 &&
            (bytes[1].toInt() and 255) in 64..127 && it.userInfo == null)
    }

    fun up(timeoutSeconds: Long) {
        val links = context.getSystemService(ConnectivityManager::class.java).let { cm ->
            cm.activeNetwork?.let { cm.getLinkProperties(it) }
        }
        val lines = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().joinToString("\n") { i ->
            val flags = buildString {
                if (i.isUp) append('u')
                if (i.isLoopback) append('l')
                if (i.isPointToPoint) append('p')
                if (i.supportsMulticast()) append('m')
            }.ifEmpty { "-" }
            val addrs = i.interfaceAddresses.joinToString(" ") {
                "${it.address.hostAddress?.substringBefore('%')}/${it.networkPrefixLength}"
            }
            "${i.name} ${i.index} ${i.mtu} $flags $addrs"
        }
        Tsship.setInterfaces(lines, links?.interfaceName ?: "")
        val state = File(context.filesDir, "tsnet").apply { mkdirs() }
        val key = File(context.filesDir, "ship/ts-authkey").takeIf { it.isFile }?.readText()?.trim() ?: ""
        try {
            Tsship.up(state.path, context.cacheDir.path, "${BuildConfig.SHIP_HOSTNAME}-pact", key, timeoutSeconds)
        } catch (e: Exception) {
            val url = Tsship.authURL()
            if (url.isNotEmpty()) throw NeedsLogin(url)
            throw IOException("Tailnet unavailable: ${e.message}")
        }
    }

    fun post(report: JSONObject) {
        val status = Tsship.post(endpoint.toString(), token, report.toString(), 20)
        if (status !in 200..299) throw IOException("Private receiver returned $status")
    }

    fun close() = Tsship.close()
}
