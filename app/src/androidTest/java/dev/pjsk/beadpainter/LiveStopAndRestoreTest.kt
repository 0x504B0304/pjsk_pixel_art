package dev.pjsk.beadpainter

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

@RunWith(AndroidJUnit4::class)
class LiveStopAndRestoreTest {
    @Test fun stopDoesNotSaveAndRestoresChangedCells() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("enableStopRestore") == "true")
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
        val painter = service!!
        val capture = PainterService::class.java.getDeclaredMethod("capture").apply { isAccessible = true }
        val vision = GameVision(instrumentation.targetContext)
        var original: IntArray? = null
        for (attempt in 0 until 15) {
            val screenshot = capture.invoke(painter) as Bitmap
            original = vision.locate(screenshot)?.let { vision.readCells(screenshot, it) }
            screenshot.recycle()
            if (original != null) break
            SystemClock.sleep(500)
        }
        assertNotNull(original)
        val target = IntArray(576) { if (it < 21) 0 else BeadPalette.EMPTY }
        val mismatches = target.indices.filter { original!![it] != target[it] }
        assertTrue("Initial mismatches: ${mismatches.take(20).map { "$it=${original!![it]}" }}", mismatches.isEmpty())

        assertTrue(painter.startRun(IntArray(576) { 46 }))
        shell("am start -n dev.pjsk.beadpainter/.MainActivity")
        shell("am start -n com.hermes.mk.bilibili/com.hermes.mk.SDKActivity")
        val stopDeadline = SystemClock.elapsedRealtime() + 30_000
        while (PaintStatus.completed < 10 && PaintStatus.busy && SystemClock.elapsedRealtime() < stopDeadline) SystemClock.sleep(50)
        painter.requestStop()
        assertTrue("Painting did not reach 10 cells", PaintStatus.completed >= 10)
        while (PaintStatus.busy && SystemClock.elapsedRealtime() < stopDeadline) SystemClock.sleep(50)
        assertFalse(PaintStatus.busy)
        assertTrue(PaintStatus.message, PaintStatus.message.contains("用户已停止"))

        assertTrue(painter.startRun(target))
        val restoreDeadline = SystemClock.elapsedRealtime() + 60_000
        while (PaintStatus.busy && SystemClock.elapsedRealtime() < restoreDeadline) SystemClock.sleep(100)
        assertFalse("Restore timed out: ${PaintStatus.message}", PaintStatus.busy)
        assertEquals("保存成功，未提交审核", PaintStatus.message)
    }
}
