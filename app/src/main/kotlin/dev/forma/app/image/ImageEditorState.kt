package dev.forma.app.image
data class ImageEditorState(val open:Boolean=false,val tool:String="Crop",val canUndo:Boolean=false,val canRedo:Boolean=false,val dirty:Boolean=false)
