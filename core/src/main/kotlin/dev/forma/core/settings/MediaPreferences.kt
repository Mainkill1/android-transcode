package dev.forma.core.settings

import dev.forma.core.Settings

/** Frozen media inheritance. Live power/privacy policies remain outside a job's media snapshot. */
data class MediaPreferences(
    val app: SettingsDocument = SettingsDocument(),
    val preset: PreferenceValues = PreferenceValues.EMPTY,
    val overrides: PreferenceValues = PreferenceValues.EMPTY,
    val presetName: String? = null,
    val legacySnapshot: Boolean = false
) {
    init {
        require(presetName == null || presetName.length in 1..160)
        require((preset.entries.keys + overrides.entries.keys).all { SettingCatalog[it].scope == SettingScope.JOB })
    }
    val overrideCount: Int get() = overrides.entries.size
    fun resolve() = SettingsResolver.resolve(app.values, preset, overrides)
    fun inherited() = SettingsResolver.resolve(app.values, preset)

    /** Direct controls write only changed bindings; a settings sheet supplies its explicit layer separately. */
    fun changed(before: Settings, after: Settings, explicitIds: Set<String> = emptySet()): MediaPreferences {
        val old=NativePreferences.captureLegacy(before)
        val next=NativePreferences.captureLegacy(after)
        val ids=NativePreferences.boundIds.filter { old[it]!=next[it] || it in explicitIds }
        val edited=overrides.entries + ids.associateWith { requireNotNull(next[it]) }
        return copy(overrides=if(legacySnapshot) PreferenceValues.legacyMedia(edited) else PreferenceValues.of(edited))
    }
    fun selectPreset(settings: Settings, name: String, keepOverrides: Boolean): MediaPreferences = copy(
        preset=NativePreferences.capture(settings),presetName=name,legacySnapshot=if(keepOverrides) legacySnapshot else false,
        overrides=if(keepOverrides) overrides else PreferenceValues.EMPTY)

    data class Applied(val settings:Settings,val preferences:MediaPreferences)
    fun applyPreset(settings:Settings,name:String,keepOverrides:Boolean):Result<Applied> = try {
        val preferences=selectPreset(settings,name,keepOverrides)
        Result.success(Applied(NativePreferences.apply(settings,preferences.resolve()),preferences))
    } catch(error:IllegalArgumentException) { Result.failure(error) }

    companion object {
        fun fromDefaults(document:SettingsDocument)=MediaPreferences(app=document.copy(
            values=PreferenceValues.of(document.values.entries.filterKeys { SettingCatalog[it].scope==SettingScope.JOB })))
        /** Legacy concrete values remain explicit; no new factory/default value can rebase old work. */
        fun legacy(settings: Settings) = MediaPreferences(overrides=NativePreferences.captureLegacy(settings),legacySnapshot=true)
    }
}
