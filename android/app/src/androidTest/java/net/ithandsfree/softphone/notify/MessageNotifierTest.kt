package net.ithandsfree.softphone.notify

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ithandsfree.softphone.data.ThreadInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Message alerts with two lines, as the background check runs them: line A has a new text, line B has none.
 * Line B's check must not remove line A's alert (that alert is also what puts the badge on the app icon).
 */
@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        MessageNotifier.cancelAll(ctx)
        ctx.getSharedPreferences("ihf_softphone_notify", 0).edit().remove("notified_fps").commit()
    }

    @After
    fun tearDown() = MessageNotifier.cancelAll(ctx)

    private fun alerts() = nm.activeNotifications.filter { it.notification.channelId == MessageNotifier.CHANNEL_ID }

    @Test
    fun otherLinesCheckKeepsTheAlert() {
        val fresh = ThreadInfo(
            threadId = "t1",
            peer = "15555550123",
            lastMessageAt = "2026-10-15 18:47:29",
            snippet = "Testing 124",
            unread = 1,
        )
        val old = ThreadInfo(threadId = "t2", peer = "15555550190", lastMessageAt = "2026-10-15 14:31:00", unread = 0)

        // Same order as SmsInboxPoller: dismissRead, then notify, per line.
        MessageNotifier.dismissRead(ctx, "1001", listOf(fresh))
        MessageNotifier.notifyNewThreads(ctx, "1001", listOf(fresh), { it }, accountId = "a")
        Thread.sleep(500)
        assertEquals("line 1001's alert is posted", 1, alerts().size)

        MessageNotifier.dismissRead(ctx, "1002", listOf(old))
        Thread.sleep(500)
        assertEquals("line 1002's check left line 1001's alert", 1, alerts().size)

        // The same line's next check keeps it too. A real inbox also lists older, read conversations; clearing those
        // must not take the new alert with them (0.4.23 and earlier did: the alert vanished within seconds).
        val readThread = ThreadInfo(threadId = "t3", peer = "15555550190", lastMessageAt = "2026-10-15 14:31:00", unread = 0)
        MessageNotifier.dismissRead(ctx, "1001", listOf(fresh, readThread))
        Thread.sleep(500)
        assertTrue(alerts().isNotEmpty())

        // Once read, it goes.
        MessageNotifier.dismissRead(ctx, "1001", listOf(fresh.copy(unread = 0)))
        Thread.sleep(500)
        assertEquals(0, alerts().size)
    }
}
