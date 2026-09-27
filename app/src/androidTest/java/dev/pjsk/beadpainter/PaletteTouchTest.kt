package dev.pjsk.beadpainter

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class PaletteTouchTest {
    @Test fun everyPaletteColorCanBePaintedAndRecognized() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("enablePaletteTouch") == "true")
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        }
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure enabled_accessibility_services dev.pjsk.beadpainter/dev.pjsk.beadpainter.PainterService")
        shell("settings put secure accessibility_enabled 1")
        var service = PainterService.instance
        repeat(40) {
            if (service != null) return@repeat
            SystemClock.sleep(250)
            service = PainterService.instance
        }
        assertNotNull(service)
        val capture = PainterService::class.java.getDeclaredMethod("capture").apply { isAccessible = true }
        val tap = PainterService::class.java.getDeclaredMethod("tap", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val vision = GameVision(instrumentation.targetContext)
        var layout: GameLayout? = null
        for (attempt in 0 until 15) {
            val initial = capture.invoke(service) as Bitmap
            val found = vision.locate(initial)
            val blank = found?.let { vision.readCells(initial, it)?.get(575) == BeadPalette.EMPTY } ?: false
            initial.recycle()
            if (blank) {
                layout = found
                break
            }
            SystemClock.sleep(500)
        }
        assertNotNull(layout)
        val activeLayout = layout!!
        val cell = activeLayout.grid.cell(23, 23)
        try {
            for (index in BeadPalette.colors.indices) {
                assertTrue(tap.invoke(service, activeLayout.palette[index].x, activeLayout.palette[index].y) as Boolean)
                SystemClock.sleep(200)
                assertTrue(tap.invoke(service, cell.x, cell.y) as Boolean)
                SystemClock.sleep(800)
                val screenshot = capture.invoke(service) as Bitmap
                val actual = vision.readCells(screenshot, activeLayout)!![575]
                screenshot.recycle()
                assertEquals(BeadPalette.colors[index].code, index, actual)
            }
        } finally {
            tap.invoke(service, activeLayout.eraser.x, activeLayout.eraser.y)
            SystemClock.sleep(200)
            tap.invoke(service, cell.x, cell.y)
        }
        SystemClock.sleep(800)
        val restored = capture.invoke(service) as Bitmap
        assertEquals(BeadPalette.EMPTY, vision.readCells(restored, activeLayout)!![575])
        restored.recycle()
    }
}
