package net.marscore.webhub.ui.hub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.data.ConfigTransfer
import net.marscore.webhub.icons.ImageIconProcessor
import net.marscore.webhub.notifications.ChildNotificationChannels
import net.marscore.webhub.shell.ProfileManager
import net.marscore.webhub.shell.WebAppActivity
import net.marscore.webhub.shortcuts.ShortcutHelper
import net.marscore.webhub.widgets.WidgetUpdater

/**
 * The WebHub management screen (tasks 4.1–4.9).
 *
 * A grid of child apps; tapping launches the WebView shell, long-press opens an options dialog
 * (edit / clear data / delete). The top bar "+" and a FAB both open the add/edit dialog.
 *
 * First launch shows an onboarding overlay (notification permission + battery whitelist) ported
 * from the reference project's setup screen, with the same onResume + 500ms Handler poll refresh
 * approach. A persistent banner warns when [ProfileManager.isMultiProfileSupported] is false.
 *
 * All persistence + side-effects (channel ensure/remove, shortcut pin/update/disable, profile
 * data clear/delete) are coordinated here in `lifecycleScope` coroutines.
 */
class HubActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var hubRoot: View
    private lateinit var emptyState: View
    private lateinit var bannerMultiProfile: View
    private lateinit var onboardingOverlay: ScrollView
    private lateinit var notifStatus: TextView
    private lateinit var batteryStatus: TextView

    private lateinit var adapter: ChildAppAdapter
    private val repository by lazy { ChildAppRepository(this) }
    private val imageProcessor by lazy { ImageIconProcessor(this) }

    /** SharedPreferences flag for the first-launch onboarding (task 4.6). */
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Onboarding status poller (ported from the reference project).
    private val onboardingPollHandler = Handler(Looper.getMainLooper())
    private val onboardingPollRunnable = object : Runnable {
        override fun run() {
            if (onboardingOverlay.visibility != View.VISIBLE) return
            refreshOnboardingStatuses()
            onboardingPollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // Photo Picker: registered once before STARTED; the dialog routes results through the bridge.
    private lateinit var pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private val pickImageBridge = ChildAppFormDialog.PickImageBridge()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Status refresh happens via onResume + the poll runnable regardless of outcome.
            refreshOnboardingStatuses()
        }

    // SAF launchers for config export/import (task 7.5). Registered before STARTED.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) doExport(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showImportModeDialog(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the Photo Picker launcher before STARTED so the dialog can launch it later.
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val consumer = pickImageBridge.consumer
            if (uri != null && consumer != null) consumer(uri)
        }

        setContentView(R.layout.activity_hub)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = ContextCompat.getColor(this, R.color.hub_surface)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.hub_surface)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            !isNightMode()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            !isNightMode()

        bindViews()
        setupGrid()
        observeChildren()
        setupMultiProfileBanner()
        maybeShowOnboarding()
    }

    private fun bindViews() {
        hubRoot = findViewById(R.id.hub_root)
        ViewCompat.setOnApplyWindowInsetsListener(hubRoot) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(hubRoot)
        grid = findViewById(R.id.grid)
        emptyState = findViewById(R.id.empty_state)
        bannerMultiProfile = findViewById(R.id.banner_multi_profile)
        onboardingOverlay = findViewById(R.id.onboarding_overlay)
        notifStatus = findViewById(R.id.notif_status)
        batteryStatus = findViewById(R.id.battery_status)

        findViewById<View>(R.id.btn_more).setOnClickListener { showHubMenu(it) }
        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener { openAddDialog() }

        // Onboarding buttons.
        findViewById<Button>(R.id.btn_notif).setOnClickListener { onNotifButton() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener { onBatteryButton() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { dismissOnboarding() }
    }

    private fun setupGrid() {
        adapter = ChildAppAdapter(
            onClick = { child -> launchChild(child.id) },
            onLongClick = { child -> showItemOptions(child) }
        )
        // 6.2: span from resources — 1 on phones, 2 on sw600dp+ (values-sw600dp/integers.xml).
        grid.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.hub_list_span))
        grid.adapter = adapter
    }

    // ---- 5.4 top-bar menu + 7.5 config export/import --------------------------

    private fun showHubMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EXPORT, 0, getString(R.string.menu_export_config))
        popup.menu.add(0, MENU_IMPORT, 1, getString(R.string.menu_import_config))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT -> {
                    exportLauncher.launch("webhub-config.json")
                    true
                }
                MENU_IMPORT -> {
                    // "*/*": some file managers mislabel .json as octet-stream;
                    // ConfigTransfer validates content anyway.
                    importLauncher.launch(arrayOf("*/*"))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun doExport(dest: Uri) {
        lifecycleScope.launch {
            val result = ConfigTransfer.export(this@HubActivity, dest)
            if (result.error == null) {
                Toast.makeText(
                    this@HubActivity,
                    getString(R.string.toast_export_done, result.count),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this@HubActivity, R.string.toast_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImportModeDialog(src: Uri) {
        val labels = arrayOf(
            getString(R.string.import_mode_append),
            getString(R.string.import_mode_overwrite)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_mode_title)
            .setItems(labels) { _, which ->
                if (which == 0) {
                    doImport(src, ConfigTransfer.ImportMode.APPEND)
                } else {
                    confirmOverwriteImport(src)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmOverwriteImport(src: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_overwrite_title)
            .setMessage(R.string.import_overwrite_message)
            .setPositiveButton(R.string.import_overwrite_title) { _, _ ->
                doImport(src, ConfigTransfer.ImportMode.OVERWRITE)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun doImport(src: Uri, mode: ConfigTransfer.ImportMode) {
        lifecycleScope.launch {
            val result = ConfigTransfer.import(this@HubActivity, src, mode)
            if (result.error == null) {
                Toast.makeText(
                    this@HubActivity,
                    getString(R.string.toast_import_result, result.added, result.skipped),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@HubActivity, R.string.toast_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- 4.1 list + launch -----------------------------------------------------

    private fun observeChildren() {
        lifecycleScope.launch {
            repository.observeAll().collectLatest { list ->
                adapter.submit(list)
                emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun launchChild(childId: Long) {
        startActivity(WebAppActivity.createIntent(this, childId))
    }

    // ---- 4.7 MULTI_PROFILE banner ----------------------------------------------

    private fun setupMultiProfileBanner() {
        bannerMultiProfile.visibility =
            if (ProfileManager.isMultiProfileSupported()) View.GONE else View.VISIBLE
    }

    // ---- 4.2 / 4.3 add + edit dialog -------------------------------------------

    private fun openAddDialog() {
        ChildAppFormDialog.show(this, existing = null, pickImageLauncher, pickImageBridge) { result ->
            createChild(result)
        }
    }

    private fun openEditDialog(child: ChildApp) {
        ChildAppFormDialog.show(this, existing = child, pickImageLauncher, pickImageBridge) { result ->
            updateChild(child, result)
        }
    }

    /**
     * Create flow (task 4.2 + 4.8): normalize URL → insert row → resolve icon per chosen source
     * → ensure notification channel → request pin shortcut. List refreshes via Flow.
     *
     * Icon resolution by source:
     *  - "favicon": FaviconFetcher.fetch → ImageIconProcessor.processAndSave; on null fall back
     *    to PresetIcons.pickForId and set iconSource="preset", iconPath=preset.key (update row).
     *  - "upload":  ImageIconProcessor.processAndSave(id, uri) → iconSource="upload".
     *  - "preset":  iconPath=preset.key (no file), iconSource="preset".
     */
    private fun createChild(form: ChildAppFormDialog.FormResult) {
        lifecycleScope.launch {
            // Insert the base row first so we have an id to attach an icon to.
            val base = ChildApp(
                name = form.name,
                url = form.url,
                iconSource = form.iconSource,
                iconPath = when (form.iconSource) {
                    "preset" -> form.presetKey
                    else -> null // resolved after we have an id
                },
                uaMode = form.uaMode,
                displayMode = form.displayMode,
                zoomPercent = form.zoomPercent,
                ignoreSsl = form.ignoreSsl
            )
            val id = withContext(Dispatchers.IO) { repository.insert(base) }
            val inserted = base.copy(id = id)

            val finalRow = when (form.iconSource) {
                "favicon" -> {
                    val bitmap = form.faviconBitmap
                    if (bitmap == null) {
                        inserted
                    } else {
                        val path = withContext(Dispatchers.IO) {
                            imageProcessor.processAndSave(id, bitmap)
                        }
                        bitmap.recycle()
                        inserted.copy(iconPath = path)
                    }
                }
                "upload" -> {
                    val uri = form.uploadUri
                    if (uri == null) inserted else inserted.copy(
                        iconPath = withContext(Dispatchers.IO) {
                            imageProcessor.processAndSave(id, uri)
                        }
                    )
                }
                else -> inserted
            }
            if (finalRow != inserted) {
                withContext(Dispatchers.IO) { repository.update(finalRow) }
            }

            // Notification channel (renamed later on edits via ensureChannel).
            ChildNotificationChannels.ensureChannel(this@HubActivity, finalRow)

            // Pin shortcut (silent skip when unsupported). Uses the resolved icon bitmap.
            pinShortcutFor(finalRow)

            // 7.3 / spec "数据同步刷新": child list changed → refresh home-screen widgets.
            WidgetUpdater.updateAllWidgets(this@HubActivity)

            Toast.makeText(this@HubActivity, R.string.toast_created, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Edit flow (task 4.3 + 4.8): apply all field changes → repository.update → ensureChannel
     * (rename propagates) → if icon changed, re-resolve + ShortcutHelper.update.
     *
     * Icon handling mirrors create, but against the existing id. When the icon source changed
     * or its artifact changed, we also call [ShortcutHelper.update] so the pinned shortcut
     * reflects the new icon.
     */
    private fun updateChild(
        existing: ChildApp,
        form: ChildAppFormDialog.FormResult
    ) {
        lifecycleScope.launch {
            // Detect icon *content* changes so we know to sync the shortcut. Path-based detection
            // is insufficient because uploads overwrite the same <id>.png slot, so we look at the
            // form directly: source switch, a newly-picked upload Uri, a favicon URL change, or a
            // preset key change all count.
            val iconChanged = didIconContentChange(existing, form)

            val updated = resolveAndPersistIconForUpdate(existing, form)
            withContext(Dispatchers.IO) { repository.update(updated) }

            // Channel rename (name change) or channel creation for a renamed child.
            ChildNotificationChannels.ensureChannel(this@HubActivity, updated)

            if (iconChanged || existing.name != updated.name) {
                // Re-resolve the icon bitmap and sync the shortcut (label and/or icon).
                ShortcutHelper.resolveIconBitmap(this@HubActivity, updated)?.let { bmp ->
                    ShortcutHelper.update(this@HubActivity, updated, bmp)
                }
            }

            // 7.3 / spec "数据同步刷新": name/icon/url fields changed → refresh home-screen widgets
            // (1×1 label/icon, grid Factory re-query).
            WidgetUpdater.updateAllWidgets(this@HubActivity)
        }
    }

    /**
     * True when the icon's visual content may have changed: source switched, a new upload Uri was
     * picked, the favicon URL changed (may re-fetch a different icon), or the preset key changed.
     */
    private fun didIconContentChange(
        existing: ChildApp,
        form: ChildAppFormDialog.FormResult
    ): Boolean {
        if (existing.iconSource != form.iconSource) return true
        return when (form.iconSource) {
            "upload" -> form.uploadUri != null
            "favicon" -> form.faviconBitmap != null
            "preset" -> existing.iconPath != form.presetKey
            else -> false
        }
    }

    /** Applies the already-resolved edit form without fetching from the network. */
    private suspend fun resolveAndPersistIconForUpdate(
        existing: ChildApp,
        form: ChildAppFormDialog.FormResult
    ): ChildApp {
        val newRow = existing.copy(
            name = form.name,
            url = existing.url,
            uaMode = form.uaMode,
            displayMode = form.displayMode,
            zoomPercent = form.zoomPercent,
            ignoreSsl = form.ignoreSsl
        )
        return when (form.iconSource) {
            "favicon" -> {
                val bitmap = form.faviconBitmap
                if (bitmap != null) {
                    val path = withContext(Dispatchers.IO) {
                        imageProcessor.processAndSave(existing.id, bitmap)
                    }
                    bitmap.recycle()
                    newRow.copy(iconSource = "favicon", iconPath = path)
                } else {
                    newRow.copy(iconSource = "favicon", iconPath = existing.iconPath)
                }
            }
            "upload" -> {
                val uri = form.uploadUri
                if (uri != null) {
                    val path = withContext(Dispatchers.IO) {
                        imageProcessor.processAndSave(existing.id, uri)
                    }
                    newRow.copy(iconSource = "upload", iconPath = path)
                } else {
                    // No new image picked: keep the existing upload path.
                    newRow.copy(iconSource = "upload", iconPath = existing.iconPath)
                }
            }
            else -> { // "preset"
                val key = form.presetKey ?: existing.iconPath
                newRow.copy(iconSource = "preset", iconPath = key)
            }
        }
    }

    // ---- 4.4 delete + 4.5 clear data -------------------------------------------

    private fun showItemOptions(child: ChildApp) {
        val labels = arrayOf(
            getString(R.string.action_edit),
            getString(R.string.action_pin),
            getString(R.string.action_clear_data),
            getString(R.string.action_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.options_title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> openEditDialog(child)
                    1 -> pinShortcutFor(child, showFeedback = true)
                    2 -> confirmClearData(child)
                    3 -> confirmDelete(child)
                }
            }
            .show()
    }

    /**
     * Delete flow (task 4.4): confirm → repository.delete (also wipes icon file) →
     * ProfileManager.deleteProfileData → ChildNotificationChannels.removeChannel →
     * ShortcutHelper.disable.
     */
    private fun confirmDelete(child: ChildApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, child.name))
            .setPositiveButton(R.string.action_delete) { _, _ -> doDelete(child) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun doDelete(child: ChildApp) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.delete(child) }
            ProfileManager.deleteProfileData(this@HubActivity, child.id)
            ChildNotificationChannels.removeChannel(this@HubActivity, child.id)
            ShortcutHelper.disable(this@HubActivity, child.id)
            // 7.3 / spec "删除子应用后矩阵重排" + "删除子应用后 1×1 兜底": refresh widgets so the grid
            // drops the deleted child and 1×1 shows the "已删除" placeholder.
            WidgetUpdater.updateAllWidgets(this@HubActivity)
            Toast.makeText(this@HubActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Clear-data flow (task 4.5): confirm → ProfileManager.clearBrowsingData → toast.
     * The child record is preserved.
     */
    private fun confirmClearData(child: ChildApp) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_clear_title)
            .setMessage(getString(R.string.confirm_clear_message, child.name))
            .setPositiveButton(R.string.action_clear_data) { _, _ -> doClearData(child) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun doClearData(child: ChildApp) {
        lifecycleScope.launch {
            try {
                ProfileManager.clearBrowsingData(this@HubActivity, child.id)
                Toast.makeText(this@HubActivity, R.string.toast_clear_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@HubActivity, R.string.toast_clear_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- shortcut pin helper ---------------------------------------------------

    private fun pinShortcutFor(child: ChildApp, showFeedback: Boolean = false) {
        if (!ShortcutHelper.isPinSupported(this)) {
            if (showFeedback) Toast.makeText(this, R.string.toast_pin_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = ShortcutHelper.resolveIconBitmap(this, child) ?: return
        when (ShortcutHelper.requestPin(this, child, bmp)) {
            ShortcutHelper.PinResult.SUCCESS -> if (showFeedback) {
                Toast.makeText(this, R.string.toast_pin_requested, Toast.LENGTH_SHORT).show()
            }
            ShortcutHelper.PinResult.UNSUPPORTED -> if (showFeedback) {
                Toast.makeText(this, R.string.toast_pin_unsupported, Toast.LENGTH_SHORT).show()
            }
            ShortcutHelper.PinResult.REJECTED -> if (showFeedback) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.pin_permission_title)
                    .setMessage(getString(R.string.pin_permission_message, child.name))
                    .setPositiveButton(R.string.pin_permission_open) { _, _ ->
                        ShortcutHelper.openShortcutPermissionSettings(this)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    // ---- 4.6 onboarding --------------------------------------------------------

    private fun maybeShowOnboarding() {
        if (prefs.getBoolean(PREF_ONBOARDING_DONE, false)) {
            onboardingOverlay.visibility = View.GONE
            return
        }
        onboardingOverlay.visibility = View.VISIBLE
        refreshOnboardingStatuses()
    }

    private fun onNotifButton() {
        if (isNotificationGranted()) {
            Toast.makeText(this, R.string.onboarding_notif_granted, Toast.LENGTH_SHORT).show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below API 33 the permission is granted at install time; just refresh.
            refreshOnboardingStatuses()
        }
    }

    private fun onBatteryButton() {
        if (isBatteryWhitelisted()) {
            Toast.makeText(this, R.string.onboarding_battery_granted, Toast.LENGTH_SHORT).show()
        } else {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {
                // Some ROMs don't expose this intent; ignore silently.
            }
        }
    }

    private fun dismissOnboarding() {
        prefs.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
        onboardingOverlay.visibility = View.GONE
        onboardingPollHandler.removeCallbacks(onboardingPollRunnable)
    }

    private fun refreshOnboardingStatuses() {
        if (isNotificationGranted()) {
            notifStatus.text = getString(R.string.onboarding_notif_granted)
            notifStatus.setTextColor(STATUS_OK_COLOR)
        } else {
            notifStatus.text = getString(R.string.onboarding_notif_pending)
            notifStatus.setTextColor(STATUS_PENDING_COLOR)
        }
        if (isBatteryWhitelisted()) {
            batteryStatus.text = getString(R.string.onboarding_battery_granted)
            batteryStatus.setTextColor(STATUS_OK_COLOR)
        } else {
            batteryStatus.text = getString(R.string.onboarding_battery_pending)
            batteryStatus.setTextColor(STATUS_PENDING_COLOR)
        }
    }

    private fun isNotificationGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryWhitelisted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    override fun onResume() {
        super.onResume()
        // If the onboarding overlay is visible, refresh statuses (user may have just granted
        // a permission in the system page). Also start the poll runnable as a belt-and-braces
        // refresh for devices that fire neither onResume nor onWindowFocusChanged reliably.
        if (onboardingOverlay.visibility == View.VISIBLE) {
            refreshOnboardingStatuses()
            onboardingPollHandler.removeCallbacks(onboardingPollRunnable)
            onboardingPollHandler.post(onboardingPollRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        onboardingPollHandler.removeCallbacks(onboardingPollRunnable)
    }

    override fun onDestroy() {
        onboardingPollHandler.removeCallbacks(onboardingPollRunnable)
        super.onDestroy()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    companion object {
        private const val PREFS_NAME = "webhub_prefs"
        private const val PREF_ONBOARDING_DONE = "onboarding_done"
        private const val MENU_EXPORT = 1
        private const val MENU_IMPORT = 2
        private const val POLL_INTERVAL_MS = 500L

        // Status label colors (kept as raw ints to avoid creating a colors.xml resource the
        // wave isn't allowed to own). Green-ish for granted, amber-ish for pending.
        private const val STATUS_OK_COLOR = 0xFF2E7D32.toInt()
        private const val STATUS_PENDING_COLOR = 0xFFEF6C00.toInt()
    }
}
