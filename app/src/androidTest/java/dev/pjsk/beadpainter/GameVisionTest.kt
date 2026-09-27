package dev.pjsk.beadpainter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class GameVisionTest {
    @Test fun findsGridPaletteAndSaveAtNativeAndFourToThreeResolutions() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vision = GameVision(instrumentation.targetContext)
        val reference = BitmapFactory.decodeStream(instrumentation.context.assets.open("reference_editor.png"))
        val native = vision.locate(reference)
        assertNotNull(native)
        assertEquals(22f, native!!.grid.cellWidth, 0.5f)
        assertEquals(47, native.palette.size)
        assertEquals(1280, native.screenshotWidth)
        val nativeCells = vision.readCells(reference, native)
        assertEquals(576, nativeCells?.size)
        assertTrue(nativeCells!!.all { it == BeadPalette.EMPTY })
        assertFalse(vision.hasSaveSuccess(reference, native))

        val successToast = BitmapFactory.decodeStream(instrumentation.context.assets.open("save_success_toast.png"))
        val saved = reference.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(saved).drawBitmap(successToast, 510f, 28f, Paint())
        assertTrue(vision.hasSaveSuccess(saved, native))
        val fadingToast = BitmapFactory.decodeStream(instrumentation.context.assets.open("save_success_fading.png"))
        val fading = reference.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(fading).drawBitmap(fadingToast, 510f, 0f, Paint())
        assertTrue(vision.hasSaveSuccess(fading, native))

        val fourToThree = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fourToThree)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(reference, null, android.graphics.Rect(0, 96, 1024, 672), Paint(Paint.FILTER_BITMAP_FLAG))
        val adjusted = vision.locate(fourToThree)
        assertNotNull(adjusted)
        assertEquals(17.6f, adjusted!!.grid.cellWidth, 1.0f)
        assertTrue(adjusted.saveIcon.x > adjusted.grid.right)
        assertFalse(vision.hasSaveSuccess(fourToThree, adjusted))
        val scaledToast = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        Canvas(scaledToast).apply {
            drawColor(Color.BLACK)
            drawBitmap(saved, null, android.graphics.Rect(0, 96, 1024, 672), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        assertTrue(vision.hasSaveSuccess(scaledToast, adjusted))
        val fourThreeToast = BitmapFactory.decodeStream(instrumentation.context.assets.open("save_success_four_three.png"))
        val actualToastPosition = fourToThree.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(actualToastPosition).drawBitmap(fourThreeToast, 380f, 0f, Paint())
        assertTrue(vision.hasSaveSuccess(actualToastPosition, adjusted))

        val wide = Bitmap.createBitmap(1600, 720, Bitmap.Config.ARGB_8888)
        val wideCanvas = Canvas(wide)
        wideCanvas.drawColor(Color.BLACK)
        wideCanvas.drawBitmap(reference, 160f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val wideLayout = vision.locate(wide)
        assertNotNull(wideLayout)
        assertEquals(693f, wideLayout!!.grid.left, 1f)
        assertEquals(22f, wideLayout.grid.cellWidth, 0.5f)
        reference.recycle()
        successToast.recycle()
        saved.recycle()
        fadingToast.recycle()
        fading.recycle()
        fourToThree.recycle()
        scaledToast.recycle()
        fourThreeToast.recycle()
        actualToastPosition.recycle()
        wide.recycle()
    }
}
