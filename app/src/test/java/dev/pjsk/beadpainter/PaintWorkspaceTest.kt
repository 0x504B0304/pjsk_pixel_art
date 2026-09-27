package dev.pjsk.beadpainter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintWorkspaceTest {
    @Test fun settingsRecalculateTheSharedTarget() {
        PaintWorkspace.setSource(PixelImage(24, 24, IntArray(576) { BeadPalette.argb(240, 40, 40) }))
        PaintWorkspace.update(ConvertOptions(dither = DitherMode.NONE))
        val original = PaintWorkspace.target!![0]
        val revision = PaintWorkspace.revision

        PaintWorkspace.update(ConvertOptions(dither = DitherMode.NONE, hue = 120))
        assertEquals(576, PaintWorkspace.target!!.size)
        assertEquals(144, PaintWorkspace.adjusted!!.width)
        assertNotEquals(original, PaintWorkspace.target!![0])
        assertTrue(PaintWorkspace.revision > revision)
    }

    @Test fun changingTheSourceInvalidatesThePreviousPreview() {
        PaintWorkspace.setSource(PixelImage(24, 24, IntArray(576) { BeadPalette.colors[0].rgb }))
        PaintWorkspace.update(ConvertOptions())
        PaintWorkspace.setSource(PixelImage(24, 24, IntArray(576) { BeadPalette.colors[46].rgb }))
        assertNull(PaintWorkspace.target)
        assertNull(PaintWorkspace.adjusted)
    }
}
