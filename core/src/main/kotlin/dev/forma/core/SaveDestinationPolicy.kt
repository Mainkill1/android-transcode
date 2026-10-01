package dev.forma.core

import dev.forma.core.image.QueueJobSpec
import dev.forma.core.settings.PreferenceValues
import dev.forma.core.settings.SettingCatalog
import dev.forma.core.settings.SettingValue

/** Resolves a new job's public destination without Android storage APIs. */
object SaveDestinationPolicy {
    fun needsLegacyStoragePermission(destination:SaveDestination?,sdk:Int):Boolean =
        sdk in 26..28 && destination is SaveDestination.FormaLibrary

    fun retry(previous:Delivery, spec:QueueJobSpec, values:PreferenceValues,
        chosenTree:SaveDestination.DocumentTree?):Delivery = Delivery(
        previous.destination ?: snapshot(spec,values,chosenTree),DeliveryReceipt.Waiting)

    fun snapshot(spec:QueueJobSpec, values:PreferenceValues, chosenTree:SaveDestination.DocumentTree?):SaveDestination {
        val mode=(values["export.destination"] ?: SettingCatalog["export.destination"].defaultValue) as SettingValue.Choice
        if(mode.value=="custom") return requireNotNull(chosenTree) { "Choose a save folder before queueing." }
        require(mode.value in setOf("forma","ask","last","app")) { "Unsupported save location." }
        val category=when(spec) {
            is QueueJobSpec.Image -> MediaCategory.IMAGE
            is QueueJobSpec.Av -> if(spec.settings.container.audioOnly || spec.source.videoTracks==0 && spec.sequence==null)
                MediaCategory.AUDIO else MediaCategory.VIDEO
        }
        return SaveDestination.FormaLibrary(category)
    }
}
