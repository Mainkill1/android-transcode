package dev.forma.core.settings

/** Shared assertions: plain Kotlin CLI and JUnit/Android wrappers run this same suite. */
object SettingsChecks {
    private fun c(value: String) = SettingValue.Choice(value)
    private fun n(value: Long) = SettingValue.Integer(value)
    private fun f(value: Boolean) = SettingValue.Flag(value)
    private fun layer(vararg values: Pair<String, SettingValue>) = PreferenceValues.of(mapOf(*values))
    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        check(rejected) { "Expected rejection" }
    }
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "catalog has 64 unique keys and eight exact groups" to {
            check(SettingCatalog.all.size == 64)
            check(SettingCatalog.all.map { it.id }.distinct().size == 64)
            check(SettingCategory.entries.map { category -> SettingCatalog.all.count { it.category == category } } == listOf(8,10,8,8,10,8,6,6))
        },
        "each wired job field has a native consumer" to {
            check(SettingCatalog.all.filter { it.wired && it.scope == SettingScope.JOB }.map { it.id }.toSet() == NativePreferences.boundIds)
            check(SettingCatalog.all.count { it.wired } == 18)
        },
        "every definition rejects a value of the wrong type" to {
            SettingCatalog.all.forEach { spec ->
                val bad = if (spec.defaultValue is SettingValue.Flag) SettingValue.Integer(0) else SettingValue.Flag(false)
                check(spec.error(bad) != null) { spec.id }
            }
        },
        "factory values validate for every registered key" to {
            SettingCatalog.all.forEach { check(it.error(it.defaultValue) == null) { it.id } }
        },
        "unknown keys and mismatched types are rejected" to {
            rejects { layer("not.a.setting" to c("auto")) }
            rejects { layer("power.low_percent" to c("20")) }
            rejects { layer("ui.theme" to c("made_up")) }
        },
        "integer bounds and finite stepped decimals" to {
            rejects { layer("power.low_percent" to n(4)) }
            rejects { layer("power.low_percent" to n(51)) }
            layer("power.low_percent" to n(5)); layer("power.low_percent" to n(50))
            rejects { layer("export.target_bytes" to n(Long.MAX_VALUE)) }
            rejects { layer("audio.target_lufs" to SettingValue.Decimal(Double.NaN)) }
            rejects { layer("audio.target_lufs" to SettingValue.Decimal(-16.3)) }
            layer("audio.target_lufs" to SettingValue.Decimal(-16.5))
        },
        "explicit auto overrides saved software" to {
            val result = SettingsResolver.resolve(layer("engine.encode_backend" to c("software")), job = layer("engine.encode_backend" to c("auto")))
            check(result.getValue("engine.encode_backend").value == c("auto"))
            check(result.getValue("engine.encode_backend").origin == ValueOrigin.JOB)
        },
        "reset removes a layer rather than copying its parent" to {
            val job = layer("engine.encode_backend" to c("auto")).without("engine.encode_backend")
            val result = SettingsResolver.resolve(layer("engine.encode_backend" to c("software")), job = job)
            check(result.getValue("engine.encode_backend").value == c("software"))
            check(result.getValue("engine.encode_backend").origin == ValueOrigin.APP)
        },
        "equal values and explicit false retain provenance" to {
            val result = SettingsResolver.resolve(layer("video.codec" to c("h264")), job = layer("video.codec" to c("h264")))
            check(result.getValue("video.codec").origin == ValueOrigin.JOB)
            check(layer("power.charging_only" to f(false))["power.charging_only"] == f(false))
        },
        "preset precedence and protected scopes" to {
            val result = SettingsResolver.resolve(layer("video.codec" to c("h264")), layer("video.codec" to c("hevc")))
            check(result.getValue("video.codec").value == c("hevc"))
            check(result.getValue("video.codec").origin == ValueOrigin.PRESET)
            rejects { SettingsResolver.resolve(preset = layer("power.low_percent" to n(30))) }
            rejects { SettingsResolver.resolve(job = layer("ui.theme" to c("dark"))) }
        },
        "layer and document snapshots do not retain caller maps" to {
            val input = mutableMapOf<String, SettingValue>("video.codec" to c("h264"))
            val values = PreferenceValues.of(input)
            input["video.codec"] = c("hevc")
            check(values["video.codec"] == c("h264"))
            val queued = SettingsResolver.resolve(values)
            values.with("video.codec", c("av1"))
            check(queued.getValue("video.codec").value == c("h264"))
        },
        "64 values round trip with deterministic versioned encoding" to {
            val values = PreferenceValues.of(SettingCatalog.all.associate { it.id to it.defaultValue })
            val document = SettingsDocument(17, values)
            val encoded = SettingsCodec.encode(document)
            check(SettingsCodec.decode(encoded) == document)
            check(SettingsCodec.encode(SettingsCodec.decode(encoded)) == encoded)
        },
        "corrupt future and duplicate documents are not defaulted" to {
            rejects { SettingsCodec.decode("FORMA_SETTINGS\t99\t1\n") }
            rejects { SettingsCodec.decode("FORMA_SETTINGS\t1\t-1\n") }
            rejects { SettingsCodec.decode("FORMA_SETTINGS\t1\t0\nui.theme\tc:dark\nui.theme\tc:light\n") }
            rejects { SettingsCodec.decode("FORMA_SETTINGS\t1\t0\nui.theme\tf:true\n") }
            rejects { SettingsCodec.decode("x".repeat(65537)) }
        },
        "planned settings and planned choice cannot be saved as active" to {
            check(SettingsRules.editErrors(layer("audio.normalize" to c("measured"))).isNotEmpty())
            check(SettingsRules.editErrors(layer("engine.filter_backend" to c("gpu"))).isNotEmpty())
            check(SettingsRules.editErrors(layer("engine.encode_backend" to c("software"))).isEmpty())
        },
        "search includes category key label help and synonyms" to {
            check(SettingCatalog.search("cpu").any { it.id == "engine.encode_backend" })
            check(SettingCatalog.search("battery").any { it.id == "power.low_percent" })
            check(SettingCatalog.search("UI.THEME").single().id == "ui.theme")
            check(SettingCatalog.search("   ").size == 64)
        },
        "draft reset dirty and conflict are deterministic" to {
            val saved = SettingsDocument(3, layer("ui.theme" to c("dark")))
            val draft = SettingsDraft(saved).edit("ui.theme", c("light"))
            check(draft.dirty)
            check(draft.commitAgainst(saved).revision == 4L)
            rejects { draft.commitAgainst(SettingsDocument(4, saved.values)) }
            check(!draft.edit("ui.theme", c("dark")).dirty)
            check(SettingsDraft(saved).reset("ui.theme").values["ui.theme"] == null)
        },
        "unsafe output names and unbounded text are rejected" to {
            rejects { layer("export.filename" to SettingValue.Text("../{source}")) }
            rejects { layer("export.filename" to SettingValue.Text("{unknown}")) }
            rejects { layer("export.filename" to SettingValue.Text("a".repeat(300))) }
        },
        "battery boundary is inclusive and charging exemption is real" to {
            check(!PowerPolicy.evaluate(PowerPreferences(), PowerSample(20, ChargeState.DISCHARGING), nowMs=0).canStart)
            check(PowerPolicy.evaluate(PowerPreferences(), PowerSample(21, ChargeState.DISCHARGING), nowMs=0).canStart)
            check(PowerPolicy.evaluate(PowerPreferences(), PowerSample(10, ChargeState.CHARGING), nowMs=0).canStart)
            check(!PowerPolicy.evaluate(PowerPreferences(), PowerSample(10, ChargeState.PLUGGED_IDLE), nowMs=0).canStart)
        },
        "unknown is not zero and charging only fails closed" to {
            check(PowerPolicy.evaluate(PowerPreferences(), PowerSample(null, ChargeState.UNKNOWN), nowMs=0).canStart)
            check(!PowerPolicy.evaluate(PowerPreferences(chargingOnly=true), PowerSample(null, ChargeState.UNKNOWN), nowMs=0).canStart)
            check(!PowerPolicy.evaluate(PowerPreferences(chargingOnly=true), PowerSample(100, ChargeState.DISCHARGING), nowMs=0).canStart)
            check(PowerSample.percent(20, 0) == null)
            check(PowerSample.percent(-1, 100) == null)
            check(PowerSample.percent(101, 100) == null)
            check(PowerSample.percent(10, 50) == 20)
        },
        "battery recovery requires margin and ten continuous seconds" to {
            val policy = PowerPreferences()
            val low = PowerPolicy.evaluate(policy, PowerSample(20, ChargeState.DISCHARGING), nowMs=0)
            val near = PowerPolicy.evaluate(policy, PowerSample(24, ChargeState.DISCHARGING), low.state, 1000)
            check(!near.canStart)
            val recovery = PowerPolicy.evaluate(policy, PowerSample(25, ChargeState.DISCHARGING), near.state, 2000)
            check(!recovery.canStart)
            check(!PowerPolicy.evaluate(policy, PowerSample(25, ChargeState.DISCHARGING), recovery.state, 11999).canStart)
            check(PowerPolicy.evaluate(policy, PowerSample(25, ChargeState.DISCHARGING), recovery.state, 12000).canStart)
        },
        "charging must not bypass guard when exemption disabled" to {
            val p = PowerPreferences(exemptWhileCharging=false)
            val low = PowerPolicy.evaluate(p, PowerSample(10, ChargeState.DISCHARGING), nowMs=0)
            val plugged = PowerPolicy.evaluate(p, PowerSample(10, ChargeState.CHARGING), low.state, 1000)
            check(!PowerPolicy.evaluate(p, PowerSample(10, ChargeState.CHARGING), plugged.state, 12000).canStart)
        },
        "warn continues while drain and stop block new attempts" to {
            val sample = PowerSample(10, ChargeState.DISCHARGING)
            check(PowerPolicy.evaluate(PowerPreferences(lowAction=PowerAction.WARN), sample, nowMs=0, activeAttempt=true).canStart)
            check(PowerPolicy.evaluate(PowerPreferences(), sample, nowMs=0, activeAttempt=true).action == PowerAction.FINISH)
            check(PowerPolicy.evaluate(PowerPreferences(lowAction=PowerAction.STOP), sample, nowMs=0, activeAttempt=true).action == PowerAction.STOP)
        },
        "critical thermal cannot be bypassed even on power" to {
            val result = PowerPolicy.evaluate(PowerPreferences(thermalAction=PowerAction.WARN), PowerSample(100, ChargeState.CHARGING, 4), nowMs=0, activeAttempt=true)
            check(!result.canStart && result.action == PowerAction.STOP)
        },
        "thermal recovery takes thirty seconds and unknown cannot clear it" to {
            val p = PowerPreferences()
            val hot = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 3), nowMs=0)
            check(!PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, null), hot.state, 50000).canStart)
            val cooling = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 2), hot.state, 1000)
            check(!PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 2), cooling.state, 30999).canStart)
            check(PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 2), cooling.state, 31000).canStart)
        },
        "recovered battery cannot clear hot or user stopped work" to {
            val hot = PowerPolicy.evaluate(PowerPreferences(), PowerSample(10, ChargeState.DISCHARGING, 3), nowMs=0)
            check(!PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 3), hot.state, 50000).canStart)
            val stopped = PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 0), nowMs=0, userStopped=true)
            check(!PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 0), stopped.state, 50000).canStart)
        },
        "manual recovery remains manual and battery saver is a request" to {
            val p = PowerPreferences(autoContinue=false)
            val low = PowerPolicy.evaluate(p, PowerSample(10, ChargeState.DISCHARGING), nowMs=0)
            val recovery = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING), low.state, 1000)
            val done = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING), recovery.state, 11000)
            check(!done.canStart && "user" in done.reasons)
            check(PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, batterySaver=true), nowMs=0).threadCeiling == 2)
        }
    )
    @JvmStatic fun main(args: Array<String>) {
        val all = cases + ProvenanceChecks.cases + ConsumerChecks.cases + NativePreferenceChecks.cases + SettingsStoreChecks.cases
        val selected = if (args.isEmpty()) all else all.filter { it.first.contains(args.single(), ignoreCase=true) }
        check(selected.isNotEmpty()) { "No tests matched" }
        selected.forEach { (name, test) -> test(); println("PASS $name") }
        println("PASS ${selected.size} settings contract checks")
    }
}
