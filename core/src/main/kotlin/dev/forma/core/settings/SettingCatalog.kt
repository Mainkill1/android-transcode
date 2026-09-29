package dev.forma.core.settings

import kotlin.math.abs
import kotlin.math.round

enum class SettingCategory(val title: String) {
    UI("Appearance & interaction"), VIDEO("Video & picture"), AUDIO("Audio"),
    ENGINE("Encoders & performance"), POWER("Battery & temperature"), EXPORT("Export & defaults"),
    QUEUE("Queue & notifications"), PRIVACY("Privacy & diagnostics")
}
data class SettingOption(val id: String, val label: String, val available: Boolean = true)
data class SettingSpec(
    val id: String, val label: String, val category: SettingCategory, val scope: SettingScope,
    val defaultValue: SettingValue, val help: String, val wired: Boolean = false,
    val options: List<SettingOption> = emptyList(), val minimum: Double? = null,
    val maximum: Double? = null, val step: Double = 1.0, val keywords: String = "",
    val allowedIntegers: Set<Long> = emptySet()
) {
    fun error(value: SettingValue): String? {
        if (value::class != defaultValue::class) return "Wrong value type"
        val numeric = when (value) {
            is SettingValue.Integer -> value.value.toDouble()
            is SettingValue.Decimal -> value.value
            else -> null
        }
        if (numeric != null) {
            if (!numeric.isFinite() || minimum != null && numeric < minimum || maximum != null && numeric > maximum)
                return "Value must be between $minimum and $maximum"
            val steps = (numeric - (minimum ?: 0.0)) / step
            if (abs(steps - round(steps)) > 0.000001) return "Value must use steps of $step"
        }
        if (value is SettingValue.Integer && allowedIntegers.isNotEmpty() && value.value !in allowedIntegers) return "Unsupported value"
        if (value is SettingValue.Choice && options.none { it.id == value.value }) return "Unknown choice"
        if (value is SettingValue.Text) {
            if (value.value.isBlank() || value.value.length > 160 || value.value.any { it < ' ' || it in "/\\:" } || ".." in value.value)
                return "Use a filename, not a path (maximum 160 characters)"
            val stripped = value.value.replace("{source}", "").replace("{date}", "").replace("{sequence}", "")
            if ('{' in stripped || '}' in stripped) return "Only source, date and sequence tokens are supported"
        }
        return null
    }
    fun display(value: SettingValue): String = when (value) {
        is SettingValue.Choice -> options.firstOrNull { it.id == value.value }?.label ?: value.value
        is SettingValue.Integer -> when { id == "video.max_height" && value.value == 0L -> "Source"
            id == "engine.cpu_threads" && value.value == 0L -> "Auto bounded"
            id == "export.target_bytes" -> "${value.value / 1_000_000.0} MB"
            else -> value.value.toString() }
        is SettingValue.Decimal -> value.value.toString()
        is SettingValue.Flag -> if (value.value) "On" else "Off"
        is SettingValue.Text -> value.value
    }
}

/** One production registry. Planned rows are searchable but cannot be saved as active controls. */
object SettingCatalog {
    val all: List<SettingSpec> = listOf(
        SettingSpec("ui.theme", "Theme", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("system"), "Saved theme applies throughout the app. Unsaved changes preview inside Settings.", wired = true,
            options = listOf(SettingOption("system", "System"), SettingOption("light", "Light"), SettingOption("dark", "Dark"), SettingOption("black", "Pure black"))),
        SettingSpec("ui.accent", "Accent color", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("mint"), "Dynamic colors require Android 12; older versions use Forma mint.", wired = true,
            options = listOf(SettingOption("mint", "Forma mint"), SettingOption("dynamic", "System dynamic"), SettingOption("blue", "Blue"), SettingOption("violet", "Violet"), SettingOption("amber", "Amber"))),
        SettingSpec("ui.contrast", "Contrast", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("standard"), "High-contrast palettes need visual and accessibility qualification.", wired = false,
            options = listOf(SettingOption("standard", "Standard"), SettingOption("high", "High"))),
        SettingSpec("ui.density", "Row spacing", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("comfortable"), "Controls keep 52 dp touch targets. Compact reduces extra Settings padding.", wired = true,
            options = listOf(SettingOption("comfortable", "Comfortable"), SettingOption("compact", "Compact"))),
        SettingSpec("ui.motion", "Motion", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("system"), "Follow system accessibility settings; reduced-motion adapter is planned.", wired = false,
            options = listOf(SettingOption("system", "Follow system"), SettingOption("reduced", "Reduced"))),
        SettingSpec("ui.haptics", "Touch feedback", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("system"), "Haptic interaction adapter is planned.", wired = false,
            options = listOf(SettingOption("system", "Follow system"), SettingOption("off", "Off"))),
        SettingSpec("ui.remember_sections", "Remember expanded sections", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Flag(true), "Persistent disclosure preferences are planned; an open Settings session retains navigation.", wired = false),
        SettingSpec("ui.technical_details", "Technical labels", SettingCategory.UI, SettingScope.GLOBAL,
            SettingValue.Choice("contextual"), "Show stable keys under every Settings row or only in its choice sheet.", wired = true,
            options = listOf(SettingOption("contextual", "Contextual"), SettingOption("always", "Always visible"))),
        SettingSpec("video.codec", "Video codec", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("h264"), "Content codec is separate from the hardware/software backend.", wired = true,
            options = listOf(SettingOption("h264", "H.264"), SettingOption("hevc", "H.265"), SettingOption("vp9", "VP9"), SettingOption("av1", "AV1"))),
        SettingSpec("video.rate_control", "Manual rate control", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("quality"), "Hardware requires bitrate mode. This native slice uses manual encoding; size fitting remains planned.", wired = true,
            options = listOf(SettingOption("quality", "Constant quality"), SettingOption("bitrate", "Average bitrate"))),
        SettingSpec("video.quality", "Constant quality value", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Integer(23L), "Only active in software constant-quality mode; H.264/H.265 maximum is 51.", wired = true, minimum = 0.0, maximum = 63.0),
        SettingSpec("video.bitrate_kbps", "Manual video bitrate (kb/s)", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Integer(4000L), "Only active in average-bitrate mode. Backend limits are checked before export.", wired = true, minimum = 100.0, maximum = 200000.0),
        SettingSpec("video.max_height", "Resolution ceiling", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Integer(0L), "0 means source. Aspect ratio is retained; smaller sources are not enlarged.", wired = true, minimum = 0.0, maximum = 4320.0, allowedIntegers = setOf(0L, 480L, 720L, 1080L, 1440L, 2160L, 4320L)),
        SettingSpec("video.frame_rate", "Frame rate", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("source"), "Fractional rates need a rational-timebase migration; hardware needs an explicit supported rate.", wired = true,
            options = listOf(SettingOption("source", "Source"), SettingOption("24000/1001", "23.976" , false), SettingOption("24", "24"), SettingOption("25", "25"), SettingOption("30000/1001", "29.97" , false), SettingOption("30", "30"), SettingOption("50", "50"), SettingOption("60000/1001", "59.94" , false), SettingOption("60", "60"), SettingOption("120", "120"))),
        SettingSpec("video.profile", "Codec profile", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("auto"), "Device-specific profile selection needs the native profile adapter.", wired = false,
            options = listOf(SettingOption("auto", "Auto"))),
        SettingSpec("video.bit_depth", "Output bit depth", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("preserve"), "Unsupported preservation must block, not silently convert HDR to 8-bit.", wired = false,
            options = listOf(SettingOption("preserve", "Preserve when supported"), SettingOption("8", "8-bit"), SettingOption("10", "10-bit"))),
        SettingSpec("video.deinterlace", "Deinterlacing", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("off"), "Detection is planned. Always uses the existing native deinterlacing filter.", wired = true,
            options = listOf(SettingOption("off", "Off"), SettingOption("detected", "Detected interlaced only" , false), SettingOption("always", "Always"))),
        SettingSpec("video.hdr_policy", "HDR handling", SettingCategory.VIDEO, SettingScope.JOB,
            SettingValue.Choice("preserve"), "HDR-to-SDR remains planned until a color-managed pipeline is qualified.", wired = false,
            options = listOf(SettingOption("preserve", "Preserve or stop"), SettingOption("sdr", "Convert to SDR"))),
        SettingSpec("audio.codec", "Audio codec", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Choice("aac"), "Copy/MP3 are planned. Removing audio is explicit and still validated against the output container.", wired = true,
            options = listOf(SettingOption("aac", "AAC"), SettingOption("opus", "Opus"), SettingOption("mp3", "MP3" , false), SettingOption("flac", "FLAC"), SettingOption("pcm_s16le", "PCM 16-bit"), SettingOption("pcm_f32le", "PCM float"), SettingOption("copy", "Copy if compatible" , false), SettingOption("none", "Remove audio"))),
        SettingSpec("audio.bitrate_kbps", "Audio bitrate (kb/s)", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Integer(128L), "Inactive for FLAC and removed audio.", wired = true, minimum = 32.0, maximum = 320.0, allowedIntegers = setOf(32L, 48L, 64L, 96L, 128L, 160L, 192L, 256L, 320L)),
        SettingSpec("audio.sample_rate", "Sample rate", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Choice("source"), "Resampling requires an audio-pipeline adapter.", wired = false,
            options = listOf(SettingOption("source", "Source"), SettingOption("44100", "44100 Hz"), SettingOption("48000", "48000 Hz"), SettingOption("96000", "96000 Hz"))),
        SettingSpec("audio.channels", "Channels", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Choice("source"), "Uses the audio editor's channel routing. All effects and other output settings are preserved.", wired = true,
            options = listOf(SettingOption("source", "Source"), SettingOption("stereo", "Stereo"), SettingOption("mono", "Mono"), SettingOption("left", "Left channel"), SettingOption("right", "Right channel"), SettingOption("swap", "Swap stereo"))),
        SettingSpec("audio.track_rule", "Default source track", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Choice("default"), "Resolve against inspected tracks; preferred-language selection is planned.", wired = false,
            options = listOf(SettingOption("default", "Source default track"), SettingOption("first", "First track"), SettingOption("language", "Preferred language"))),
        SettingSpec("audio.normalize", "Loudness normalization", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Choice("off"), "Requires real analysis and output measurement; not player volume.", wired = false,
            options = listOf(SettingOption("off", "Off"), SettingOption("measured", "Measured loudness normalization"))),
        SettingSpec("audio.target_lufs", "Loudness target (LUFS)", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Decimal(-16.0), "Only active when measured normalization is implemented and enabled.", wired = false, minimum = -24.0, maximum = -14.0, step = 0.5),
        SettingSpec("audio.true_peak_dbtp", "True-peak ceiling (dBTP)", SettingCategory.AUDIO, SettingScope.JOB,
            SettingValue.Decimal(-1.0), "Requires decoded-output measurement.", wired = false, minimum = -6.0, maximum = -0.1, step = 0.1),
        SettingSpec("engine.encode_backend", "Encoding backend", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("auto"), "Auto currently selects software conservatively. Hardware required never silently falls back; exact configuration is checked before export.", wired = true,
            options = listOf(SettingOption("auto", "Auto (currently software)"), SettingOption("software", "Software CPU"), SettingOption("hardware", "Hardware required")), keywords = "CPU GPU MediaCodec acceleration override"),
        SettingSpec("engine.software_effort", "Software encoding effort", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("balanced"), "Codec-specific effort mappings are planned.", wired = false,
            options = listOf(SettingOption("fast", "Fast"), SettingOption("balanced", "Balanced"), SettingOption("thorough", "Thorough"))),
        SettingSpec("engine.cpu_threads", "CPU thread request", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Integer(0L), "0 means existing Auto bounded policy. Thread overrides are not yet connected to the executor.", wired = false, minimum = 0.0, maximum = 8.0),
        SettingSpec("engine.decode_backend", "Video decoding", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("software"), "The current native pipeline uses software decode. Hardware decoding needs independent qualification.", wired = true,
            options = listOf(SettingOption("software", "Software CPU"), SettingOption("auto", "Auto" , false), SettingOption("hardware", "Hardware required" , false))),
        SettingSpec("engine.filter_backend", "Filter processing", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("cpu"), "Current filters run on CPU. GPU requires a qualified filter/surface pipeline.", wired = true,
            options = listOf(SettingOption("cpu", "CPU"), SettingOption("auto", "Auto" , false), SettingOption("gpu", "GPU required" , false))),
        SettingSpec("engine.hardware_component", "Hardware encoder component", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("auto"), "Manual component selection is planned; do not infer execution from a vendor name.", wired = false,
            options = listOf(SettingOption("auto", "Auto"))),
        SettingSpec("engine.fallback", "Automatic fallback", SettingCategory.ENGINE, SettingScope.JOB,
            SettingValue.Choice("classified"), "Native fallback orchestration is planned. Explicit hardware requests do not fall back.", wired = false,
            options = listOf(SettingOption("classified", "Software on classified codec-init failure"), SettingOption("ask", "Ask"), SettingOption("never", "Never"))),
        SettingSpec("engine.preview_during_encode", "Preview while encoding", SettingCategory.ENGINE, SettingScope.GLOBAL,
            SettingValue.Choice("reduce"), "Preview policy wiring is planned; it must not alter exported frames.", wired = false,
            options = listOf(SettingOption("reduce", "Reduce preview work"), SettingOption("normal", "Keep normal preview"), SettingOption("pause", "Pause preview"))),
        SettingSpec("power.low_action", "Low-battery action", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Choice("finish"), "Policy reducer is tested; live worker enforcement is not connected yet.", wired = false,
            options = listOf(SettingOption("warn", "Warn only"), SettingOption("finish", "Finish current file then wait"), SettingOption("stop", "Stop current file and wait"), SettingOption("ignore", "Ignore optional guard"))),
        SettingSpec("power.low_percent", "Battery threshold (%)", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Integer(20L), "At or below this level. Waiting/restart integration is planned.", wired = false, minimum = 5.0, maximum = 50.0),
        SettingSpec("power.only_when_not_charging", "Apply only when not charging", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Flag(true), "Cable present alone is not active charging; full-and-plugged is eligible.", wired = false),
        SettingSpec("power.charging_only", "Start only while charging", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Flag(false), "Independent of battery level. Unknown charging state must wait.", wired = false),
        SettingSpec("power.auto_continue", "Continue after recovery", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Flag(true), "Only after cleanup and when Android foreground-start rules permit it; otherwise tap Resume.", wired = false),
        SettingSpec("power.resume_margin", "Battery recovery margin", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Integer(5L), "Percentage points above the threshold, stable for 10 seconds.", wired = false, minimum = 2.0, maximum = 20.0),
        SettingSpec("power.unplug_action", "When charging stops", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Choice("continue"), "Continue still obeys the low-battery and charging-only rules.", wired = false,
            options = listOf(SettingOption("continue", "Continue"), SettingOption("finish", "Finish current file then wait"), SettingOption("stop", "Stop current file and wait"))),
        SettingSpec("power.thermal_action", "When the device is too hot", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Choice("stop"), "Critical thermal always stops/blocks. This is a planned worker consumer.", wired = false,
            options = listOf(SettingOption("warn", "Warn only"), SettingOption("finish", "Finish current file then wait"), SettingOption("stop", "Stop current file and wait"))),
        SettingSpec("power.thermal_threshold", "Thermal guard threshold", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Choice("severe"), "Android severity, not a guessed CPU temperature. Needs API 29+ thermal telemetry.", wired = false,
            options = listOf(SettingOption("moderate", "Moderate"), SettingOption("severe", "Severe"))),
        SettingSpec("power.respect_saver", "Respect Android Battery Saver", SettingCategory.POWER, SettingScope.POWER,
            SettingValue.Flag(true), "Planned consumer reduces optional preview and requests at most two CPU threads for the next attempt.", wired = false),
        SettingSpec("export.goal", "Export goal", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Choice("upload"), "Native size fitting is still planned. Current native exports use manual encoding.", wired = false,
            options = listOf(SettingOption("upload", "Upload size"), SettingOption("manual", "Manual encoding"))),
        SettingSpec("export.target_bytes", "Upload size limit", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Integer(10000000L), "Decimal bytes, not binary MiB. Strict size verification is required before this can be active.", wired = false, minimum = 1000000.0, maximum = 10000000000.0),
        SettingSpec("export.max_attempts", "Video/audio size-fit attempts", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Integer(4L), "Total attempt budget including initial encode and allowed backend retries.", wired = false, minimum = 1.0, maximum = 4.0),
        SettingSpec("export.container", "Default container", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Choice("mp4"), "Container and codec compatibility is checked before applying to the editor.", wired = true,
            options = listOf(SettingOption("mp4", "MP4"), SettingOption("mkv", "MKV"), SettingOption("webm", "WebM"), SettingOption("m4a", "M4A"), SettingOption("wav", "WAV"), SettingOption("flac", "FLAC"))),
        SettingSpec("export.destination", "Save destination", SettingCategory.EXPORT, SettingScope.GLOBAL,
            SettingValue.Choice("ask"), "Needs SAF permission ownership and per-job destination snapshots.", wired = false,
            options = listOf(SettingOption("ask", "Ask where to save"), SettingOption("last", "Last approved folder"), SettingOption("app", "App-managed exports"))),
        SettingSpec("export.filename", "Output naming", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Text("{source}_forma"), "Allowlisted source/date/sequence tokens only; naming adapter is planned.", wired = false),
        SettingSpec("export.collision", "Existing output name", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Choice("suffix"), "No overwrite-original mode. Publication must recheck collisions.", wired = false,
            options = listOf(SettingOption("suffix", "Add numbered suffix"), SettingOption("ask", "Ask"))),
        SettingSpec("export.metadata", "Metadata retention", SettingCategory.EXPORT, SettingScope.JOB,
            SettingValue.Choice("remove"), "Reviewed-field metadata filtering is planned; the existing editor metadata setting is preserved.", wired = false,
            options = listOf(SettingOption("remove", "Remove optional metadata"), SettingOption("descriptive", "Keep non-location descriptive metadata"), SettingOption("custom", "Custom reviewed fields"))),
        SettingSpec("queue.auto_start_added", "Start newly added queue items", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("manual"), "Separate from the explicit Convert action; never overlap native runs.", wired = false,
            options = listOf(SettingOption("manual", "Only after Start"), SettingOption("auto", "Automatically when idle"))),
        SettingSpec("queue.on_error", "After an item fails", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("review"), "Cancellation is not a failed item. Queue-boundary integration is planned.", wired = false,
            options = listOf(SettingOption("review", "Wait for review"), SettingOption("continue", "Continue remaining items"))),
        SettingSpec("queue.interrupted_prompt", "Interrupted-work prompt", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("review"), "Neither option resumes a partial file or starts work on boot.", wired = false,
            options = listOf(SettingOption("review", "Offer review on next launch"), SettingOption("quiet", "Keep in queue without prompt"))),
        SettingSpec("queue.notification_detail", "Notification detail", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("minimal"), "Mandatory foreground notification remains; OS permission/channel rules apply.", wired = false,
            options = listOf(SettingOption("minimal", "Minimal"), SettingOption("details", "Include filename and progress"))),
        SettingSpec("queue.completion_sound", "Completion sound", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("off"), "One completion sound per batch; notification channel and DND remain authoritative.", wired = false,
            options = listOf(SettingOption("off", "Off"), SettingOption("channel", "Follow notification channel"))),
        SettingSpec("queue.keep_screen_on", "Keep screen awake", SettingCategory.QUEUE, SettingScope.GLOBAL,
            SettingValue.Choice("off"), "Visible UI only; distinct from a worker wake lock.", wired = false,
            options = listOf(SettingOption("off", "Off"), SettingOption("preview", "During preview", false), SettingOption("encoding", "While encoding and app visible"))),
        SettingSpec("privacy.history_days", "Completed-history retention (days)", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Integer(7L), "0 disables completed history. Never prune active or interrupted recovery records.", wired = false, minimum = 0.0, maximum = 90.0, allowedIntegers = setOf(0L, 1L, 7L, 30L, 90L)),
        SettingSpec("diagnostics.level", "Local diagnostic detail", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Choice("normal"), "Debug must be session-bound; redact private data before collecting it.", wired = false,
            options = listOf(SettingOption("errors", "Errors only"), SettingOption("normal", "Normal"), SettingOption("debug", "Debug for this session"))),
        SettingSpec("diagnostics.retention_days", "Local-log retention (days)", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Integer(7L), "Also bounded to 10 MiB total; log retention adapter is planned.", wired = false, minimum = 1.0, maximum = 30.0, allowedIntegers = setOf(1L, 7L, 30L)),
        SettingSpec("diagnostics.include_filenames", "Filenames in shared reports", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Flag(false), "Each report needs a content preview and confirmation; never include tokens or private paths.", wired = false),
        SettingSpec("privacy.temporary_retention", "Completed temporary files", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Choice("remove"), "Reference-aware cleanup only after native readers and retry needs are gone.", wired = false,
            options = listOf(SettingOption("remove", "Remove when no longer needed"), SettingOption("24h", "Keep up to 24 hours"), SettingOption("7d", "Keep up to 7 days"))),
        SettingSpec("privacy.metered_downloads", "Downloads on metered networks", SettingCategory.PRIVACY, SettingScope.GLOBAL,
            SettingValue.Choice("ask"), "Native direct-media downloading is planned; this does not affect local exports.", wired = false,
            options = listOf(SettingOption("ask", "Ask"), SettingOption("allow", "Allow"), SettingOption("unmetered", "Unmetered only")))
    )
    private val index = all.associateBy { it.id }
    operator fun get(id: String): SettingSpec = requireNotNull(index[id]) { "Unknown setting: $id" }
    fun search(query: String): List<SettingSpec> {
        val tokens = query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return all.filter { spec ->
            val text = "${spec.id} ${spec.label} ${spec.category.title} ${spec.help} ${spec.keywords}".lowercase(java.util.Locale.ROOT)
            tokens.all { it in text }
        }
    }
}
