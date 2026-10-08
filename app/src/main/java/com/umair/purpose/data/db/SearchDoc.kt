package com.umair.purpose.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * UPDATE-15: the archive's full-text index (SQLite FTS4). One row per session summary, quote, behavior event,
 * letter, chapter and archived note, so a chat message can find what's relevant from years ago without sending
 * years of history. Kept in step by [com.umair.purpose.memory.SearchIndex]; it can always be rebuilt from the
 * real tables, so it holds no data of its own.
 */
@Fts4(notIndexed = ["kind", "refId", "day"])
@Entity(tableName = "search_doc")
data class SearchDoc(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Int = 0,
    /** summary | quote | event | letter | chapter | note */
    val kind: String,
    /** The row it came from, e.g. the session or quote id. */
    val refId: String,
    /** ISO date it's about. */
    val day: String,
    val text: String,
)

/** One match from the archive search. */
data class SearchHit(val kind: String, val refId: String, val day: String, val text: String)
