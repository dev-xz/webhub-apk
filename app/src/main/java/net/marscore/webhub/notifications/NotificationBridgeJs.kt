package net.marscore.webhub.notifications

import android.webkit.WebView

/**
 * Injects the `window.Notification` shim that routes the Web Notifications API to [NotificationBridge]
 * (task 6.1).
 *
 * The shim installs exactly once per page (guarded by a window flag), always reports
 * `permission = 'granted'` to the page so web flows never stall on a permission prompt, and forwards
 * `new Notification(title, options)` to the native bridge. `requestPermission()` triggers the runtime
 * POST_NOTIFICATIONS request on API 33+ but still resolves to `'granted'` page-side.
 *
 * Ported from the reference project's `injectNotificationBridge` with the bridge name renamed to
 * `__webHubNotifBridge` and the install flag renamed to match.
 */
internal object NotificationBridgeJs {

    private const val BRIDGE_NAME = "__webHubNotifBridge"
    private const val INSTALLED_FLAG = "__webHubNotifInstalled"

    fun inject(webView: WebView) {
        val js = """
        (function(){
            if (window.$INSTALLED_FLAG) return;
            window.$INSTALLED_FLAG = true;
            var bridge = window.$BRIDGE_NAME;
            function NativeNotification(title, options){
                options = options || {};
                try {
                    bridge.show(title,
                        options.body || '',
                        options.tag || ('n_' + (Math.random()*1e9|0)),
                        options.icon || '');
                } catch(e){}
            }
            NativeNotification.permission = 'granted';
            NativeNotification.requestPermission = function(cb){
                try { bridge.requestPermission(); } catch(e){}
                if (cb) cb('granted');
                return Promise.resolve('granted');
            };
            NativeNotification.prototype = { close: function(){} };
            try {
                Object.defineProperty(window, 'Notification', {
                    value: NativeNotification,
                    configurable: true,
                    writable: true
                });
            } catch(e){ window.Notification = NativeNotification; }
        })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** The window bridge name under which [NotificationBridge] is exposed via addJavascriptInterface. */
    const val bridgeName: String = BRIDGE_NAME
}