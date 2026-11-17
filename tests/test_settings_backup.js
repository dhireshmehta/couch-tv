// Home settings backup: the JSON file written to the TV and read back (About, Save / Load settings).
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, '../tv-home/web/app.js'), 'utf8');

const context = { ST: {}, HOME: {}, TV: null, SET: { msg: '' }, t: key => key, console };
vm.createContext(context);
const start = source.indexOf('  // ---- Home settings backup');
const end = source.indexOf('  // About, Restart home:');
assert(start >= 0 && end > start, 'backup block found in app.js');
vm.runInContext(source.slice(start, end), context);

// 1. the export leaves out this TV's photos and token, and keeps everything else
context.ST = {
  lang: 'en', look: 'night', ha_url: 'http://ha.local:8123', ha_token: 'secret-token',
  photos: [{ file: 'a.jpg' }], ha_refresh: 30, hidden_apps: ['com.x.y']
};
const text = context.settingsBackupText();
const parsed = JSON.parse(text);
assert.equal(parsed.format, 'couch-tv-home-settings', 'format tag');
assert.equal(parsed.version, 1, 'version');
assert.ok(!text.includes('secret-token'), 'the token is not in the file');
assert.ok(!('photos' in parsed.settings), 'photos are not in the file');
assert.equal(parsed.settings.ha_url, 'http://ha.local:8123', 'the HA address is kept');
assert.equal(parsed.settings.ha_refresh, 30, 'the auto refresh interval is kept');
assert.equal(JSON.stringify(parsed.settings.hidden_apps), JSON.stringify(['com.x.y']), 'hidden apps are kept');

// 2. a valid file is read; anything else is refused
const ok = context.settingsFromBackup(text);
assert.ok(!ok.error, 'a valid backup is accepted');
assert.equal(ok.settings.lang, 'en', 'its settings come back');
assert.equal(context.settingsFromBackup('not json').error, 'backup_bad', 'not JSON is refused');
assert.equal(context.settingsFromBackup(JSON.stringify({ format: 'other', version: 1, settings: {} })).error, 'backup_bad', 'another format is refused');
assert.equal(context.settingsFromBackup(JSON.stringify({ format: 'couch-tv-home-settings', version: 2, settings: {} })).error, 'backup_bad', 'a newer version is refused');
assert.equal(context.settingsFromBackup(JSON.stringify({ format: 'couch-tv-home-settings', version: 1, settings: [] })).error, 'backup_bad', 'settings must be an object');

// 3. a load keeps this TV's own photos and token, even if the file names others
context.ST = { photos: [{ file: 'mine.jpg' }], ha_token: 'this-tv-token', lang: 'fr' };
const merged = context.mergeBackupSettings({ lang: 'de', photos: [{ file: 'theirs.jpg' }], ha_token: 'other', look: 'day' });
assert.equal(merged.lang, 'de', 'the loaded language wins');
assert.equal(merged.look, 'day', 'the loaded look wins');
assert.equal(JSON.stringify(merged.photos), JSON.stringify([{ file: 'mine.jpg' }]), 'this TV keeps its photos');
assert.equal(merged.ha_token, 'this-tv-token', 'this TV keeps its token');

// 4. without the launcher, there is no backup bridge and no rows
assert.equal(context.backupBridge(), null, 'no launcher, no backup');
assert.equal(context.backupRows().length, 0, 'no rows without the launcher');

console.log('Settings backup tests passed.');
