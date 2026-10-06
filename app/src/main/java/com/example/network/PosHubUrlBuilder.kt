package com.example.network

/**
 * Helper to build safe, normalized POS Hub URLs (Section 13)
 * Guarantees correct handling of slashes and schemes without //posHub or missing /posHub.
 */
object PosHubUrlBuilder {

    /**
     * Builds HTTP URL for POS Hub: e.g. http://192.168.68.104:5000/posHub
     */
    fun buildPosHubUrl(serverUrl: String): String {
        val trimmed = serverUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/posHub", ignoreCase = true)) {
            trimmed
        } else {
            "$trimmed/posHub"
        }
    }

    /**
     * Builds ASP.NET Core SignalR Negotiation URL (Section 15):
     * e.g. http://192.168.68.104:5000/posHub/negotiate?negotiateVersion=1
     */
    fun buildNegotiateUrl(serverUrl: String): String {
        val base = buildPosHubUrl(serverUrl)
        return "$base/negotiate?negotiateVersion=1"
    }

    /**
     * Builds WebSocket URL (ws:// or wss://) with optional connectionToken from negotiation:
     * e.g. ws://192.168.68.104:5000/posHub?id=TOKEN
     */
    fun buildWebSocketUrl(serverUrl: String, connectionToken: String? = null): String {
        val base = buildPosHubUrl(serverUrl)
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) -> base.replaceFirst("https://", "wss://", ignoreCase = true)
            base.startsWith("http://", ignoreCase = true) -> base.replaceFirst("http://", "ws://", ignoreCase = true)
            else -> "ws://$base"
        }
        return if (!connectionToken.isNullOrBlank()) {
            if (wsBase.contains("?")) "$wsBase&id=$connectionToken" else "$wsBase?id=$connectionToken"
        } else {
            wsBase
        }
    }
}
