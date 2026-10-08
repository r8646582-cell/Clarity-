package com.umair.purpose.memory

/** Which conversations a memory came from, kept as "3,7,12". Empty means unknown. A merged unknown source is kept as 0, which is never forgotten. */
object Provenance {
    fun parse(ids: String): Set<Long> = ids.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    fun format(ids: Set<Long>): String = ids.sorted().joinToString(",")

    /** Preserve unknown history when several source lists are folded into one row. */
    fun merge(sources: List<String>): String = format(sources.flatMap {
        parse(it).ifEmpty { setOf(0L) }
    }.toSet())

    fun add(ids: String, sessionId: Long): String = format(parse(ids) + sessionId)

    /** Adding a new observation to an existing legacy row must not erase its unknown older history. */
    fun extend(ids: String, sessionId: Long): String = merge(listOf(ids, sessionId.toString()))

    fun remove(ids: String, sessionId: Long): String = format(parse(ids) - sessionId)

    /** True if [sessionId] is the one and only known source. */
    fun onlyFrom(ids: String, sessionId: Long): Boolean = parse(ids) == setOf(sessionId)
}
