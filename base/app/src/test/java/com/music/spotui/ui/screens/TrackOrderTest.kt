package com.music.spotui.ui.screens

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackOrderTest {
    private val beta = track("beta", "Outro", "Beta", "Blue", 30)
    private val alphaZ = track("alpha-z", "Zebra", "Alpha", "Red", 20)
    private val alphaA = track("alpha-a", "Arrival", "alpha", "Red", 10)

    @Test fun artistOrderGroupsDownloadsDespiteCompletionTimes() {
        val tracks = listOf(beta, alphaZ, alphaA)
        assertEquals(listOf(alphaA, alphaZ, beta), TrackOrder(TrackSort.ARTIST).downloads(tracks))
        assertEquals(listOf(beta, alphaA, alphaZ), TrackOrder(TrackSort.ARTIST, reversed = true).downloads(tracks))
        assertEquals(listOf(beta, alphaZ, alphaA), TrackOrder(TrackSort.ADDED).downloads(tracks.asReversed()))
    }

    @Test fun favoritesUseDateAndKeepOriginalOrderForOlderCacheEntriesWithoutDates() {
        val unknownA = song(1, "Zebra", "")
        val unknownB = song(2, "Arrival", "")
        val old = song(3, "Old", "2025-01-01T00:00:00Z")
        val recent = song(4, "New", "2026-01-01T00:00:00Z")
        val tracks = listOf(unknownA, unknownB, old, recent)
        assertEquals(listOf(recent, old, unknownA, unknownB), TrackOrder(TrackSort.ADDED).apply(tracks))
        assertEquals(listOf(unknownA, unknownB, old, recent), TrackOrder(TrackSort.ADDED, reversed = true).apply(tracks))
    }

    private fun song(id: Int, title: String, addedAt: String) =
        SongsModel(id, title, "Album", "Artist", "", "spotify:track:$id", addedAt = addedAt)

    private fun track(id: String, title: String, artist: String, album: String, addedAt: Long) = OfflineTrack(
        trackId = id, title = title, artist = artist, album = album, albumId = "", durationMs = 120_000,
        coverUrl = "", file = "$id.m4a", cover = "", source = "Test", verified = false, bytes = 1_000, addedAt = addedAt,
    )
}
