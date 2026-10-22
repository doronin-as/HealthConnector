package ru.doronin.healthconnector.watchdog

import java.time.LocalDate

/** Pure decision logic of the sync watchdog (unit-tested). */
object WatchdogPolicy {
    const val OUTBOX_STUCK_HOURS = 12L
    const val REPEAT_HOURS = 24L

    sealed class Alert(val key: String) {
        /** No bracelet data in HC_Дни for yesterday or later. */
        data class StaleDays(val latestDate: LocalDate?) : Alert("stale_days")
        /** Water/wellbeing entries not delivered for too long. */
        data class OutboxStuck(val count: Int, val hours: Long, val lastError: String) : Alert("outbox_stuck")
        /** The server itself could not be asked. */
        data class ServerUnreachable(val message: String) : Alert("server_unreachable")
    }

    /**
     * [latestDate] is the newest day in HC_Дни (null if none). Yesterday must already be
     * there; today's row may legitimately still be missing.
     */
    fun staleDays(today: LocalDate, latestDate: LocalDate?): Alert? =
        if (latestDate == null || latestDate.isBefore(today.minusDays(1))) Alert.StaleDays(latestDate) else null

    fun outboxStuck(nowMs: Long, count: Int, oldestCreatedMs: Long?, lastError: String): Alert? {
        if (count == 0 || oldestCreatedMs == null) return null
        val hours = (nowMs - oldestCreatedMs) / 3_600_000L
        return if (hours >= OUTBOX_STUCK_HOURS) Alert.OutboxStuck(count, hours, lastError) else null
    }

    /** Repeat the same alert at most once per [REPEAT_HOURS]. */
    fun shouldNotify(nowMs: Long, lastNotifiedMs: Long?): Boolean =
        lastNotifiedMs == null || nowMs - lastNotifiedMs >= REPEAT_HOURS * 3_600_000L

    fun message(alert: Alert): Pair<String, String> = when (alert) {
        is Alert.StaleDays -> "Данные с браслета не доходят до таблицы" to
            (alert.latestDate?.let { "Последний день в HC_Дни — $it. Откройте приложение и запустите синхронизацию." }
                ?: "В HC_Дни нет дней за последний месяц. Откройте приложение и запустите синхронизацию.")
        is Alert.OutboxStuck -> "Не отправлено записей: ${alert.count}" to
            "Вода и самочувствие ждут отправки ${alert.hours} ч." +
            (if (alert.lastError.isNotBlank()) " Причина: ${alert.lastError}" else "")
        is Alert.ServerUnreachable -> "Google Sheets недоступен" to
            "Сторож синхронизации не смог связаться с таблицей: ${alert.message}"
    }
}
