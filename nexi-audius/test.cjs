// Nexi Player, GPL-3.0. Run with node test.cjs; no network needed.
const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
let hook;
let data = [];
let ok = true;
let lastUrl;
vm.runInNewContext(fs.readFileSync(__dirname + '/main.js', 'utf8'), {
  myspoti: {
    provide(name, fn) { assert.equal(name, 'download-source'); hook = fn; },
    query(values) { return new URLSearchParams(values).toString(); },
    http: { get(url) { lastUrl = url; return { ok, json: () => ({ data }) }; } },
  },
});
const track = { title: 'Signal', artist: 'Nexi', durationMs: 180000 };
const item = { id: 'Ab123', title: 'Signal', user: { name: 'Nexi' }, duration: 180,
  is_downloadable: true, is_download_gated: false, is_stream_gated: false, is_streamable: true };
data = [item];
assert.equal(hook(track)[0].url, 'https://api.audius.co/v1/tracks/Ab123/stream');
assert.ok(lastUrl.startsWith('https://api.audius.co/v1/tracks/search?'));
for (const patch of [
  { is_downloadable: false }, { is_downloadable: undefined },
  { is_download_gated: true }, { is_download_gated: undefined },
  { is_stream_gated: true }, { is_streamable: false },
  { title: 'Signal remix' }, { user: { name: 'Different artist' } },
  { duration: 60 }, { duration: undefined }, { id: '../private' },
]) {
  data = [{ ...item, ...patch }];
  assert.equal(hook(track), null, JSON.stringify(patch));
}
data = [{ ...item, duration: 183 }, { ...item, id: 'Best', duration: 180 }];
assert.equal(hook(track)[0].url, 'https://api.audius.co/v1/tracks/Best/stream');
data = [{ ...item, title: ' NEXI - SIGNAL! ' }];
assert.equal(hook(track).length, 1);
data = [item];
assert.equal(hook({ ...track, artist: 'A & B', artists: ['Nexi'] }).length, 1);
assert.equal(hook({ ...track, durationMs: 0 }), null);
assert.equal(hook({ ...track, title: '' }), null);
ok = false;
assert.equal(hook(track), null);
console.log('Audius source: 19 matching, access, input and failure checks passed.');
