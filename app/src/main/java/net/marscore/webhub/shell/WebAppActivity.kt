package net.marscore.webhub.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.notifications.ChildNotificationChannels
import net.marscore.webhub.notifications.NotificationBridge
import net.marscore.webhub.notifications.NotificationBridgeJs
import androidx.webkit.WebViewCompat

/**
 * Parameterized WebView shell (tasks 5.1–5.9).
 *
 * Launched with [EXTRA_CHILD_ID] (or, for shortcut/notification deep links, a
 * `webhub://app/<childId>` data URI). Resolves the matching [ChildApp] and loads its URL inside an
 * isolated WebView Profile. Each child gets its own recents entry via FLAG_ACTIVITY_NEW_DOCUMENT +
 * the unique `webhub://app/<id>` data (task 5.7).
 *
 * If the child no longer exists (stale launcher shortcut), shows a fallback dialog and finishes
 * (task 5.1).
 *
 * Patterns (immersive fullscreen, video fullscreen ChromeClient, navigation policy, SSL, back
 * handling, WebView lifecycle): single bridge registration (registered once in configureWebView),
 * per-child channels (never delete-then-recreate), per-child UA/zoom, and Profile isolation.
 */
class WebAppActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var child: ChildApp? = null
    private var homeUrl: String? = null
    private var homeHost: String = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private lateinit var repository: ChildAppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyImmersiveFullscreen()
        setContentView(R.layout.activity_webapp)

        repository = ChildAppRepository(this)

        // 5.1: resolve childId from EXTRA_CHILD_ID, falling back to the data URI's last segment
        // (notification/shortcut deep links carry webhub://app/<id>).
        val childId = resolveChildId(intent)
        val resolved = if (childId != null) {
            // Loading a ChildApp is a quick DB read; runBlocking on create is acceptable and keeps
            // the rest of the shell synchronous (the reference app did the same with prefs).
            runBlocking { repository.getById(childId) }
        } else null

        if (resolved == null) {
            showDeletedFallback()
            return
        }

        child = resolved
        // 6.2 safety net: make sure the child's notification channel exists before any page fires
        // a notification. Idempotent and cheap.
        ChildNotificationChannels.ensureChannel(this, resolved)

        // 5.7 / round-2 problem D: give this child its own recents-task label + icon so the
        // recent-apps card shows the child's name and icon rather than the WebHub host's. Done
        // after the child is resolved (so the name/icon are known) and before startWeb so the
        // task description is in place by the time the WebView paints.
        applyTaskDescription(resolved)

        startWeb(resolved)
        installBackHandler()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyImmersiveFullscreen()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    // ---------------- task 5.1: launch + deleted-child fallback ----------------

    private fun resolveChildId(intent: Intent?): Long? {
        // Primary: explicit extra from createIntent / Hub. getLongExtra returns a primitive long;
        // -1L is our "absent" sentinel.
        val direct = intent?.getLongExtra(EXTRA_CHILD_ID, -1L) ?: -1L
        if (direct >= 0L) return direct
        // Fallback: parse webhub://app/<id> from the data URI (shortcut / notification taps).
        val data = intent?.data ?: return null
        if (data.scheme == "webhub" && data.host == "app") {
            return data.lastPathSegment?.toLongOrNull()
        }
        return null
    }

    private fun showDeletedFallback() {
        AlertDialog.Builder(this)
            .setTitle(R.string.shell_deleted_title)
            .setMessage(R.string.shell_deleted_message)
            .setCancelable(false)
            .setPositiveButton(R.string.shell_deleted_ok) { _, _ -> finish() }
            .show()
    }

    // ---------------- WebView wiring ----------------

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun startWeb(app: ChildApp) {
        homeUrl = app.url
        homeHost = Uri.parse(app.url)?.host ?: ""

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        webView.visibility = View.VISIBLE

        configureWebView(app)
        webView.loadUrl(app.url)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView(app: ChildApp) {
        // 5.2: associate the WebView with the child's profile FIRST, before any load/usage, when
        // multi-profile is supported. Must be called before the WebView loads anything.
        if (ProfileManager.isMultiProfileSupported()) {
            try {
                WebViewCompat.setProfile(webView, ProfileManager.profileNameFor(app.id))
            } catch (_: Throwable) {
                // If setProfile fails for any reason, continue on the default profile.
            }
        }

        installServiceWorkerCookieShim(app)

        val s = webView.settings
        // Baseline WebView settings.
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // 5.9 / 9.4 zoom: fixed scale vs. overview defaults.
        // 100% is the neutral default (slider era): it must NOT enter the fixed-scale branch,
        // because that branch disables useWideViewPort — ignoring <meta viewport> breaks
        // responsive mobile pages. 0 (legacy "not fixed") and 100 both take the default path.
        if (app.zoomPercent > 0 && app.zoomPercent != 100) {
            // MUST be set before loadUrl (we're still pre-load here).
            // 9.4: setInitialScale interprets percent against PHYSICAL pixels, bypassing density
            // normalization — so 100% looks tiny on a density≈2.5 device. Multiply by device density
            // so user-facing 100% == the system default rendering ratio.
            webView.setInitialScale(effectiveInitialScale(app.zoomPercent, resources.displayMetrics.density))
            s.setSupportZoom(false)
            s.loadWithOverviewMode = false
            s.useWideViewPort = false
        } else {
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.setSupportZoom(false)
        }

        // 5.8 UA modes. "default" (and unknown) leave the WebView default untouched.
        UserAgents.forMode(app.uaMode)?.let { s.userAgentString = it }

        webView.webViewClient = object : WebViewClient() {
            // 5.4 navigation policy: home host (incl. *.homehost) stays in-WebView; else system browser.
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url ?: return false
                val host = url.host ?: return false
                return if (isHomeHost(host)) {
                    false
                } else {
                    try {
                        val i = Intent(Intent.ACTION_VIEW, url)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    } catch (_: Exception) { }
                    true
                }
            }

            // 5.5 SSL: proceed only when the child opted into ignoreSsl; else cancel (reject).
            override fun onReceivedSslError(
                v: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?
            ) {
                if (app.ignoreSsl) handler?.proceed() else handler?.cancel()
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                super.onPageFinished(v, url)
                // 6.1: inject the Notification shim on every page finish (the shim self-guards
                // against double-install via a window flag).
                NotificationBridgeJs.inject(webView)
            }
        }

        webView.webChromeClient = ChromeClient()

        // 6.1: register the JS bridge exactly once, here in configureWebView (NOT in onStart —
        // that was the reference's duplicate-registration hazard).
        webView.addJavascriptInterface(NotificationBridge(this, app), NotificationBridgeJs.bridgeName)
    }

    private fun isHomeHost(host: String): Boolean =
        homeHost.isNotEmpty() && (host == homeHost || host.endsWith(".$homeHost"))

    // ---------------- round-2 problem SW-401: ServiceWorker cookie compensation ----------------

    /**
     * Install a ServiceWorker request interceptor that re-issues SW fetches with the profile's
     * cookies attached. See [ServiceWorkerInterceptor] for the full rationale.
     *
     * Profile-aware path: when MULTI_PROFILE is supported, the WebView is bound to the child's
     * profile, so we install the client on [ProfileManager.serviceWorkerControllerFor] (which uses
     * `Profile.getServiceWorkerController()` — androidx webkit 1.9+). Degraded path (no
     * MULTI_PROFILE): the WebView rides the framework default profile, so we install on the
     * framework [android.webkit.ServiceWorkerController.getInstance()].
     *
     * Gated by [ProfileManager.isServiceWorkerInterceptSupported]; if the WebView can't intercept
     * SW requests at all, this is a no-op (the SW 401 stays unfixable here, but page loading is
     * unaffected). Best-effort: any failure is swallowed — never break page loading.
     */
    private fun installServiceWorkerCookieShim(app: ChildApp) {
        if (!ProfileManager.isServiceWorkerInterceptSupported()) return
        try {
            val cookieManager = if (ProfileManager.isMultiProfileSupported()) {
                // Profile-bound cookie jar — same one the normal WebView requests use, just reached
                // via the androidx Profile handle.
                androidx.webkit.ProfileStore.getInstance()
                    .getOrCreateProfile(ProfileManager.profileNameFor(app.id))
                    .cookieManager
            } else {
                android.webkit.CookieManager.getInstance()
            }
            val cookieSource = ServiceWorkerInterceptor.RealCookieSource(cookieManager)
            val ua = webView.settings.userAgentString
                ?: android.webkit.WebSettings.getDefaultUserAgent(this)
            val interceptor = ServiceWorkerInterceptor(cookieSource, ua)

            val controller = ProfileManager.serviceWorkerControllerFor(app.id)
                ?: ProfileManager.defaultServiceWorkerController()
            controller?.setServiceWorkerClient(interceptor.asClient())
        } catch (e: Throwable) {
            android.util.Log.w("WebHub/SWShim", "ServiceWorker cookie shim install failed", e)
        }
    }

    // ---------------- task 5.3 + 5.4: ChromeClient (fullscreen video + target=_blank) ----------------

    private inner class ChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val granted = request.resources.toList()
            runOnUiThread {
                if (granted.isNotEmpty()) request.grant(granted.toTypedArray())
                else request.deny()
            }
        }

        // 5.3 video fullscreen.
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            customView = view
            customViewCallback = callback
            applyImmersiveFullscreen()
            addContentView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        fun hideCustom() {
            customView?.let { (it.parent as? ViewGroup)?.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            applyImmersiveFullscreen()
        }

        override fun onHideCustomView() = hideCustom()

        // 5.4 target=_blank: apply the same home-host navigation policy.
        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
        ): Boolean {
            val target = WebView(this@WebAppActivity)
            target.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                    val url = req?.url ?: return false
                    val host = url.host ?: return false
                    return if (isHomeHost(host)) {
                        webView.loadUrl(url.toString())
                        false
                    } else {
                        try {
                            val i = Intent(Intent.ACTION_VIEW, url)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(i)
                        } catch (_: Exception) { }
                        true
                    }
                }
            }
            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = target
            resultMsg?.sendToTarget()
            return true
        }
    }

    // ---------------- task 5.6: back handling ----------------

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!::webView.isInitialized || homeUrl == null) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                // 1. Exit video fullscreen first.
                if (customView != null) {
                    (webView.webChromeClient as? ChromeClient)?.hideCustom()
                    return
                }
                // 2. WebView history: go back unless we're on the home URL.
                val cur = webView.url
                if (webView.canGoBack() && cur != null && cur != homeUrl) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ---------------- task 5.3: immersive fullscreen ----------------

    @SuppressLint("InlinedApi")
    private fun applyImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    // ---------------- round-2 problem D: recents-task label + icon ----------------

    /**
     * Set the recent-tasks card to show the child's name and icon instead of the WebHub host
     * app's. Called once the [ChildApp] is resolved so the label and icon source are known.
     *
     * Icon: a plain (non-adaptive) bitmap — `TaskDescription` does not accept adaptive bitmaps.
     * Resolution matches the hub-list icon: favicon/upload → [IconStore] file; preset → preset
     * drawable rendered to a bitmap; decode failure → omit the icon parameter (the platform falls
     * back to the activity's default icon) rather than crash.
     *
     * Best-effort: any failure (decode, API level) is swallowed — the recents card degrades to the
     * host app's icon, never to a crash. Does NOT touch [ProfileManager] / WebView wiring.
     */
    @SuppressLint("InlinedApi")
    private fun applyTaskDescription(app: ChildApp) {
        try {
            val icon = resolveTaskIcon(app)
            val td = if (icon != null) {
                ActivityManager.TaskDescription(app.name, icon)
            } else {
                // Label-only: still overrides the recents name even when no icon bitmap is available.
                ActivityManager.TaskDescription(app.name)
            }
            setTaskDescription(td)
        } catch (_: Throwable) {
            // Some OEMs throw on TaskDescription; never let it break the shell.
        }
    }

    /**
     * Resolve a plain (non-adaptive) bitmap suitable for `ActivityManager.TaskDescription`:
     * favicon/upload → decode the on-disk IconStore file; preset → render the preset drawable.
     * Returns null on any decode failure — callers omit the icon param rather than crash.
     *
     * Internal for test access (problem D verification).
     */
    internal fun resolveTaskIcon(app: ChildApp): Bitmap? = TaskIconResolver.resolve(this, app)

    // ---------------- lifecycle ----------------

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        /** Intent extra carrying the child app id. */
        const val EXTRA_CHILD_ID = "net.marscore.webhub.extra.CHILD_ID"

        /**
         * Explicit intent to launch the shell for [childId]. Sets the extra, a unique
         * `webhub://app/<id>` data URI (so shortcut/notification taps route to the right recents
         * document), and FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK so each child
         * gets its own recents entry (task 5.7).
         */
        /**
         * Launch intent for a child app. `data` distinguishes each child's task in recents.
         *
         * Multi-card semantics are manifest-driven (`documentLaunchMode="intoExisting"` +
         * `autoRemoveFromRecents="false"`): one persistent recents card per child, reopening a
         * child returns to its existing task, and the card survives the activity finishing.
         * NEW_DOCUMENT here aligns with that; MULTIPLE_TASK must NOT be set — it would stack a
         * duplicate task on every launch of the same child (task 5.7, revamp-hub-ux D13).
         */
        fun createIntent(context: Context, childId: Long): Intent =
            Intent(context, WebAppActivity::class.java).apply {
                putExtra(EXTRA_CHILD_ID, childId)
                data = Uri.parse("webhub://app/$childId")
                flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            }
    }
}

/**
 * Resolves a plain (non-adaptive) bitmap for a child app's [android.app.ActivityManager.TaskDescription].
 *
 * Extracted from [WebAppActivity.resolveTaskIcon] as a pure helper so the resolution logic (the
 * load-bearing part of problem D) is unit-testable without standing up the full WebView shell.
 *
 * Resolution mirrors the hub-list icon (NOT the adaptive-canvas composition): favicon/upload →
 * decode the on-disk [IconStore] file; preset → render the preset drawable to a bitmap. Any decode
 * failure yields null — the caller omits the icon parameter so [TaskDescription] falls back to the
 * activity default rather than crashing.
 */
internal object TaskIconResolver {
    fun resolve(context: Context, app: ChildApp): Bitmap? {
        return when (app.iconSource) {
            "preset" -> {
                val key = app.iconPath
                if (!key.isNullOrBlank()) {
                    val resId = PresetIcons.resForKey(key)
                    if (resId != 0) net.marscore.webhub.shortcuts.ShortcutHelper
                        .renderDrawableToBitmap(context, resId) else null
                } else null
            }
            else -> {
                val path = app.iconPath
                if (!path.isNullOrBlank()) {
                    try {
                        BitmapFactory.decodeFile(path)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }
        }
    }
}