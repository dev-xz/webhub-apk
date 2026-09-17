package net.marscore.webhub.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.icons.UrlValidator
import net.marscore.webhub.notifications.ChildNotificationChannels
import net.marscore.webhub.notifications.NotificationBridge
import net.marscore.webhub.notifications.NotificationBridgeJs
import net.marscore.webhub.widgets.WidgetUpdater
import androidx.webkit.WebViewCompat
import android.Manifest

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

    // ---- task 7.1 / design D1: display-mode branching ----
    // True when the child is configured for fullscreen mode (immersive + manual
    // IME inset padding + no theme-color tint). Set in startWeb after resolving
    // the child; defaults false until then.
    private var isFullscreenMode: Boolean = false

    // ---- task 2.3 / 2.4: theme-color status-bar state ----
    /** Current page's parsed theme color, or null when none/invalid (design D4). */
    private var currentThemeColor: Int? = null
    /** Saved status-bar state for video-fullscreen restore (task 2.4). */
    private var savedBarState: ThemeColorApplier.BarState? = null

    // ---- task 3.2–3.6: file chooser lifecycle ----
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    /** Pending capture params held while CAMERA permission is being requested. */
    private var pendingCaptureNormalized: Array<String>? = null
    private var pendingCaptureMultiple: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1.1 / design D1: immersive on the default browsing path fights Manifest
        // windowSoftInputMode="adjustResize" (LAYOUT_STABLE suppresses the IME
        // resize). system mode therefore does NOT apply immersive here.
        // 7.1: fullscreen mode re-enables immersive on the 4 lifecycle entry points
        // (onCreate/onResume/onNewIntent/onWindowFocusChanged); the video-fullscreen
        // path (ChromeClient.onShowCustomView / hideCustom) stays unconditional.
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
        isFullscreenMode = resolved.displayMode == "fullscreen"
        if (isFullscreenMode) applyImmersiveFullscreen()
        // 7.2 / D3: stamp lastOpenedAt (recency) and notify grid widgets to re-sort. Async on IO
        // so the WebView starts loading immediately; the 300ms debounce in WidgetUpdater coalesces
        // rapid re-entries. Only on the success path (deleted-child fallback doesn't "open" it).
        lifecycleScope.launch(Dispatchers.IO) {
            repository.touchLastOpened(resolved.id)
            WidgetUpdater.notifyAllWidgetsChanged(this@WebAppActivity)
        }
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
        setIntent(intent)
        // 7.1: immersive only in fullscreen mode (system mode relies on adjustResize).
        if (isFullscreenMode) applyImmersiveFullscreen()
        // External jump deep-link arriving on an already-running shell
        // (documentLaunchMode="intoExisting"): navigate to the new target url so a second
        // webhub://jump/auto?url=... call switches the page instead of being ignored.
        // homeUrl stays the child's configured URL — back-to-home semantics are preserved.
        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL)
            ?.takeIf { UrlValidator.isValid(it) }
        if (targetUrl != null && ::webView.isInitialized) {
            webView.loadUrl(targetUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        // 7.1: immersive only in fullscreen mode (system mode relies on adjustResize).
        if (isFullscreenMode) applyImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 7.1: immersive only in fullscreen mode; re-apply on focus regain so
        // IMMERSIVE_STICKY stays sticky after system UI briefly reappears.
        if (isFullscreenMode && hasFocus) applyImmersiveFullscreen()
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

        // 7.1: record display mode for lifecycle/immersive branching. Set here (the
        // authoritative resolve point) per design D1; onCreate also sets it for the
        // pre-startWeb immersive call, but this keeps it in sync if startWeb is ever
        // called again with a different app.
        isFullscreenMode = app.displayMode == "fullscreen"

        // 7.3 / design D1: dynamic fitsSystemWindows. system mode keeps the layout's
        // hardcoded true so content avoids the status bar; fullscreen mode clears it
        // so content fills under the hidden status bar (immersive + manual IME
        // padding handle the rest).
        findViewById<View>(R.id.webapp_root).fitsSystemWindows = !isFullscreenMode

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        webView.visibility = View.VISIBLE

        configureWebView(app)
        // External jump (webhub://jump/auto?url=…): the target url is the first page to load,
        // validated via UrlValidator. homeUrl stays app.url so back-to-home returns to the child's
        // configured home, not the deep link. Absent/invalid target falls back to the child's URL.
        val targetUrl = intent?.getStringExtra(EXTRA_TARGET_URL)
            ?.takeIf { UrlValidator.isValid(it) }
        webView.loadUrl(targetUrl ?: app.url)
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
                // against double-install via a window flag). Notifications work in both modes.
                NotificationBridgeJs.inject(webView)
                // 2.2 / design D2: read <meta name="theme-color"> (incl. dark-mode media
                // variants) and hand the chosen color back to the __webHubThemeBridge.
                // Re-injected on every page finish so SPA navigations re-tint the bar.
                // 7.2: skip in fullscreen mode — the status bar is hidden, tinting is pointless
                // and would fight the immersive flags.
                if (!isFullscreenMode) {
                    webView.evaluateJavascript(ThemeColorJs.jsSnippet(), null)
                }
            }
        }

        webView.webChromeClient = ChromeClient()

        // 6.1: register the JS bridge exactly once, here in configureWebView (NOT in onStart —
        // that was the reference's duplicate-registration hazard).
        webView.addJavascriptInterface(NotificationBridge(this, app), NotificationBridgeJs.bridgeName)
        // 2.2: register the theme-color bridge exactly once alongside the notification bridge.
        webView.addJavascriptInterface(ThemeColorBridge(), ThemeColorJs.bridgeName)

        // 4.1 / design D7: route WebView-detected downloads through the system
        // DownloadManager (public Downloads dir, UA + referer forwarded so
        // servers that gate on those headers don't 403 us).
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        // fullscreen mode: immersive fullscreen is applied in onCreate/onResume/onWindowFocusChanged.
        // Keyboard avoidance in fullscreen mode is intentionally NOT implemented — immersive's
        // LAYOUT_STABLE suppresses IME insets (WindowInsetsCompat.Type.ime() returns 0) and
        // temporarily-exiting-immersive approaches proved unreliable across ROMs. Users who need
        // keyboard avoidance should use the "system" display mode (adjustResize handles it
        // natively). See design D9 (revised) for the full rationale.
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
            // 2.4: snapshot the current status-bar state (color + LIGHT_STATUS_BAR
            // bit) before immersive hides the bar, so hideCustom can restore it.
            savedBarState = ThemeColorApplier.snapshot(
                currentThemeColor,
                window.decorView.systemUiVisibility
            )
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
            // 2.4: restore the status-bar color + icon tint captured in
            // onShowCustomView. No-op when no snapshot was taken (e.g. fullscreen
            // entered before any onPageFinished fired).
            ThemeColorApplier.restore(window, savedBarState)
            savedBarState = null
        }

        override fun onHideCustomView() = hideCustom()

        // 3.2 / design D5: <input type="file"> routing. Normalize accept types,
        // prefer the camera-capture path when `capture` is set + reachable +
        // CAMERA granted, else fall back to the plain content picker.
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val acceptTypes = fileChooserParams.acceptTypes ?: arrayOf()
            val isMultiple =
                fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            val isCapture = fileChooserParams.isCaptureEnabled()

            if (isCapture && FileChooserDecision.isCaptureReachable(acceptTypes)) {
                val cameraGranted = ContextCompat.checkSelfPermission(
                    this@WebAppActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (!cameraGranted) {
                    // 3.5: hold the params and ask for CAMERA; the result handler
                    // either launches the capture Intent or falls back to the
                    // content picker.
                    pendingCaptureNormalized = FileChooserHandler.normalizeAcceptTypes(acceptTypes)
                    pendingCaptureMultiple = isMultiple
                    fileChooserCallback = filePathCallback
                    ActivityCompat.requestPermissions(
                        this@WebAppActivity,
                        arrayOf(Manifest.permission.CAMERA),
                        REQUEST_CAMERA_PERMISSION,
                    )
                    return true
                }
            }

            val decision = FileChooserDecision.chooseIntent(
                isCapture = isCapture,
                isCameraGranted = true,
                acceptTypes = acceptTypes,
                isMultiple = isMultiple,
            )
            return launchFileChooser(decision.intent, filePathCallback)
        }

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

    // ---------------- task 3.2–3.6: file chooser launch + result + permission ----------------

    /**
     * Launch the picker [intent] and remember [callback] so onActivityResult
     * can deliver the picked Uris (or `null` on cancel) back to the WebView.
     * Returns true on a successful `startActivityForResult`; on
     * `ActivityNotFoundException` toasts and cancels the callback (design D5).
     */
    private fun launchFileChooser(
        intent: Intent,
        callback: ValueCallback<Array<Uri>>?,
    ): Boolean {
        fileChooserCallback = callback
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_FILE_CHOOSER)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.shell_no_file_chooser, Toast.LENGTH_SHORT).show()
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            false
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val callback = fileChooserCallback
            fileChooserCallback = null
            val uris: Array<Uri>? = if (resultCode == RESULT_OK) {
                buildUrisFromResult(data)
            } else null
            callback?.onReceiveValue(uris)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    /** Collect the picked Uri(s) from `data.data` (single) or `data.clipData` (multiple). */
    private fun buildUrisFromResult(data: Intent?): Array<Uri>? {
        if (data == null) return null
        data.data?.let { return arrayOf(it) }
        val clip = data.clipData ?: return null
        if (clip.itemCount == 0) return null
        return Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        val normalized = pendingCaptureNormalized
        val multiple = pendingCaptureMultiple
        pendingCaptureNormalized = null
        pendingCaptureMultiple = false
        if (normalized == null) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        val intent = if (granted) {
            FileChooserHandler.buildCaptureIntent(normalized)
                ?: FileChooserHandler.buildContentIntent(normalized, multiple)
        } else {
            // 3.5: denied → fall back to the plain content picker.
            FileChooserHandler.buildContentIntent(normalized, multiple)
        }
        launchFileChooser(intent, fileChooserCallback)
    }

    // 3.4 / design D6: WebView standard — if the Activity is recreated while a
    // picker is in flight, the old callback's Uri is stale; send null to release
    // the WebView so future <input type=file> taps still work.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_PENDING_FILE_CHOOSER, fileChooserCallback != null)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(KEY_PENDING_FILE_CHOOSER, false) &&
            fileChooserCallback != null
        ) {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    // ---------------- task 4.1 / design D7: download routing ----------------

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
    ) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        // 4.1 / D7 (revised): scheme gate. DownloadManager only handles http(s).
        // blob:/data:/file:/about: can't be enqueued (the Request constructor
        // throws or enqueues garbage) AND can't be handed to the browser either
        // (blob: lives only inside the WebView renderer). Show a toast and stop.
        if (scheme != "http" && scheme != "https") {
            Toast.makeText(this, R.string.shell_download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        // Best-effort cookie forwarding: read the WebView's session cookies for
        // this URL so auth-gated downloads don't 401/403. Profile-aware — mirrors
        // installServiceWorkerCookieShim's pattern. Any failure → null cookie,
        // download proceeds without it.
        val cookie = readDownloadCookie(url)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            // No DownloadManager → fall back to the browser's downloader.
            fallbackToBrowser(uri!!)
            return
        }
        try {
            val request = DownloadRequestBuilder.buildRequest(
                url = url,
                userAgent = userAgent,
                referer = if (::webView.isInitialized) webView.url else null,
                mimetype = mimetype,
                contentDisposition = contentDisposition,
                cookie = cookie,
            )
            dm.enqueue(request)
        } catch (e: Exception) {
            // 4.3: enqueue can throw on malformed URLs / ROM-disabled
            // DownloadManager. Per user decision, fall back to the browser's
            // downloader rather than just toasting.
            fallbackToBrowser(uri!!)
        }
    }

    /**
     * Best-effort Cookie header value for [url], sourced from the WebView's
     * cookie jar. Profile-aware: when MULTI_PROFILE is supported, reads from the
     * child's profile cookie manager (matching [installServiceWorkerCookieShim]);
     * otherwise the framework default [android.webkit.CookieManager]. Any
     * failure → null (download proceeds without cookies).
     */
    private fun readDownloadCookie(url: String): String? = try {
        val app = child
        if (ProfileManager.isMultiProfileSupported() && app != null) {
            androidx.webkit.ProfileStore.getInstance()
                .getOrCreateProfile(ProfileManager.profileNameFor(app.id))
                .cookieManager
                .getCookie(url)
        } else {
            android.webkit.CookieManager.getInstance().getCookie(url)
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * D7 (revised): browser-download fallback. Hands [uri] to the system browser
     * via `ACTION_VIEW` so its own downloader can fetch the resource when the
     * system DownloadManager is unavailable or refuses the request. Last-resort
     * toast when no browser can handle it either.
     */
    private fun fallbackToBrowser(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.shell_download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- task 2.3: theme-color bridge ----------------

    /**
     * `@JavascriptInterface` receiver for `ThemeColorJs.jsSnippet()`. The JS
     * calls `onThemeColor(colorHexOrNull)` once per `onPageFinished`. Runs on a
     * WebKit JS thread — must hop to the UI thread before touching the Window.
     */
    private inner class ThemeColorBridge {
        @android.webkit.JavascriptInterface
        fun onThemeColor(colorHex: String?) {
            // 7.2: defense in depth — even if the bridge is somehow invoked in
            // fullscreen mode (e.g. a cached page finishing after a mode flip),
            // fullscreen mode must not touch statusBarColor (the bar is hidden).
            if (isFullscreenMode) return
            runOnUiThread {
                val color = ThemeColorReader.parseThemeColor(colorHex)
                currentThemeColor = color
                if (color != null) {
                    // D4: only override statusBarColor when we have a valid color;
                    // otherwise leave the app theme's value untouched.
                    ThemeColorApplier.applyStatusBarColor(
                        window,
                        color = color,
                        isLight = ThemeColorReader.isLightColor(color),
                    )
                }
            }
        }
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

        /** Optional intent extra: a deep-link URL to load instead of the child's configured home URL. Used by external jump routing. */
        const val EXTRA_TARGET_URL = "net.marscore.webhub.extra.TARGET_URL"

        // 3.2 / 3.5: request codes for the file-chooser + camera-permission flows.
        private const val REQUEST_FILE_CHOOSER = 1001
        private const val REQUEST_CAMERA_PERMISSION = 1002
        private const val KEY_PENDING_FILE_CHOOSER = "pending_file_chooser"

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

        /**
         * Launch intent for a child app with an explicit deep-link target URL. The shell loads [targetUrl]
         * as the first page but keeps the child's configured URL as the home (back-to-home target).
         * [targetUrl] may be null to behave identically to the single-arg overload.
         */
        fun createIntent(context: Context, childId: Long, targetUrl: String?): Intent =
            createIntent(context, childId).apply {
                if (!targetUrl.isNullOrBlank()) {
                    putExtra(EXTRA_TARGET_URL, targetUrl)
                }
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