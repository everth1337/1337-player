package com.music.spotui.nexi

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.music.spotui.data.offline.DownloadForegroundService
import com.music.spotui.data.offline.OfflineDownloader
import com.music.spotui.data.offline.OfflineStore
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs only on an emulator: never changes the network or downloads of a real account. */
class DownloadServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("condition did not settle", condition())
    }
    private class Connection : ServiceConnection {
        val ready = CountDownLatch(1)
        lateinit var service: DownloadForegroundService
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as DownloadForegroundService.LocalBinder).service
            ready.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    private fun bind(): Connection = Connection().also {
        assertTrue(context.bindService(Intent(context, DownloadForegroundService::class.java), it, Context.BIND_AUTO_CREATE))
        assertTrue(it.ready.await(10, TimeUnit.SECONDS))
    }
    private fun lock(service: DownloadForegroundService): PowerManager.WakeLock? =
        DownloadForegroundService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }.get(service) as? PowerManager.WakeLock

    @Test fun timeoutPreservesQueue_thenForegroundResumes_andCancelReleasesResources() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val wifi = shell("settings get global wifi_on")
        val data = shell("settings get global mobile_data")
        var connection: Connection? = null
        val scenario = ActivityScenario.launch(app.myspoti.MainActivity::class.java)
        try {
            OfflineDownloader.stopAll()
            shell("svc wifi disable")
            shell("svc data disable")
            waitUntil { !OfflineStore.isOnline(context) }
            val queue = listOf(
                OfflineDownloader.Request("nexi-test-queue-1", "Queue fixture one", "Test", "", "", 30_000, ""),
                OfflineDownloader.Request("nexi-test-queue-2", "Queue fixture two", "Test", "", "", 30_000, ""),
            )
            // Exercise the real durable queue; the offline gate prevents any source/network lookup.
            OfflineDownloader::class.java.getDeclaredMethod("enqueueAll", List::class.java)
                .apply { isAccessible = true }.invoke(OfflineDownloader, queue)
            connection = bind()
            val first = connection.service
            waitUntil { lock(first)?.isHeld == true }
            assertEquals(2, OfflineDownloader.pendingCount())
            assertEquals(2, JSONArray(OfflineStore.pendingFile.readText()).length())
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1337001 })

            instrumentation.runOnMainSync { first.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
            assertTrue(OfflineDownloader.backgroundPaused)
            context.unbindService(connection)
            connection = null
            waitUntil { lock(first) == null }
            assertEquals(2, OfflineDownloader.pendingCount())
            assertEquals(2, JSONArray(OfflineStore.pendingFile.readText()).length())

            scenario.onActivity { OfflineDownloader.onPlayerForeground() }
            connection = bind()
            val second = connection.service
            waitUntil { lock(second)?.isHeld == true }
            assertFalse(OfflineDownloader.backgroundPaused)
            assertEquals(2, OfflineDownloader.pendingCount())
            context.startService(Intent(context, DownloadForegroundService::class.java).setAction("app.nexi.player.STOP_DOWNLOADS"))
            waitUntil { OfflineDownloader.pendingCount() == 0 }
            assertEquals(0, JSONArray(OfflineStore.pendingFile.readText()).length())
            context.unbindService(connection)
            connection = null
            waitUntil { lock(second) == null }
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1337001 })
        } finally {
            OfflineDownloader.stopAll()
            connection?.let { context.unbindService(it) }
            context.stopService(Intent(context, DownloadForegroundService::class.java))
            if (wifi == "1") shell("svc wifi enable")
            if (data == "1") shell("svc data enable")
            scenario.onActivity { OfflineDownloader.onPlayerForeground() }
            scenario.close()
        }
    }
}
