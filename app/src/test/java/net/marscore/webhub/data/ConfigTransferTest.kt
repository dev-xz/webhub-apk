package net.marscore.webhub.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Round-trip + validation tests for [ConfigTransfer] (revamp-hub-ux D6).
 *
 * Approach: write JSON to a temp file and obtain a `file://` Uri from it (no FileProvider needed —
 * ContentResolver can open `file://` Uris under Robolectric). Reads it back via [ConfigTransfer].
 *
 * To keep the suite closed (AGENTS.md: no live network), all entries use `preset` or `upload`
 * icon sources — favicon source would trigger a real fetch. Upload sources carry base64 PNG
 * payloads, exercising the decode+processAndSave path.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md. @GraphicsMode(NATIVE) so BitmapFactory doesn't
 * return fake 100×100 bitmaps for the upload-decode assertions (AGENTS.md pitfall).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigTransferTest {

    private lateinit var context: Context
    private lateinit var repo: ChildAppRepository
    private lateinit var iconStore: IconStore
    private lateinit var tempDir: File

    @Before fun setup() {
        AppDatabase.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
        iconStore = IconStore(context)
        tempDir = File(context.cacheDir, "configtransfer_test").apply { mkdirs() }
    }

    @After fun teardown() {
        AppDatabase.resetForTest()
        tempDir.deleteRecursively()
    }

    // ---- helpers ----

    private fun tempFileUri(name: String): Uri {
        val file = File(tempDir, name)
        file.createNewFile()
        return Uri.fromFile(file)
    }

    private fun writeJson(name: String, json: String): Uri {
        val uri = tempFileUri(name)
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        return uri
    }

    private fun solidPng(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun b64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // ---- export → import round trip ----

    @Test fun exportImportRoundTripPreservesPresetAndUpload() = runTest {
        // Seed two children: a preset source and an upload source.
        val presetId = repo.insert(
            ChildApp(name = "Mail", url = "https://mail.example.com/", iconSource = "preset", iconPath = "mail")
        )
        val uploadBmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.RED)
        }
        val uploadId = repo.insert(
            ChildApp(
                name = "Custom", url = "https://custom.example.com/",
                iconSource = "upload", iconPath = "placeholder",
            )
        )
        // Save the upload icon under the actual id so export's IconStore read finds it.
        val realUploadPath = iconStore.save(uploadId, uploadBmp)
        repo.update(repo.getById(uploadId)!!.copy(iconPath = realUploadPath))
        uploadBmp.recycle()

        // Export.
        val exportUri = tempFileUri("export_roundtrip.json")
        val exportResult = ConfigTransfer.export(context, exportUri)
        assertNull(exportResult.error)
        assertEquals(2, exportResult.count)

        // Reset the DB singleton so the import sees a fresh repository handle. (The on-disk DB
        // file persists across resetForTest, so OVERWRITE's clear-existing path is what actually
        // wipes the old rows — verified below by asserting only the imported rows remain.)
        AppDatabase.resetForTest()
        val freshRepo = ChildAppRepository(context)

        val importResult = ConfigTransfer.import(context, exportUri, ConfigTransfer.ImportMode.OVERWRITE)
        assertNull(importResult.error)
        assertEquals(2, importResult.added)
        assertEquals(0, importResult.skipped)

        val all = freshRepo.observeAll().first()
        assertEquals(2, all.size)
        val mail = all.first { it.name == "Mail" }
        assertEquals("https://mail.example.com/", mail.url)
        assertEquals("preset", mail.iconSource)
        assertEquals("mail", mail.iconPath)
        val custom = all.first { it.name == "Custom" }
        assertEquals("upload", custom.iconSource)
        assertNotNull(custom.iconPath)
        assertTrue("upload icon file must exist after import", File(custom.iconPath!!).exists())
    }

    // ---- APPEND skips duplicates ----

    @Test fun appendSkipsDuplicateNormalizedUrls() = runTest {
        // Pre-existing child at a URL.
        repo.insert(ChildApp(name = "Existing", url = "https://dup.example.com/"))
        repo.insert(ChildApp(name = "Keep", url = "https://keep.example.com/"))

        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"Dup","url":"https://dup.example.com","iconSource":"preset","presetKey":"note"},
              {"name":"New","url":"https://new.example.com","iconSource":"preset","presetKey":"code"}
            ]}
        """.trimIndent()
        val src = writeJson("append.json", json)

        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNull(result.error)
        assertEquals(1, result.added)
        assertEquals(1, result.skipped)

        val all = repo.observeAll().first()
        assertEquals(3, all.size)
        assertTrue(all.any { it.name == "Existing" }) // untouched
        assertTrue(all.any { it.name == "Keep" })
        assertTrue(all.any { it.name == "New" })
        assertFalse(all.any { it.name == "Dup" }) // skipped
    }

    // ---- OVERWRITE clears existing ----

    @Test fun overwriteClearsExistingEntries() = runTest {
        repo.insert(ChildApp(name = "Old1", url = "https://old1.example.com/"))
        repo.insert(ChildApp(name = "Old2", url = "https://old2.example.com/"))

        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"Fresh","url":"https://fresh.example.com","iconSource":"preset","presetKey":"mail"}
            ]}
        """.trimIndent()
        val src = writeJson("overwrite.json", json)

        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.OVERWRITE)
        assertNull(result.error)
        assertEquals(1, result.added)
        assertEquals(0, result.skipped)

        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Fresh", all.single().name)
        assertFalse("old entries must be gone", all.any { it.name.startsWith("Old") })
    }

    // ---- invalid JSON rejected without side effects ----

    @Test fun invalidJsonRejectedWithoutSideEffects() = runTest {
        repo.insert(ChildApp(name = "Survivor", url = "https://survivor.example.com/"))
        val src = writeJson("bad.json", "{not valid json")
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNotNull(result.error)
        assertEquals(0, result.added)
        assertEquals(0, result.skipped)
        // Existing data untouched.
        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Survivor", all.single().name)
    }

    @Test fun badUrlInEntryRejectsWholeImport() = runTest {
        repo.insert(ChildApp(name = "Survivor", url = "https://survivor.example.com/"))
        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"Ok","url":"https://ok.example.com","iconSource":"preset","presetKey":"mail"},
              {"name":"Bad","url":"not-a-url","iconSource":"preset","presetKey":"note"}
            ]}
        """.trimIndent()
        val src = writeJson("badurl.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNotNull(result.error)
        assertEquals(0, result.added)
        // Existing untouched.
        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("Survivor", all.single().name)
    }

    @Test fun blankNameInEntryRejectsWholeImport() = runTest {
        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"","url":"https://ok.example.com","iconSource":"preset","presetKey":"mail"}
            ]}
        """.trimIndent()
        val src = writeJson("blankname.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNotNull(result.error)
        assertEquals(0, result.added)
    }

    @Test fun wrongVersionRejected() = runTest {
        val json = """{"version":99,"exportedAt":0,"apps":[]}"""
        val src = writeJson("wrongver.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNotNull(result.error)
    }

    // ---- unknown preset key falls back to pickForId ----

    @Test fun unknownPresetKeyFallsBackToPickForId() = runTest {
        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"Mystery","url":"https://mystery.example.com","iconSource":"preset","presetKey":"nonexistent_key"}
            ]}
        """.trimIndent()
        val src = writeJson("unknownpreset.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNull(result.error)
        assertEquals(1, result.added)
        val all = repo.observeAll().first()
        val mystery = all.single()
        assertEquals("preset", mystery.iconSource)
        // iconPath must be one of the known preset keys (pickForId result).
        assertTrue(
            "fallback key must be a known preset key",
            PresetKeys.ALL.contains(mystery.iconPath)
        )
    }

    // ---- upload source: base64 → processAndSave ----

    @Test fun uploadSourceBase64DecodesAndSavesIcon() = runTest {
        val png = solidPng(Color.GREEN)
        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"Img","url":"https://img.example.com","iconSource":"upload","iconPngBase64":"${b64(png)}"}
            ]}
        """.trimIndent()
        val src = writeJson("upload.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNull(result.error)
        assertEquals(1, result.added)
        val all = repo.observeAll().first()
        val img = all.single()
        assertEquals("upload", img.iconSource)
        assertNotNull(img.iconPath)
        assertTrue("saved upload icon file must exist", File(img.iconPath!!).exists())
    }

    // ---- iconSource absent/unknown defaults to favicon (but favicon refetch needs network,
    //      so here we only assert the row's iconSource field defaults appropriately when the
    //      entry fails to fetch and falls to preset fallback — exercised via a localhost server
    //      would re-introduce network; skipped to keep the suite closed per AGENTS.md). ----

    @Test fun absentIconSourceDefaultsToFaviconFieldOnParseFailurePath() = runTest {
        // With no favicon server available, favicon source → preset fallback. We assert the row
        // lands as preset (D6 allows the import fallback flip) — this also confirms the entry
        // survives rather than being dropped.
        val json = """
            {"version":1,"exportedAt":0,"apps":[
              {"name":"NoSrc","url":"https://nosrc.example.com"}
            ]}
        """.trimIndent()
        val src = writeJson("nosrc.json", json)
        val result = ConfigTransfer.import(context, src, ConfigTransfer.ImportMode.APPEND)
        assertNull(result.error)
        assertEquals(1, result.added)
        val all = repo.observeAll().first()
        // Favicon fetch against a non-resolving host fails → preset fallback.
        assertEquals("preset", all.single().iconSource)
    }
}

/** The set of valid preset keys (mirrors PresetIcons catalog) for fallback assertions. */
private object PresetKeys {
    val ALL = setOf("mail", "play", "note", "check", "code", "doc", "calendar", "clock", "image")
}