package net.marscore.webhub.shell

/**
 * The JS snippet injected in `WebAppActivity.onPageFinished` to read the
 * page's `<meta name="theme-color">` (including `media="(prefers-color-scheme:
 * light|dark)"` variants) and hand the chosen color back to Kotlin via the
 * `__webHubThemeBridge` JavascriptInterface.
 *
 * OpenSpec improve-webapp-shell-ux task 2.2 / design D2.
 *
 * The snippet is extracted into a pure function so the JS string itself is
 * unit-testable (structural assertions) without standing up a real WebView —
 * mirrors the `TaskIconResolver` / `ThemeColorReader` testability pattern.
 *
 * Behavior:
 *  - Collects every `<meta name="theme-color">` element.
 *  - Prefers the variant whose `media` matches `window.matchMedia` for the
 *    current `prefers-color-scheme`; if none matches, falls back to the
 *    no-`media` variant.
 *  - Calls `__webHubThemeBridge.onThemeColor(contentOrNull)` exactly once.
 *  - Self-guarded with `try/catch` so a JS error never breaks page loading.
 */
internal object ThemeColorJs {

    /** Name of the JS bridge object registered on the WebView. */
    const val bridgeName = "__webHubThemeBridge"

    /**
     * The self-contained JS snippet. Suitable for direct
     * `webView.evaluateJavascript(jsSnippet(), null)`.
     */
    fun jsSnippet(): String = """
        (function() {
            try {
                var metas = document.querySelectorAll('meta[name="theme-color"]');
                if (!metas || metas.length === 0) {
                    __webHubThemeBridge.onThemeColor(null);
                    return;
                }
                var darkMq = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
                var lightMq = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)');
                var noMedia = null;
                var darkVariant = null;
                var lightVariant = null;
                for (var i = 0; i < metas.length; i++) {
                    var m = metas[i];
                    var content = m.getAttribute('content');
                    if (!content) continue;
                    var media = m.getAttribute('media');
                    if (!media) {
                        noMedia = content;
                    } else if (darkMq && media.indexOf('dark') !== -1) {
                        darkVariant = content;
                    } else if (lightMq && media.indexOf('light') !== -1) {
                        lightVariant = content;
                    }
                }
                var picked = null;
                if (darkMq && darkMq.matches && darkVariant) {
                    picked = darkVariant;
                } else if (lightMq && lightMq.matches && lightVariant) {
                    picked = lightVariant;
                } else {
                    picked = noMedia;
                }
                __webHubThemeBridge.onThemeColor(picked);
            } catch (e) {
                __webHubThemeBridge.onThemeColor(null);
            }
        })();
    """.trimIndent()
}