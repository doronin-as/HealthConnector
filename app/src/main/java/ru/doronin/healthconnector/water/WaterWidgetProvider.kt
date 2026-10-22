package ru.doronin.healthconnector.water

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.doronin.healthconnector.PermissionGateActivity
import ru.doronin.healthconnector.R

/** Home-screen widget: +250 / +500 ml and undo, without opening the app. */
class WaterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ml = when (intent.action) {
            ACTION_ADD_250 -> 250
            ACTION_ADD_500 -> 500
            ACTION_UNDO -> 0
            else -> return super.onReceive(context, intent)
        }
        val pending = goAsync()
        scope.launch {
            try {
                if (ml > 0) WaterRepository.add(context, ml) else WaterRepository.undoLast(context)
            } finally {
                refreshAll(context)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_ADD_250 = "ru.doronin.healthconnector.water.ADD_250"
        private const val ACTION_ADD_500 = "ru.doronin.healthconnector.water.ADD_500"
        private const val ACTION_UNDO = "ru.doronin.healthconnector.water.UNDO"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-renders every placed widget, e.g. after water was added from the app. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WaterWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val today = WaterRepository.today(context)
            val views = RemoteViews(context.packageName, R.layout.widget_water).apply {
                setTextViewText(R.id.waterAmount, WaterFormat.summary(today.totalMl, today.goalMl))
                setProgressBar(R.id.waterProgress, 100, WaterFormat.progressPercent(today.totalMl, today.goalMl), false)
                setOnClickPendingIntent(R.id.waterAdd250, broadcast(context, ACTION_ADD_250, 1))
                setOnClickPendingIntent(R.id.waterAdd500, broadcast(context, ACTION_ADD_500, 2))
                setOnClickPendingIntent(R.id.waterUndo, broadcast(context, ACTION_UNDO, 3))
                setOnClickPendingIntent(
                    R.id.waterAmount,
                    PendingIntent.getActivity(
                        context, 4,
                        Intent(context, PermissionGateActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            manager.updateAppWidget(ids, views)
        }

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, WaterWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
