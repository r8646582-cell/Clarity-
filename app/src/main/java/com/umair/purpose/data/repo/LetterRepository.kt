package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.memory.SearchIndex
import com.umair.purpose.memory.SearchDocs
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.PurposeDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LetterRepository @Inject constructor(private val db: PurposeDatabase, private val search: SearchIndex) {
    private val dao = db.letterDao()
    private val chapters = db.chapterDao()
    fun observeAll(): Flow<List<Letter>> = dao.observeAll()
    fun observe(id: Long): Flow<Letter?> = dao.observe(id)
    fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()
    fun observeCount(): Flow<Int> = dao.observeCount()

    /** UPDATE-15: Mirror loads letters a page at a time, so ten years of them open as fast as ten. */
    fun paged(): Flow<androidx.paging.PagingData<Letter>> =
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 30, enablePlaceholders = false)) { dao.paged() }.flow

    fun observeChapters(): Flow<List<com.umair.purpose.data.db.Chapter>> = chapters.observeAll()
    fun observeChapter(id: Long): Flow<com.umair.purpose.data.db.Chapter?> = chapters.observe(id)
    suspend fun markChapterRead(id: Long, now: Long) = chapters.markRead(id, now)
    suspend fun markRead(id: Long, now: Long) = dao.markRead(id, now)
    suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        search.remove(SearchDocs.LETTER, id.toString())
    }
}
