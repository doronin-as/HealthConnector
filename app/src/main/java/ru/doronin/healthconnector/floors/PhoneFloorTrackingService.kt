package ru.doronin.healthconnector.floors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.doronin.healthconnector.R
import ru.doronin.healthconnector.StreamingMainActivity
import java.util.Locale

class PhoneFloorTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var sensorManager: SensorManager
    private var pressureSensor: Sensor? = null
    private var stepDetector: Sensor? = null
    private val estimator = FloorEstimator()

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PhoneFloorStore.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!PhoneFloorStore.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pressureSensor == null || stepDetector == null) {
            PhoneFloorStore.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        registerSensors()
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        estimator.reset()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                if (event.values.firstOrNull()?.let { it > 0f } == true) estimator.onStep(now)
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values.firstOrNull() ?: return
                if (pressure <= 0f) return
                val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                val detection = estimator.onAltitude(altitude.toDouble(), now) ?: return
                val snapshot = PhoneFloorStore.addDetection(this, detection)
                updateNotification(snapshot)
                scope.launch {
                    runCatching { FloorHealthConnectWriter.write(applicationContext, detection) }
                        .onSuccess { PhoneFloorStore.setHealthConnectError(applicationContext, null) }
                        .onFailure { error ->
                            PhoneFloorStore.setHealthConnectError(
                                applicationContext,
                                error.message ?: error.javaClass.simpleName
                            )
                        }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(PhoneFloorStore.snapshot(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun updateNotification(snapshot: PhoneFloorStore.Snapshot) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: PhoneFloorStore.Snapshot): android.app.Notification {
        val openPendingIntent = PendingIntent.getActivity(
            this,
            20401,
            Intent(this, StreamingMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            20402,
            Intent(this, PhoneFloorTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elevation = String.format(Locale.getDefault(), "%.0f", snapshot.elevationMeters)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("Подсчёт этажей")
            .setContentText("Сегодня: ${snapshot.floors} этажей · +$elevation м")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(0, "Остановить", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Этажи и высота",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Фоновый подсчёт этажей по барометру и шагам телефона"
                }
            )
        }
    }

    companion object {
        const val ACTION_STOP = "ru.doronin.healthconnector.action.STOP_FLOOR_TRACKING"
        private const val CHANNEL_ID = "phone_floor_tracking"
        private const val NOTIFICATION_ID = 20400
    }
}
