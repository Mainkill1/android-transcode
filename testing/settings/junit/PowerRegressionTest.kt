package dev.forma.core.settings

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PowerRegressionTest(@Suppress("UNUSED_PARAMETER") name: String, private val assertion: () -> Unit) {
    @Test fun contract() = assertion()
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun cases(): List<Array<Any>> = PowerRegressionChecks.cases.map { arrayOf<Any>(it.first, it.second) }
    }
}
