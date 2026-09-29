package dev.forma.core.settings

import dev.forma.core.*

object ConsumerChecks {
    val cases:List<Pair<String,()->Unit>> = listOf(
        "minimal notifications never contain source names and details are explicit" to {
            val secret="secret-token.mp4"
            check(!ConsumerSettings.notification(PreferenceValues.EMPTY,"Converting",secret).contains(secret))
            val details=PreferenceValues.EMPTY.with("queue.notification_detail",SettingValue.Choice("details"))
            check(ConsumerSettings.notification(details,"Converting",secret).contains(secret))
            check(ConsumerSettings.notification(details,"Converting","private\nname").contains("\n").not())
        },
        "manual queue and wait after error are defaults; stopped work never auto starts" to {
            check(!ConsumerSettings.autoStart(PreferenceValues.EMPTY,false))
            val auto=PreferenceValues.EMPTY.with("queue.auto_start_added",SettingValue.Choice("auto"))
            check(ConsumerSettings.autoStart(auto,true))
            check(!ConsumerSettings.autoStart(auto,false))
            check(!ConsumerSettings.continueAfterError(PreferenceValues.EMPTY))
        },
        "screen awake belongs only to visible encoding and preview remains unavailable" to {
            val values=PreferenceValues.EMPTY.with("queue.keep_screen_on",SettingValue.Choice("encoding"))
            check(ConsumerSettings.keepScreenAwake(values,true,true))
            check(!ConsumerSettings.keepScreenAwake(values,false,true))
            check(!ConsumerSettings.keepScreenAwake(values,true,false))
            check(!ConsumerSettings.keepScreenAwake(PreferenceValues.EMPTY,true,true))
            check(SettingsRules.editErrors(PreferenceValues.EMPTY.with("queue.keep_screen_on",SettingValue.Choice("preview"))).isNotEmpty())
            check(ConsumerSettings.boundIds.all { SettingCatalog[it].implemented })
        },
        "history pruning only selects timestamped completed jobs and keeps recovery" to {
            val spec=JobSpec("stable",Source("content://a/b","a",1000),Trim(),Settings())
            val now=10*ConsumerSettings.DAY_MS
            val old=QueueEntry(spec,JobState.COMPLETED,completedAtMs=ConsumerSettings.DAY_MS)
            check(ConsumerSettings.expiredHistory(listOf(old),7,now)==setOf("stable"))
            check(ConsumerSettings.expiredHistory(listOf(old.copy(completedAtMs=null)),0,now).isEmpty())
            for(state in JobState.entries.filter { it!=JobState.COMPLETED }) check(ConsumerSettings.expiredHistory(listOf(old.copy(state=state)),0,now).isEmpty())
            check(ConsumerSettings.expiredHistory(listOf(old.copy(completedAtMs=now+1)),0,now).isEmpty())
        },
        "managed files require exact UUID identities and never use source names" to {
            check(runCatching { ManagedMediaPaths.work(java.io.File("root"),"../clip") }.isFailure)
            check(runCatching { ManagedMediaPaths.work(java.io.File("root"),"foreign") }.isFailure)
            check(ManagedMediaPaths.work(java.io.File("root"),"a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4").name=="a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4")
        }
    )
}
