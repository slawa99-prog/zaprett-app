package com.slawa99.pockettv

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private class FakeBackend : PocketBackend {
        val names = (1..297).map { "strategy-${it.toString().padStart(3, '0')}" }
        val snapshot = TestSnapshot("persistent-test", "completed", 297, started = 1791072000,
            results = listOf(ProbeResult(names[0], "OK", 1, 4, 1, 4, 1, 4), ProbeResult(names[10], "OK", 4, 4, 3, 4, 3, 4)))
        @Volatile var calls = 0
        override fun inspect() = ModuleInfo("v71", "running", names[10], names, true, bootEnabled = true)
        override fun poll() = snapshot
        override fun command(command: String, argument: String): String { calls++; return "ok" }
        override fun launch(profile: String) = "started"
        override fun log() = "Fake Pocket log\n".repeat(200)
    }
    private fun tagged(activity: Activity, tag: String): View = activity.window.decorView.findViewWithTag(tag)
    private fun key(code: Int) { instrument.sendKeyDownUpSync(code); instrument.waitForIdleSync() }
    private fun waitForIdle(app: PocketApplication) {
        repeat(60) { if (app.busy.isEmpty() && app.connected) { instrument.waitForIdleSync(); return }; Thread.sleep(100) }
        fail("Application did not connect: ${app.error}")
    }
    private fun waitForWindow(activity: Activity) {
        repeat(60) {
            var ready = false
            instrument.runOnMainSync { ready = activity.hasWindowFocus() && activity.window.decorView.isLaidOut }
            if (ready) { instrument.waitForIdleSync(); return }
            Thread.sleep(100)
        }
        shell("mkdir -p /sdcard/Download/pocket-tv-qa")
        shell("screencap -p /sdcard/Download/pocket-tv-qa/window-failure.png")
        println(String(shell("dumpsys window")).lineSequence().filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString("\n"))
        fail("Activity did not receive input focus; destroyed=${activity.isDestroyed}, finishing=${activity.isFinishing}, changing=${activity.isChangingConfigurations}")
    }
    private fun shell(command: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun screenshot(activity: Activity, name: String) {
        waitForWindow(activity)
        // Gradle uninstalls the target APK after instrumentation; app-private files disappear.
        shell("mkdir -p /sdcard/Download/pocket-tv-qa")
        shell("screencap -p /sdcard/Download/pocket-tv-qa/$name.png")
    }
    @Test fun remoteNavigationAndHistorySurviveActivityRestart() {
        val fake = FakeBackend()
        PocketApplication.backendFactory = { fake }
        instrument.setInTouchMode(false)
        val context = instrument.targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var scenario = ActivityScenario.launch<MainActivity>(intent)
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        val app = activity.application as PocketApplication
        waitForIdle(app)
        screenshot(activity, "00-launch")

        instrument.runOnMainSync { assertTrue("Start must accept focus", tagged(activity, "start").requestFocus()) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        screenshot(activity, "00-after-down")
        instrument.runOnMainSync { assertEquals("stop", activity.currentFocus?.tag) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        instrument.runOnMainSync { assertEquals("restart", activity.currentFocus?.tag) }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitForIdle(app)
        assertEquals(1, fake.calls)
        screenshot(activity, "01-home")

        instrument.runOnMainSync { tagged(activity, "nav_1").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val list = tagged(activity, "strategy_list") as ListView
        instrument.runOnMainSync { list.requestFocus(); list.setSelection(0) }
        repeat(25) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        instrument.runOnMainSync {
            assertTrue("D-pad must advance selection", list.selectedItemPosition >= 20)
            assertTrue("D-pad must scroll the list", list.firstVisiblePosition > 0)
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        instrument.runOnMainSync { assertEquals("nav_1", activity.currentFocus?.tag) }

        instrument.runOnMainSync { tagged(activity, "nav_2").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        screenshot(activity, "02-results")
        assertEquals("strategy-011", HistoryStore(context).load().ranked.first().name)
        assertEquals(10, HistoryStore(context).load().ranked.first().ok)
        scenario.close()
        scenario = ActivityScenario.launch(intent)
        scenario.onActivity { activity = it }
        waitForIdle(app)
        waitForWindow(activity)
        assertEquals("strategy-011", app.snapshot.ranked.first().name)
        assertEquals(297, app.module.strategies.size)

        // Exercise autoscroll on the test screen as well as the catalog screen.
        instrument.runOnMainSync { tagged(activity, "nav_2").requestFocus() }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        val results = tagged(activity, "strategy_list") as ListView
        instrument.runOnMainSync { results.requestFocus(); results.setSelection(0) }
        repeat(20) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        instrument.runOnMainSync { assertTrue(results.firstVisiblePosition > 0) }
        scenario.close()
    }
}
