package dev.forma.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegListingTest {
    @Test fun parsesTwoFlagFilterRowsFromFfmpeg9WithoutLegendEntries() {
        // FFmpeg n9.0.1 fftools/opt_common.c: show_filters prints two row flags,
        // while its human-readable legend still uses three characters.
        val listing = """
            Filters:
              T.. = Timeline support
              .S. = Slice threading
              A = Audio input/output
              ------
             .. scale             V->V       Scale the input video size and/or convert the image format.
             T. hqdn3d            V->V       Apply a High Quality 3D Denoiser.
             TS yadif             V->V       Deinterlace the input image.
             .. anull             A->A       Pass the source unchanged.
             .. testsrc           |->V       Generate test pattern.
        """.trimIndent()
        assertEquals(setOf("scale", "hqdn3d", "yadif", "anull", "testsrc"), FfmpegListing.filters(listing))
    }
    @Test fun stillParsesOlderThreeFlagFilterRows() {
        assertEquals(setOf("scale", "hqdn3d"), FfmpegListing.filters(" ..C scale V->V Scale\n TSC hqdn3d V->V Denoise\n"))
    }
    @Test fun excludesEncoderAndMuxerLegendSymbols() {
        assertEquals(setOf("libx264", "aac", "libvpx-vp9", "libaom-av1"),
            FfmpegListing.encoders(" V..... = Video\n V....D libx264 H.264\n A..... aac AAC\n V....D libvpx-vp9 VP9\n V....D libaom-av1 AV1\n"))
        assertEquals(setOf("mp4", "mov", "ipod"), FfmpegListing.muxers(" E = Muxing supported\n E mp4,mov MPEG-4\n E ipod Apple MPEG-4 audio\n"))
    }
    @Test fun retainsAdjacentRowsWithoutDescriptionsFromSmallNativeBuild() {
        assertEquals(setOf("libx264", "h264_mediacodec", "aac", "ac3"),
            FfmpegListing.encoders(" V....D libx264        \n V....D h264_mediacodec \n A..... aac            \n A....D ac3            \n"))
        assertEquals(setOf("mp4", "ipod", "mov"), FfmpegListing.muxers(" E mp4   \n E ipod  \n E mov   \n"))
    }
}
