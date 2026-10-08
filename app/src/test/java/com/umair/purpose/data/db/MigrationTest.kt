package com.umair.purpose.data.db

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * UPDATE-15 "Never lose data": every migration runs on real SQLite, from every schema version Room exported, and
 * the result must match the schema Room expects (tables, columns, types, nullability, keys, indexes, the FTS
 * table) with every row still there. Versions 4 and 5 were never exported, so they're covered by the 3 → 6 run.
 */
class MigrationTest {
    private val dir = File("schemas/com.umair.purpose.data.db.PurposeDatabase")
    private val latest = 11
    private val conns = mutableListOf<Connection>()

    @After
    fun close() = conns.forEach { it.close() }

    @Test
    fun `the latest schema is exported and the migrations reach it`() {
        assertTrue("schemas/$latest.json missing: build once so Room exports it", File(dir, "$latest.json").exists())
        assertEquals(latest, Migrations.ALL.last().endVersion)
        // One step per version, no gaps.
        Migrations.ALL.toList().zipWithNext().forEach { (a, b) -> assertEquals(a.endVersion, b.startVersion) }
    }

    @Test
    fun `every exported version migrates to the latest with every row kept`() {
        for (v in exported().filter { it < latest }) {
            val db = create(v)
            val before = fillEveryTable(db, v)
            migrate(db, v, latest)
            assertSchema(db, schema(latest), "from $v")
            for ((table, n) in before) {
                when (table) {
                    // 2 → 3: reports became letters (monthly ones, which the filler writes).
                    "report" -> assertEquals("from $v: report → letter", n, count(db, "letter"))
                    else -> assertEquals("from $v: rows in $table", n, count(db, table))
                }
            }
        }
    }

    @Test
    fun `each step matches the schema of the version it reaches`() {
        val versions = exported()
        for ((from, to) in versions.zipWithNext()) {
            val db = create(from)
            fillEveryTable(db, from)
            migrate(db, from, to)
            assertSchema(db, schema(to), "$from → $to")
        }
    }

    @Test
    fun `8 to 9 keeps his data and only adds`() {
        val db = create(8)
        exec(db, "INSERT INTO session (id, startedAt, reflected, userMessageCount, titleByUser) VALUES (1, 100, 1, 2, 0)")
        exec(db, "INSERT INTO message (id, sessionId, role, content, createdAt, thinking, status) VALUES (1, 1, 'user', 'salaam', 101, 0, 'complete')")
        exec(db, "INSERT INTO promise (id, text, createdAt, status, sourceSessionId) VALUES (1, 'Study at 5pm', 102, 'open', 1)")
        exec(db, "INSERT INTO profile_entry (`key`, value, updatedAt, editedByUser, deletedByUser, sourceSessionIds) VALUES ('Home', 'Chitral', 1, 1, 0, '1')")
        exec(db, "INSERT INTO strength (id, sessionId, text, createdAt, editedByUser, deletedByUser) VALUES (1, 1, 'You keep going', 1, 0, 0)")
        exec(db, "INSERT INTO journey (id, name, startedAt, currentDay, totalDays, status) VALUES (1, 'Break the avoidance loop', 1, 3, 7, 'active')")
        exec(db, "INSERT INTO prompt_override (name, text, updatedAt) VALUES ('persona.md', 'mine', 1)")
        exec(db, "INSERT INTO usage_stat (id, createdAt, purpose, model, promptTokens, cacheHitTokens, cacheMissTokens, completionTokens) VALUES (1, 1, 'chat', 'm', 10, 5, 5, 3)")
        // His own prices stay; the old defaults move to the new ones.
        exec(
            db,
            "INSERT INTO settings (id, toughLove, provider, baseUrl, chatModel, deepModel, chatTemperature, reflectionTemperature, " +
                "supportsThinkingToggle, chatPriceCacheHit, chatPriceCacheMiss, chatPriceOutput, deepPriceCacheHit, deepPriceCacheMiss, " +
                "deepPriceOutput, theme, lockEnabled, hideInRecents, voiceLanguage, pulseEnabled, readAloud, helpNumbers, alwaysDeep, " +
                "monthlyBudget, helpNumbersEdited, autoBackup) VALUES (0, 'firm', 'DeepSeek', 'https://api.deepseek.com', 'f', 'p', 0.7, 0.3, 1, " +
                "0.028, 0.28, 0.42, 0.1, 0.2, 0.3, 'night', 1, 1, 'default', 0, 0, 'A|1', 0, 5.0, 1, 0)"
        )
        migrate(db, 8, 9)
        assertSchema(db, schema(9), "8 → 9")
        assertEquals("salaam", one(db, "SELECT content FROM message WHERE id = 1"))
        assertEquals("Study at 5pm", one(db, "SELECT text FROM promise WHERE id = 1"))
        assertNull(one(db, "SELECT actionKey FROM promise WHERE id = 1"))
        assertEquals("Chitral|1|0", one(db, "SELECT value || '|' || editedByUser || '|' || retired FROM profile_entry"))
        assertEquals("0", one(db, "SELECT retired FROM strength"))
        assertEquals("active|3|null", one(db, "SELECT status || '|' || currentDay || '|' || IFNULL(stepsJson, 'null') FROM journey"))
        assertEquals("mine", one(db, "SELECT text FROM prompt_override"))
        assertNull(one(db, "SELECT builtInHash FROM prompt_override"))
        assertEquals("0", one(db, "SELECT offPeak FROM usage_stat"))
        assertEquals("firm|A|1|1", one(db, "SELECT toughLove || '|' || helpNumbers || '|' || helpNumbersEdited FROM settings"))
        assertEquals("0.014|0.44|1.32", one(db, "SELECT chatPriceCacheHit || '|' || chatPriceCacheMiss || '|' || chatPriceOutput FROM settings"))
        assertEquals("0.1|0.2|0.3", one(db, "SELECT deepPriceCacheHit || '|' || deepPriceCacheMiss || '|' || deepPriceOutput FROM settings"))
        assertEquals("1|01:00-04:00,06:00-10:00|1|0.5|", one(db, "SELECT offPeakEnabled || '|' || peakWindows || '|' || peakWeekdaysOnly || '|' || offPeakFactor || '|' || backupProvider FROM settings"))
        // The archive index works.
        exec(db, "INSERT INTO search_doc (kind, refId, day, text) VALUES ('summary', '1', '2026-10-01', 'Talked about FAR and Abbu')")
        assertEquals("1", one(db, "SELECT refId FROM search_doc WHERE search_doc MATCH 'abbu'"))
    }

    @Test
    fun `9 to 10 corrects the wrong Flash prices`() {
        val db = create(9)
        insertSettings(db, wrongChatPrices = true)
        migrate(db, 9, 10)
        assertSchema(db, schema(10), "9 → 10")
        // 0.014 / 0.44 / 1.32 was a mangled copy of the pro row; corrected only where he never changed them.
        assertEquals("0.006|0.3|1.2", one(db, "SELECT chatPriceCacheHit || '|' || chatPriceCacheMiss || '|' || chatPriceOutput FROM settings"))
        // The pro row was already right, so it is left alone.
        assertEquals("0.044|1.32|3.96", one(db, "SELECT deepPriceCacheHit || '|' || deepPriceCacheMiss || '|' || deepPriceOutput FROM settings"))
    }

    @Test
    fun `9 to 10 leaves prices he set himself alone`() {
        val db = create(9)
        insertSettings(db, wrongChatPrices = true)
        exec(db, "UPDATE settings SET chatPriceCacheHit = 0.01, chatPriceCacheMiss = 0.5, chatPriceOutput = 2.0")
        migrate(db, 9, 10)
        assertEquals("0.01|0.5|2.0", one(db, "SELECT chatPriceCacheHit || '|' || chatPriceCacheMiss || '|' || chatPriceOutput FROM settings"))
    }

    @Test
    fun `9 to 10 keeps every row and defaults the new columns`() {
        val db = create(9)
        insertSettings(db, wrongChatPrices = true)
        exec(db, "INSERT INTO session (id, startedAt, reflected, userMessageCount, titleByUser) VALUES (1, 100, 1, 2, 0)")
        exec(db, "INSERT INTO promise (id, text, createdAt, status, sourceSessionId) VALUES (1, 'Walk 20 minutes', 102, 'open', 1)")
        exec(db, "INSERT INTO area_status (area, status, note, updatedAt) VALUES ('money', 'stuck', 'rent', 1)")
        migrate(db, 9, 10)
        assertSchema(db, schema(10), "9 → 10")
        assertEquals("Walk 20 minutes", one(db, "SELECT text FROM promise WHERE id = 1"))
        assertEquals("0", one(db, "SELECT offTheRecord FROM promise WHERE id = 1"))
        assertEquals("money|stuck|0|0", one(db, "SELECT area || '|' || status || '|' || editedByUser || '|' || deletedByUser FROM area_status"))
    }

    @Test
    fun `10 to 11 backfills when facts were recorded and when retired ones stopped being true`() {
        val db = create(10)
        val note = "INSERT INTO note (id, type, text, confidence, status, timesSeen, firstSeen, lastSeen, editedByUser, sourceSessionIds) VALUES"
        exec(db, "$note (1, 'pattern', 'Walks at dawn', 'likely', 'active', 2, 100, 500, 0, '')")
        exec(db, "$note (2, 'thread', 'Job hunt', 'guess', 'retired', 1, 200, 600, 0, '')")
        exec(db, "$note (3, 'thread', 'Exam', 'guess', 'resolved', 1, 300, 700, 0, '')")
        val p = "INSERT INTO profile_entry (key, value, updatedAt, editedByUser, deletedByUser, sourceSessionIds, retired) VALUES"
        exec(db, "$p ('city', 'Lahore', 900, 0, 0, '', 0)")
        exec(db, "$p ('old', 'Student', 800, 0, 0, '', 1)")
        migrate(db, 10, 11)
        assertSchema(db, schema(11), "10 → 11")
        assertEquals("100|100|null", one(db, "SELECT recordedAt || '|' || validFrom || '|' || ifnull(validTo, 'null') FROM note WHERE id = 1"))
        assertEquals("200|200|600", one(db, "SELECT recordedAt || '|' || validFrom || '|' || ifnull(validTo, 'null') FROM note WHERE id = 2"))
        assertEquals("300|300|700", one(db, "SELECT recordedAt || '|' || validFrom || '|' || ifnull(validTo, 'null') FROM note WHERE id = 3"))
        assertEquals("900|900|null", one(db, "SELECT recordedAt || '|' || validFrom || '|' || ifnull(validTo, 'null') FROM profile_entry WHERE key = 'city'"))
        assertEquals("800|800|800", one(db, "SELECT recordedAt || '|' || validFrom || '|' || ifnull(validTo, 'null') FROM profile_entry WHERE key = 'old'"))
    }

    /**
     * schemas/1.json was never exported, so v1 is rebuilt from v2's own definitions minus exactly what 1 → 2 adds
     * (the six price columns on `settings`). 1 → 2 is the migration that created the whole memory schema, and
     * before this it was executed by no test at all.
     */
    @Test
    fun `1 to 2 reaches the version 2 schema with his data kept`() {
        val db = open()
        val s2 = schema(2)
        val v1Tables = setOf("session", "message", "settings", "usage_stat")
        for (e in s2["entities"]!!.jsonArray.map { it.jsonObject }) {
            val name = e.str("tableName")!!
            if (name !in v1Tables) continue
            val sql = if (name == "settings") settingsWithoutPrices(s2) else e.str("createSql")!!.replace("\${TABLE_NAME}", name)
            exec(db, sql)
            e["indices"]?.jsonArray?.forEach { exec(db, it.jsonObject.str("createSql")!!.replace("\${TABLE_NAME}", name)) }
        }
        insertSettings(db, wrongChatPrices = false)
        migrate(db, 1, 2)
        assertSchema(db, s2, "1 → 2")
        // The new tables exist and his settings row survived.
        assertEquals("firm", one(db, "SELECT toughLove FROM settings"))
        assertEquals("0", one(db, "SELECT COUNT(*) FROM note"))
    }

    /** The six price columns 1 → 2 added to `settings`. */
    private val PRICE_COLUMNS = listOf(
        "chatPriceCacheHit", "chatPriceCacheMiss", "chatPriceOutput",
        "deepPriceCacheHit", "deepPriceCacheMiss", "deepPriceOutput",
    )

    /** v2's `settings` definition with those columns taken back out, i.e. settings as it stood at v1. */
    private fun settingsWithoutPrices(s2: JsonObject): String {
        val sql = s2["entities"]!!.jsonArray.map { it.jsonObject }
            .first { it.str("tableName") == "settings" }.str("createSql")!!
            .replace("\${TABLE_NAME}", "settings")
        return PRICE_COLUMNS.fold(sql) { acc, c -> acc.replace(Regex(""",\s*`$c`[^,)]*"""), "") }
    }

    /**
     * Inserts the single settings row, filling **every** column the table actually has.
     *
     * `create(v)` builds its tables from the exported schema's `createSql`, which is derived from the entities and
     * therefore carries no SQL `DEFAULT` clauses — those only exist on columns a *migration* added with
     * `ADD COLUMN … DEFAULT …`. An INSERT that omits such a column inserts NULL and trips NOT NULL, which is why
     * this reads the columns from the table instead of listing them by hand.
     */
    private fun insertSettings(db: Db, wrongChatPrices: Boolean) {
        val prices = if (wrongChatPrices) Triple("0.014", "0.44", "1.32") else Triple("0.02", "0.4", "1.0")
        val cols = mutableListOf<String>()
        val vals = mutableListOf<String>()
        val info = query(db, "PRAGMA table_info(`settings`)") { rs ->
            Triple(rs.getString("name"), rs.getString("type").uppercase(), rs.getInt("notnull"))
        }
        for ((col, type, _) in info) {
            val value = when {
                col == "id" -> "0"
                col == "toughLove" -> "'firm'"
                col == "chatPriceCacheHit" -> prices.first
                col == "chatPriceCacheMiss" -> prices.second
                col == "chatPriceOutput" -> prices.third
                col == "deepPriceCacheHit" -> "0.044"
                col == "deepPriceCacheMiss" -> "1.32"
                col == "deepPriceOutput" -> "3.96"
                type.contains("INT") -> "0"
                type.contains("REAL") -> "0.0"
                else -> "'x'"
            }
            cols += "`$col`"
            vals += value
        }
        exec(db, "INSERT INTO settings (${cols.joinToString()}) VALUES (${vals.joinToString()})")
    }

    @Test
    fun `no destructive fallback anywhere in the app`() {
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("fallbackToDestructive") }.toList()
        assertTrue("Destructive migration fallback found in $offenders", offenders.isEmpty())
    }

    // ---- SQLite on the JVM, behind Room's SupportSQLiteDatabase ----

    private class Db(val conn: Connection, val support: SupportSQLiteDatabase)

    private fun open(): Db {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:").also { conns += it }
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        val support = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, m, args ->
            when (m.name) {
                "execSQL" -> {
                    conn.prepareStatement(args[0] as String).use { st ->
                        (args.getOrNull(1) as? Array<*>)?.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                        st.execute()
                    }
                    null
                }
                "query" -> {
                    val sql = args[0] as? String ?: throw UnsupportedOperationException("query(${args[0]?.javaClass})")
                    val st = conn.prepareStatement(sql)
                    (args.getOrNull(1) as? Array<*>)?.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                    cursor(st.executeQuery()) { st.close() }
                }
                "isOpen" -> true
                "toString" -> "JdbcSupportDb"
                "hashCode" -> System.identityHashCode(conn)
                "equals" -> false
                else -> throw UnsupportedOperationException("SupportSQLiteDatabase.${m.name} isn't used by migrations")
            }
        } as SupportSQLiteDatabase
        return Db(conn, support)
    }

    private fun cursor(rs: ResultSet, onClose: () -> Unit): Cursor =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Cursor::class.java)) { _, m, args ->
            when (m.name) {
                "moveToNext" -> rs.next()
                "isNull" -> rs.getObject((args[0] as Int) + 1) == null
                "getString" -> rs.getString((args[0] as Int) + 1)
                "getLong" -> rs.getLong((args[0] as Int) + 1)
                "getInt" -> rs.getInt((args[0] as Int) + 1)
                "getDouble" -> rs.getDouble((args[0] as Int) + 1)
                "getColumnCount" -> rs.metaData.columnCount
                "close" -> { rs.close(); onClose(); null }
                "isClosed" -> rs.isClosed
                "toString" -> "JdbcCursor"
                "hashCode" -> System.identityHashCode(rs)
                "equals" -> false
                else -> throw UnsupportedOperationException("Cursor.${m.name} isn't used by migrations")
            }
        } as Cursor

    private fun exec(db: Db, sql: String) = db.conn.createStatement().use { it.execute(sql) }

    private fun one(db: Db, sql: String): String? =
        db.conn.createStatement().use { st -> st.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }

    private fun count(db: Db, table: String): Int = one(db, "SELECT COUNT(*) FROM `$table`")!!.toInt()

    private fun exported(): List<Int> =
        dir.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()

    private fun schema(v: Int): JsonObject =
        Json.parseToJsonElement(File(dir, "$v.json").readText()).jsonObject["database"]!!.jsonObject

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** A database at version [v], exactly as Room creates it. */
    private fun create(v: Int): Db {
        val db = open()
        val s = schema(v)
        for (e in s["entities"]!!.jsonArray.map { it.jsonObject }) {
            val name = e.str("tableName")!!
            exec(db, e.str("createSql")!!.replace("\${TABLE_NAME}", name))
            e["indices"]?.jsonArray?.forEach { exec(db, it.jsonObject.str("createSql")!!.replace("\${TABLE_NAME}", name)) }
        }
        s["setupQueries"]?.jsonArray?.forEach { exec(db, it.jsonPrimitive.content) }
        return db
    }

    private fun migrate(db: Db, from: Int, to: Int) {
        var v = from
        while (v < to) {
            val m = Migrations.ALL.firstOrNull { it.startVersion == v } ?: fail("No migration from $v").let { return }
            m.migrate(db.support)
            v = m.endVersion
        }
    }

    /** One row in every table, with a value in every column (foreign keys pointing at row 1). */
    private fun fillEveryTable(db: Db, v: Int): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        val entities = schema(v)["entities"]!!.jsonArray.map { it.jsonObject }
        // Parents first, so foreign keys hold.
        val ordered = entities.sortedBy { if (it["foreignKeys"]?.jsonArray.isNullOrEmpty()) 0 else 1 }
        for (e in ordered) {
            val name = e.str("tableName")!!
            val fields = e["fields"]!!.jsonArray.map { it.jsonObject }
            val cols = fields.map { "`${it.str("columnName")}`" }
            val values = fields.map { f ->
                val col = f.str("columnName")!!
                when {
                    col == "kind" -> "'monthly'"
                    col == "content" -> "'A title\nThe letter'"
                    col == "role" -> "'user'"
                    col == "status" && name == "message" -> "'complete'"
                    f.str("affinity") == "INTEGER" -> "1"
                    f.str("affinity") == "REAL" -> "1.0"
                    else -> "'x'"
                }
            }
            exec(db, "INSERT INTO `$name` (${cols.joinToString()}) VALUES (${values.joinToString()})")
            out[name] = 1
        }
        return out
    }

    /** What Room checks when it opens the database: columns, keys, indexes and foreign keys of every table. */
    private fun assertSchema(db: Db, expected: JsonObject, label: String) {
        for (e in expected["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e.str("tableName")!!
            val actualCols = query(db, "PRAGMA table_info(`$table`)") { rs ->
                rs.getString("name") to Triple(rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk"))
            }.toMap()
            assertTrue("$label: table $table is missing", actualCols.isNotEmpty())
            val fields = e["fields"]!!.jsonArray.map { it.jsonObject }
            if (e["ftsVersion"] != null) {
                assertEquals("$label: $table columns", fields.map { it.str("columnName") }.toSet(), actualCols.keys)
                val sql = one(db, "SELECT sql FROM sqlite_master WHERE name = '$table'")!!
                assertEquals("$label: $table FTS options", ftsOptions(e.str("createSql")!!), ftsOptions(sql))
                continue
            }
            assertEquals("$label: $table columns", fields.map { it.str("columnName") }.toSet(), actualCols.keys)
            val pk = e["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (f in fields) {
                val col = f.str("columnName")!!
                val (type, notNull, pkPos) = actualCols.getValue(col)
                assertEquals("$label: $table.$col type", f.str("affinity"), type)
                assertEquals("$label: $table.$col not null", f["notNull"]?.jsonPrimitive?.boolean ?: false, notNull)
                assertEquals("$label: $table.$col primary key", pk.indexOf(col) + 1, pkPos)
                f.str("defaultValue")?.let { want ->
                    val have = query(db, "PRAGMA table_info(`$table`)") { rs -> rs.getString("name") to rs.getString("dflt_value") }.toMap()[col]
                    assertEquals("$label: $table.$col default", unparen(want), have?.let(::unparen))
                }
            }
            val wantIdx = (e["indices"] as? JsonArray).orEmpty().map { it.jsonObject }.map { i ->
                Triple(i.str("name")!!, i["unique"]!!.jsonPrimitive.boolean, i["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content })
            }.toSet()
            val haveIdx = query(db, "PRAGMA index_list(`$table`)") { rs -> Triple(rs.getString("name"), rs.getInt("unique") == 1, rs.getString("origin")) }
                .filter { it.third == "c" }
                .map { (n, u, _) -> Triple(n, u, query(db, "PRAGMA index_info(`$n`)") { it.getString("name") }) }.toSet()
            assertEquals("$label: $table indexes", wantIdx, haveIdx)
            val wantFk = (e["foreignKeys"] as? JsonArray).orEmpty().map { it.jsonObject }.map { f ->
                listOf(f.str("table"), f.str("onDelete"), f["columns"].toString(), f["referencedColumns"].toString())
            }.toSet()
            val haveFk = query(db, "PRAGMA foreign_key_list(`$table`)") { rs ->
                listOf(rs.getString("table"), rs.getString("on_delete"), "[\"${rs.getString("from")}\"]", "[\"${rs.getString("to")}\"]")
            }.toSet()
            assertEquals("$label: $table foreign keys", wantFk, haveFk)
        }
    }

    private fun <T> query(db: Db, sql: String, row: (ResultSet) -> T): List<T> =
        db.conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }

    private fun ftsOptions(sql: String): Set<String> =
        Regex("""notindexed\s*=\s*`?(\w+)`?""", RegexOption.IGNORE_CASE).findAll(sql).map { it.groupValues[1] }.toSet()

    private fun unparen(s: String): String {
        var t = s.trim()
        while (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length - 1).trim()
        return t
    }
}
