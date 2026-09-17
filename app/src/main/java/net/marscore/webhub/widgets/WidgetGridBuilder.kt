package net.marscore.webhub.widgets

import net.marscore.webhub.data.ChildApp

/**
 * Pure selection helper for grid widgets (see OpenSpec change `add-home-screen-widgets`,
 * design D5 / risks "Robolectric").
 *
 * The DAO ([net.marscore.webhub.data.ChildAppDao.getAllByRecency]) is responsible for the
 * recency ordering — `lastOpenedAt DESC, createdAt ASC`. This function only applies the
 * per-size capacity truncation so it can be unit-tested without a database.
 *
 * Empty-capacity (0) yields an empty list; a capacity greater than the input size yields
 * the whole input (matrix leaves the surplus cells transparent — see spec "子应用数不足格数时留空").
 */
object WidgetGridBuilder {

    fun buildItems(all: List<ChildApp>, capacity: Int): List<ChildApp> {
        if (capacity <= 0) return emptyList()
        return all.take(capacity)
    }
}