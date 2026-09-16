package ru.doronin.healthconnector.floors

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneId

object FloorHealthConnectWriter {
    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(FloorsClimbedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class)
    )

    suspend fun hasAllPermissions(context: Context): Boolean {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return false
        val client = HealthConnectClient.getOrCreate(context)
        return client.permissionController.getGrantedPermissions().containsAll(writePermissions)
    }

    suspend fun write(context: Context, detection: FloorDetection) {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val canWriteFloors = HealthPermission.getWritePermission(FloorsClimbedRecord::class) in granted
        val canWriteElevation = HealthPermission.getWritePermission(ElevationGainedRecord::class) in granted
        if (!canWriteFloors && !canWriteElevation) return

        val start = Instant.ofEpochMilli(detection.startedAtEpochMs)
        val end = Instant.ofEpochMilli(detection.endedAtEpochMs.coerceAtLeast(detection.startedAtEpochMs + 1L))
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val device = Device(
            type = Device.TYPE_PHONE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL
        )
        val baseId = "phone-floor-${detection.endedAtEpochMs}-${detection.floors}"
        val records = mutableListOf<Record>()

        if (canWriteFloors) {
            records += FloorsClimbedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                floors = detection.floors.toDouble(),
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "$baseId-floors",
                    clientRecordVersion = 1L
                )
            )
        }
        if (canWriteElevation) {
            records += ElevationGainedRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                elevation = Length.meters(detection.elevationMeters),
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "$baseId-elevation",
                    clientRecordVersion = 1L
                )
            )
        }

        if (records.isNotEmpty()) client.insertRecords(records)
    }
}
