package dev.forma.core.image
import dev.forma.core.WorkPolicy
import org.junit.Assert.*
import org.junit.Test
class ImageThreadBudgetTest {
    @Test fun everyMarkupInputGetsItsOwnBoundedDecoderPool() {
        val args=WorkPolicy.withImageThreadBudget(listOf("-noautorotate","-i","original.png","-i","markup.png","-filter_complex","[0:v][1:v]overlay[out]","-map","[out]","-c:v","png","candidate.png"),8)
        val inputs=args.indices.filter{args[it]=="-i"}
        assertEquals(2,inputs.size)
        for(i in inputs){assertEquals("-threads:v",args[i-2]);assertEquals("4",args[i-1])}
        assertEquals("candidate.png",args.last());assertEquals("-filter_complex_threads",args[2])
    }
}
