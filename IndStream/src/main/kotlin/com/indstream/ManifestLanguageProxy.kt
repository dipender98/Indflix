package com.indstream

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object ManifestLanguageProxy {

    private val manifests = ConcurrentHashMap<String, String>()
    private const val MAX_MANIFESTS = 2048
    private val serverLock = Any()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    @Volatile
    private var host: String = "localhost"

    fun urlFor(master: String, baseUrl: String, language: String? = null): String? {
        val target = language?.trim()?.takeIf { it.isNotBlank() }
        val rewritten = rewrittenManifest(master, baseUrl, target) ?: return null
        val boundPort = ensureServer() ?: return null
        val key = encodeKey(baseUrl + '\u0000' + target.orEmpty())
        manifests[key] = rewritten
        if (manifests.size > MAX_MANIFESTS) {
            val it = manifests.keys.iterator()
            while (manifests.size > MAX_MANIFESTS && it.hasNext()) {
                val candidate = it.next()
                if (candidate != key) it.remove()
            }
        }
        return "http://${host}:$boundPort/$key.m3u8"
    }

    internal fun declaredLanguages(master: String): List<String> {
        val out = LinkedHashSet<String>()
        for (line in master.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#EXT-X-MEDIA:", true) ||
                (!trimmed.contains("TYPE=AUDIO", true) && !trimmed.contains("""TYPE="AUDIO"""", true))
            ) continue
            languageOf(attributesOf(trimmed))?.let { out += it }
        }
        return out.toList()
    }

    internal fun rewrittenManifest(
        master: String,
        baseUrl: String,
        onlyLanguage: String? = null,
    ): String? {
        if (!master.contains("#EXT-X-MEDIA:", true)) return null
        val rebuilt = ArrayList<String>()
        var declared = 0
        for (rawLine in master.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-MEDIA:", true) &&
                (trimmed.contains("TYPE=AUDIO", true) || trimmed.contains("""TYPE="AUDIO"""", true))
            ) {
                val attrs = attributesOf(trimmed)
                val resolved = languageOf(attrs)
                if (onlyLanguage != null) {
                    if (resolved != onlyLanguage) continue
                    var next = absolutizeUris(line, baseUrl)
                    next = setAttribute(next, "LANGUAGE", resolved)
                    next = setAttribute(next, "DEFAULT", "YES", quoted = false)
                    next = setAttribute(next, "AUTOSELECT", "YES", quoted = false)
                    rebuilt += next
                    declared++
                    continue
                }
                val declaredTag = attrs["LANGUAGE"]
                val replaceable = declaredTag == null || ManifestKit.isUndeterminedLanguage(declaredTag)
                var next = absolutizeUris(line, baseUrl)
                if (replaceable && resolved != null) {
                    next = setAttribute(next, "LANGUAGE", resolved)
                    declared++
                }
                rebuilt += next
            } else if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                rebuilt += absolutizeUris(line, baseUrl)
            } else {
                rebuilt += ManifestKit.resolveUrl(baseUrl, trimmed)
            }
        }
        return if (declared > 0) rebuilt.joinToString("\n") else null
    }

    private fun attributesOf(line: String): Map<String, String> =
        ATTR.findAll(line).associate { m ->
            m.groupValues[1].uppercase() to m.groupValues[2].ifEmpty { m.groupValues[3] }
        }

    private fun languageOf(attrs: Map<String, String>): String? {
        val declared = attrs["LANGUAGE"]
        if (!declared.isNullOrBlank() && !ManifestKit.isUndeterminedLanguage(declared)) {
            LinkNaming.languageCode(declared)?.let { return it }
        }
        return LinkNaming.languageCode(attrs["NAME"])
    }

    private fun reachableHost(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "localhost"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isMulticastAddress) continue
                    val host = addr.hostAddress?.substringBefore('%') ?: continue
                    if (host.contains(':')) continue
                    return host
                }
            }
        } catch (t: Throwable) {
            Unit
        }
        return "localhost"
    }

    private fun ensureServer(): Int? {
        port.takeIf { it > 0 }?.let { return it }
        return synchronized(serverLock) {
            port.takeIf { it > 0 }?.let { return@synchronized it }
            val socket = try {
                ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
            } catch (t: Throwable) {
                null
            } ?: return@synchronized null
            server = socket
            port = socket.localPort
            host = reachableHost()
            Thread({ serve(socket) }, "manifest-language-proxy").apply { isDaemon = true }.start()
            socket.localPort
        }
    }

    private fun serve(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                return
            }
            Thread({ respond(client) }, "manifest-language-proxy-client").apply {
                isDaemon = true
            }.start()
        }
    }

    private fun respond(client: java.net.Socket) {
        try {
            client.soTimeout = 3_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
            val request = reader.readLine()?.takeIf { it.isNotBlank() } ?: return
            val method = request.substringBefore(' ')
            val key = request.split(' ').getOrNull(1).orEmpty().substringBefore('?').trim('/')
                .substringBefore('.')
            val out = client.getOutputStream()
            val body = manifests[key]
            when {
                body != null -> {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/vnd.apple.mpegurl\r\n")
                        append("Content-Length: ").append(bytes.size).append("\r\n")
                        append("Cache-Control: no-store\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(head.toByteArray(StandardCharsets.US_ASCII))
                    if (!method.equals("HEAD", true)) out.write(bytes)
                }
                else -> {
                    val original = decodeKey(key)?.substringBefore('\u0000')
                    val head = if (original != null && original.startsWith("http")) {
                        "HTTP/1.1 302 Found\r\nLocation: $original\r\nContent-Length: 0\r\n" +
                            "Connection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                    out.write(head.toByteArray(StandardCharsets.US_ASCII))
                }
            }
            out.flush()
        } catch (t: Throwable) {
            Unit
        } finally {
            try {
                client.close()
            } catch (t: Throwable) {
                Unit
            }
        }
    }

    private fun absolutizeUris(line: String, baseUrl: String): String =
        URI_ATTR.replace(line) { m ->
            m.groupValues[1] + ManifestKit.resolveUrl(baseUrl, m.groupValues[2]) + m.groupValues[3]
        }

    private fun setAttribute(line: String, key: String, value: String, quoted: Boolean = true): String {
        val quotedAttr = Regex("""(\b$key\s*=\s*")[^"]*(")""", RegexOption.IGNORE_CASE)
        if (quotedAttr.containsMatchIn(line)) {
            return quotedAttr.replace(line) { m -> m.groupValues[1] + value + m.groupValues[2] }
        }
        val bareAttr = Regex("""\b$key\s*=\s*[^,\s]+""", RegexOption.IGNORE_CASE)
        if (bareAttr.containsMatchIn(line)) {
            return bareAttr.replace(line) { "$key=$value" }
        }
        return if (quoted) "$line,$key=\"$value\"" else "$line,$key=$value"
    }

    private fun encodeKey(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(chars)
    }

    private fun decodeKey(key: String): String? {
        if (key.isEmpty() || key.length % 2 != 0) return null
        val bytes = ByteArray(key.length / 2)
        for (i in bytes.indices) {
            val high = Character.digit(key[i * 2], 16)
            val low = Character.digit(key[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private const val HEX = "0123456789abcdef"
    private val ATTR = Regex("""(?:^|,)\s*([A-Z0-9-]+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)
    private val URI_ATTR = Regex("""(\bURI\s*=\s*")([^"]*)(")""", RegexOption.IGNORE_CASE)
}
