package net.marscore.webhub.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for child-app CRUD. Hard API contract — later waves call exactly these
 * signatures. Runs DB work on the Room/Flow dispatchers; callers should use a coroutine scope.
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

    fun observeAll(): Flow<List<ChildApp>> = dao.observeAll()
}