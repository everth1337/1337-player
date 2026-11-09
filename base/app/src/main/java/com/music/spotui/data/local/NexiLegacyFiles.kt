// Nexi Player, GPL-3.0. Local-file compatibility for the Myspoti-based native client.
package com.music.spotui.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Reads earlier Nexi files in place, preserving the audio already kept on this phone. */
internal object NexiLegacyFiles {
    private val legacyName = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}_.+")
    private fun oldFiles(context: Context) = File(context.filesDir, "offline").listFiles()
        ?.filter { it.isFile && legacyName.matches(it.name) }.orEmpty()

    /** Isolated from the native engine's account-specific download index and cleanup. */
    @Synchronized fun migrate(context: Context): Boolean {
        val old = oldFiles(context)
        if (old.isEmpty()) return true
        val destination = File(context.filesDir, "nexi_legacy_audio")
        if (!destination.isDirectory && !destination.mkdirs()) return false
        for (file in old) {
            var target = File(destination, file.name)
            if (target.exists()) {
                // Never overwrite either copy after an interrupted upgrade.
                target = File(destination, java.util.UUID.randomUUID().toString() + "_" + file.name.substringAfter('_'))
            }
            if (!file.renameTo(target)) return false
        }
        return true
    }

    private fun files(context: Context): List<File> =
        File(context.filesDir, "nexi_legacy_audio").listFiles()
            ?.filter { it.isFile && legacyName.matches(it.name) }.orEmpty() + oldFiles(context)

    fun hasTracks(context: Context) = files(context).isNotEmpty()

    fun tracks(context: Context): List<LocalTrack> = files(context).map { file ->
        val fallback = file.name.substringAfter('_', file.name)
        val metadata = MediaMetadataRetriever()
        var title = fallback
        var artist = ""
        var duration = 0L
        try {
            metadata.setDataSource(file.absolutePath)
            title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallback
            artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // A damaged file remains visible and can be handled by the player's normal error UI.
        } finally {
            runCatching { metadata.release() }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray(Charsets.UTF_8))
        val id = -100_000_000_000_000L - (ByteBuffer.wrap(hash).long and 0x0000ffffffffffffL)
        LocalTrack(
            id = id,
            uri = Uri.fromFile(file),
            title = title,
            artist = artist,
            album = "Nexi",
            durationMs = duration,
            artUri = Uri.EMPTY,
            folder = "Nexi/",
        )
    }
}
