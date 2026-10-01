package dev.forma.core.settings

import dev.forma.core.*
import dev.forma.core.audio.*

object NativePreferenceChecks {
    private fun values(vararg v: Pair<String, SettingValue>) = PreferenceValues.of(mapOf(*v))
    private fun c(v: String) = SettingValue.Choice(v)
    private fun rejects(block: () -> Unit) { check(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "audio editor formats and hidden graph survive settings round trip" to {
            for ((container,encoder) in listOf(Container.WAV to AudioEncoder.PCM_S16LE, Container.WAV to AudioEncoder.PCM_F32LE, Container.FLAC to AudioEncoder.FLAC)) {
                val old=Settings(container=container,audio=encoder,audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("gain","gain",parameters=GainParameters(-6.0))),
                    output=AudioOutputPolicy(channels=ChannelMode.MONO,sampleRateHz=48000,normalization=NormalizationPolicy(mode=NormalizationMode.PEAK),maxBytes=9000000)))
                check(NativePreferences.apply(old,SettingsResolver.resolve(job=NativePreferences.capture(old)))==old)
            }
        },
        "channel edits affect the graph and preserve all other audio processing" to {
            val old=Settings(audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("gain","gain",parameters=GainParameters(-6.0))),
                output=AudioOutputPolicy(channels=ChannelMode.MONO,sampleRateHz=48000,maxBytes=9000000)))
            check(NativePreferences.capture(old)["audio.channels"]==c("mono"))
            for(mode in ChannelMode.entries) {
                val changed=NativePreferences.capture(old).with("audio.channels",c(mode.name.lowercase()))
                val result=NativePreferences.apply(old,SettingsResolver.resolve(job=changed))
                check(result.audioEdit==old.audioEdit.copy(output=old.audioEdit.output.copy(channels=mode)))
                check(result.stereo==old.stereo) // Keep the legacy fallback so undoing the explicit route restores it.
            }
        },
        "legacy explicit hardware is preserved by capture and apply" to {
            for (encoder in VideoEncoder.entries.filter { it.hardware }) {
                val old = Settings(video=encoder, rateControl=RateControl.BITRATE, fps=30, audioTrack=2, denoise=true, keepMetadata=true)
                val result = NativePreferences.apply(old, SettingsResolver.resolve(job=NativePreferences.capture(old)))
                check(result == old)
            }
        },
        "all legacy software encoders round trip without dropping unrelated fields" to {
            for (encoder in VideoEncoder.entries.filterNot { it.deviceRequested }) {
                val old = Settings(video=encoder, audioTrack=2, denoise=true, keepMetadata=true)
                check(NativePreferences.apply(old, SettingsResolver.resolve(job=NativePreferences.capture(old))) == old)
            }
        },
        "software override changes only the encoder route" to {
            val old = Settings(video=VideoEncoder.H264_HW, rateControl=RateControl.BITRATE, fps=30)
            val captured = NativePreferences.capture(old).with("engine.encode_backend", c("software"))
            val result = NativePreferences.apply(old, SettingsResolver.resolve(job=captured))
            check(result == old.copy(video=VideoEncoder.X264))
        },
        "automatic choice stays explicit and uses the same codec family" to {
            val old = Settings(video=VideoEncoder.H264_HW, rateControl=RateControl.BITRATE, fps=30)
            val captured = NativePreferences.capture(old).with("engine.encode_backend", c("auto"))
            check(NativePreferences.apply(old, SettingsResolver.resolve(job=captured)).video == VideoEncoder.H264_AUTO)
        },
        "automatic capture and apply retain quality or bitrate without manufacturing hardware constraints" to {
            for(encoder in listOf(VideoEncoder.H264_AUTO,VideoEncoder.H265_AUTO))
                for(control in RateControl.entries) {
                    val old=Settings(video=encoder,rateControl=control,fps=if(control==RateControl.BITRATE)30 else 0,
                        audioTrack=2,denoise=true,keepMetadata=true)
                    val captured=NativePreferences.capture(old)
                    check(captured["engine.encode_backend"]==c("auto"))
                    check(NativePreferences.apply(old,SettingsResolver.resolve(job=captured))==old)
                    check(MediaPreferences.legacy(old).resolve().getValue("engine.encode_backend").origin==ValueOrigin.JOB)
                }
        },
        "unsupported VP9 and AV1 automatic routes require an explicit supported backend" to {
            for(codec in listOf("vp9","av1")) {
                val captured=NativePreferences.capture(Settings()).with("video.codec",c(codec)).with("engine.encode_backend",c("auto"))
                rejects { NativePreferences.apply(Settings(),SettingsResolver.resolve(job=captured)) }
                check(!NativePreferences.apply(Settings(),SettingsResolver.resolve(job=captured.with("engine.encode_backend",c("software")))).video.deviceRequested)
            }
        },
        "hardware does not silently coerce CRF or source frame rate" to {
            val base = NativePreferences.capture(Settings()).with("engine.encode_backend", c("hardware"))
            rejects { NativePreferences.apply(Settings(), SettingsResolver.resolve(job=base)) }
            rejects { NativePreferences.apply(Settings(), SettingsResolver.resolve(job=base.with("video.rate_control", c("bitrate")))) }
            for(codec in listOf("vp9","av1")) {
                val explicit=base.with("video.rate_control",c("bitrate")).with("video.frame_rate",c("30")).with("video.codec",c(codec))
                check(NativePreferences.apply(Settings(),SettingsResolver.resolve(job=explicit)).video.hardware)
            }
        },
        "container conflicts and planned media choices block apply" to {
            rejects { NativePreferences.apply(Settings(), SettingsResolver.resolve(job=values("export.container" to c("webm")))) }
            rejects { NativePreferences.apply(Settings(), SettingsResolver.resolve(job=values("audio.codec" to c("mp3")))) }
            rejects { NativePreferences.apply(Settings(), SettingsResolver.resolve(job=values("video.frame_rate" to c("24000/1001")))) }
        },
        "default changes cannot mutate captured queue values" to {
            val old = Settings(audioKbps=160)
            val job = JobSpec("stable", Source("source", "clip", 1000), Trim(), old)
            NativePreferences.apply(old, SettingsResolver.resolve(values("audio.bitrate_kbps" to SettingValue.Integer(128))))
            check(job.settings.audioKbps == 160)
        },
        "CPU pipeline convenience action makes three explicit values" to {
            val updated = NativePreferences.cpuOnly(PreferenceValues.EMPTY)
            check(updated.entries.keys == setOf("engine.encode_backend", "engine.decode_backend", "engine.filter_backend"))
            check(updated["engine.encode_backend"] == c("software"))
            check(updated["engine.decode_backend"] == c("software"))
            check(updated["engine.filter_backend"] == c("cpu"))
        }
    )
}
