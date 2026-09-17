package net.marscore.webhub.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for child-app CRUD. Hard API contract — later waves call exactly these
 * signatures. Runs DB work on the Room/Flow dispatchers; callers should use a coroutine scope.
 *
 * Recency API:
 *  - [touchLastOpened] records the current time as a child app's `lastOpenedAt`; call it when
 *    the user opens that child app's WebView shell (see design D3).
 *  - [getAllByRecency] returns all child apps ordered by `lastOpenedAt` descending, with
 *    never-opened apps (value 0) ordered by [ChildApp.createdAt] ascending as a tie-breaker.
 */
class ChildAppRepository(context: Context) {

    private val dao = AppDatabase.get(context).childAppDao()
    private val iconStore = IconStore(context)

    suspend fun insert(child: ChildApp): Long = dao.insert(child)

    suspend fun update(child: ChildApp) = dao.update(child)

    /**
     * Delete [child] and also remove its on-disk icon file (if any). Preset icons have no file
     * to clean up; deleteFor is simply a no-op in that case.
     */
    suspend fun delete(child: ChildApp) {
        dao.delete(child)
        iconStore.deleteFor(child.id)
    }

    suspend fun getById(id: Long): ChildApp? = dao.getById(id)

    /** One-shot snapshot of all child apps, ordered by [ChildApp.createdAt] ascending. */
    suspend fun getAll(): List<ChildApp> = dao.getAll()

    /** Update [id]'s `lastOpenedAt` to the current time (idempotent overwrite semantics). */
    suspend fun touchLastOpened(id: Long) = dao.updateLastOpened(id, System.currentTimeMillis())

    /**
     * One-shot snapshot of all child apps ordered by `lastOpenedAt` descending; never-opened
     * apps (value 0) fall back to [ChildApp.createdAt] ascending. Consumed by home-screen widgets.
     */
    suspend fun getAllByRecency(): List<ChildApp> = dao.getAllByRecency()

    fun observeAll(): Flow<List<ChildApp>> = dao.observeAll()
}