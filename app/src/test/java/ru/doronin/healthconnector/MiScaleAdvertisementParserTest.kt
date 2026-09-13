package ru.doronin.healthconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MiScaleAdvertisementParserTest {
    @Test
    fun parsesStableKgMeasurementWithImpedance() {
        val payload = byteArrayOf(
            0x00, 0x44,             // stable + impedance
            0xEA.toByte(), 0x07,    // 2026
            0x09, 0x0D,             // Sep 13
            0x0C, 0x00, 0x00,       // 12:00:00
            0xF4.toByte(), 0x01,    // 500 ohm
            0xF0.toByte(), 0x55     // 22000 / 200 = 110 kg
        )
        val result = MiScaleAdvertisementParser.parsePayload(
            payload,
            "aa:bb:cc:dd:ee:ff",
            zoneId = ZoneId.of("UTC")
        )
        assertNotNull(result)
        result!!
        assertEquals(110.0, result.weightKg, 0.001)
        assertEquals(500, result.impedanceOhm)
        assertTrue(result.hasImpedance)
        assertEquals("AA:BB:CC:DD:EE:FF", result.deviceAddress)
        assertTrue(result.id.startsWith("xmtzc05hm|AA:BB:CC:DD:EE:FF|"))
    }

    @Test
    fun ignoresUnstableMeasurement() {
        val payload = ByteArray(13)
        payload[11] = 0xF0.toByte()
        payload[12] = 0x55
        assertNull(MiScaleAdvertisementParser.parsePayload(payload, "AA:BB:CC:DD:EE:FF"))
    }
}
