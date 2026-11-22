package com.music.spotui.data.local

import android.content.Context
import android.os.SystemClock
import com.music.spotui.data.offline.OfflineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** MusicBrainz artist tags grouped into the genres shown in the library. */
internal object ArtistGenres {
    private const val URL = "https://musicbrainz.org/ws/2/artist"
    private const val PREFS = "artist_genres"
    private const val MAX_RESULTS = 25
    private val mutex = Mutex()
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
    private val cache = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val tags = cache.asStateFlow()
    private var loaded = false
    private var nextRequestAt = 0L

    fun primary(credit: String) = credit.substringBefore(", ").trim()
    fun key(name: String) = name.trim().lowercase(Locale.ROOT)

    private val families = mapOf(
        "J-pop" to "j-pop|japanese pop|city pop|vocaloid|japanese idol",
        "J-rock" to "j-rock|japanese rock|visual kei",
        "K-pop" to "k-pop|korean pop|korean idol",
        "Pop" to "pop|dance-pop|synth-pop|indie pop|electropop|art pop|pop rock",
        "Hip-hop" to "hip hop|hip-hop|rap|trap|emo rap|cloud rap|underground hip hop|drill|boom bap",
        "Rock" to "rock|alternative rock|indie rock|hard rock|post-rock|psychedelic rock|grunge|punk|punk rock|emo|shoegaze",
        "Metal" to "metal|heavy metal|nu metal|metalcore|death metal|black metal|thrash metal|doom metal",
        "Electronic" to "electronic|electronica|edm|ambient|experimental electronic|hyperpop|glitchcore|breakcore|phonk",
        "House / Techno" to "house|techno|deep house|tech house|trance|progressive house|hardstyle",
        "Drum & Bass" to "drum and bass|drum & bass|jungle|liquid funk|neurofunk",
        "R&B / Soul" to "r&b|rhythm and blues|contemporary r&b|soul|neo soul|funk",
        "Jazz / Blues" to "jazz|blues|jazz fusion|bebop|smooth jazz",
        "Folk / Country" to "folk|country|americana|bluegrass|folk rock|indie folk",
        "Classical" to "classical|classical music|baroque|romantic music|contemporary classical",
        "Soundtrack" to "soundtrack|video game music|anime soundtrack|film score",
        "Reggae" to "reggae|ska|dub|dancehall",
        "Latin" to "latin|latin pop|reggaeton|reguetón|salsa|bachata|bossa nova",
    ).flatMap { (genre, names) -> names.split('|').map { it to genre } }.toMap()

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(context) }
    }

    private fun loadLocked(context: Context) {
        if (loaded) return
        cache.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (name, value) ->
            (value as? Set<*>)?.filterIsInstance<String>()?.let { name to it.sorted() }
        }.toMap()
        loaded = true
    }

    /** Always user initiated. Only distinct artist names go to MusicBrainz. */
    suspend fun fetch(context: Context, credits: List<String>, progress: suspend (Int, Int) -> Unit) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked(context)
                val artists = credits.map(::primary).filter(String::isNotBlank).distinctBy(::key)
                    .filter { key(it) !in cache.value }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                withContext(Dispatchers.Main.immediate) { progress(0, artists.size) }
                var done = 0
                for (batch in artists.chunked(5)) {
                    if (!OfflineStore.isOnline(context)) throw IOException("No connection")
                    val found = fetchBatch(batch)
                    prefs.edit().apply { found.forEach { (name, genres) -> putStringSet(name, genres.toSet()) } }.apply()
                    cache.value += found
                    done += batch.size
                    withContext(Dispatchers.Main.immediate) { progress(done, artists.size) }
                }
            }
        }

    private suspend fun fetchBatch(artists: List<String>): Map<String, List<String>> {
        val query = artists.joinToString(" OR ") { name ->
            val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
            "artist:\"$escaped\""
        }
        val url = URL.toHttpUrl().newBuilder().addQueryParameter("fmt", "json")
            .addQueryParameter("limit", MAX_RESULTS.toString()).addQueryParameter("query", query).build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "1337Player/2 (https://github.com/everth1337/1337-player)").build()
        val body = search(request, retry = true)
        val results = body.getJSONArray("artists")
        if (body.getInt("count") > results.length()) {
            // A truncated search may hide an exact match or an artist with the same name.
            if (artists.size == 1) return mapOf(key(artists.single()) to emptyList())
            val mid = artists.size / 2
            return fetchBatch(artists.take(mid)) + fetchBatch(artists.drop(mid))
        }
        return classify(artists, results)
    }

    private suspend fun search(request: Request, retry: Boolean): JSONObject {
        delay((nextRequestAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        nextRequestAt = SystemClock.elapsedRealtime() + 1_200L
        val pause = http.newCall(request).execute().use { response ->
            if (response.isSuccessful) return JSONObject(response.body.string())
            if (response.code != 503 || !retry) throw IOException("MusicBrainz HTTP ${response.code}")
            val hint = response.header("Retry-After")
            val seconds = if (hint == null) 5L else hint.toLongOrNull()
            if (seconds == null || seconds > 30L) throw IOException("MusicBrainz asked to retry later")
            seconds.coerceAtLeast(5L) * 1_000L
        }
        delay(pause)
        return search(request, retry = false)
    }

    internal fun classify(artists: List<String>, results: JSONArray): Map<String, List<String>> {
        val exact = (0 until results.length()).mapNotNull(results::optJSONObject)
            .filter { it.optString("id").isNotBlank() }
            .groupBy { key(it.optString("name")) }
        return artists.associate { name ->
            val tags = exact[key(name)]?.distinctBy { it.optString("id") }?.singleOrNull()?.optJSONArray("tags")
            val votes = (0 until (tags?.length() ?: 0)).mapNotNull { index ->
                val tag = tags?.optJSONObject(index) ?: return@mapNotNull null
                val genre = families[key(tag.optString("name"))] ?: return@mapNotNull null
                val count = tag.optInt("count")
                if (count > 0) genre to count else null
            }
            key(name) to votes.groupingBy { it.first }.fold(0) { total, vote -> total + vote.second }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { it.key }
        }
    }
}
