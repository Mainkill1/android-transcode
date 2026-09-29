package dev.forma.core.settings

import java.util.Collections

sealed interface SettingValue {
    data class Choice(val value: String) : SettingValue
    data class Integer(val value: Long) : SettingValue
    data class Decimal(val value: Double) : SettingValue
    data class Flag(val value: Boolean) : SettingValue
    data class Text(val value: String) : SettingValue
}
enum class SettingScope { GLOBAL, JOB, POWER }
enum class ValueOrigin { FACTORY, APP, PRESET, JOB }
data class ResolvedSetting(val value: SettingValue, val origin: ValueOrigin)

/** Copies at every boundary: caller-owned maps can never mutate queued/resolved settings. */
class PreferenceValues private constructor(private val data: Map<String, SettingValue>, private val legacy: Boolean = false) {
    operator fun get(id: String): SettingValue? = data[id]
    val entries: Map<String, SettingValue> get() = data
    fun with(id: String, value: SettingValue): PreferenceValues {
        require(SettingCatalog[id].error(value)==null) { "Invalid setting: $id" }
        return if(legacy) legacyMedia(data + (id to value)) else of(data + (id to value))
    }
    fun without(id: String) = if(legacy) legacyMedia(data - id) else of(data - id)
    fun withoutCategory(category: SettingCategory) = (data.filterKeys { SettingCatalog[it].category != category }).let { if(legacy) legacyMedia(it) else of(it) }
    override fun equals(other: Any?) = other is PreferenceValues && data == other.data
    override fun hashCode() = data.hashCode()
    companion object {
        val EMPTY = PreferenceValues(emptyMap())
        /** Queue migration only: concrete legacy values may exceed newer menu choices/ranges. */
        internal fun legacyMedia(values: Map<String,SettingValue>):PreferenceValues {
            require(values.keys.all { it in NativePreferences.boundIds })
            require(values.all { (id,value) -> value::class==SettingCatalog[id].defaultValue::class })
            return PreferenceValues(Collections.unmodifiableMap(LinkedHashMap(values)),true)
        }
        fun of(values: Map<String, SettingValue>): PreferenceValues {
            values.forEach { (id, value) -> require(SettingCatalog[id].error(value) == null) { "$id: ${SettingCatalog[id].error(value)}" } }
            return PreferenceValues(Collections.unmodifiableMap(LinkedHashMap(values)))
        }
    }
}

object SettingsResolver {
    fun resolve(app: PreferenceValues = PreferenceValues.EMPTY, preset: PreferenceValues = PreferenceValues.EMPTY,
                job: PreferenceValues = PreferenceValues.EMPTY): Map<String, ResolvedSetting> {
        (preset.entries.keys + job.entries.keys).forEach {
            require(SettingCatalog[it].scope == SettingScope.JOB) { "$it cannot be supplied by a preset or job" }
        }
        val result = SettingCatalog.all.associate { it.id to ResolvedSetting(it.defaultValue, ValueOrigin.FACTORY) }.toMutableMap()
        listOf(app to ValueOrigin.APP, preset to ValueOrigin.PRESET, job to ValueOrigin.JOB).forEach { (values, origin) ->
            values.entries.forEach { (id, value) -> result[id] = ResolvedSetting(value, origin) }
        }
        return Collections.unmodifiableMap(result)
    }
}

data class SettingsDocument(val revision: Long = 0, val values: PreferenceValues = PreferenceValues.EMPTY) {
    init { require(revision >= 0) { "Negative preferences revision" } }
}

data class SettingsDraft(val saved: SettingsDocument, val values: PreferenceValues = saved.values) {
    val dirty get() = values != saved.values
    fun edit(id: String, value: SettingValue) = copy(values = values.with(id, value))
    fun reset(id: String) = copy(values = values.without(id))
    fun resetCategory(category: SettingCategory) = copy(values = values.withoutCategory(category))
    fun commitAgainst(current: SettingsDocument): SettingsDocument {
        require(current == saved) { "Settings changed elsewhere. Reload before saving." }
        require(current.revision < Long.MAX_VALUE) { "Preferences revision exhausted" }
        return if (dirty) SettingsDocument(current.revision + 1, values) else current
    }
}

object SettingsRules {
    /** General parsing preserves known values; the production save boundary also rejects controls without consumers. */
    fun editErrors(values: PreferenceValues): List<String> = values.entries.mapNotNull { (id, value) ->
        val spec = SettingCatalog[id]
        when {
            !spec.implemented -> "$id: Planned; there is no active consumer yet."
            spec.options.isNotEmpty() && value is SettingValue.Choice && spec.options.none { it.id == value.value && it.available } ->
                "$id: This choice is not implemented yet."
            else -> spec.error(value)?.let { "$id: $it" }
        }
    }
}
