package dev.forma.app.data
/** URI identity alone does not retain the original location after a shared import is copied. */
internal fun requireEmptyExportDestination(sourceUri:String,destinationUri:String,firstByte:Int?) {
    require(sourceUri!=destinationUri) { "The original cannot be the export destination." }
    require(firstByte == -1) { "Choose a new empty document. Existing or unreadable files cannot be replaced." }
}
