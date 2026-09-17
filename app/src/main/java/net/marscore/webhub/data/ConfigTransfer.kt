package net.marscore.webhub.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.marscore.webhub.icons.FaviconFetcher
import net.marscore.webhub.icons.ImageIconProcessor
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.icons.UrlValidator
import net.marscore.webhub.notifications.ChildNotificationChannels
import net.marscore.webhub.shell.ProfileManager
import net.marscore.webhub.shortcuts.ShortcutHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * JSON config import / export for the full child-app set (revamp-hub-ux D6).
 *
 * Uses only `org.json` (no new dependencies). JSON schema v1:
 * ```json
 * {
 *   "version": 1,
 *   "exportedAt": 1758000000000,
 *   "apps": [
 *     {"name":"...","url":"...","iconSource":"favicon|preset|upload",
 *      "presetKey":"mail","iconPngBase64":"...","uaMode":"default",
 *      "zoomPercent":0,"ignoreSsl":false}
 *   ]
 * }
 * ```
 *
 * Export writes the file to a SAF Uri. favicon source records only the source (no icon bytes —
 * import re-fetches); upload source base64-encodes the on-disk PNG from [IconStore]; preset
 * records the key.
 *
 * Import validates the whole file first (structure + each entry: name non-blank, URL normalizes
 * via [UrlValidator.normalize]); any failure rejects the whole import with no side effects. After
 * the user picks a mode:
 *  - [ImportMode.OVERWRITE]: run the full delete path for every existing child
 *    (repository.delete + ProfileManager.deleteProfileData + ChildNotificationChannels.removeChannel
 *    + ShortcutHelper.disable) before inserting imported entries.
 *  - [ImportMode.APPEND]: skip entries whose normalized URL already exists.
 *
 * Per imported entry: insert ChildApp → rebuild icon (favicon re-fetch; preset key lookup with
 * pickForId fallback for unknown keys; upload base64 → ImageIconProcessor) → update row →
 * ChildNotificationChannels.ensureChannel. **Never** calls ShortcutHelper.requestPin (import is a
 * batch background operation; pinning is user-initiated elsewhere).
 */
object ConfigTransfer {

    /** Import merge strategy chosen by the user after a valid file is parsed. */
    enum class ImportMode {
        /** Delete all existing child apps (full delete path) then insert imported entries. */
        OVERWRITE,
        /** Keep existing entries; skip imported entries whose normalized URL already exists. */
        APPEND,
    }

    /**
     * Outcome of an import. [added]/[skipped] count entries actually inserted / skipped (duplicates
     * in APPEND mode). [error] is non-null when the whole import was rejected (validation failure
     * or IO error) — in that case nothing was changed and added/skipped are 0.
     */
    data class ImportResult(val added: Int, val skipped: Int, val error: String? = null)

    /** Outcome of an export. [count] is the number of apps written; [error] non-null on failure. */
    data class ExportResult(val count: Int, val error: String? = null)

    private const val TAG = "ConfigTransfer"
    private const val SCHEMA_VERSION = 1
    private val KNOWN_ICON_SOURCES = setOf("favicon", "preset", "upload")

    // ------------------------------------------------------------------ export

    /**
     * Export all child apps to [dest] as JSON. favicon → records source only; upload → base64 PNG
     * read from [IconStore]; preset → records key.
     */
    suspend fun export(context: Context, dest: Uri): ExportResult = withContext(Dispatchers.IO) {
        val repo = ChildAppRepository(context)
        val iconStore = IconStore(context)
        val apps = repo.observeAll().first()
        val appsArray = JSONArray()
        for (app in apps) {
            val obj = JSONObject()
            obj.put("name", app.name)
            obj.put("url", app.url)
            obj.put("iconSource", app.iconSource)
            when (app.iconSource) {
                "preset" -> {
                    obj.put("presetKey", app.iconPath ?: "")
                }
                "upload" -> {
                    val base64 = readIconAsBase64(iconStore, app.id, app.iconPath)
                    if (base64 != null) obj.put("iconPngBase64", base64)
                }
                "favicon" -> {
                    // No icon payload — import re-fetches from the URL.
                }
            }
            obj.put("uaMode", app.uaMode)
            obj.put("zoomPercent", app.zoomPercent)
            obj.put("ignoreSsl", app.ignoreSsl)
            appsArray.put(obj)
        }
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("apps", appsArray)

        val written = try {
            context.contentResolver.openOutputStream(dest)?.use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                true
            } ?: return@withContext ExportResult(0, "io")
        } catch (e: Exception) {
            Log.w(TAG, "export write failed: ${e.message}")
            return@withContext ExportResult(0, "io")
        }
        if (!written) return@withContext ExportResult(0, "io")
        ExportResult(apps.size)
    }

    /** Read the on-disk icon PNG for [childId] and base64-encode it; null if missing. */
    private fun readIconAsBase64(iconStore: IconStore, childId: Long, iconPath: String?): String? {
        val path = iconPath ?: iconStore.pathFor(childId)
        val file = java.io.File(path)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            // Sanity: must decode as a bitmap to be a valid PNG; otherwise skip.
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "readIconAsBase64 failed for $childId: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Parse + validate the file at [src], then apply it under [mode]. Validation runs entirely
     * before any mutation: on any structural / field failure returns [ImportResult.error] non-null
     * with nothing changed.
     */
    suspend fun import(context: Context, src: Uri, mode: ImportMode): ImportResult =
        withContext(Dispatchers.IO) {
            val text = try {
                context.contentResolver.openInputStream(src)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                Log.w(TAG, "import read failed: ${e.message}")
                null
            } ?: return@withContext ImportResult(0, 0, "io")

            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                return@withContext ImportResult(0, 0, "bad_json")
            }

            // Structural validation.
            if (parsed.optInt("version", -1) != SCHEMA_VERSION) {
                return@withContext ImportResult(0, 0, "bad_version")
            }
            val appsArray = parsed.optJSONArray("apps") ?: return@withContext ImportResult(0, 0, "bad_structure")
            val entries = mutableListOf<ParsedEntry>()
            for (i in 0 until appsArray.length()) {
                val obj = appsArray.optJSONObject(i) ?: return@withContext ImportResult(0, 0, "bad_entry")
                val name = obj.optString("name").trim()
                if (name.isBlank()) return@withContext ImportResult(0, 0, "bad_name")
                val rawUrl = obj.optString("url").trim()
                val normalizedUrl = UrlValidator.normalize(rawUrl)
                    ?: return@withContext ImportResult(0, 0, "bad_url")
                val rawSource = obj.optString("iconSource", "favicon").trim().lowercase()
                val iconSource = if (rawSource in KNOWN_ICON_SOURCES) rawSource else "favicon"
                val presetKey = obj.optString("presetKey", "").takeIf { it.isNotBlank() }
                val iconPngBase64 = obj.optString("iconPngBase64", "").takeIf { it.isNotBlank() }
                val uaMode = obj.optString("uaMode", "default").trim().lowercase()
                val zoomPercent = obj.optInt("zoomPercent", 0)
                val ignoreSsl = obj.optBoolean("ignoreSsl", false)
                entries.add(
                    ParsedEntry(
                        name = name,
                        url = normalizedUrl,
                        iconSource = iconSource,
                        presetKey = presetKey,
                        iconPngBase64 = iconPngBase64,
                        uaMode = uaMode,
                        zoomPercent = zoomPercent,
                        ignoreSsl = ignoreSsl,
                    )
                )
            }

            applyEntries(context, entries, mode)
        }

    /** After validation, perform the DB mutation under [mode]. */
    private suspend fun applyEntries(
        context: Context,
        entries: List<ParsedEntry>,
        mode: ImportMode,
    ): ImportResult {
        val repo = ChildAppRepository(context)
        val existing = repo.observeAll().first()
        val existingNormalizedUrls = existing.mapNotNull { UrlValidator.normalize(it.url) }.toSet()

        if (mode == ImportMode.OVERWRITE) {
            // Full delete path for every existing child.
            for (child in existing) {
                repo.delete(child) // also wipes the icon file
                try { ProfileManager.deleteProfileData(context, child.id) } catch (_: Throwable) {}
                try { ChildNotificationChannels.removeChannel(context, child.id) } catch (_: Throwable) {}
                try { ShortcutHelper.disable(context, child.id) } catch (_: Throwable) {}
            }
        }

        val imageProcessor = ImageIconProcessor(context)
        var added = 0
        var skipped = 0
        for (entry in entries) {
            if (mode == ImportMode.APPEND && entry.url in existingNormalizedUrls) {
                skipped++
                continue
            }
            val inserted = insertOne(context, repo, imageProcessor, entry)
            if (inserted != null) {
                ChildNotificationChannels.ensureChannel(context, inserted)
                added++
            } else {
                // An insert should never fail; count as skipped defensively.
                skipped++
            }
        }
        return ImportResult(added = added, skipped = skipped, error = null)
    }

    /**
     * Insert one entry, rebuild its icon per source, update the row, and return the final row.
     * Returns null only if the DB insert returned an invalid id (defensive — shouldn't happen).
     */
    private suspend fun insertOne(
        context: Context,
        repo: ChildAppRepository,
        imageProcessor: ImageIconProcessor,
        entry: ParsedEntry,
    ): ChildApp? {
        val base = ChildApp(
            name = entry.name,
            url = entry.url,
            iconSource = entry.iconSource,
            iconPath = when (entry.iconSource) {
                "preset" -> entry.presetKey // may be null/unknown; resolved below
                else -> null
            },
            uaMode = entry.uaMode,
            zoomPercent = entry.zoomPercent,
            ignoreSsl = entry.ignoreSsl,
        )
        val id = repo.insert(base)
        if (id <= 0) return null
        val inserted = base.copy(id = id)

        val finalRow = when (entry.iconSource) {
            "favicon" -> {
                // Re-fetch; on failure fall back to pickForId preset (D6 allows this in import).
                val result = FaviconFetcher().fetch(entry.url, entry.ignoreSsl)
                val bmp = result.bitmap
                if (bmp != null) {
                    val path = try { imageProcessor.processAndSave(id, bmp) } finally { bmp.recycle() }
                    inserted.copy(iconSource = "favicon", iconPath = path)
                } else {
                    val preset = PresetIcons.pickForId(id)
                    inserted.copy(iconSource = "preset", iconPath = preset.key)
                }
            }
            "preset" -> {
                val key = entry.presetKey
                if (key != null && PresetIcons.resForKey(key) != 0) {
                    inserted.copy(iconSource = "preset", iconPath = key)
                } else {
                    // Unknown preset key → fall back to pickForId with the new id (D6).
                    val preset = PresetIcons.pickForId(id)
                    inserted.copy(iconSource = "preset", iconPath = preset.key)
                }
            }
            "upload" -> {
                val base64 = entry.iconPngBase64
                if (!base64.isNullOrBlank()) {
                    val path = decodeBase64AndSave(imageProcessor, id, base64)
                    if (path != null) {
                        inserted.copy(iconSource = "upload", iconPath = path)
                    } else {
                        // Bad base64 / undecodable → preset fallback.
                        val preset = PresetIcons.pickForId(id)
                        inserted.copy(iconSource = "preset", iconPath = preset.key)
                    }
                } else {
                    val preset = PresetIcons.pickForId(id)
                    inserted.copy(iconSource = "preset", iconPath = preset.key)
                }
            }
            else -> inserted // unreachable: iconSource was validated to a known value
        }

        repo.update(finalRow)
        return finalRow
    }

    /** Decode a base64 PNG → bytes → ImageIconProcessor.processAndSave; null on any failure. */
    private fun decodeBase64AndSave(
        imageProcessor: ImageIconProcessor,
        childId: Long,
        base64: String,
    ): String? {
        val bytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "base64 decode failed: ${e.message}")
            return null
        }
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            imageProcessor.processAndSave(childId, bmp)
        } finally {
            bmp.recycle()
        }
    }

    /** A single parsed-and-validated import entry (post-normalization). */
    private data class ParsedEntry(
        val name: String,
        val url: String,
        val iconSource: String,
        val presetKey: String?,
        val iconPngBase64: String?,
        val uaMode: String,
        val zoomPercent: Int,
        val ignoreSsl: Boolean,
    )
}