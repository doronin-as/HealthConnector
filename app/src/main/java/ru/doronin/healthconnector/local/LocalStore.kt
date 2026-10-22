package ru.doronin.healthconnector.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device source of truth for data the user enters in the app (water, wellbeing).
 *
 * Google Sheets is a mirror: every change is first committed here together with an
 * outbox item in one transaction, then delivered by [OutboxWorker]. A failed or
 * offline upload therefore never loses an entry, and the widget works without network.
 */
class LocalStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE water (
                id TEXT PRIMARY KEY,
                time_ms INTEGER NOT NULL,
                date TEXT NOT NULL,
                ml INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                updated_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX water_date ON water(date)")
        db.execSQL(
            """CREATE TABLE wellbeing (
                id TEXT PRIMARY KEY,
                time_ms INTEGER NOT NULL,
                date TEXT NOT NULL,
                slot TEXT NOT NULL,
                energy INTEGER,
                mood INTEGER,
                soreness INTEGER,
                stress INTEGER,
                tags TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                updated_ms INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX wellbeing_date ON wellbeing(date)")
        db.execSQL(
            """CREATE TABLE outbox (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_ms INTEGER NOT NULL,
                UNIQUE(kind, entity_id)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---------- water ----------

    data class WaterEntry(val id: String, val timeMs: Long, val date: String, val ml: Int, val deleted: Boolean)

    fun waterTotal(date: String): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(ml), 0) FROM water WHERE date = ? AND deleted = 0", arrayOf(date)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lastActiveWater(date: String): WaterEntry? = readableDatabase.rawQuery(
        "SELECT id, time_ms, date, ml, deleted FROM water WHERE date = ? AND deleted = 0 ORDER BY time_ms DESC LIMIT 1",
        arrayOf(date)
    ).use { c ->
        if (!c.moveToFirst()) null
        else WaterEntry(c.getString(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4) != 0)
    }

    fun waterEntry(id: String): WaterEntry? = readableDatabase.rawQuery(
        "SELECT id, time_ms, date, ml, deleted FROM water WHERE id = ?", arrayOf(id)
    ).use { c ->
        if (!c.moveToFirst()) null
        else WaterEntry(c.getString(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4) != 0)
    }

    /** Saves a water entry and its outbox item atomically. */
    fun saveWater(entry: WaterEntry, outboxPayload: String) = transaction { db ->
        db.insertWithOnConflict("water", null, ContentValues().apply {
            put("id", entry.id)
            put("time_ms", entry.timeMs)
            put("date", entry.date)
            put("ml", entry.ml)
            put("deleted", if (entry.deleted) 1 else 0)
            put("updated_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        putOutbox(db, KIND_WATER, entry.id, outboxPayload)
    }

    // ---------- wellbeing ----------

    data class WellbeingEntry(
        val id: String,
        val timeMs: Long,
        val date: String,
        val slot: String,
        val energy: Int?,
        val mood: Int?,
        val soreness: Int?,
        val stress: Int?,
        val tags: List<String>,
        val note: String
    )

    fun saveWellbeing(entry: WellbeingEntry, outboxPayload: String) = transaction { db ->
        db.insertWithOnConflict("wellbeing", null, ContentValues().apply {
            put("id", entry.id)
            put("time_ms", entry.timeMs)
            put("date", entry.date)
            put("slot", entry.slot)
            putNullable("energy", entry.energy)
            putNullable("mood", entry.mood)
            putNullable("soreness", entry.soreness)
            putNullable("stress", entry.stress)
            put("tags", entry.tags.joinToString(","))
            put("note", entry.note)
            put("updated_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        putOutbox(db, KIND_WELLBEING, entry.id, outboxPayload)
    }

    fun wellbeingFor(date: String, slot: String): WellbeingEntry? = readableDatabase.rawQuery(
        "SELECT id, time_ms, date, slot, energy, mood, soreness, stress, tags, note FROM wellbeing " +
            "WHERE date = ? AND slot = ? ORDER BY time_ms DESC LIMIT 1",
        arrayOf(date, slot)
    ).use { c ->
        if (!c.moveToFirst()) null
        else WellbeingEntry(
            id = c.getString(0),
            timeMs = c.getLong(1),
            date = c.getString(2),
            slot = c.getString(3),
            energy = if (c.isNull(4)) null else c.getInt(4),
            mood = if (c.isNull(5)) null else c.getInt(5),
            soreness = if (c.isNull(6)) null else c.getInt(6),
            stress = if (c.isNull(7)) null else c.getInt(7),
            tags = c.getString(8).split(',').filter { it.isNotBlank() },
            note = c.getString(9)
        )
    }

    // ---------- outbox ----------

    data class OutboxItem(
        val seq: Long,
        val kind: String,
        val entityId: String,
        val payload: String,
        val attempts: Int,
        val lastError: String,
        val createdMs: Long
    )

    fun pendingOutbox(limit: Int = 50): List<OutboxItem> = readableDatabase.rawQuery(
        "SELECT seq, kind, entity_id, payload, attempts, last_error, created_ms FROM outbox ORDER BY seq LIMIT ?",
        arrayOf(limit.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(OutboxItem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getString(5), c.getLong(6)))
            }
        }
    }

    data class OutboxStats(val count: Int, val oldestCreatedMs: Long?, val lastError: String)

    fun outboxStats(): OutboxStats = readableDatabase.rawQuery(
        "SELECT COUNT(*), MIN(created_ms), (SELECT last_error FROM outbox WHERE last_error != '' ORDER BY seq DESC LIMIT 1) FROM outbox",
        null
    ).use { c ->
        c.moveToFirst()
        OutboxStats(c.getInt(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) "" else c.getString(2))
    }

    /**
     * Removes a delivered item only if it was not replaced meanwhile: a newer edit of the
     * same entity gets a new seq and must still be uploaded.
     */
    fun completeOutbox(seq: Long) {
        writableDatabase.delete("outbox", "seq = ?", arrayOf(seq.toString()))
    }

    fun failOutbox(seq: Long, error: String) {
        writableDatabase.execSQL(
            "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?",
            arrayOf(error.take(300), seq)
        )
    }

    private fun putOutbox(db: SQLiteDatabase, kind: String, entityId: String, payload: String) {
        // One pending item per entity: the latest state replaces an undelivered older one.
        db.delete("outbox", "kind = ? AND entity_id = ?", arrayOf(kind, entityId))
        db.insertOrThrow("outbox", null, ContentValues().apply {
            put("kind", kind)
            put("entity_id", entityId)
            put("payload", payload)
            put("created_ms", System.currentTimeMillis())
        })
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    companion object {
        private const val DB_NAME = "health_local.db"
        private const val DB_VERSION = 1
        const val KIND_WATER = "water"
        const val KIND_WELLBEING = "wellbeing"

        @Volatile private var instance: LocalStore? = null

        fun get(context: Context): LocalStore =
            instance ?: synchronized(this) { instance ?: LocalStore(context).also { instance = it } }
    }
}
