package dev.forma.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test APK only. Advertised capabilities are neither executed exports nor speed measurements. */
@RunWith(AndroidJUnit4::class)
class HardwareAccelerationInventoryTest {
    @Test fun collectAdvertisedVideoCapabilities() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with formaAccelerationInventory=true.",
            arguments.getString("formaAccelerationInventory") == "true")
        val runId = requireNotNull(arguments.getString("formaRunId")) {
            "Direct ADB runs must supply a fresh formaRunId UUID."
        }
        require(UUID.fromString(runId).toString() == runId) { "Expected a canonical lowercase UUID." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val errors = JSONArray()
        val entries = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
            .put("inventoryComplete", false).put("deviceQualified", false)
            .put("nativeExecutionTested", false).put("timestampMs", System.currentTimeMillis())
            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
            .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("codecs", entries).put("errors", errors)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                report.put("socManufacturer", Build.SOC_MANUFACTURER).put("socModel", Build.SOC_MODEL)
            }
            report.put("baseApkSha256", query(errors, "baseApkSha256") {
                sha256(File(context.applicationInfo.sourceDir))
            })
            report.put("thermalStatus", query(errors, "thermalStatus") {
                if (Build.VERSION.SDK_INT >= 29)
                    context.getSystemService(PowerManager::class.java)?.currentThermalStatus else null
            })
            // ALL_CODECS intentionally includes secure, tunneled and alias entries. They are
            // diagnostic discoveries only; this does not make them eligible for application jobs.
            for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                val types = try { info.supportedTypes.filter { it.startsWith("video/") } }
                catch (error: RuntimeException) {
                    errors.put(JSONObject().put("component", info.name).put("query", "supportedTypes")
                        .put("error", error.javaClass.simpleName))
                    emptyList()
                }
                for (mime in types) entries.put(component(info, mime))
            }
            check(entries.length() > 0) { "No video components were enumerated." }
            report.put("inventoryComplete", true)
        } catch (error: Exception) {
            report.put("error", error.javaClass.simpleName)
            throw error
        } finally {
            writeFreshReport(File(context.filesDir, "acceleration"), "inventory-$runId.json", report)
        }
    }

    private fun component(info: MediaCodecInfo, mime: String): JSONObject {
        val errors = JSONArray()
        val entry = JSONObject().put("name", info.name).put("mime", mime)
            .put("encoder", info.isEncoder).put("errors", errors)
        entry.put("hardware", query(errors, "hardware") {
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated && !info.isSoftwareOnly else null
        }).put("softwareOnly", query(errors, "softwareOnly") {
            if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else null
        }).put("alias", query(errors, "alias") {
            if (Build.VERSION.SDK_INT >= 29) info.isAlias else null
        })
        val caps = try { info.getCapabilitiesForType(mime) }
        catch (error: RuntimeException) {
            errors.put(JSONObject().put("query", "capabilities").put("error", error.javaClass.simpleName))
            return entry
        }
        entry.put("colorFormats", query(errors, "colorFormats") { JSONArray(caps.colorFormats.toList()) })
            .put("profiles", query(errors, "profiles") {
                JSONArray(caps.profileLevels.map { JSONObject().put("profile", it.profile).put("level", it.level) })
            }).put("maxInstancesAdvertised", query(errors, "maxInstances") { caps.maxSupportedInstances })
            .put("surfaceInputAdvertised", query(errors, "surfaceInput") {
                if (info.isEncoder) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats else null
            })
        val features = JSONObject()
        for (feature in listOf(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
                               MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
            features.put(feature, query(errors, feature) {
                JSONObject().put("supported", caps.isFeatureSupported(feature))
                    .put("required", caps.isFeatureRequired(feature))
            })
        }
        entry.put("features", features)
        if (info.isEncoder) entry.put("bitrateModes", query(errors, "bitrateModes") {
            caps.encoderCapabilities?.let { encoder ->
                JSONObject().put("vbr", encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    .put("cbr", encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                    .put("cq", encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ))
            }
        })
        entry.put("video", query(errors, "video") {
            caps.videoCapabilities?.let { video ->
                JSONObject().put("widthAlignment", video.widthAlignment).put("heightAlignment", video.heightAlignment)
                    .put("bitrateLower", video.bitrateRange.lower).put("bitrateUpper", video.bitrateRange.upper)
                    .put("performancePointsAdvertised", query(errors, "performancePoints") {
                        if (Build.VERSION.SDK_INT >= 29)
                            video.supportedPerformancePoints?.let { JSONArray(it.map { point -> point.toString() }) }
                        else null
                    }).put("samples", JSONArray(listOf(640 to 480, 1280 to 720, 1920 to 1080,
                                                       1080 to 1920, 3840 to 2160).map { (width, height) ->
                        val sample = JSONObject().put("width", width).put("height", height)
                        for (fps in listOf(30, 60, 120)) sample.put("supports${fps}Fps", query(errors, "${width}x${height}@$fps") {
                            video.areSizeAndRateSupported(width, height, fps.toDouble())
                        })
                        sample.put("achievableFpsEstimate", query(errors, "${width}x${height}:achievable") {
                            video.getAchievableFrameRatesFor(width, height)?.let {
                                JSONObject().put("lower", it.lower).put("upper", it.upper)
                            }
                        })
                    }))
            }
        })
        return entry
    }

    private inline fun query(errors: JSONArray, label: String, action: () -> Any?): Any = try {
        action() ?: JSONObject.NULL
    } catch (error: Exception) {
        errors.put(JSONObject().put("query", label).put("error", error.javaClass.simpleName))
        JSONObject.NULL // Missing and failed queries are not a declaration of unsupported hardware.
    }

    private fun writeFreshReport(directory: File, name: String, report: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the test report directory." }
        val target = File(directory, name)
        check(target.createNewFile()) { "A report already exists for this run ID; use a new formaRunId." }
        target.writeText(report.toString(2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
