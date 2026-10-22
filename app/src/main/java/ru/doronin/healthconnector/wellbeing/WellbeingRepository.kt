package ru.doronin.healthconnector.wellbeing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.doronin.healthconnector.local.LocalStore
import ru.doronin.healthconnector.local.OutboxWorker
import java.time.Instant
import java.time.ZoneId

object WellbeingRepository {
    val TAGS: List<Pair<String, String>> = listOf(
        "late_caffeine" to "Кофе поздно",
        "alcohol" to "Алкоголь",
        "sick" to "Болею",
        "poor_sleep" to "Плохо спал",
        "heavy_dinner" to "Тяжёлый ужин",
        "workout_sore" to "Крепатура"
    )

    /**
     * Saves a check-in. One entry per date+slot: a second check-in in the same slot
     * updates it instead of creating a duplicate.
     */
    fun save(
        context: Context,
        energy: Int?,
        mood: Int?,
        soreness: Int?,
        stress: Int?,
        tags: List<String>,
        note: String = ""
    ) {
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val date = local.toLocalDate().toString()
        val slot = ReminderSchedule.slotFor(local.toLocalTime())
        val store = LocalStore.get(context)
        val existing = store.wellbeingFor(date, slot)
        val entry = LocalStore.WellbeingEntry(
            id = existing?.id ?: "wellbeing-$date-$slot",
            timeMs = now,
            date = date,
            slot = slot,
            energy = energy?.coerceIn(1, 5),
            mood = mood?.coerceIn(1, 5),
            soreness = soreness?.coerceIn(1, 5),
            stress = stress?.coerceIn(1, 5),
            tags = tags.distinct(),
            note = note.take(500)
        )
        store.saveWellbeing(entry, payload(entry))
        OutboxWorker.schedule(context)
    }

    fun current(context: Context): LocalStore.WellbeingEntry? {
        val local = Instant.now().atZone(ZoneId.systemDefault())
        return LocalStore.get(context).wellbeingFor(
            local.toLocalDate().toString(),
            ReminderSchedule.slotFor(local.toLocalTime())
        )
    }

    private fun payload(entry: LocalStore.WellbeingEntry): String = JSONObject().apply {
        put("id", entry.id)
        put("time", Instant.ofEpochMilli(entry.timeMs).toString())
        put("date", entry.date)
        put("slot", entry.slot)
        put("energy", entry.energy ?: JSONObject.NULL)
        put("mood", entry.mood ?: JSONObject.NULL)
        put("soreness", entry.soreness ?: JSONObject.NULL)
        put("stress", entry.stress ?: JSONObject.NULL)
        put("tags", JSONArray(entry.tags))
        put("note", entry.note)
    }.toString()
}
