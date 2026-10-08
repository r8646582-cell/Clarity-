package com.umair.purpose.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Phase 2: the embedding of one archive search document. Derived data, kept inside the encrypted database because
 * a vector can leak what a sentence said. [textHash] and [model] say when it is out of date.
 */
@Entity(tableName = "embedding_row", primaryKeys = ["kind", "refId"])
data class EmbeddingRow(
    val kind: String,
    val refId: String,
    val model: String,
    val textHash: String,
    /** Little-endian float32s, see [com.umair.purpose.memory.EmbeddingIndex.pack]. */
    val vector: ByteArray,
)

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rows: List<EmbeddingRow>)
    @Query("SELECT * FROM embedding_row") suspend fun all(): List<EmbeddingRow>
    @Query("DELETE FROM embedding_row WHERE kind = :kind AND refId = :refId") suspend fun delete(kind: String, refId: String)
    @Query("DELETE FROM embedding_row") suspend fun clear()
    @Query("SELECT COUNT(*) FROM embedding_row") suspend fun count(): Int
}
