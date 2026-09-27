package dev.pjsk.beadpainter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageConverterTest {
    @Test fun paletteContainsAllGameColors() {
        assertEquals(47, BeadPalette.colors.size)
        assertEquals("H2", BeadPalette.colors[BeadPalette.WHITE].code)
        assertEquals("A4", BeadPalette.colors.first().code)
        assertEquals("H7", BeadPalette.colors.last().code)
    }

    @Test fun whiteAndTransparentCellsEraseByDefault() {
        val white = PixelImage(24, 24, IntArray(576) { 0xffffffff.toInt() })
        val transparent = PixelImage(24, 24, IntArray(576))
        assertTrue(ImageConverter.convert(white, ConvertOptions(dither = DitherMode.NONE)).all { it == BeadPalette.EMPTY })
        assertTrue(ImageConverter.convert(transparent, ConvertOptions(dither = DitherMode.NONE)).all { it == BeadPalette.EMPTY })
        assertTrue(ImageConverter.convert(white, ConvertOptions(dither = DitherMode.NONE, paintWhite = true)).all { it == BeadPalette.WHITE })
    }

    @Test fun exactGridAndBlackRemainStable() {
        val image = PixelImage(24, 24, IntArray(576) { BeadPalette.colors.last().rgb })
        assertTrue(ImageConverter.convert(image, ConvertOptions(dither = DitherMode.NONE)).all { it == 46 })
    }

    @Test fun paintWhiteKeepsWhiteBorderInCropMode() {
        val pixels = IntArray(48 * 48) { 0xffffffff.toInt() }
        for (row in 16 until 32) for (column in 16 until 32) pixels[row * 48 + column] = BeadPalette.colors[33].rgb
        val image = PixelImage(48, 48, pixels)
        val erasedWhite = ImageConverter.convert(image, ConvertOptions(dither = DitherMode.NONE))
        val coloredWhite = ImageConverter.convert(image, ConvertOptions(dither = DitherMode.NONE, paintWhite = true))
        assertEquals(33, erasedWhite[0])
        assertEquals(BeadPalette.WHITE, coloredWhite[0])
        assertEquals(33, coloredWhite[12 * 24 + 12])
    }

    @Test fun containCreatesBlankMarginsButCropDoesNot() {
        val image = PixelImage(48, 24, IntArray(48 * 24) { BeadPalette.colors[33].rgb })
        val contain = ImageConverter.convert(image, ConvertOptions(fit = FitMode.CONTAIN, dither = DitherMode.NONE))
        val crop = ImageConverter.convert(image, ConvertOptions(fit = FitMode.CROP, dither = DitherMode.NONE))
        assertEquals(BeadPalette.EMPTY, contain[0])
        assertEquals(33, crop[0])
        assertEquals(33, contain[12 * 24 + 12])
    }

    @Test fun allDitherModesReturnValidFullGrid() {
        val pixels = IntArray(24 * 24) { index ->
            val gray = index * 255 / 575
            BeadPalette.argb(gray, gray, gray)
        }
        for (mode in DitherMode.entries) {
            val result = ImageConverter.convert(PixelImage(24, 24, pixels), ConvertOptions(dither = mode))
            assertEquals(576, result.size)
            assertTrue(result.all { it == BeadPalette.EMPTY || it in BeadPalette.colors.indices })
        }
    }

    @Test fun adjustedSourceAndQuantizedTargetRespondToColorControls() {
        val red = PixelImage(24, 24, IntArray(576) { BeadPalette.argb(240, 40, 40) })
        val original = ImageConverter.convert(red, ConvertOptions(dither = DitherMode.NONE))
        val shifted = ConvertOptions(dither = DitherMode.NONE, hue = 120)
        val preview = ImageConverter.adjustedPreview(red, shifted, 24)
        assertTrue(BeadPalette.green(preview.pixels[0]) > BeadPalette.red(preview.pixels[0]))
        assertTrue(ImageConverter.convert(red, shifted).all { it != original[0] })

        val desaturated = ImageConverter.adjustedPreview(red, ConvertOptions(saturation = 0), 24)
        assertEquals(BeadPalette.red(desaturated.pixels[0]), BeadPalette.green(desaturated.pixels[0]))
        assertEquals(BeadPalette.green(desaturated.pixels[0]), BeadPalette.blue(desaturated.pixels[0]))
    }

    @Test fun temperatureAndToneControlsChangeSourcePreview() {
        val neutral = PixelImage(24, 24, IntArray(576) { BeadPalette.argb(128, 128, 128) })
        val warmer = ImageConverter.adjustedPreview(neutral, ConvertOptions(temperature = 60), 24).pixels[0]
        val baseline = ImageConverter.adjustedPreview(neutral, ConvertOptions(), 24).pixels[0]
        assertTrue(BeadPalette.red(warmer) > BeadPalette.red(baseline))
        assertTrue(BeadPalette.blue(warmer) < BeadPalette.blue(baseline))

        val lifted = ImageConverter.adjustedPreview(neutral, ConvertOptions(shadows = 100, highlights = 100), 24).pixels[0]
        assertTrue(BeadPalette.red(lifted) > BeadPalette.red(baseline))
        val stronger = ImageConverter.adjustedPreview(neutral, ConvertOptions(contrast = 200), 24).pixels[0]
        assertTrue(BeadPalette.red(stronger) > BeadPalette.red(baseline))
    }

    @Test fun adjustedPreviewUsesSameCropAsTarget() {
        val pixels = IntArray(48 * 24) { index ->
            if (index % 48 < 12) BeadPalette.colors[33].rgb else BeadPalette.colors[15].rgb
        }
        val image = PixelImage(48, 24, pixels)
        val crop = ImageConverter.adjustedPreview(image, ConvertOptions(fit = FitMode.CROP), 24)
        val contain = ImageConverter.adjustedPreview(image, ConvertOptions(fit = FitMode.CONTAIN), 24)
        assertTrue(crop.pixels[0] != contain.pixels[0])
    }
}
