package net.marscore.webhub.shell

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.UrlValidator

/**
 * Headless router for the external jump scheme `webhub://jump/auto?url=<encoded>`.
 *
 * On [onCreate] it:
 * 1. Validates the incoming URI (scheme=webhub, host=jump, path=/auto) and the `url` query
 *    parameter via [UrlValidator.normalize]. On any validation failure it Toasts
 *    `shell_jump_invalid` and finishes.
 * 2. Loads a one-shot snapshot of all child apps via [ChildAppRepository.getAll] (runBlocking,
 *    mirroring [WebAppActivity]'s `runBlocking { getById }` precedent — the read is fast and the
 *    routing decision is synchronous).
 * 3. Matches the target url against the children with [JumpMatcher]. On a hit it forwards to
 *    [WebAppActivity] via [WebAppActivity.createIntent] carrying `EXTRA_CHILD_ID` +
 *    `EXTRA_TARGET_URL`, then finishes. On a miss it Toasts `shell_jump_no_match` and finishes.
 *
 * The activity has no UI: the manifest gives it a translucent no-titlebar theme, plus
 * `excludeFromRecents` + `noHistory` so it leaves no recents/task trace. The shell (WebAppActivity)
 * loads the deep-link as the first page but keeps the matched child's configured url as the home
 * (back-to-home target), per spec.
 */
class JumpRouterActivity : AppCompatActivity() {

    private lateinit var repository: ChildAppRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = ChildAppRepository(this)

        val data = intent?.data
        // Defensive: the intent-filter already constrains scheme/host/path, but guard anyway in
        // case the activity is launched directly (e.g. by an internal caller).
        if (data == null || data.scheme != "webhub" || data.host != "jump" || data.path != "/auto") {
            toastInvalid()
            finish()
            return
        }

        val targetRaw = data.getQueryParameter("url")
        if (targetRaw.isNullOrBlank()) {
            toastInvalid()
            finish()
            return
        }

        val targetUrl = UrlValidator.normalize(targetRaw)
        if (targetUrl == null) {
            toastInvalid()
            finish()
            return
        }

        val children = runBlocking { repository.getAll() }
        val match = JumpMatcher.match(targetUrl, children)
        if (match == null) {
            Toast.makeText(this, R.string.shell_jump_no_match, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        startActivity(WebAppActivity.createIntent(this, match.id, targetUrl))
        finish()
    }

    private fun toastInvalid() {
        Toast.makeText(this, R.string.shell_jump_invalid, Toast.LENGTH_SHORT).show()
    }
}