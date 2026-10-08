package com.umair.purpose.backup

/** Validate identity/link integrity before the restore transaction deletes any current rows. */
object BackupValidation {
    fun check(data: BackupData) {
        require(data.formatVersion in 1..BackupData.FORMAT) { "Unsupported backup format. Update Purpose before restoring." }
        require(data.schemaVersion >= 0) { "Invalid backup schema version" }
        fun ids(name: String, values: List<Long>) {
            require(values.all { it > 0 } && values.distinct().size == values.size) { "Invalid or duplicate $name IDs in backup" }
        }
        ids("session", data.sessions.map { it.id })
        ids("message", data.messages.map { it.id })
        ids("person", data.people.map { it.id })
        ids("note", data.notes.map { it.id })
        ids("promise", data.promises.map { it.id })
        ids("letter", data.allLetters().map { it.id })
        ids("journey", data.journeys.map { it.id })
        val sessions = data.sessions.map { it.id }.toSet()
        require(data.messages.all { it.sessionId in sessions }) { "A backup message has no conversation" }
        require(data.sessions.count { it.endedAt == null } <= 1) { "The backup has multiple open conversations" }
        require(data.journeys.count { !it.isArchived } <= 1) { "The backup has multiple current journeys" }
        require(data.journeys.all { it.totalDays > 0 && it.currentDay in 1..(it.totalDays.toLong() + 1) }) { "Invalid journey day in backup" }
    }
}
