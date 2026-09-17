package net.marscore.webhub.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates Room migration v1 → v2 (adding `lastOpenedAt`).
 *
 * Approach: build an in-memory Room DB (which auto-creates the *current* v3 `child_app` schema),
 * then drop that table and re-create it by hand with the **pre-v2** schema (no `lastOpenedAt`),
 * seed it with rows, and finally invoke [MIGRATION_1_2.migrate] directly on the
 * [SupportSQLiteDatabase]. This exercises the migration against a real SQLite table that looks
 * exactly like a v1 install, without needing exported schema JSON (the project uses
 * `exportSchema = false`) or the internal `FrameworkSQLiteOpenHelper`.
 *
 * See OpenSpec change `add-home-screen-widgets` task 1.5 and design D8.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppDatabaseMigrationTest {

    private lateinit var db: SupportSQLiteDatabase
    private lateinit var room: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room.openHelper.writableDatabase

        // Replace the auto-created v2 table with the pre-v2 (v1) shape: no lastOpenedAt column.
        db.execSQL("DROP TABLE IF EXISTS child_app")
        db.execSQL(
            "CREATE TABLE child_app (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "iconSource TEXT NOT NULL, " +
                "iconPath TEXT, " +
                "uaMode TEXT NOT NULL, " +
                "zoomPercent INTEGER NOT NULL, " +
                "ignoreSsl INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL" +
                ")"
        )

        // Sanity: confirm we actually started at a v1-shaped schema (no lastOpenedAt).
        assertFalse("v1 schema should NOT have lastOpenedAt", hasColumn("lastOpenedAt"))
    }

    @After
    fun teardown() {
        room.close()
    }

    @Test
    fun migration_1_2_adds_lastOpenedAt_column_with_default_0() {
        // Empty table: migration just adds the column.
        MIGRATION_1_2.migrate(db)

        assertTrue("lastOpenedAt column should exist after migration", hasColumn("lastOpenedAt"))

        // Insert a row using the new schema and confirm lastOpenedAt defaults to 0 when omitted.
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt) " +
                "VALUES ('DefaultCheck', 'https://default.example.com', 'favicon', NULL, 'default', 0, 0, 1000)"
        )
        db.query("SELECT lastOpenedAt FROM child_app WHERE name = 'DefaultCheck'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("lastOpenedAt")))
        }
    }

    @Test
    fun migration_1_2_preserves_existing_rows() {
        // Two pre-existing v1 rows (no lastOpenedAt in their insert).
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt) " +
                "VALUES ('First', 'https://first.example.com', 'favicon', NULL, 'default', 0, 0, 1000)"
        )
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt) " +
                "VALUES ('Second', 'https://second.example.com', 'upload', '/data/icons/2.png', 'desktop', 110, 1, 2000)"
        )

        MIGRATION_1_2.migrate(db)

        assertTrue("lastOpenedAt column should exist after migration", hasColumn("lastOpenedAt"))

        db.query(
            "SELECT name, url, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt " +
                "FROM child_app ORDER BY createdAt ASC"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("First", c.getString(c.getColumnIndexOrThrow("name")))
            assertEquals("https://first.example.com", c.getString(c.getColumnIndexOrThrow("url")))
            assertEquals("default", c.getString(c.getColumnIndexOrThrow("uaMode")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("zoomPercent")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("ignoreSsl")))
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("lastOpenedAt")))

            assertTrue(c.moveToNext())
            assertEquals("Second", c.getString(c.getColumnIndexOrThrow("name")))
            assertEquals("https://second.example.com", c.getString(c.getColumnIndexOrThrow("url")))
            assertEquals("/data/icons/2.png", c.getString(c.getColumnIndexOrThrow("iconPath")))
            assertEquals("desktop", c.getString(c.getColumnIndexOrThrow("uaMode")))
            assertEquals(110, c.getInt(c.getColumnIndexOrThrow("zoomPercent")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("ignoreSsl")))
            assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("lastOpenedAt")))

            assertFalse(c.moveToNext())
        }
    }

    private fun hasColumn(name: String): Boolean {
        db.query("PRAGMA table_info(child_app)").use { c ->
            while (c.moveToNext()) {
                val colName = c.getString(c.getColumnIndexOrThrow("name"))
                if (colName == name) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // v2 → v3: add `displayMode TEXT NOT NULL DEFAULT 'system'`.
    // See OpenSpec change `improve-webapp-shell-ux` task 5.3 / design D1.
    // ------------------------------------------------------------------

    /**
     * Re-creates `child_app` with the **pre-v3** (v2) schema: all v2 columns including
     * `lastOpenedAt` but no `displayMode`. Call this from v2→v3 tests instead of relying on
     * the `@Before` which sets up a v1-shaped table.
     */
    private fun recreateAsV2Schema() {
        db.execSQL("DROP TABLE IF EXISTS child_app")
        db.execSQL(
            "CREATE TABLE child_app (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "url TEXT NOT NULL, " +
                "iconSource TEXT NOT NULL, " +
                "iconPath TEXT, " +
                "uaMode TEXT NOT NULL, " +
                "zoomPercent INTEGER NOT NULL, " +
                "ignoreSsl INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "lastOpenedAt INTEGER NOT NULL" +
                ")"
        )
        assertFalse("v2 schema should NOT have displayMode", hasColumn("displayMode"))
    }

    @Test
    fun migration_2_3_adds_displayMode_column_with_default_system() {
        recreateAsV2Schema()

        // Empty table: migration just adds the column.
        MIGRATION_2_3.migrate(db)

        assertTrue("displayMode column should exist after migration", hasColumn("displayMode"))

        // Insert a row using the new schema and confirm displayMode defaults to 'system' when omitted.
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt) " +
                "VALUES ('DefaultCheck', 'https://default.example.com', 'favicon', NULL, 'default', 0, 0, 1000, 0)"
        )
        db.query("SELECT displayMode FROM child_app WHERE name = 'DefaultCheck'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("system", c.getString(c.getColumnIndexOrThrow("displayMode")))
        }
    }

    @Test
    fun migration_2_3_preserves_existing_rows_and_defaults_displayMode_to_system() {
        recreateAsV2Schema()

        // Two pre-existing v2 rows (no displayMode in their insert).
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt) " +
                "VALUES ('First', 'https://first.example.com', 'favicon', NULL, 'default', 0, 0, 1000, 5000)"
        )
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt) " +
                "VALUES ('Second', 'https://second.example.com', 'upload', '/data/icons/2.png', 'desktop', 110, 1, 2000, 9000)"
        )

        MIGRATION_2_3.migrate(db)

        assertTrue("displayMode column should exist after migration", hasColumn("displayMode"))

        db.query(
            "SELECT name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt, displayMode " +
                "FROM child_app ORDER BY createdAt ASC"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("First", c.getString(c.getColumnIndexOrThrow("name")))
            assertEquals("https://first.example.com", c.getString(c.getColumnIndexOrThrow("url")))
            assertEquals("favicon", c.getString(c.getColumnIndexOrThrow("iconSource")))
            assertNull(c.getString(c.getColumnIndexOrThrow("iconPath")))
            assertEquals("default", c.getString(c.getColumnIndexOrThrow("uaMode")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("zoomPercent")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("ignoreSsl")))
            assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
            assertEquals(5000L, c.getLong(c.getColumnIndexOrThrow("lastOpenedAt")))
            assertEquals("system", c.getString(c.getColumnIndexOrThrow("displayMode")))

            assertTrue(c.moveToNext())
            assertEquals("Second", c.getString(c.getColumnIndexOrThrow("name")))
            assertEquals("https://second.example.com", c.getString(c.getColumnIndexOrThrow("url")))
            assertEquals("upload", c.getString(c.getColumnIndexOrThrow("iconSource")))
            assertEquals("/data/icons/2.png", c.getString(c.getColumnIndexOrThrow("iconPath")))
            assertEquals("desktop", c.getString(c.getColumnIndexOrThrow("uaMode")))
            assertEquals(110, c.getInt(c.getColumnIndexOrThrow("zoomPercent")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("ignoreSsl")))
            assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("createdAt")))
            assertEquals(9000L, c.getLong(c.getColumnIndexOrThrow("lastOpenedAt")))
            assertEquals("system", c.getString(c.getColumnIndexOrThrow("displayMode")))

            assertFalse(c.moveToNext())
        }
    }

    @Test
    fun migration_2_3_allows_fullscreen_value_to_be_stored() {
        recreateAsV2Schema()

        MIGRATION_2_3.migrate(db)

        // Insert a row that explicitly sets displayMode = 'fullscreen'.
        db.execSQL(
            "INSERT INTO child_app (name, url, iconSource, iconPath, uaMode, zoomPercent, ignoreSsl, createdAt, lastOpenedAt, displayMode) " +
                "VALUES ('Immersive', 'https://game.example.com', 'preset', 'ic_game', 'mobile', 100, 0, 3000, 0, 'fullscreen')"
        )
        db.query("SELECT displayMode FROM child_app WHERE name = 'Immersive'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fullscreen", c.getString(c.getColumnIndexOrThrow("displayMode")))
        }
    }
}