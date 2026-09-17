package net.marscore.webhub.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import java.io.File

/**
 * Exercises the real [ChildAppRepository] (which builds its own disk-backed Room DB + IconStore
 * via the constructor) to lock down the public API contract end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChildAppRepositoryTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: ChildAppRepository
    private lateinit var iconStore: IconStore

    @Before fun setup() {
        AppDatabase.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
        iconStore = IconStore(context)
    }

    @After fun teardown() {
        AppDatabase.resetForTest()
    }

    @Test fun crudRoundTrip() = runTest {
        val id = repo.insert(ChildApp(name = "First", url = "https://first.example.com"))
        assertTrue(id > 0)

        val loaded = repo.getById(id)
        assertNotNull(loaded)
        assertEquals("First", loaded!!.name)

        repo.update(loaded.copy(name = "Renamed"))
        assertEquals("Renamed", repo.getById(id)!!.name)

        repo.delete(repo.getById(id)!!)
        assertNull(repo.getById(id))
    }

    @Test fun deleteAlsoRemovesIconFile() = runTest {
        val id = repo.insert(ChildApp(name = "Iconized", url = "https://x.example.com"))
        val path = iconStore.save(id, solidBitmap(8, Color.RED))
        assertTrue(File(path).exists())

        repo.delete(repo.getById(id)!!)
        assertFalse(File(path).exists())
    }

    @Test fun observeAllReturnsInsertedRows() = runTest {
        repo.insert(ChildApp(name = "A", url = "https://a.example.com", createdAt = 1000L))
        repo.insert(ChildApp(name = "B", url = "https://b.example.com", createdAt = 2000L))
        val all = repo.observeAll().first()
        assertEquals(listOf("A", "B"), all.map { it.name })
    }

    private fun solidBitmap(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }
}