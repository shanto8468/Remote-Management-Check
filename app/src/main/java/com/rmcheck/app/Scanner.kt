package com.rmcheck.app

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

const val CONNECT_TIMEOUT_MS = 2000
const val READ_TIMEOUT_MS = 3000
const val PROBE_CONCURRENCY = 120   // simultaneous port probes
const val PING_CONCURRENCY = 24     // simultaneous pings
const val POOL_THREADS = 150

data class PortResult(val port: Int, val scheme: String, val info: String)

data class PingResult(val ok: Boolean, val ms: Double?, val available: Boolean = true)

object Scanner {

    private class HttpInfo(val isHttp: Boolean, val info: String)

    /** Routers usually have self-signed certificates, so certificates are not verified. */
    private val trustAllContext: SSLContext by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        ctx
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun httpGet(socket: Socket, host: String): HttpInfo {
        val request = "GET / HTTP/1.0\r\nHost: ${urlHost(host)}\r\nUser-Agent: router-probe\r\nConnection: close\r\n\r\n"
        socket.soTimeout = READ_TIMEOUT_MS
        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        val input = socket.getInputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (total < buf.size) {
            val n = try {
                input.read(buf, total, buf.size - total)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            total += n
            socket.soTimeout = 600   // the page title is usually in the first chunk, do not wait long for more
            val soFar = String(buf, 0, total, Charsets.ISO_8859_1)
            if (soFar.contains("</title>", ignoreCase = true) || soFar.contains("</head>", ignoreCase = true)) break
        }

        val text = String(buf, 0, total, Charsets.ISO_8859_1)
        if (!text.startsWith("HTTP/")) return HttpInfo(false, "")

        val headEnd = text.indexOf("\r\n\r\n")
        val head = if (headEnd >= 0) text.substring(0, headEnd) else text
        val body = if (headEnd >= 0) text.substring(headEnd + 4) else ""
        var server = ""
        var auth = ""
        for (line in head.split("\r\n").drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (key == "server") server = value
            else if (key == "www-authenticate") auth = value
        }
        val titleRegex = Regex("<title>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val title = titleRegex.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        val info = title.ifEmpty { server.ifEmpty { auth } }
            .replace(Regex("\\s+"), " ")
            .take(80)
        return HttpInfo(true, info)
    }

    /**
     * Checks one port. Returns null if the port is closed, otherwise the detected scheme:
     * "https" (TLS handshake worked), "http" (plain HTTP answered) or "other".
     */
    fun probe(host: String, port: Int): PortResult? {
        // 1) is the port open?
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        } catch (e: Exception) {
            return null
        }

        // 2) try HTTPS first (a successful TLS handshake means HTTPS)
        val tls = Socket()
        try {
            tls.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tls.soTimeout = READ_TIMEOUT_MS
            val ssl = trustAllContext.socketFactory.createSocket(tls, host, port, true) as SSLSocket
            try {
                ssl.enabledProtocols = ssl.supportedProtocols      // allow old TLS versions used by old routers
            } catch (e: Exception) {
                // keep defaults
            }
            try {
                ssl.enabledCipherSuites = ssl.supportedCipherSuites
            } catch (e: Exception) {
                // keep defaults
            }
            ssl.startHandshake()
            val info = try {
                httpGet(ssl, host).info
            } catch (e: Exception) {
                ""
            }
            return PortResult(port, "https", info)
        } catch (e: Exception) {
            // not TLS, try plain HTTP below
        } finally {
            closeQuietly(tls)
        }

        // 3) otherwise plain HTTP
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val r = httpGet(plain, host)
            if (r.isHttp) return PortResult(port, "http", r.info)
        } catch (e: Exception) {
            // no HTTP answer
        } finally {
            closeQuietly(plain)
        }
        return PortResult(port, "other", "")
    }

    /** Uses the phone's own ping command (no root needed on most devices). */
    fun ping(host: String, count: Int): PingResult {
        val cmd: List<String> = if (host.contains(':')) {
            if (File("/system/bin/ping6").exists()) {
                listOf("/system/bin/ping6", "-c", "$count", "-W", "2", host)
            } else {
                listOf("/system/bin/ping", "-6", "-c", "$count", "-W", "2", host)
            }
        } else {
            listOf("/system/bin/ping", "-c", "$count", "-W", "2", host)
        }
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroy()
                PingResult(false, null)
            } else {
                val out = p.inputStream.bufferedReader().readText().lowercase()
                val times = Regex("time[=<]([0-9.]+)").findAll(out)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .toList()
                val ok = p.exitValue() == 0 && (out.contains("ttl=") || out.contains("hlim=") || times.isNotEmpty())
                PingResult(ok, if (times.isEmpty()) null else times.average())
            }
        } catch (e: java.io.IOException) {
            PingResult(false, null, available = false)   // no ping command on this device
        } catch (e: Exception) {
            PingResult(false, null)
        }
    }
}
