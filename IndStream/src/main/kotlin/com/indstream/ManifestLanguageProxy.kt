package com.indstream

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object ManifestLanguageProxy {

    private val manifests = ConcurrentHashMap<String, String>()
    private val serverLock = Any()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = -1

    fun urlFor(master: String, baseUrl: String): String? {
        val rewritten = rewrittenManifest(master, baseUrl) ?: return null
        val boundPort = ensureServer() ?: return null
        val key = encodeKey(baseUrl)
        manifests[key] = rewritten
        return "http://127.0.0.1:$boundPort/$key.m3u8"
    }

    internal fun rewrittenManifest(master: String, baseUrl: String): String? {
        if (!master.contains("#EXT-X-MEDIA:", true)) return null
        val rebuilt = ArrayList<String>()
        var declared = 0
        for (rawLine in master.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-MEDIA:", true) && trimmed.contains("TYPE=AUDIO", true)) {
                val attrs = ATTR.findAll(trimmed)
                    .associate { it.groupValues[1].uppercase() to it.groupValues[2] }
                val current = attrs["LANGUAGE"]
                val replaceable = current == null || ManifestKit.isUndeterminedLanguage(current)
                val code = if (replaceable) LinkNaming.languageCode(attrs["NAME"]) else null
                var next = absolutizeUris(line, baseUrl)
                if (code != null) {
                    next = setAttribute(next, "LANGUAGE", code)
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

    private fun ensureServer(): Int? {
        port.takeIf { it > 0 }?.let { return it }
        return synchronized(serverLock) {
            port.takeIf { it > 0 }?.let { return@synchronized it }
            val socket = try {
                ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            } catch (t: Throwable) {
                null
            } ?: return@synchronized null
            server = socket
            port = socket.localPort
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
                    val original = decodeKey(key)
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

    private fun setAttribute(line: String, key: String, value: String): String {
        val pattern = Regex("""(\b$key\s*=\s*")[^"]*(")""", RegexOption.IGNORE_CASE)
        return if (pattern.containsMatchIn(line)) {
            pattern.replace(line) { m -> m.groupValues[1] + value + m.groupValues[2] }
        } else "$line,$key=\"$value\""
    }

    private fun encodeKey(url: String): String {
        val bytes = url.toByteArray(StandardCharsets.UTF_8)
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX[value ushr 4]
            chars[i * 2 + 1] = HEX[value and 0x0f]
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
    private val ATTR = Regex("""(?:^|,)\s*([A-Z0-9-]+)\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val URI_ATTR = Regex("""(\bURI\s*=\s*")([^"]*)(")""", RegexOption.IGNORE_CASE)
}
