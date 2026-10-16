package net.ithandsfree.softphone.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ithandsfree.softphone.ui.components.PictureActions
import net.ithandsfree.softphone.ui.components.ZoomableImageViewer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-screen picture shows Save, Share and Copy on screen (0.4.23 drew them under the navigation bar).
 * Checked on a real screenshot: the white labels and icons appear in the band just under the status bar, left of
 * Close. A screenshot is also left at /data/local/tmp/viewer.png for review.
 */
@RunWith(AndroidJUnit4::class)
class PictureViewerTest {
    @Test
    fun saveShareCopyAreOnScreen() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val picture = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF4477AA.toInt()) }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    ZoomableImageViewer(
                        model = picture,
                        contentDescription = "MMS image",
                        onDismiss = {},
                        pictureActions = PictureActions("https://example.invalid/p.jpg", null, "p.jpg"),
                    )
                }
            }
            Thread.sleep(2500)
            instr.waitForIdleSync()
            val ui = instr.uiAutomation
            ui.executeShellCommand("screencap -p /data/local/tmp/viewer.png").close()
            val shot = ui.takeScreenshot()
            val density = instr.targetContext.resources.displayMetrics.density
            // Band from the bottom of the status bar to ~110 dp, left 70% of the width (Close is on the right).
            val top = (24 * density).toInt()
            val bottom = (110 * density).toInt().coerceAtMost(shot.height / 4)
            var white = 0
            for (y in top until bottom step 2) {
                for (x in 0 until (shot.width * 0.7).toInt() step 2) {
                    val p = shot.getPixel(x, y)
                    if (Color.red(p) > 230 && Color.green(p) > 230 && Color.blue(p) > 230) white++
                }
            }
            assertTrue("Save / Share / Copy drawn at the top (white pixels: $white)", white > 300)
        }
    }
}
