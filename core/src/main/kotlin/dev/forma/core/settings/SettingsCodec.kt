package dev.forma.core.settings

import java.util.Base64

/** Deterministic, bounded UTF-8 document; not Java serialization and never interpreted as commands. */
object SettingsCodec {
    const val MAX_BYTES = 65_536
    private const val HEADER = "FORMA_SETTINGS"
    fun encode(document: SettingsDocument): String = buildString {
        append("$HEADER\t1\t${document.revision}\n")
        document.values.entries.toSortedMap().forEach { (id, value) ->
            val wire = when (value) {
                is SettingValue.Choice -> "c:${value.value}"
                is SettingValue.Integer -> "i:${value.value}"
                is SettingValue.Decimal -> "d:${value.value}"
                is SettingValue.Flag -> "f:${value.value}"
                is SettingValue.Text -> "t:${Base64.getEncoder().encodeToString(value.value.toByteArray(Charsets.UTF_8))}"
            }
            append("$id\t$wire\n")
        }
    }.also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preferences document too large" } }

    fun decode(text: String): SettingsDocument {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Preferences document too large" }
        val lines = text.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        val header = lines.firstOrNull()?.split('\t') ?: emptyList()
        require(header.size == 3 && header[0] == HEADER && header[1] == "1") { "Unsupported preferences document/version" }
        val revision = header[2].toLongOrNull()
        require(revision != null && revision >= 0) { "Invalid preferences revision" }
        val values = linkedMapOf<String, SettingValue>()
        for (line in lines.drop(1)) {
            val pair = line.split('\t')
            require(pair.size == 2 && pair[0] !in values && pair[1].length >= 2 && pair[1][1] == ':') { "Invalid or duplicate preference entry" }
            val raw = pair[1].substring(2)
            val value = when (pair[1][0]) {
                'c' -> SettingValue.Choice(raw)
                'i' -> SettingValue.Integer(requireNotNull(raw.toLongOrNull()) { "Invalid integer" })
                'd' -> SettingValue.Decimal(requireNotNull(raw.toDoubleOrNull()) { "Invalid decimal" })
                'f' -> SettingValue.Flag(requireNotNull(raw.toBooleanStrictOrNull()) { "Invalid boolean" })
                't' -> SettingValue.Text(Base64.getDecoder().decode(raw).toString(Charsets.UTF_8))
                else -> throw IllegalArgumentException("Unknown preference type")
            }
            values[pair[0]] = value
        }
        return SettingsDocument(revision, PreferenceValues.of(values))
    }
}
