package net.ithandsfree.softphone.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bottom tabs after a call, with the app's tab switch ([openTab]) on the same routes as SoftphoneNav.
 * RSW 2026-10-15: answered a call, minimised it, opened Settings; after the call Settings would not open.
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    private fun step(nav: NavHostController, action: NavHostController.() -> Unit): String? {
        instr.runOnMainSync { nav.action() }
        instr.waitForIdleSync()
        Thread.sleep(200)
        var route: String? = null
        instr.runOnMainSync { route = nav.currentDestination?.route }
        return route
    }

    @Test
    fun settingsOpensAfterACallTakenOnSettings() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var nav: NavHostController
            scenario.onActivity {
                it.setContent {
                    nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "app/messages") {
                        for (r in listOf("app/messages", "app/calls", "app/lines", "app/settings", "app/settings/appearance")) {
                            composable(r) { Text(r) }
                        }
                    }
                }
            }
            Thread.sleep(500)
            assertEquals("app/calls", step(nav) { openTab("app/calls", restore = false) }) // call answered
            assertEquals("app/settings", step(nav) { openTab("app/settings", restore = true) }) // call minimised, Settings
            assertEquals("app/calls", step(nav) { openTab("app/calls", restore = false) }) // call ended
            assertEquals("app/messages", step(nav) { openTab("app/messages", restore = true) })
            assertEquals("app/lines", step(nav) { openTab("app/lines", restore = true) })
            assertEquals("app/settings", step(nav) { openTab("app/settings", restore = true) })
            assertEquals("app/calls", step(nav) { openTab("app/calls", restore = true) })
            // A page inside Settings is still remembered when coming back to the tab.
            assertEquals("app/settings/appearance", step(nav) { openTab("app/settings", restore = true); navigate("app/settings/appearance") })
            assertEquals("app/messages", step(nav) { openTab("app/messages", restore = true) })
            assertEquals("app/settings/appearance", step(nav) { openTab("app/settings", restore = true) })
        }
    }
}
