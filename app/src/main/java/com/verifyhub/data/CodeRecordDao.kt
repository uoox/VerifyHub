package com.verifyhub.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeRecordDao {
    @Query("SELECT * FROM code_records ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 200): Flow<List<CodeRecord>>

    @Query("SELECT * FROM code_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): CodeRecord?

    @Insert
    suspend fun insert(record: CodeRecord): Long

    @Query("DELETE FROM code_records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM code_records")
    suspend fun clear()

    /**
     * Deduplication: did we just record an identical value within the last
     * `windowMs` milliseconds? Hook processes can fire more than once for a
     * single SMS (broadcast + ContentProvider insert), so we collapse those.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM code_records " +
        "WHERE value = :value AND timestamp > :since)"
    )
    suspend fun existsSince(value: String, since: Long): Boolean
}
