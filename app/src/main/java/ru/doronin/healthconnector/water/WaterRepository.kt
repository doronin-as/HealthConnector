package ru.doronin.healthconnector.water

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import org.json.JSONObject
import ru.doronin.healthconnector.local.LocalStore
import ru.doronin.healthconnector.local.OutboxWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Water intake: local store first, then Health Connect (if allowed) and Google Sheets. */
object WaterRepository {
    const val PREF_GOAL_ML = "water_goal_ml"
    const val DEFAULT_GOAL_ML = 2000

    val healthConnectPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class)
    )

    data class Today(val date: String, val totalMl: Int, val goalMl: Int, val lastMl: Int?)

    fun today(context: Context): Today {
        val date = LocalDate.now(ZoneId.systemDefault()).toString()
        val store = LocalStore.get(context)
        return Today(date, store.waterTotal(date), goalMl(context), store.lastActiveWater(date)?.ml)
    }

    fun goalMl(context: Context): Int =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt(PREF_GOAL_ML, DEFAULT_GOAL_ML).coerceIn(500, 6000)

    suspend fun add(context: Context, ml: Int) {
        require(ml in 1..3000) { "Некорректный объём: $ml мл" }
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val entry = LocalStore.WaterEntry(
            id = "water-" + UUID.randomUUID(),
            timeMs = now,
            date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
            ml = ml,
            deleted = false
        )
        LocalStore.get(context).saveWater(entry, payload(entry))
        OutboxWorker.schedule(context)
        runCatching { writeHealthConnect(context, entry) }
    }

    /** Cancels today's latest intake. Returns the cancelled volume, or null if nothing to undo. */
    suspend fun undoLast(context: Context): Int? {
        val store = LocalStore.get(context)
        val date = LocalDate.now(ZoneId.systemDefault()).toString()
        val last = store.lastActiveWater(date) ?: return null
        val deleted = last.copy(deleted = true)
        store.saveWater(deleted, payload(deleted))
        OutboxWorker.schedule(context)
        runCatching { deleteHealthConnect(context, last.id) }
        return last.ml
    }

    suspend fun canWriteHealthConnect(context: Context): Boolean {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return false
        val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return HealthPermission.getWritePermission(HydrationRecord::class) in granted
    }

    private fun payload(entry: LocalStore.WaterEntry): String = JSONObject().apply {
        put("id", entry.id)
        put("time", Instant.ofEpochMilli(entry.timeMs).toString())
        put("date", entry.date)
        put("ml", entry.ml)
        put("deleted", entry.deleted)
    }.toString()

    private suspend fun writeHealthConnect(context: Context, entry: LocalStore.WaterEntry) {
        if (!canWriteHealthConnect(context)) return
        val start = Instant.ofEpochMilli(entry.timeMs)
        val end = start.plusSeconds(1)
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        HealthConnectClient.getOrCreate(context).insertRecords(
            listOf(
                HydrationRecord(
                    startTime = start,
                    startZoneOffset = offset,
                    endTime = end,
                    endZoneOffset = offset,
                    volume = Volume.milliliters(entry.ml.toDouble()),
                    metadata = Metadata.manualEntry(clientRecordId = entry.id, clientRecordVersion = 1L)
                )
            )
        )
    }

    private suspend fun deleteHealthConnect(context: Context, clientRecordId: String) {
        if (!canWriteHealthConnect(context)) return
        HealthConnectClient.getOrCreate(context).deleteRecords(
            HydrationRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(clientRecordId)
        )
    }
}
