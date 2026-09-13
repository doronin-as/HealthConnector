package ru.doronin.healthconnector

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

data class MiScaleMeasurement(
    val id: String,
    val deviceAddress: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Int?,
    val stable: Boolean,
    val hasImpedance: Boolean
)

object MiScaleAdvertisementParser {
    const val BODY_COMPOSITION_SERVICE_UUID = "0000181b-0000-1000-8000-00805f9b34fb"

    /** Android ScanRecord.getServiceData() returns the 13-byte payload after the UUID. */
    fun parsePayload(
        payload: ByteArray,
        deviceAddress: String,
        receivedAtEpochMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MiScaleMeasurement? {
        if (payload.size < 13) return null
        val control = u8(payload[0]) or (u8(payload[1]) shl 8)
        val isEmpty = control and (1 shl 8) != 0
        val stable = control and (1 shl 10) != 0
        val hasImpedance = control and (1 shl 14) != 0
        if (isEmpty || !stable) return null

        val rawWeight = u8(payload[11]) or (u8(payload[12]) shl 8)
        if (rawWeight <= 0) return null
        val pounds = control and (1 shl 7) != 0
        val catty = control and (1 shl 9) != 0
        val weightKg = when {
            pounds -> rawWeight / 100.0 * 0.45359237
            catty -> rawWeight / 100.0 * 0.5
            else -> rawWeight / 200.0
        }
        if (weightKg !in 5.0..250.0) return null

        val impedance = if (hasImpedance) {
            (u8(payload[9]) or (u8(payload[10]) shl 8)).takeIf { it in 1..3000 }
        } else null

        val measuredAt = parseDeviceTime(payload, zoneId) ?: receivedAtEpochMs
        val normalizedAddress = deviceAddress.uppercase(Locale.ROOT)
        val roundedWeight = kotlin.math.round(weightKg * 100.0) / 100.0
        val id = "xmtzc05hm|$normalizedAddress|$measuredAt|${(roundedWeight * 100).toInt()}"
        return MiScaleMeasurement(id, normalizedAddress, measuredAt, roundedWeight, impedance, stable, impedance != null)
    }

    private fun parseDeviceTime(payload: ByteArray, zoneId: ZoneId): Long? {
        val year = u8(payload[2]) or (u8(payload[3]) shl 8)
        val month = u8(payload[4])
        val day = u8(payload[5])
        val hour = u8(payload[6])
        val minute = u8(payload[7])
        val second = u8(payload[8])
        if (year !in 2016..2100) return null
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff
}
