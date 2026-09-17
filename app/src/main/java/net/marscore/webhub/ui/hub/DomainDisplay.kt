package net.marscore.webhub.ui.hub

import java.net.URI

object DomainDisplay {
    fun of(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return url
            val defaultPort = when (uri.scheme?.lowercase()) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
            if (uri.port == -1 || uri.port == defaultPort) host else "$host:${uri.port}"
        } catch (_: Exception) {
            url
        }
    }
}
