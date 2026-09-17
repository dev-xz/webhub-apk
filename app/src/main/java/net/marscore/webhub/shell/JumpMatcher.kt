package net.marscore.webhub.shell

import net.marscore.webhub.data.ChildApp
import java.net.URI

/**
 * Pure-JVM URL prefix matcher used by the external jump router.
 *
 * Given an external target URL and the current list of child apps, returns the single best-matching
 * child or null. Matching rules (see openspec change `external-jump-scheme`, design D3 + Q1):
 *
 * 1. Parse target and each child URL with [java.net.URI]. Unparseable URLs, or URLs missing a
 *    scheme/host, drop out of candidacy (target unparseable => null result).
 * 2. Origin equality: scheme equal ignoring case; host equal ignoring case; port equal after
 *    normalizing -1 to the scheme default (80 for http, 443 for https).
 * 3. Path prefix match using the complete-path-segment rule: normalize both target and child paths
 *    by treating null/empty as "/" and stripping a single trailing "/"; then the normalized child
 *    path `c` matches normalized target path `t` iff `c` is empty (root, matches anything) OR
 *    `t == c` OR `t.startsWith("$c/")`. Query and fragment do NOT participate.
 * 4. Among all matching candidates pick the longest normalized child path; tie-break by smallest
 *    [ChildApp.createdAt] (oldest); further tie-break by smallest [ChildApp.id] (deterministic).
 *
 * Has no Android dependencies so it can be unit-tested on a plain JVM.
 */
object JumpMatcher {

    fun match(targetUrl: String, children: List<ChildApp>): ChildApp? {
        val target = parseOrNull(targetUrl) ?: return null
        if (target.scheme.isNullOrBlank() || target.host.isNullOrBlank()) return null

        val targetScheme = target.scheme.lowercase()
        val targetHost = target.host.lowercase()
        val targetPort = normalizedPort(target)
        val targetPath = normalizePath(target.path).lowercase()

        var best: ChildApp? = null
        var bestPath: String? = null

        for (child in children) {
            val cu = parseOrNull(child.url) ?: continue
            val cs = cu.scheme
            val ch = cu.host
            if (cs.isNullOrBlank() || ch.isNullOrBlank()) continue

            if (!cs.equals(targetScheme, ignoreCase = true)) continue
            if (!ch.equals(targetHost, ignoreCase = true)) continue
            if (normalizedPort(cu) != targetPort) continue

            val childPath = normalizePath(cu.path).lowercase()
            if (!pathPrefixMatch(targetPath, childPath)) continue

            if (best == null) {
                best = child
                bestPath = childPath
                continue
            }
            val bp = bestPath!!
            val cmp = compareCandidate(childPath, child, bp, best)
            if (cmp < 0) {
                best = child
                bestPath = childPath
            }
        }
        return best
    }

    private fun compareCandidate(
        newPath: String, newChild: ChildApp,
        bestPath: String, bestChild: ChildApp,
    ): Int {
        // Longer child path wins -> descending by length.
        if (newPath.length != bestPath.length) {
            return bestPath.length - newPath.length
        }
        // Oldest createdAt wins -> ascending.
        if (newChild.createdAt != bestChild.createdAt) {
            return newChild.createdAt.compareTo(bestChild.createdAt)
        }
        // Smallest id wins -> ascending.
        return newChild.id.compareTo(bestChild.id)
    }

    private fun parseOrNull(url: String): URI? = try {
        URI(url)
    } catch (e: Exception) {
        null
    }

    private fun normalizedPort(uri: URI): Int {
        val p = uri.port
        if (p != -1) return p
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /** Treat null/empty as "/"; strip a single trailing "/" if present. */
    private fun normalizePath(path: String?): String {
        val p = if (path.isNullOrBlank()) "/" else path
        return if (p.length > 1 && p.endsWith("/")) p.substring(0, p.length - 1) else p
    }

    private fun pathPrefixMatch(targetPath: String, childPath: String): Boolean {
        // After normalization, childPath "/" becomes "" (empty). Empty child path matches any target.
        val c = if (childPath == "/") "" else childPath
        if (c.isEmpty()) return true
        val t = if (targetPath == "/") "" else targetPath
        return t == c || t.startsWith("$c/")
    }
}