package dev.forma.core.settings

import dev.forma.core.Settings

/** Blocking persistence contract. Android calls it on IO and implements atomic replacement. */
interface SettingsStorage {
    fun read(): String?
    fun write(text: String)
}

/** One process-wide owner, serialized read/modify/write, publish only after storage succeeds. */
class SettingsStore(private val storage: SettingsStorage) {
    private var current: SettingsDocument? = null
    @Synchronized fun load(): SettingsDocument {
        current?.let { return it }
        return validate(storage.read()?.let(SettingsCodec::decode) ?: SettingsDocument()).also { current = it }
    }
    @Synchronized fun save(draft: SettingsDraft): SettingsDocument {
        val old = load() // Failure/corruption is never silently replaced with factory defaults.
        val next = draft.commitAgainst(old)
        validate(next)
        if (next != old) storage.write(SettingsCodec.encode(next))
        current = next
        return next
    }
    private fun validate(document: SettingsDocument): SettingsDocument {
        val errors = SettingsRules.editErrors(document.values)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        NativePreferences.apply(Settings(), SettingsResolver.resolve(document.values))
        return document
    }
}
