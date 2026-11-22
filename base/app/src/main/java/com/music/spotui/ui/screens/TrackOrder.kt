package com.music.spotui.ui.screens

import android.content.Context
import app.myspoti.engine.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.offline.OfflineTrack
import java.text.Collator

enum class TrackSort(val label: Int) {
    CUSTOM(R.string.your_library_sort_order_custom),
    TITLE(R.string.track_sort_title),
    ARTIST(R.string.track_sort_artist),
    ALBUM(R.string.track_sort_album),
    DURATION(R.string.track_sort_duration),
    ADDED(R.string.track_sort_added),
}

/** Date sorts start with the newest track, text sorts start in alphabetical order. */
data class TrackOrder(val sort: TrackSort = TrackSort.CUSTOM, val reversed: Boolean = false) {
    val isCustom get() = sort == TrackSort.CUSTOM && !reversed
    val descending get() = (sort == TrackSort.ADDED || sort == TrackSort.CUSTOM) != reversed

    fun apply(songs: List<SongsModel>): List<SongsModel> = ordered(
        songs, SongsModel::title, SongsModel::singer, SongsModel::album,
        compareBy(SongsModel::durationMs), compareByDescending(SongsModel::addedAt),
    )

    fun downloads(tracks: List<OfflineTrack>): List<OfflineTrack> = ordered(
        tracks, OfflineTrack::title, OfflineTrack::artist, OfflineTrack::album,
        compareBy(OfflineTrack::durationMs), compareByDescending(OfflineTrack::addedAt),
    )

    private fun <T> ordered(
        tracks: List<T>,
        title: (T) -> String,
        artist: (T) -> String,
        album: (T) -> String,
        duration: Comparator<T>,
        added: Comparator<T>,
    ): List<T> {
        if (sort == TrackSort.CUSTOM) return if (reversed) tracks.asReversed() else tracks
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        fun text(field: (T) -> String) = compareBy(collator, field)
        val byTitle = text(title)
        val byArtist = text(artist)
        val byAlbum = text(album)
        val primary = when (sort) {
            TrackSort.TITLE -> byTitle
            TrackSort.ARTIST -> byArtist
            TrackSort.ALBUM -> byAlbum
            TrackSort.DURATION -> duration
            TrackSort.ADDED -> added
            TrackSort.CUSTOM -> error("Custom order has no comparator")
        }
        val directed = if (reversed) primary.reversed() else primary
        val order = when (sort) {
            TrackSort.ARTIST -> directed.then(byAlbum).then(byTitle)
            TrackSort.ALBUM -> directed.then(byArtist).then(byTitle)
            TrackSort.ADDED -> directed
            else -> directed.then(byTitle).then(byArtist).then(byAlbum)
        }
        return tracks.sortedWith(order)
    }

    companion object {
        const val DOWNLOADS = "local:downloads"
        private const val LIKED = "spotify:collection:tracks"
        private const val PREFS = "track_sort"

        private fun default(uri: String) = when (uri) {
            LIKED -> TrackOrder(TrackSort.ADDED)
            DOWNLOADS -> TrackOrder(TrackSort.ARTIST)
            else -> TrackOrder()
        }

        fun load(context: Context, uri: String): TrackOrder {
            val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uri, null).orEmpty()
            val sort = TrackSort.entries.firstOrNull { it.name == value.substringBefore(':') } ?: return default(uri)
            val reversed = value.endsWith(":reversed")
            return if (sort == TrackSort.CUSTOM && uri == LIKED) TrackOrder(TrackSort.ADDED, reversed)
            else TrackOrder(sort, reversed)
        }

        fun save(context: Context, uri: String, order: TrackOrder) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (order == default(uri)) remove(uri)
                else putString(uri, order.sort.name + if (order.reversed) ":reversed" else "")
            }.apply()
        }
    }
}
