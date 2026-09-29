package dev.forma.core.image
class ImageHistory(val current: ImageEditDocument, val past: List<ImageEditDocument> = emptyList(),
    val future: List<ImageEditDocument> = emptyList(), val byteBudget: Long = 8L*1024*1024) {
    val estimatedBytes get() = past.sumOf { it.estimatedBytes() }+future.sumOf { it.estimatedBytes() }
    fun apply(document: ImageEditDocument): ImageHistory {
        if(document==current)return this
        val entries=(past+current.frozen()).takeLast(100).toMutableList()
        while(entries.sumOf { it.estimatedBytes() }>byteBudget && entries.isNotEmpty())entries.removeAt(0)
        return ImageHistory(document.frozen(),entries.toList(),emptyList(),byteBudget)
    }
    fun undo() = if(past.isEmpty())this else ImageHistory(past.last(),past.dropLast(1),listOf(current)+future,byteBudget)
    fun redo() = if(future.isEmpty())this else ImageHistory(future.first(),past+current,future.drop(1),byteBudget)
}
