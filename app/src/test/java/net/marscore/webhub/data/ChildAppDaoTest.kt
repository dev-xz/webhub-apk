package net.marscore.webhub.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.room.Room
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChildAppDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChildAppDao

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.childAppDao()
    }

    @After fun teardown() { db.close() }

    @Test fun insertAndReadBack() = runTest {
        val id = dao.insert(sample("A", "https://a.example.com"))
        assertTrue(id > 0)
        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("A", loaded!!.name)
    }

    @Test fun updateChangesFields() = runTest {
        val id = dao.insert(sample("A", "https://a.example.com"))
        val loaded = dao.getById(id)!!
        dao.update(loaded.copy(name = "B", uaMode = "desktop", zoomPercent = 120))
        val after = dao.getById(id)!!
        assertEquals("B", after.name)
        assertEquals("desktop", after.uaMode)
        assertEquals(120, after.zoomPercent)
    }

    @Test fun deleteRemovesRow() = runTest {
        val id = dao.insert(sample("A", "https://a.example.com"))
        dao.delete(dao.getById(id)!!)
        assertNull(dao.getById(id))
    }

    @Test fun observeAllOrderedByCreatedAtAsc() = runTest {
        dao.insert(sample("Older", "https://a.example.com").copy(createdAt = 1000L))
        dao.insert(sample("Newest", "https://c.example.com").copy(createdAt = 3000L))
        dao.insert(sample("Newer", "https://b.example.com").copy(createdAt = 2000L))
        val all = dao.observeAll().first()
        assertEquals(listOf("Older", "Newer", "Newest"), all.map { it.name })
    }

    @Test fun defaultsMatchContract() = runTest {
        val id = dao.insert(ChildApp(name = "D", url = "https://d.example.com"))
        val loaded = dao.getById(id)!!
        assertEquals("favicon", loaded.iconSource)
        assertNull(loaded.iconPath)
        assertEquals("default", loaded.uaMode)
        assertEquals(0, loaded.zoomPercent)
        assertFalse(loaded.ignoreSsl)
    }

    private fun sample(name: String, url: String) =
        ChildApp(name = name, url = url, createdAt = System.currentTimeMillis())
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IconStoreTest {

    private lateinit var store: IconStore
    private lateinit var context: android.content.Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = IconStore(context)
    }

    @Test fun saveCreatesFileAndLoadReadsItBack() {
        val bmp = solidBitmap(16, Color.BLUE)
        val path = store.save(101L, bmp)
        assertTrue(path.endsWith("/icons/101.png"))
        val loaded = store.load(path)
        assertNotNull(loaded)
        assertEquals(16, loaded!!.width)
    }

    @Test fun saveOverwritesExisting() {
        store.save(202L, solidBitmap(8, Color.RED))
        store.save(202L, solidBitmap(32, Color.GREEN))
        val path = store.pathFor(202L)
        val loaded = store.load(path)
        assertNotNull(loaded)
        assertEquals(32, loaded!!.width)
    }

    @Test fun deleteForRemovesFile() {
        val path = store.save(303L, solidBitmap(8, Color.RED))
        assertTrue(store.load(path) != null)
        store.deleteFor(303L)
        assertNull(store.load(path))
    }

    @Test fun deleteForMissingIsNoop() {
        // Should not throw.
        store.deleteFor(999L)
    }

    @Test fun loadMissingPathReturnsNull() {
        assertNull(store.load("/no/such/file.png"))
    }

    private fun solidBitmap(size: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return bmp
    }
}