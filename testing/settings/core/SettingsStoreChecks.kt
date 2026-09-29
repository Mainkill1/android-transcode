package dev.forma.core.settings

import java.io.IOException

object SettingsStoreChecks {
    private class MemoryStorage(var text: String? = null) : SettingsStorage {
        var fail = false
        var writes = 0
        override fun read() = text
        override fun write(text: String) { if (fail) throw IOException("write failed"); this.text=text; writes++ }
    }
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "store loads empty saves one transaction and survives reopening" to {
            val storage = MemoryStorage()
            val store = SettingsStore(storage)
            val saved = store.load()
            val draft = SettingsDraft(saved).edit("ui.theme", SettingValue.Choice("dark")).edit("audio.bitrate_kbps", SettingValue.Integer(192))
            val result = store.save(draft)
            check(result.revision == 1L && storage.writes == 1)
            check(SettingsStore(storage).load() == result)
        },
        "failed write cannot publish a new revision" to {
            val storage = MemoryStorage()
            val store = SettingsStore(storage)
            val saved = store.load()
            storage.fail = true
            check(runCatching { store.save(SettingsDraft(saved).edit("ui.theme", SettingValue.Choice("dark"))) }.exceptionOrNull() is IOException)
            check(store.load() == saved && storage.writes == 0)
        },
        "corruption is preserved and never overwritten by save" to {
            val storage = MemoryStorage("corrupt")
            val store = SettingsStore(storage)
            check(runCatching { store.load() }.isFailure)
            check(runCatching { store.save(SettingsDraft(SettingsDocument()).edit("ui.theme", SettingValue.Choice("light"))) }.isFailure)
            check(storage.text == "corrupt" && storage.writes == 0)
        },
        "semantically invalid stored settings fail before being published" to {
            val invalid = SettingsDocument(8, PreferenceValues.of(mapOf("export.container" to SettingValue.Choice("webm"))))
            val storage = MemoryStorage(SettingsCodec.encode(invalid))
            check(runCatching { SettingsStore(storage).load() }.isFailure)
            check(storage.writes == 0)
        },
        "store rejects stale writers and unwired selections" to {
            val storage = MemoryStorage()
            val store = SettingsStore(storage)
            val old = store.load()
            store.save(SettingsDraft(old).edit("ui.theme", SettingValue.Choice("dark")))
            check(runCatching { store.save(SettingsDraft(old).edit("ui.theme", SettingValue.Choice("light"))) }.isFailure)
            check(runCatching { store.save(SettingsDraft(store.load()).edit("power.charging_only", SettingValue.Flag(true))) }.isFailure)
            check(storage.writes == 1)
        }
    )
}
