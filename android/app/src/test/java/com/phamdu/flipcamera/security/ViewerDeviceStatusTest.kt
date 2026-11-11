package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerDeviceStatusTest {
    private fun control(temp: Float?, available: Boolean, on: Boolean) = object : ViewerDeviceControl {
        override fun batteryTemperatureC() = temp
        override fun torchAvailable() = available
        override fun torchOn() = on
        override fun setTorch(on: Boolean) = available
    }

    @Test fun jsonWithValues() {
        assertEquals(
            """{"batteryTempC":36.5,"torchAvailable":true,"torchOn":false}""",
            deviceStatusJson(control(36.5f, true, false)),
        )
    }

    @Test fun jsonWithoutTemperature() {
        assertEquals(
            """{"batteryTempC":null,"torchAvailable":false,"torchOn":false}""",
            deviceStatusJson(control(null, false, false)),
        )
    }
}
