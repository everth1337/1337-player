package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R

@Composable
internal fun DownloadSortButton(order: TrackOrder, onChange: (TrackOrder) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            val label = stringResource(if (order.sort == TrackSort.ADDED) R.string.track_sort_downloaded else order.sort.label)
            Text(stringResource(R.string.player_sort_label, label) + if (order.descending) " ↓" else " ↑")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackSort.entries.filter { it != TrackSort.CUSTOM }.forEach { option ->
                val label = stringResource(if (option == TrackSort.ADDED) R.string.track_sort_downloaded else option.label)
                DropdownMenuItem(
                    text = { Text(if (option == order.sort) "✓  $label" else label) },
                    onClick = { onChange(TrackOrder(option)); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_sort_reverse)) },
                onClick = { onChange(order.copy(reversed = !order.reversed)); open = false },
            )
        }
    }
}
