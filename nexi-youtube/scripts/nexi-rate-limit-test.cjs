// GPL-3.0-only. Regression for the host request cap, without network or real waits.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
for (const locale of ['en', 'ja']) {
    let now = 1000000;
    const hooks = {};
    const sandbox = {
        Date: { now: () => now },
        myspoti: {
            locale, provide: (name, fn) => { hooks[name] = fn; }, log: () => {},
            util: { sleep: ms => { now += ms; } },
            http: { post: () => { throw new Error('too many requests in a minute'); } }
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../main.js'), 'utf8'), sandbox);
    for (let i = 0; i < 3; i++) {
        const result = hooks['download-source']({ title: 'Yellow', artist: 'Coldplay', artists: ['Coldplay'], durationMs: 269000 });
        assert.equal(result.outcome, 'rate-limited');
        assert.equal(result.retryAfterMs, 60000);
        now += result.retryAfterMs;
    }
}
console.log('Host request-cap cooldown: 6 cases passed (English and Japanese).');
