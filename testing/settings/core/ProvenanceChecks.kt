package dev.forma.core.settings

import dev.forma.core.*

object ProvenanceChecks {
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "job defaults freeze media and exclude live power and global appearance" to {
            val saved=SettingsDocument(12,PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(19))
                .with("power.low_percent",SettingValue.Integer(30)).with("ui.theme",SettingValue.Choice("dark")))
            val snapshot=MediaPreferences.fromDefaults(saved)
            check(snapshot.app.revision==12L)
            check(snapshot.app.values.entries.keys==setOf("video.quality"))
            check(snapshot.resolve().getValue("video.quality").origin==ValueOrigin.APP)
        },
        "equal explicit value stays a job override across reset and snapshot" to {
            val app=SettingsDocument(8,PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)))
            val inherited=MediaPreferences(app)
            val explicit=inherited.copy(overrides=PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)))
            check(explicit.overrideCount==1)
            check(explicit.resolve().getValue("video.quality").origin==ValueOrigin.JOB)
            check(explicit.copy(overrides=explicit.overrides.without("video.quality")).resolve().getValue("video.quality").origin==ValueOrigin.APP)
            val settings=NativePreferences.apply(Settings(),explicit.resolve())
            val job=JobSpec("queued",Source("content://a/b","a",1000),Trim(),settings,explicit)
            val changed=app.copy(revision=9,values=app.values.with("video.quality",SettingValue.Integer(35)))
            check(job.preferences.app==app && changed!=job.preferences.app)
            check(job.settings.crf==23)
        },
        "preset reset returns to frozen preset and direct controls add only changed fields" to {
            val defaults=MediaPreferences(SettingsDocument(2))
            val preset=defaults.selectPreset(Settings(crf=19),"Clearer",false)
            check(preset.resolve().getValue("video.quality").origin==ValueOrigin.PRESET)
            val current=NativePreferences.apply(Settings(),preset.resolve())
            val changed=preset.changed(current,current.copy(crf=19),setOf("video.quality"))
            check(changed.overrideCount==1)
            val reset=changed.copy(overrides=PreferenceValues.EMPTY)
            check(reset.resolve().getValue("video.quality").value==SettingValue.Integer(19))
            check(reset.changed(current,current.copy(denoise=true)).overrideCount==0)
        },
        "legacy settings outside the new menu lists migrate without narrowing the old planner contract" to {
            val old=Settings(fps=33,maxHeight=1000,audioKbps=384)
            val job=JobSpec("legacy",Source("content://a/b","old",1000),Trim(),old)
            check(job.settings==old)
            check(job.preferences.resolve().getValue("video.frame_rate").value==SettingValue.Choice("33"))
            check(job.preferences.overrideCount==14)
        },
        "legacy required hardware and original media facts remain explicit" to {
            val old=Settings(video=VideoEncoder.H265_HW,rateControl=RateControl.BITRATE,fps=30,maxHeight=1080,audioKbps=160,stereo=true,keepMetadata=false)
            val legacy=MediaPreferences.legacy(old)
            check(legacy.resolve().getValue("engine.encode_backend").origin==ValueOrigin.JOB)
            check(legacy.resolve().getValue("engine.encode_backend").value==SettingValue.Choice("hardware"))
            check(NativePreferences.apply(old,legacy.resolve())==old)
        },
        "incompatible retained overrides reject preset application without mutating the draft" to {
            val old=MediaPreferences(overrides=PreferenceValues.EMPTY.with("audio.codec",SettingValue.Choice("opus")))
            val result=old.applyPreset(Settings(),"Share",true)
            check(result.isFailure)
            check(old.overrides["audio.codec"]==SettingValue.Choice("opus"))
        },
        "keeping overrides on a preset is deliberate and replacing removes them" to {
            val old=MediaPreferences(overrides=PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(31)))
            check(old.selectPreset(Settings(crf=19),"Clearer",true).resolve().getValue("video.quality").value==SettingValue.Integer(31))
            check(old.selectPreset(Settings(crf=19),"Clearer",false).overrideCount==0)
        }
    )
}
