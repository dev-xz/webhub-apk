package net.marscore.webhub.widgets

import net.marscore.webhub.data.ChildApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [WidgetGridBuilder.buildItems] — the capacity-truncation step of the
 * matrix widget data pipeline. Recency ordering is the DAO's responsibility (tested via
 * `ChildAppDaoTest`), so here the input is pre-sorted and we only verify the truncation +
 * empty/over-capacity edge cases (design D5, risks "Robolectric").
 */
class WidgetGridBuilderTest {

    private fun app(name: String, createdAt: Long, lastOpenedAt: Long = 0L): ChildApp =
        ChildApp(
            name = name,
            url = "https://$name.example.com",
            createdAt = createdAt,
            lastOpenedAt = lastOpenedAt
        )

    @Test
    fun six_apps_capacity_4_returns_first_4() {
        // Pre-sorted by recency (DAO contract): A most-recent, F least-recent.
        val apps = listOf(
            app("A", createdAt = 6000, lastOpenedAt = 3000),
            app("B", createdAt = 5000, lastOpenedAt = 2000),
            app("C", createdAt = 4000, lastOpenedAt = 1000),
            app("D", createdAt = 3000, lastOpenedAt = 0),
            app("E", createdAt = 2000, lastOpenedAt = 0),
            app("F", createdAt = 1000, lastOpenedAt = 0)
        )

        val result = WidgetGridBuilder.buildItems(apps, capacity = 4)

        assertEquals(4, result.size)
        assertEquals(apps.take(4), result)
        assertEquals(listOf("A", "B", "C", "D"), result.map { it.name })
    }

    @Test
    fun three_apps_capacity_8_returns_3() {
        val apps = listOf(
            app("A", createdAt = 3000, lastOpenedAt = 3000),
            app("B", createdAt = 2000, lastOpenedAt = 2000),
            app("C", createdAt = 1000, lastOpenedAt = 1000)
        )

        val result = WidgetGridBuilder.buildItems(apps, capacity = 8)

        // Capacity exceeds supply → all items returned; surplus cells stay transparent (spec).
        assertEquals(3, result.size)
        assertEquals(apps, result)
    }

    @Test
    fun empty_input_returns_empty() {
        val result = WidgetGridBuilder.buildItems(emptyList(), capacity = 4)
        assertTrue(result.isEmpty())
    }

    @Test
    fun capacity_zero_returns_empty() {
        val apps = listOf(app("A", createdAt = 1000))
        val result = WidgetGridBuilder.buildItems(apps, capacity = 0)
        assertTrue(result.isEmpty())
    }
}