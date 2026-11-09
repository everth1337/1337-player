// Nexi Player, GPL-3.0. An optional Myspoti-compatible download source.
function nameKey(value) {
    if (value && typeof value === "object") value = value.name;
    return String(value || "").toLowerCase().replace(/[\s\-_\.,!?()"'’“”:;…]+/g, "");
}

myspoti.provide("download-source", function (track) {
    if (!track.title || !track.artist || !(track.durationMs > 0)) return null;
    var response = myspoti.http.get("https://api.audius.co/v1/tracks/search?" +
        myspoti.query({ query: track.artist + " " + track.title, limit: 25 }));
    if (!response.ok) return null;

    var expectedTitle = nameKey(track.title);
    var artists = [track.artist].concat(track.artists || []).map(nameKey);
    var matches = (response.json().data || []).filter(function (item) {
        if (item.is_downloadable !== true || item.is_download_gated !== false ||
                item.is_stream_gated !== false || item.is_streamable === false) return false;
        if (!/^[A-Za-z0-9]+$/.test(item.id || "")) return false;
        var artist = nameKey(item.user && item.user.name);
        if (artists.indexOf(artist) < 0) return false;
        var title = nameKey(item.title);
        if (title !== expectedTitle && title !== artist + expectedTitle) return false;
        return Math.abs(item.duration * 1000 - track.durationMs) <= 4000;
    });
    matches.sort(function (a, b) {
        return Math.abs(a.duration * 1000 - track.durationMs) -
            Math.abs(b.duration * 1000 - track.durationMs);
    });
    if (!matches.length) return null;

    return [{
        url: "https://api.audius.co/v1/tracks/" + matches[0].id + "/stream",
        format: "mp3",
        provider: "Audius"
    }];
});
