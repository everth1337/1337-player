package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.myspoti.engine.R
import app.myspoti.extensions.Origin
import com.music.spotui.data.preferences.LyricsRomanizationPref
import com.music.spotui.extensions.Extensions
import com.music.spotui.ui.theme.AppAccent
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NexiPick(val id: String, val name: String, val description: Int,
    val asset: String, val repo: String, val tag: String?, val versionCode: Int)

private val picks = listOf(
    NexiPick("net.yexe.myspoti.youtubemusic", "YouTube Music Download Helper", R.string.nexi_youtube_description,
        "youtube.zip", "y-exe/myspoti-youtube-music", null, 13),
    NexiPick("io.github.illumye.romanizer", "Lyrics Romanizer", R.string.nexi_romanizer_description,
        "romanizer.zip", "Illumye/myspoti-romanizer", "v0.4.0", 400),
)

/** Bundled packages; installation still uses the normal permission and conflict prompt. */
@Composable
internal fun NexiExtensions(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry by Extensions.registry.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        .fillMaxWidth().background(AppAccent.copy(alpha = 0.08f), RoundedCornerShape(24.dp))) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.nexi_extensions_title), color = AppText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(stringResource(R.string.nexi_extensions_subtitle), color = AppMuted, fontSize = 12.sp)
            }
            Text(if (expanded) "−" else "+", color = AppAccent, fontSize = 24.sp)
        }
        if (expanded) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            picks.forEach { pick ->
                val current = registry.extensions.find { it.id == pick.id }
                val installed = current != null
                val update = current != null && current.versionCode < pick.versionCode
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pick.name, color = AppText, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Text(stringResource(pick.description), color = AppMuted, fontSize = 13.sp)
                    TextButton(enabled = busy == null, onClick = {
                        if (installed && !update) navController.navigate(installedRoute(pick.id))
                        else scope.launch {
                            busy = pick.id
                            try {
                                val zip = withContext(Dispatchers.IO) { ctx.assets.open("nexi-extensions/${pick.asset}").use { it.readBytes() } }
                                val origin = pick.tag?.let { Origin(repo = pick.repo, ref = it, tag = true, asset = true) }
                                offer(ctx, zip, if (origin == null) "Nexi · ${pick.repo}" else "https://github.com/${pick.repo}", origin)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { toastError(ctx, e) }
                            finally { busy = null }
                        }
                    }) {
                        Text(stringResource(if (busy == pick.id) R.string.nexi_extension_loading else if (update) R.string.nexi_extension_update else if (installed) R.string.nexi_extension_open else R.string.nexi_extension_install), color = AppAccent)
                    }
                }
            }
            if (registry.extensions.any { it.id == "io.github.illumye.romanizer" && it.enabled }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nexi_romanization), color = AppText, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = LyricsRomanizationPref.on, onCheckedChange = { LyricsRomanizationPref.set(ctx, it) })
                }
            }
            Text(stringResource(R.string.nexi_download_hint), color = AppMuted, fontSize = 12.sp)
        }
    }
}
