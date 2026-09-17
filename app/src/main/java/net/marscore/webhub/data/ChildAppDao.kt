package net.marscore.webhub.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildAppDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(child: ChildApp): Long

    @Update
    suspend fun update(child: ChildApp)

    @Delete
    suspend fun delete(child: ChildApp)

    @Query("SELECT * FROM child_app WHERE id = :id")
    suspend fun getById(id: Long): ChildApp?

    @Query("SELECT * FROM child_app ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ChildApp>>

    @Query("SELECT * FROM child_app ORDER BY createdAt ASC")
    suspend fun getAll(): List<ChildApp>
}