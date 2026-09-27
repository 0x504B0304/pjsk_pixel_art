package dev.pjsk.beadpainter

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiveSaveTest {
    @Test fun paintsTwentyOneCellsAndSavesWithoutSubmitting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("enableLiveSave") == "true")
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
        assertTrue(service!!.startRun(IntArray(576) { if (it < 21) 0 else BeadPalette.EMPTY }))
        shell("am start -n dev.pjsk.beadpainter/.MainActivity")
        shell("am start -n com.hermes.mk.bilibili/com.hermes.mk.SDKActivity")
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(3)
        while (PaintStatus.busy && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
        assertFalse("Timed out: ${PaintStatus.message}", PaintStatus.busy)
        assertTrue(PaintStatus.message, PaintStatus.message == "保存成功，未提交审核")
    }
}
