// Nexi Player, GPL-3.0. Tests for the Myspoti-based native client.
package com.music.spotui.nexi

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.myspoti.MainActivity
import app.myspoti.host.android.MyspotiRuntime
import com.music.spotui.cli.CliReceiver
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.ScreenCache
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.local.LocalMusic
import com.music.spotui.data.local.NexiLegacyFiles
import com.music.spotui.data.preferences.Look
import com.music.spotui.data.preferences.LookPref
import com.music.spotui.di.LocalPlayer
import com.music.spotui.ui.navigation.PlayerSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic catalogue data, confined to the test installation. No Spotify session is forged. */
@RunWith(AndroidJUnit4::class)
class NexiNativeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shot(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Timed out", condition())
    }

    @Test fun cachedCatalogueAndLegacyAudioWorkWithoutNetwork() = runBlocking {
        main { MyspotiRuntime.suspendPlayer() }
        context.getSharedPreferences("myspoti_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("LoggedIn", true).putBoolean("SetupDone", true)
            .putBoolean("SetupExtensionsSeen", true).commit()
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("quality_chosen", true).commit()
        main { LookPref.set(context, Look.IMMERSIVE); LookPref.setChosen(context) }

        val home = HomeFeedModel("Тестовый каталог · без сети", listOf(
            HomeSection("Продолжить слушать", listOf(
                HomeItem.Playlist("Вечерний сигнал", "", "Тестовые данные", "nexi-test-1"),
                HomeItem.Playlist("В дороге", "", "Тестовые данные", "nexi-test-2"),
                HomeItem.Playlist("Тихий режим", "", "Тестовые данные", "nexi-test-3"),
            )),
            HomeSection("Сохранённые подборки", listOf(
                HomeItem.Album("nexi-test-4", "После полуночи", "", "Тестовый альбом", "Nexi Test"),
                HomeItem.Album("nexi-test-5", "Новый день", "", "Тестовый альбом", "Nexi Test"),
            )),
        ))
        val library = listOf(
            LibraryEntry("nexi-test-1", "Вечерний сигнал", "Плейлист · тестовые данные", "", true),
            LibraryEntry("nexi-test-2", "В дороге", "Плейлист · тестовые данные", "", true),
        )
        ScreenCache.home.write(home)
        ScreenCache.libraryRoot.write(library)
        waitUntil { ScreenCache.home.read() == home && ScreenCache.libraryRoot.read() == library }
        // Discard the memory copy: the screen must restore its catalogue from disk.
        Api.HomeCache.home = null
        Api.HomeCache.libraryRoot = null

        File(context.filesDir, "nexi_legacy_audio/00000000-0000-0000-0000-000000000001_Offline tone.wav").delete()
        val file = File(context.filesDir, "offline/00000000-0000-0000-0000-000000000001_Offline tone.wav")
        file.parentFile!!.mkdirs()
        // A silent 20-second PCM WAV generated here; no music is bundled in tests or the app.
        val bytes = 16_000 * 2 * 20
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes).array()
        file.outputStream().use { it.write(header); it.write(ByteArray(bytes)) }
        assertTrue(LocalMusic.canBrowse(context))
        val tracks = LocalMusic.tracks(context, refresh = true)
        val track = tracks.single { it.uri.path == file.absolutePath }
        assertEquals("Offline tone.wav", track.title)
        assertEquals(20_000L, track.durationMs)
        assertTrue(track.id < 0)
        assertEquals(track.id, LocalMusic.tracks(context, refresh = true).single { it.uri == track.uri }.id)
        val nativeIndex = File(context.filesDir, "offline/index.json")
        if (!nativeIndex.exists()) nativeIndex.writeText("[]")
        assertTrue(NexiLegacyFiles.migrate(context))
        assertFalse(file.exists())
        assertTrue(nativeIndex.exists())
        val migrated = LocalMusic.tracks(context, refresh = true).single { it.id == track.id }
        assertTrue(migrated.uri.path!!.contains("/nexi_legacy_audio/"))
        assertEquals(track.durationMs, migrated.durationMs)
        assertFalse(LocalMusic.tracks(context).any { it.title == "index.json" })

        // The loading indicator can keep drawing while offline; wait for RESUMED, not global idle.
        main { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var activity: MainActivity? = null
        waitUntil {
            main { activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
            activity != null
        }
        try {
            Thread.sleep(3_000)
            shot("nexi-home-offline")
            assertEquals(home, Api.HomeCache.home)
            main { CliReceiver.nav.tryEmit("library") }
            Thread.sleep(1_500)
            shot("nexi-library-offline")
            main { CliReceiver.nav.tryEmit("localfiles") }
            Thread.sleep(1_500)
            shot("nexi-local-offline")
            main { LocalPlayer.play(listOf(migrated), 0, context = "Nexi · офлайн") }
            waitUntil {
                var played = false
                main { played = LocalPlayer.isPlaying() && LocalPlayer.position() > 300 }
                played
            }
            main { LocalPlayer.seekTo(8_000); PlayerSheet.show() }
            Thread.sleep(1_500)
            shot("nexi-player-offline")
            main {
                assertTrue(LocalPlayer.position() >= 8_000)
                LocalPlayer.pause()
                assertFalse(LocalPlayer.isPlaying())
                LocalPlayer.resume()
            }
            waitUntil {
                var playing = false
                main { playing = LocalPlayer.isPlaying() }
                playing
            }
        } finally {
            main { LocalPlayer.stop(); activity?.finish() }
        }
    }

    @Test fun migrationKeepsCollidingFilesAndSkipsNativeMetadata() {
        val name = "00000000-0000-0000-0000-000000000002_Migration fixture.wav"
        val old = File(context.filesDir, "offline/$name")
        val saved = File(context.filesDir, "nexi_legacy_audio/$name")
        old.parentFile!!.mkdirs(); saved.parentFile!!.mkdirs()
        old.writeText("older import")
        saved.writeText("already preserved")
        val marker = File(context.filesDir, "offline/migration-test-native.json")
        marker.writeText("native metadata")
        try {
            assertTrue(NexiLegacyFiles.migrate(context))
            assertEquals("already preserved", saved.readText())
            assertFalse(old.exists())
            assertTrue(saved.parentFile!!.listFiles()!!.any {
                it.name.endsWith("_Migration fixture.wav") && it.readText() == "older import"
            })
            assertEquals("native metadata", marker.readText())
        } finally {
            old.delete(); marker.delete()
            saved.parentFile!!.listFiles()?.filter { it.name.endsWith("_Migration fixture.wav") }?.forEach { it.delete() }
        }
    }
}
