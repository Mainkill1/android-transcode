package dev.forma.core

import dev.forma.core.image.*
import dev.forma.core.settings.*
import org.junit.Assert.*
import org.junit.Test

class SaveDestinationPolicyTest {
    @Test fun legacyStoragePromptAppliesOnlyToPublicFormaFolders() {
        val library=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val tree=SaveDestination.DocumentTree("content://documents/tree/folder","Folder")
        assertTrue(SaveDestinationPolicy.needsLegacyStoragePermission(library,28))
        assertFalse(SaveDestinationPolicy.needsLegacyStoragePermission(tree,28))
        assertFalse(SaveDestinationPolicy.needsLegacyStoragePermission(library,29))
    }
    @Test fun formaFoldersAreDefaultForEveryMediaKind() {
        val source=Source("content://source","clip",1000,videoTracks=1)
        fun av(container:Container)=QueueJobSpec.Av(JobSpec("id",source,Trim(),Settings(container=container)))
        val image=QueueJobSpec.Image(ImageJobSpec("image",ImageEditDocument(source=ImageSource("content://image","still","a".repeat(64),1))))
        assertEquals(SaveDestination.FormaLibrary(MediaCategory.VIDEO),SaveDestinationPolicy.snapshot(av(Container.MP4),PreferenceValues.EMPTY,null))
        assertEquals(SaveDestination.FormaLibrary(MediaCategory.AUDIO),SaveDestinationPolicy.snapshot(av(Container.M4A),PreferenceValues.EMPTY,null))
        val audioOnlySource=QueueJobSpec.Av(JobSpec("audio",source.copy(videoTracks=0,audioTracks=1),Trim(),Settings(container=Container.MP4)))
        assertEquals(SaveDestination.FormaLibrary(MediaCategory.AUDIO),SaveDestinationPolicy.snapshot(audioOnlySource,PreferenceValues.EMPTY,null))
        assertEquals(SaveDestination.FormaLibrary(MediaCategory.IMAGE),SaveDestinationPolicy.snapshot(image,PreferenceValues.EMPTY,null))
    }

    @Test fun customFolderIsFrozenAtQueueTimeAndRequiresSelection() {
        val spec=QueueJobSpec.Av(JobSpec("id",Source("content://source","clip",1000),Trim(),Settings()))
        val values=PreferenceValues.EMPTY.with("export.destination",SettingValue.Choice("custom"))
        val first=SaveDestination.DocumentTree("content://provider/tree/one","One")
        val second=SaveDestination.DocumentTree("content://provider/tree/two","Two")
        assertThrows(IllegalArgumentException::class.java) { SaveDestinationPolicy.snapshot(spec,values,null) }
        val snapshot=SaveDestinationPolicy.snapshot(spec,values,first)
        assertEquals(first,snapshot)
        assertEquals(second,SaveDestinationPolicy.snapshot(spec,values,second))
        assertEquals(first,snapshot)
    }

    @Test fun saveLocationIsAnActiveGlobalSetting() {
        assertEquals(SettingValue.Choice("forma"),SettingCatalog["export.destination"].defaultValue)
        assertTrue(SettingCatalog["export.destination"].implemented)
        assertTrue(SettingsRules.editErrors(PreferenceValues.EMPTY.with("export.destination",SettingValue.Choice("custom"))).isEmpty())
    }

    @Test fun retryKeepsAnExistingDestinationSnapshot() {
        val spec=QueueJobSpec.Av(JobSpec("retry",Source("content://source","clip",1000,videoTracks=1),Trim(),Settings()))
        val old=Delivery(SaveDestination.DocumentTree("content://provider/tree/old","Old"),
            DeliveryReceipt.Failed("Conversion failed",null))
        val newer=PreferenceValues.EMPTY.with("export.destination",SettingValue.Choice("forma"))
        assertEquals(Delivery(old.destination,DeliveryReceipt.Waiting),SaveDestinationPolicy.retry(old,spec,newer,null))
        assertEquals(Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting),
            SaveDestinationPolicy.retry(Delivery.LEGACY,spec,newer,null))
    }
}
