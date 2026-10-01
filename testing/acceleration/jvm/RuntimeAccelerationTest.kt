package dev.forma.ffmpeg

import dev.forma.core.EncoderChoiceChecks
import dev.forma.core.RuntimeCodecChecks
import org.junit.Test

/** Android local unit tests reference external test sources; none ship in the release APK. */
class RuntimeAccelerationTest {
    @Test fun runtimeCodecPolicy() = RuntimeCodecChecks.run()
    @Test fun encoderChoices() = EncoderChoiceChecks.run()
    @Test fun outputValidation() = OutputValidationChecks.run()
    @Test fun exportRetries() = ExportRetryChecks.run()
}
