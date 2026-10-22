package ru.doronin.healthconnector.wellbeing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Notification quick answer: one tap records energy and mood with the same score. */
class WellbeingQuickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val score = intent.getIntExtra(EXTRA_SCORE, 0)
        if (score !in 1..5) return
        val existing = WellbeingRepository.current(context)
        WellbeingRepository.save(
            context,
            energy = score,
            mood = score,
            soreness = existing?.soreness,
            stress = existing?.stress,
            tags = existing?.tags.orEmpty()
        )
        NotificationManagerCompat.from(context).cancel(WellbeingReminderWorker.NOTIFICATION_ID)
        Toast.makeText(context, "Самочувствие отмечено", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_SCORE = "score"

        fun pendingIntent(context: Context, score: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            18210 + score,
            Intent(context, WellbeingQuickReceiver::class.java).putExtra(EXTRA_SCORE, score),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
