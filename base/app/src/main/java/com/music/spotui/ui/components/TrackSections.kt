package com.music.spotui.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.myspoti.engine.R
import com.music.spotui.data.local.ArtistGenres
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.Collator

internal enum class SectionKind(val label: Int) {
    GENRE(R.string.track_sections_genres),
    ARTIST(R.string.track_sections_artists),
    ALBUM(R.string.track_sections_albums),
}

internal data class TrackSection(val kind: SectionKind, val name: String)
internal data class SectionTrack(val artist: String, val album: String, val music: Boolean = true)

internal fun TrackSection.matches(artist: String, album: String, tags: Map<String, List<String>>): Boolean =
    name in sectionNames(kind, artist, album, tags)

private fun sectionNames(kind: SectionKind, artist: String, album: String, tags: Map<String, List<String>>) = when (kind) {
    SectionKind.GENRE -> tags[ArtistGenres.key(ArtistGenres.primary(artist))].orEmpty().ifEmpty { listOf("") }
    SectionKind.ARTIST -> artist.split(", ").map { it.trim() }.distinct()
    SectionKind.ALBUM -> listOf(album.trim())
}

@Composable
internal fun TrackSections(
    tracks: List<SectionTrack>,
    tags: Map<String, List<String>>,
    selected: TrackSection?,
    onSelect: (TrackSection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { ArtistGenres.load(context.applicationContext) }
    var open by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(SectionKind.GENRE) }
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<Job?>(null) }
    @Composable fun sectionLabel(type: SectionKind, name: String): String = name.ifBlank {
        stringResource(when (type) {
            SectionKind.GENRE -> R.string.track_sections_no_genre
            SectionKind.ARTIST -> R.string.track_sections_no_artist
            SectionKind.ALBUM -> R.string.track_sections_no_album
        })
    }
    TextButton(onClick = {
        kind = selected?.kind ?: SectionKind.GENRE
        query = ""
        open = true
    }, modifier = modifier) {
        Text(selected?.let { sectionLabel(it.kind, it.name) } ?: stringResource(R.string.track_sections))
    }
    if (!open) return

    val counts = remember(tracks, tags, kind) {
        val collator = Collator.getInstance()
        tracks.asSequence().flatMap { sectionNames(kind, it.artist, it.album, tags).asSequence() }
            .groupingBy { it }.eachCount().toList()
            .sortedWith(compareBy<Pair<String, Int>> { it.first.isBlank() }.thenBy(collator) { it.first })
    }
    val filtered = remember(counts, query) { counts.filter { query.isBlank() || matchesFind(query, it.first) } }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.track_sections)) },
        text = {
            Column {
                Row {
                    SectionKind.entries.forEach { option ->
                        TextButton(
                            onClick = { kind = option; query = "" },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (option == kind) "✓ " + stringResource(option.label) else stringResource(option.label)) }
                    }
                }
                if (kind == SectionKind.GENRE) {
                    Text(stringResource(R.string.track_sections_genres_help))
                    val current = progress
                    if (lookup?.isActive == true) {
                        TextButton(onClick = { lookup?.cancel() }) {
                            Text(stringResource(R.string.track_sections_stop, current?.first ?: 0, current?.second ?: 0))
                        }
                    } else {
                        val missing = tracks.any {
                            val artist = ArtistGenres.primary(it.artist)
                            it.music && artist.isNotBlank() && ArtistGenres.key(artist) !in tags
                        }
                        if (missing) TextButton(onClick = {
                            failed = false
                            progress = null
                            val artists = tracks.filter(SectionTrack::music).map(SectionTrack::artist)
                            lookup = scope.launch {
                                try {
                                    ArtistGenres.fetch(context.applicationContext, artists) { done, total -> progress = done to total }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    failed = true
                                } finally {
                                    lookup = null
                                }
                            }
                        }) { Text(stringResource(R.string.track_sections_load_genres)) }
                        if (failed) Text(stringResource(R.string.track_sections_genres_error))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.track_sections_find)) },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 215.dp)) {
                    items(filtered, key = { it.first }) { (name, count) ->
                        Text(
                            text = stringResource(R.string.track_sections_count, sectionLabel(kind, name), count),
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(TrackSection(kind, name))
                                open = false
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(null); open = false }) {
                Text(stringResource(R.string.track_sections_all))
            }
        },
    )
}

@Composable
internal fun SpotifyDjButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = modifier) {
        Text(stringResource(R.string.spotify_dj_title))
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.spotify_dj_title)) },
        text = { Text(stringResource(R.string.spotify_dj_description)) },
        confirmButton = {
            TextButton(onClick = {
                open = false
                val link = Uri.parse("https://open.spotify.com/playlist/37i9dQZF1EYkqdzj48dyYq")
                val intent = Intent(Intent.ACTION_VIEW, link)
                try {
                    context.startActivity(intent.setPackage("com.spotify.music"))
                } catch (_: android.content.ActivityNotFoundException) {
                    try {
                        context.startActivity(intent.setPackage(null))
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(context, R.string.spotify_dj_no_handler, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text(stringResource(R.string.spotify_dj_open)) }
        },
    )
}
