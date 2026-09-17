package net.marscore.webhub.shell

import android.content.Context
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature

/**
 * WebView Profile isolation (task 5.2).
 *
 * Per-child profiles via androidx WebKit's Profile API give each child its own
 * cookie/localStorage/IndexedDB/cache/geolocation partition. Profile name = `child_<id>`.
 *
 * Runtime detection: `WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)` gates the
 * whole feature; when unsupported we degrade to the shared default profile (documented behavior,
 * surfaced as a Hub-lane banner) and clear data is best-effort on the default profile.
 *
 * NOTE on `androidx.webkit` 1.12.1 API surface (verified against the actual jar):
 *   - `WebViewCompat.setProfile(WebView, String)` and `WebViewCompat.getProfile(WebView)` exist.
 *   - `ProfileStore.getInstance().getOrCreateProfile(name)` returns a `Profile`.
 *   - `Profile` exposes `getCookieManager()` (android.webkit.CookieManager) and
 *     `getWebStorage()` (android.webkit.WebStorage).
 *   - `ProfileStore.deleteProfile(name)` exists; it **fails** if any WebView using that profile is
 *     still alive — callers must finish the shell first, and we fall back to clearBrowsingData on
 *     failure (task 6.2 / the Hub lane's delete path).
 *   - There is **no** `WebStorageCompat` class in webkit 1.12.1, so we clear via the framework
 *     `WebStorage.deleteAllData()` / `CookieManager.removeAllCookies(ValueCallback)` returned by
 *     the profile. This is the deviation from the task spec's `WebStorageCompat` wording — see the
 *     final report.
 */
object ProfileManager {

    /** Whether the device WebView supports per-child profiles. */
    fun isMultiProfileSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Whether ServiceWorker request interception (`setServiceWorkerClient`) is supported on this
     * device WebView. Used to gate the SW cookie-compensation shim — without this we can't install
     * a [android.webkit.ServiceWorkerClient], and the SW fetch 401 stays unfixable here.
     */
    fun isServiceWorkerInterceptSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)

    /** Stable profile name for [childId]: `child_<id>`. */
    fun profileNameFor(childId: Long): String = "child_$childId"

    /**
     * Resolve the [ServiceWorkerController] bound to [childId]'s profile.
     *
     * Returns null in three cases:
     *  - MULTI_PROFILE unsupported (degraded to the default profile) AND SW interception
     *    unsupported — no controller reachable.
     *  - The profile lookup throws (OEM quirks).
     *  - SW interception itself is unsupported (`SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST`).
     *
     * Caller must guard with [isServiceWorkerInterceptSupported] before using the returned
     * controller's `setServiceWorkerClient`.
     *
     * When multi-profile is unsupported, the WebView uses the framework default profile; in that
     * case callers should use the framework [ServiceWorkerController.getInstance()] directly — see
     * [defaultServiceWorkerController].
     */
    fun serviceWorkerControllerFor(childId: Long): ServiceWorkerController? {
        if (!isMultiProfileSupported()) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(profileNameFor(childId))
                .serviceWorkerController
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The framework-default [ServiceWorkerController] — used on the degraded path (no
     * MULTI_PROFILE) where the WebView rides the shared default profile. Returns null if the
     * framework SW API itself is unavailable.
     */
    fun defaultServiceWorkerController(): ServiceWorkerController? = try {
        ServiceWorkerController.getInstance()
    } catch (_: Throwable) {
        null
    }

    /**
     * Clear browsing data for [childId]'s profile without deleting the profile itself (task: "清空
     * 数据"). Used by the Hub lane's "clear data" action.
     *
     * Supported: removes cookies + all WebStorage for the profile. Unsupported: best-effort on the
     * shared default profile (documented degraded behavior — clears *all* children's data, which is
     * the unavoidable consequence of no profile isolation).
     */
    fun clearBrowsingData(context: Context, childId: Long) {
        try {
            if (isMultiProfileSupported()) {
                val store = ProfileStore.getInstance()
                val profile = store.getOrCreateProfile(profileNameFor(childId))
                clearProfileData(profile)
            } else {
                // Degraded: fall back to the default profile's storage. This clears data for every
                // child — there is no isolation to scope the wipe to.
                clearProfileData(defaultProfile())
            }
        } catch (_: Throwable) {
            // Swallow: clearing data is best-effort; a failure here must not break the Hub UI flow.
        }
    }

    /**
     * Delete [childId]'s profile entirely (task: delete child app). The Hub lane finishes any live
     * shell for this child **before** calling this.
     *
     * Supported: tries `ProfileStore.deleteProfile(name)`; if that fails (live WebView still
     * holding the profile), clears browsing data as a fallback and retries the delete once.
     * Unsupported: delegates to [clearBrowsingData] (no profile to delete).
     */
    fun deleteProfileData(context: Context, childId: Long) {
        try {
            if (!isMultiProfileSupported()) {
                clearBrowsingData(context, childId)
                return
            }
            val store = ProfileStore.getInstance()
            val name = profileNameFor(childId)
            if (!store.deleteProfile(name)) {
                // Likely a live WebView is holding it. Clear data then retry once.
                clearBrowsingData(context, childId)
                store.deleteProfile(name)
            }
        } catch (_: Throwable) {
            // Best-effort; the DB row is the source of truth for "deleted".
        }
    }

    /** Resolve the default profile (used in the degraded, unsupported case). */
    private fun defaultProfile(): Profile {
        // ProfileStore exposes the default profile by its canonical name.
        return ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME)
    }

    private fun clearProfileData(profile: Profile) {
        // Cookies (async; we don't block on the callback — clearing is fire-and-forget best-effort).
        try {
            profile.cookieManager.removeAllCookies(ValueCallback<Boolean> { _ -> })
        } catch (_: Throwable) { }
        // WebStorage: delete everything for this profile.
        try {
            profile.webStorage.deleteAllData()
        } catch (_: Throwable) { }
    }
}