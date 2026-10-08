package com.umair.purpose.promise

import com.umair.purpose.chat.ActionReceipt
import com.umair.purpose.data.db.Promise
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Uses repository results rather than the coach's proposed wording or date arithmetic. */
object PromiseConfirmation {
    private val date = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    fun saved(p: Promise, zone: ZoneId = ZoneId.systemDefault()): String = "Promise: ${p.text}\n${schedule(p, zone)}"

    fun action(receipt: ActionReceipt, zone: ZoneId = ZoneId.systemDefault()): String {
        if (!receipt.ok) return "Promise change wasn't saved. Open Path to check or try again."
        val p = receipt.promise ?: return "Promise changed. Open Path to check the details."
        val label = when (receipt.type) {
            "resolve_promise" -> when (p.status) {
                Promise.KEPT -> "Kept"
                Promise.BROKEN -> "Not kept"
                Promise.DROPPED -> "Let go"
                else -> "Renegotiated"
            }
            "reschedule_promise" -> "Moved"
            "record_promise" -> "Saved promise"
            else -> "Updated"
        }
        return "$label: ${p.text}\n${schedule(p, zone)}"
    }

    fun schedule(p: Promise, zone: ZoneId = ZoneId.systemDefault()): String {
        val due = Due.parse(p.dueAt)
        val whenDue = due?.let { it.date.format(date) + (it.time?.let { t -> " at ${t.format(time).lowercase(Locale.ENGLISH)}" } ?: " (no time set)") }
            ?: "No due date set"
        val reminder = p.remindAt?.let {
            val t = Instant.ofEpochMilli(it).atZone(zone)
            "Reminder: ${t.format(date)} at ${t.format(time).lowercase(Locale.ENGLISH)}"
        } ?: "No reminder set"
        return "$whenDue\n$reminder"
    }
}
