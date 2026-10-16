package net.ithandsfree.softphone.car

import androidx.car.app.HandshakeInfo
import androidx.car.app.OnDoneCallback
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.constraints.TabContentsConstraints
import androidx.car.app.testing.TestCarContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the Android Auto home screen as a current car host would, and checks the tabs are valid for it. */
@RunWith(AndroidJUnit4::class)
class HomeCarScreenTest {
    @Test
    fun homeHasRecentsAndContactsTabs() {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.uiAutomation.grantRuntimePermission(instr.targetContext.packageName, android.Manifest.permission.READ_CONTACTS)
        lateinit var screen: HomeCarScreen
        lateinit var home: TabTemplate
        instr.runOnMainSync {
            val car = TestCarContext.createCarContext(instr.targetContext)
            // A current Android Auto host (API 7); the real host sets this before the first screen is created.
            car.updateHandshakeInfo(HandshakeInfo(instr.targetContext.packageName, CarAppApiLevels.LEVEL_7))
            screen = HomeCarScreen(car)
            home = screen.onGetTemplate() as TabTemplate
        }
        assertEquals(listOf("Recents", "Contacts"), home.tabs.map { it.title.toString() })
        assertEquals("recents", home.activeTabContentId)
        TabContentsConstraints.DEFAULT.validateOrThrow(home.tabContents!!.template)

        instr.runOnMainSync { home.tabCallbackDelegate!!.sendTabSelected("contacts", object : OnDoneCallback {}) }
        Thread.sleep(1500) // contacts are read in the background
        instr.waitForIdleSync()
        lateinit var contacts: TabTemplate
        instr.runOnMainSync { contacts = screen.onGetTemplate() as TabTemplate }
        assertEquals("contacts", contacts.activeTabContentId)
        val inner = contacts.tabContents!!.template
        TabContentsConstraints.DEFAULT.validateOrThrow(inner)
        inner as ListTemplate
        assertTrue("contacts should have loaded", !inner.isLoading)
        val rows = inner.singleList!!.items.map { (it as Row).title.toString() }
        assertEquals("Search contacts", rows.first())
        // The emulator used for release checks has one contact, "Zoe Testcar" (a name late in the alphabet).
        assertTrue("contact listed: $rows", "Zoe Testcar" in rows)
    }

    @Test
    fun olderCarHostKeepsTheSingleList() {
        val instr = InstrumentationRegistry.getInstrumentation()
        lateinit var template: androidx.car.app.model.Template
        instr.runOnMainSync {
            val car = TestCarContext.createCarContext(instr.targetContext)
            car.updateHandshakeInfo(HandshakeInfo(instr.targetContext.packageName, CarAppApiLevels.LEVEL_5))
            template = HomeCarScreen(car).onGetTemplate()
        }
        assertTrue("older hosts get the 0.4.19 list, got ${template.javaClass.simpleName}", template is ListTemplate)
    }
}
