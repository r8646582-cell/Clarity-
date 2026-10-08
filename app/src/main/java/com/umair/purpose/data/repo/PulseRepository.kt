package com.umair.purpose.data.repo

import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.PurposeDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PulseRepository @Inject constructor(db: PurposeDatabase) {
    private val dao = db.pulseDao()

    fun observe(date: LocalDate): Flow<Pulse?> = dao.observe(date.toString())

    suspend fun save(date: LocalDate, mood: Int, energy: Int, word: String?) {
        dao.upsert(Pulse(date.toString(), mood.coerceIn(1, 5), energy.coerceIn(1, 5), word?.trim()?.takeIf { it.isNotEmpty() }))
    }

    suspend fun since(date: LocalDate): List<Pulse> = dao.since(date.toString())
}
