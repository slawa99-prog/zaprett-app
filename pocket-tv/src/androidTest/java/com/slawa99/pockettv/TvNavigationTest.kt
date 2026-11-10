package com.slawa99.pockettv

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.widget.ListView
import android.widget.Button
import android.app.AlertDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        val names = (1..297).map { if (it == 11) "general (ALT11)" else "strategy-${it.toString().padStart(3, '0')}" }
        var snapshot = TestSnapshot("persistent-test", "completed", 297, started = 1791072000,
            results = listOf(ProbeResult(names[0], "OK", 1, 4, 1, 4, 1, 4), ProbeResult(names[10], "OK", 4, 4, 3, 4, 3, 4)), moduleVersion = "v71", catalogId = "fixture-v71")
        @Volatile var calls = 0
        var selected = names[10]
        var moduleVersion = 71
        var updateState = ModuleUpdateState()
        var installs = 0
        val inspections = AtomicInteger()
        @Volatile var blockedRead: CountDownLatch? = null
        val readEntered = CountDownLatch(1)
        val cancelReceived = CountDownLatch(1)
        override fun inspect(): ModuleInfo {
            inspections.incrementAndGet()
            return ModuleInfo("v$moduleVersion", "running", selected, names, true, bootEnabled = true, versionCode = moduleVersion, catalogId = "fixture-v$moduleVersion")
        }
        override fun readState(includeModule: Boolean): BackendState {
            val state = super.readState(includeModule)
            blockedRead?.let { readEntered.countDown(); check(it.await(15, TimeUnit.SECONDS)) }
            return state
        }
        override fun cancelTest(): TestSnapshot {
            snapshot = snapshot.copy(cancelRequested = true)
            cancelReceived.countDown()
            return snapshot
        }
        override fun updateStatus() = updateState
        override fun installUpdate(file: File, release: ModuleRelease): String {
            UpdateSecurity.validateZip(file, release)
            installs++; updateState = ModuleUpdateState("pending_reboot", release.versionCode, "Fake installer: archive verified; no device changes")
            return "staged"
        }
        override fun poll() = snapshot
        override fun command(command: String, argument: String): String { calls++; if (command == "apply") selected = argument; return "ok" }
        override fun launch(profile: String): String {
            snapshot = TestSnapshot("failed-test", "failed", 297, failure = "Не удалось запустить curl")
            return "started"
        }
        override fun log() = "Fake Pocket log\n".repeat(200)
    }
    private fun tagged(activity: Activity, tag: String): View = activity.window.decorView.findViewWithTag(tag)
    private fun key(code: Int) {
        instrument.sendKeyDownUpSync(code)
        // The main queue can be idle before the next layout/accessibility frame.
        // Wait for the visible response before injecting another remote press.
        instrument.uiAutomation.waitForIdle(100, 5000)
        instrument.waitForIdleSync()
    }
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
    private fun waitForList(list: ListView) {
        repeat(60) {
            var ready = false
            instrument.runOnMainSync { ready = list.hasFocus() && list.childCount > 0 && !list.isLayoutRequested }
            if (ready) return
            Thread.sleep(50)
        }
        fail("Strategy list did not settle before remote input")
    }
    private fun moveDownTo(list: ListView, position: Int) {
        // Native ListView can use one press to reveal a clipped card before
        // changing selection. The tablet taskbar changes the available height.
        // Verify reaching the requested row, with a finite limit on key presses.
        repeat(100) {
            var selected = -1
            instrument.runOnMainSync {
                assertTrue("Remote focus left the strategy list", list.hasFocus())
                selected = list.selectedItemPosition
            }
            if (selected >= position) return
            key(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        fail("Remote could not reach strategy row $position")
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

        fun click(tag: String) {
            instrument.runOnMainSync { assertTrue("Focus $tag", tagged(activity, tag).requestFocus()) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        fun expectFocus(tag: String) {
            instrument.runOnMainSync { assertEquals("Remote focus", tag, activity.currentFocus?.tag) }
        }
        instrument.runOnMainSync { tagged(activity, "home_primary").requestFocus() }
        screenshot(activity, "00-home-primary")
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        instrument.runOnMainSync { assertEquals("home_help", activity.currentFocus?.tag) }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        instrument.runOnMainSync { assertEquals("home_smarttube", activity.currentFocus?.tag) }
        screenshot(activity, "00-after-down")

        click("nav_6"); key(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrument.runOnMainSync {
            assertEquals("open_updates", activity.currentFocus?.tag)
            assertTrue(tagged(activity, "nav_6").isSelected)
            assertFalse(tagged(activity, "nav_0").isSelected)
        }
        screenshot(activity, "01-settings")
        click("open_advanced")
        click("restart"); waitForIdle(app)
        assertEquals(1, fake.calls)
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync { assertEquals("open_advanced", activity.currentFocus?.tag) }
        click("open_about"); screenshot(activity, "01-about")
        repeat(8) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
        screenshot(activity, "01-about-bottom")
        key(KeyEvent.KEYCODE_BACK)
        click("open_advanced"); click("open_strategies")
        val list = tagged(activity, "strategy_list") as ListView
        instrument.runOnMainSync { list.requestFocus(); list.setSelection(0) }
        waitForList(list)
        moveDownTo(list, 25)
        screenshot(activity, "01-strategies-scrolled")
        instrument.runOnMainSync {
            assertTrue("Selected=${list.selectedItemPosition}, first=${list.firstVisiblePosition}, focus=${activity.currentFocus?.tag}", list.selectedItemPosition >= 25)
            assertTrue("Strategy list did not scroll", list.firstVisiblePosition > 0)
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        instrument.runOnMainSync { assertEquals("nav_6", activity.currentFocus?.tag) }
        key(KeyEvent.KEYCODE_BACK)
        click("open_tests")
        // Regression: from All services, Left used to skip YouTube and jump to
        // Settings. Use real D-pad events, not requestFocus(), for these routes.
        expectFocus("test_youtube")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("test_all")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("test_all") // Stop is disabled.
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("test_youtube")
        screenshot(activity, "02-youtube-focused")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("YouTube test confirmation must open", instrument.uiAutomation.rootInActiveWindow
            .findAccessibilityNodeInfosByText("адреса YouTube").isNotEmpty())
        shell("screencap -p /sdcard/Download/pocket-tv-qa/02-youtube-confirm.png")
        key(KeyEvent.KEYCODE_BACK)
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("nav_6")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("test_youtube")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("test_all")
        key(KeyEvent.KEYCODE_DPAD_DOWN); expectFocus("strategy_list")
        waitForList(tagged(activity, "strategy_list") as ListView)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("test_all")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("test_youtube")
        key(KeyEvent.KEYCODE_DPAD_DOWN); expectFocus("strategy_list")
        waitForList(tagged(activity, "strategy_list") as ListView)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("test_youtube")
        screenshot(activity, "02-results")
        assertEquals("general (ALT11)", HistoryStore(context).load().ranked.first().name)
        assertEquals(10, HistoryStore(context).load().ranked.first().ok)
        val results = tagged(activity, "strategy_list") as ListView
        instrument.runOnMainSync { results.requestFocus(); results.setSelection(0) }
        waitForList(results)
        moveDownTo(results, 20)
        instrument.runOnMainSync { assertTrue(results.firstVisiblePosition > 0) }
        var selectedResult = 0
        instrument.runOnMainSync { selectedResult = results.selectedItemPosition }
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("nav_6")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("strategy_list")
        waitForList(results)
        instrument.runOnMainSync { assertEquals(selectedResult, results.selectedItemPosition) }

        val completed = fake.snapshot
        instrument.runOnMainSync { fake.snapshot = completed.copy(id = "empty-test", status = "idle", results = emptyList()); app.refresh() }
        waitForIdle(app)
        instrument.runOnMainSync { results.setSelection(0) }
        waitForList(results)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("test_youtube")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("test_all")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("test_youtube")

        // While a test runs, only Stop is enabled. Disabled start buttons must
        // be skipped, and Up from results must still return to a usable action.
        instrument.runOnMainSync { fake.snapshot = completed.copy(status = "running"); app.refresh() }
        waitForIdle(app)
        instrument.runOnMainSync { results.requestFocus(); results.setSelection(0) }
        waitForList(results)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("cancel")
        key(KeyEvent.KEYCODE_DPAD_LEFT); expectFocus("nav_6")
        key(KeyEvent.KEYCODE_DPAD_RIGHT); expectFocus("cancel")
        key(KeyEvent.KEYCODE_DPAD_DOWN); expectFocus("strategy_list")
        waitForList(results)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("cancel")
        screenshot(activity, "02-running-stop-focused")
        // Reproduce a status read that has not returned. Cancellation must be
        // acknowledged independently, without another catalog inspection, and
        // the late read must not overwrite the acknowledged cancellation.
        val releaseRead = CountDownLatch(1)
        fake.blockedRead = releaseRead
        instrument.runOnMainSync { app.refresh() }
        assertTrue(fake.readEntered.await(5, TimeUnit.SECONDS))
        val inspectionsBeforeCancel = fake.inspections.get()
        try {
            instrument.runOnMainSync {
                assertTrue(tagged(activity, "cancel").isEnabled)
                app.control("cancel-test")
            }
            assertTrue("Cancel queued behind a status read", fake.cancelReceived.await(2, TimeUnit.SECONDS))
            repeat(40) { if (!app.snapshot.cancelRequested || app.cancelInFlight) Thread.sleep(50) }
            assertTrue(app.snapshot.cancelRequested)
            assertFalse(app.cancelInFlight)
            assertEquals(inspectionsBeforeCancel, fake.inspections.get())
        } finally { fake.blockedRead = null; releaseRead.countDown() }
        waitForIdle(app)
        assertTrue("Late status read replaced cancellation", app.snapshot.cancelRequested)
        instrument.runOnMainSync { fake.snapshot = completed.copy(status = "cancelled", cancelRequested = true); app.poll() }
        repeat(60) { if (app.snapshot.status != "cancelled") Thread.sleep(100) }
        assertEquals("cancelled", app.snapshot.status)
        assertTrue(app.notice.contains("Готовые результаты сохранены"))
        assertFalse(app.notice.contains("Дождитесь"))
        assertEquals(2, HistoryStore(context).load().results.size)
        screenshot(activity, "02-cancelled")
        instrument.runOnMainSync { fake.snapshot = completed; app.refresh() }
        waitForIdle(app)
        instrument.runOnMainSync { results.requestFocus(); results.setSelection(0) }
        waitForList(results)
        key(KeyEvent.KEYCODE_DPAD_UP); expectFocus("test_youtube")

        click("nav_5")
        instrument.runOnMainSync { app.videoOpened() }
        screenshot(activity, "03-help-feedback")
        click("video_yes")
        assertEquals("general (ALT11)", app.confirmedName)
        assertTrue(app.selectedConfirmed)
        instrument.runOnMainSync { assertEquals("help_home", activity.currentFocus?.tag) }
        instrument.runOnMainSync { app.videoOpened() }
        click("video_no")
        assertFalse(app.selectedConfirmed)
        assertNotNull(app.nextStrategy)
        assertFalse(app.notice.contains("Сохранена рабочая"))
        instrument.runOnMainSync { assertEquals("next_strategy", activity.currentFocus?.tag) }
        screenshot(activity, "04-help-next")

        click("nav_6"); click("open_updates")
        assertNotNull(app.release)
        assertEquals(72, app.release!!.versionCode)
        instrument.runOnMainSync { assertTrue(tagged(activity, "install_update").isEnabled) }
        screenshot(activity, "05-update-available")
        click("install_update")
        shell("screencap -p /sdcard/Download/pocket-tv-qa/05-update-confirm.png")
        key(KeyEvent.KEYCODE_BACK)
        assertEquals(0, fake.installs)

        // Exercise foreground download/validation/install orchestration with the actual reviewed ZIP.
        // Only the Magisk boundary is fake: the test never modifies the device module or firewall.
        val dir = File(context.cacheDir, "module-updates").apply { mkdirs() }
        File(dir, "pocket-72.zip").writeBytes(shell("cat /data/local/tmp/pocket72-test.zip"))
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        instrument.runOnMainSync { app.startModuleUpdate(72) }
        repeat(200) {
            if (!app.update.pending) Thread.sleep(100)
        }
        waitForIdle(app)
        assertEquals(1, fake.installs)
        assertTrue(app.update.pending)
        instrument.runOnMainSync { assertTrue(tagged(activity, "reboot_update").isEnabled) }
        screenshot(activity, "06-update-reboot")
        scenario.close()
        scenario = ActivityScenario.launch(intent)
        scenario.onActivity { activity = it }
        waitForIdle(app); waitForWindow(activity)
        assertTrue(app.update.pending)
        assertEquals("general (ALT11)", app.snapshot.ranked.first().name)
        assertEquals(297, app.module.strategies.size)
        instrument.runOnMainSync { fake.moduleVersion = 72; fake.updateState = ModuleUpdateState("completed", 72); app.refresh() }
        waitForIdle(app)
        assertFalse(app.snapshot.matches(app.module))
        click("nav_6"); click("open_advanced"); click("open_tests")
        screenshot(activity, "07-old-rating")
        instrument.runOnMainSync { app.launch("youtube") }
        waitForIdle(app)
        assertFalse(app.notice.contains("продолжится"))
        assertTrue(app.notice.contains("Не удалось запустить curl"))
        screenshot(activity, "08-failed-test")
        scenario.close()
    }
}
