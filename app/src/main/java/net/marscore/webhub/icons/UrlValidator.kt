package net.marscore.webhub.icons

import java.net.URI
import java.net.URISyntaxException

/**
 * Validates and normalizes child-app URLs. Accepts only http/https with a non-empty valid host.
 * Normalizes by ensuring exactly one trailing slash on the path (matching the reference project's
 * convention) so stored URLs are stable.
 *
 * Returns null for invalid input; callers surface their own error message.
 */
object UrlValidator {

    private val allowedSchemes = setOf("http", "https")

    /**
     * @return the normalized URL string, or null when [input] is not a valid http/https URL with a host.
     */
    fun normalize(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        // Require an explicit scheme; bare hosts like "example.com" are rejected.
        val scheme = try {
            URI(trimmed).scheme?.lowercase()
        } catch (e: URISyntaxException) {
            return null
        }
        if (scheme !in allowedSchemes) return null

        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }

        val host = uri.host
        if (host.isNullOrBlank()) return null
        // Reject hosts that are obviously not domain names / IPs (e.g. contain spaces).
        if (!isValidHost(host)) return null

        return buildNormalized(uri, host)
    }

    private fun buildNormalized(uri: URI, host: String): String {
        val scheme = uri.scheme.lowercase()
        val port = uri.port.takeIf { it > 0 && it != defaultPort(scheme) } ?: -1
        var path = uri.rawPath ?: ""
        val query = uri.rawQuery
        val fragment = uri.rawFragment

        // Normalize path: non-empty paths keep their content but gain a trailing slash if missing;
        // empty path becomes "/". This yields a stable canonical form.
        path = when {
            path.isEmpty() -> "/"
            !path.endsWith("/") -> "$path/"
            else -> path
        }

        val sb = StringBuilder()
        sb.append(scheme).append("://").append(host)
        if (port > 0) sb.append(":").append(port)
        sb.append(path)
        if (!query.isNullOrBlank()) sb.append("?").append(query)
        if (!fragment.isNullOrBlank()) sb.append("#").append(fragment)
        return sb.toString()
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80

    private fun isValidHost(host: String): Boolean {
        if (host.isBlank()) return false
        // Disallow whitespace and control chars anywhere.
        if (host.any { it.isWhitespace() || it.code < 0x21 }) return false
        // Domain labels cannot be empty; a single trailing dot (root) is allowed by RFC but we
        // reject pure "." to keep things sane.
        val trimmed = host.trimEnd('.')
        if (trimmed.isBlank()) return false
        // Each label non-empty, and the whole thing must contain at least one dot for a domain
        // OR be a valid IPv4 / bracketed IPv6 handled by URI.getHost already.
        val labels = trimmed.split('.')
        if (labels.any { it.isBlank() }) return false
        // Accept IPv4 literals (URI already stripped brackets from IPv6).
        if (isIpv4(trimmed)) return true
        // Require at least one dot for a domain name (e.g. "localhost" alone rejected as too loose
        // for a web app target — keeps user input explicit).
        return labels.size >= 2
    }

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.toIntOrNull()?.let { it in 0..255 } == true && !p.startsWith('0') || p == "0"
        }
    }

    /** Convenience: true iff [input] normalizes to a non-null value. */
    fun isValid(input: String?): Boolean = normalize(input) != null
}