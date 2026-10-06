package com.example.network

import java.net.URI

data class NormalizedUrl(
    val fullUrl: String,
    val host: String,
    val port: Int,
    val tls: Boolean
)

/**
 * Normalizes user-input server URLs according to TaloolaPos V100 specification.
 *
 * Examples:
 * - 192.168.68.104:5000 -> http://192.168.68.104:5000
 * - 192.168.68.104 -> http://192.168.68.104:5000 (default port 5000)
 * - http://192.168.68.104:5000/ -> http://192.168.68.104:5000
 * - https://192.168.68.104:5000 -> https://192.168.68.104:5000
 */
object UrlNormalizer {

    const val DEFAULT_PORT = 5000

    fun normalize(input: String): NormalizedUrl? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // Add scheme if missing
        val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            "http://$trimmed"
        } else {
            trimmed
        }

        return try {
            val uri = URI(withScheme)
            val host = uri.host
                ?: withScheme.substringAfter("://").substringBefore(":").substringBefore("/")

            if (host.isBlank()) return null

            val tls = uri.scheme?.equals("https", ignoreCase = true) == true
            val scheme = if (tls) "https" else "http"

            val port = if (uri.port != -1) {
                uri.port
            } else {
                // Check if port is in authority string
                val afterHost = withScheme.substringAfter("://$host", "")
                if (afterHost.startsWith(":")) {
                    afterHost.substringAfter(":").substringBefore("/").toIntOrNull() ?: DEFAULT_PORT
                } else {
                    DEFAULT_PORT
                }
            }

            val fullUrl = "$scheme://$host:$port"
            NormalizedUrl(
                fullUrl = fullUrl,
                host = host,
                port = port,
                tls = tls
            )
        } catch (e: Exception) {
            // Regex fallback
            try {
                val clean = trimmed.removePrefix("http://").removePrefix("https://").removeSuffix("/")
                val host = clean.substringBefore(":")
                val port = clean.substringAfter(":", "$DEFAULT_PORT").toIntOrNull() ?: DEFAULT_PORT
                val tls = trimmed.startsWith("https://", ignoreCase = true)
                val scheme = if (tls) "https" else "http"
                NormalizedUrl(
                    fullUrl = "$scheme://$host:$port",
                    host = host,
                    port = port,
                    tls = tls
                )
            } catch (e2: Exception) {
                null
            }
        }
    }
}
