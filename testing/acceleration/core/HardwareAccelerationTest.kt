package dev.forma.core

import dev.forma.accelerationlab.accelerationLabChecks
import org.junit.Test

class HardwareAccelerationTest {
    @Test fun encoderSelections() = hardwareAccelerationChecks()
    @Test fun exactConfigurations() = encoderConfigurationChecks()
    @Test fun isolatedLabCommandsAndTimestamps() = accelerationLabChecks()
}
