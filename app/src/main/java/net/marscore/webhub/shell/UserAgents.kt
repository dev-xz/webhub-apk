package net.marscore.webhub.shell

/**
 * User-Agent string mapping for the four UA modes (task 5.8, revamp-hub-ux D8).
 *
 * - `default`  → null (leave WebView's default UA untouched)
 * - `mobile`   → Android phone Chrome UA containing the ` Mobile ` token
 * - `tablet`   → the same Android Chrome UA with the ` Mobile ` token removed (Chrome tablet convention)
 * - `desktop`  → desktop Chrome UA (Windows NT 10.0; Win64; x64)
 *
 * Kept as a pure, Android-free object so the mapping is unit-testable on a plain JVM.
 *
 * Per D8 the Android platform segment uses the frozen Chrome convention `Linux; Android 10; K`
 * (the `K` device token is what current stable Chrome ships to confound fingerprinting), and the
 * Chrome version is bumped to a 2026-era frozen value. The structure (Mobile token presence,
 * platform segment) is what site UA sniffing keys on; the exact version is not load-bearing.
 */
internal object UserAgents {

    private const val CHROME_VERSION = "142.0.0.0"

    /** Android phone Chrome UA. Contains ` Mobile ` per the UA-CH / Chrome phone convention. */
    val mobile: String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_VERSION Mobile Safari/537.36"

    /** Android tablet Chrome UA: same as [mobile] with the ` Mobile ` token removed. */
    val tablet: String = mobile.replace(" Mobile ", " ")

    /** Desktop Chrome UA (Windows 10). */
    val desktop: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROME_VERSION Safari/537.36"

    /**
     * @return the UA string for [uaMode], or null when [uaMode] is `"default"` (caller should leave
     *         the WebView's default UA untouched). Unknown modes also yield null.
     */
    fun forMode(uaMode: String): String? = when (uaMode) {
        "mobile" -> mobile
        "tablet" -> tablet
        "desktop" -> desktop
        else -> null // "default" or anything unknown → don't touch the default UA
    }
}