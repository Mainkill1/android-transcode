# Queue and Finished

The navigation has two destinations: Queue contains waiting and active conversions; Finished contains completed, failed, cancelled, and interrupted conversions. Each destination has its own empty state. The persisted queue format and retry behavior remain unchanged.

Each media job card displays its immutable encoder choice. The active job displays the actual encoder and backend reported by the native attempt. This makes a queued H.264 software job distinguishable from a newly selected H.265 device setting.

No entry is silently removed during this presentation change. Failed jobs stay accessible for retry in Finished, and completed jobs retain play, share, and save actions.
