package dev.pjsk.beadpainter

import android.graphics.Bitmap
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class AccessibilityCaptureTest {
    @Test fun capturesLiveGameAndFindsEditor() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("enableLiveCapture") == "true")
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
        val method = PainterService::class.java.getDeclaredMethod("capture")
        method.isAccessible = true
        val screenshot = method.invoke(service) as Bitmap?
        assertNotNull(screenshot)
        val vision = GameVision(instrumentation.targetContext)
        val layout = vision.locate(screenshot!!)
        assertNotNull(layout)
        assertEquals(BeadPalette.EMPTY, vision.readCells(screenshot, layout!!)!![575])
        screenshot.recycle()

        val tap = PainterService::class.java.getDeclaredMethod("tap", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        tap.isAccessible = true
        val cell = layout.grid.cell(23, 23)
        try {
            assertTrue(tap.invoke(service, layout.palette[0].x, layout.palette[0].y) as Boolean)
            SystemClock.sleep(200)
            assertTrue(tap.invoke(service, cell.x, cell.y) as Boolean)
            SystemClock.sleep(350)
            val painted = method.invoke(service) as Bitmap
            assertEquals(0, vision.readCells(painted, vision.locate(painted)!!)!![575])
            painted.recycle()
        } finally {
            tap.invoke(service, layout.eraser.x, layout.eraser.y)
            SystemClock.sleep(200)
            tap.invoke(service, cell.x, cell.y)
        }
        SystemClock.sleep(350)
        val restored = method.invoke(service) as Bitmap
        assertEquals(BeadPalette.EMPTY, vision.readCells(restored, vision.locate(restored)!!)!![575])
        restored.recycle()
    }
}
