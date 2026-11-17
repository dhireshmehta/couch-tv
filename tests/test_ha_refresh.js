// Home Assistant auto refresh and the refresh tile (Home settings, Smart Home).
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, '../tv-home/web/app.js'), 'utf8');

const toasts = [];
let saves = 0, shelfRenders = 0, settingsRenders = 0;
const intervals = [];
let nextId = 1;
let fetchImpl = null;

const context = {
  ST: {}, SET: { typing: null, lift: null }, zone: 'grid',
  t: (key, vars) => (vars ? key + ' ' + JSON.stringify(vars) : key), esc: s => String(s),
  toast: msg => toasts.push(msg),
  saveSettings: () => { saves += 1; },
  renderHaDevicesShelf: () => { shelfRenders += 1; },
  renderSettings: () => { settingsRenders += 1; },
  getHaDevices: () => (Array.isArray(context.ST.ha_devices) ? context.ST.ha_devices : []),
  setInterval: (fn, ms) => { const id = nextId++; intervals.push({ id, fn, ms, cleared: false }); return id; },
  clearInterval: id => { const iv = intervals.find(x => x.id === id); if (iv) iv.cleared = true; },
  fetch: (...args) => fetchImpl(...args),
  console
};
vm.createContext(context);

// the helper block (options, timer, refresh, device filter) and canon()
const start = source.indexOf('  // the entities the home shows');
const end = source.indexOf('  function importHaFromUrl(');
assert(start >= 0 && end > start, 'helper block found in app.js');
vm.runInContext(source.slice(start, end), context);
const canonStart = source.indexOf('  function canon(');
vm.runInContext(source.slice(canonStart, source.indexOf('\n  }', canonStart) + 4), context);

const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const live = () => intervals.filter(iv => !iv.cleared);

// 1. the device filter keeps the toggleable domains only
const kept = context.haDevicesFrom([
  { entity_id: 'light.desk', state: 'on', attributes: { friendly_name: 'Desk' } },
  { entity_id: 'sun.sun', state: 'above_horizon', attributes: {} },
  { entity_id: 'sensor.temp', state: '21', attributes: {} },
  { entity_id: 'button.reset', state: 'x', attributes: {} },
  null,
  { state: 'orphan' }
]);
assert.equal(JSON.stringify(kept.map(d => d.entity_id)), JSON.stringify(['light.desk', 'sensor.temp']), 'only supported domains are kept');
assert.equal(kept[0].name, 'Desk', 'friendly name is used');
assert.equal(kept[1].name, 'sensor.temp', 'entity id is the name when there is none');

// 2. the offered intervals, and anything else reads as off
assert.equal(JSON.stringify(Array.from(context.HA_REFRESH_OPTIONS, o => o[0])), JSON.stringify([0, 10, 15, 30, 60, 180, 300, 600, 900]), 'the nine choices, Off first');
context.ST.ha_refresh = 600;
assert.equal(context.haRefreshSecs(), 600, '600 is kept');
assert.equal(context.haRefreshLabel(600), 'ha_refresh_10m', 'label for 10 minutes');
context.ST.ha_refresh = 7;
assert.equal(context.haRefreshSecs(), 0, 'an unknown interval is off');
delete context.ST.ha_refresh;
assert.equal(context.haRefreshSecs(), 0, 'unset is off');

// 3. the timer follows the setting and the URL
context.ST.ha_url = 'http://ha.local:8123';
context.ST.ha_refresh = 30;
context.applyHaAutoRefresh();
assert.equal(live().length, 1, 'one timer runs at 30 seconds');
assert.equal(live()[0].ms, 30000, 'and it fires every 30000 ms');
context.applyHaAutoRefresh();
assert.equal(live().length, 1, 'calling again does not stack a second timer');
context.ST.ha_refresh = 60;
context.applyHaAutoRefresh();
assert.equal(live().length, 1, 'changing the interval replaces the timer');
assert.equal(live()[0].ms, 60000, 'the new timer uses 60000 ms');
context.ST.ha_refresh = 0;
context.applyHaAutoRefresh();
assert.equal(live().length, 0, 'Off stops the timer');
context.ST.ha_refresh = 900;
context.ST.ha_url = '';
context.applyHaAutoRefresh();
assert.equal(live().length, 0, 'no timer without a Home Assistant address');
context.ST.ha_url = 'http://ha.local:8123';
context.applyHaAutoRefresh();
assert.equal(live()[0].ms, 900000, '15 minutes when a URL is set');

// 4. a refresh with new states: the list is replaced, settings saved only when the list changes
const statesA = [
  { entity_id: 'light.desk', state: 'on', attributes: { friendly_name: 'Desk' } },
  { entity_id: 'switch.fan', state: 'off', attributes: {} }
];
context.ST.ha_token = 'tok';
context.ST.ha_devices = [{ entity_id: 'light.desk', name: 'Desk', domain: 'light', state: 'off' }];
let seenHeaders = null, seenUrl = null;
fetchImpl = (url, opts) => { seenUrl = url; seenHeaders = opts.headers; return Promise.resolve({ ok: true, json: () => Promise.resolve(statesA) }); };
saves = 0; shelfRenders = 0; toasts.length = 0;
context.refreshHaDevices(true);
flush().then(() => flush()).then(() => {
  assert.equal(seenUrl, 'http://ha.local:8123/api/states', 'reads /api/states');
  assert.equal(seenHeaders.Authorization, 'Bearer tok', 'sends the token');
  assert.equal(context.ST.ha_devices.length, 2, 'the new entity is added');
  assert.equal(context.ST.ha_devices[0].state, 'on', 'the state is updated');
  assert.equal(saves, 1, 'the list changed, so settings are saved once');
  assert.equal(shelfRenders, 1, 'the shelf is redrawn');
  assert.equal(toasts.length, 0, 'a silent refresh says nothing');

  // the same list again, only states differ: no save
  saves = 0;
  fetchImpl = () => Promise.resolve({ ok: true, json: () => Promise.resolve([
    { entity_id: 'light.desk', state: 'off', attributes: { friendly_name: 'Desk' } },
    { entity_id: 'switch.fan', state: 'on', attributes: {} }
  ]) });
  context.refreshHaDevices(true);
  return flush().then(() => flush()).then(() => {
    assert.equal(saves, 0, 'a state-only change does not save settings');
    assert.equal(context.ST.ha_devices[1].state, 'on', 'but the state is still updated in memory');
    assert.equal(shelfRenders, 2, 'and the shelf is redrawn with it');
  });
}).then(() => {
  // a failure: silent (timer) says nothing, a manual refresh says why
  fetchImpl = () => Promise.resolve({ ok: false, status: 503 });
  toasts.length = 0;
  context.refreshHaDevices(true);
  return flush().then(() => flush()).then(() => {
    assert.equal(toasts.length, 0, 'a failed silent refresh is quiet');
    context.refreshHaDevices(false);
    return flush().then(() => flush()).then(() => {
      assert.equal(toasts.length, 1, 'a failed manual refresh says so');
      assert.ok(toasts[0].includes('HTTP 503'), 'with the HTTP status');
    });
  });
}).then(() => {
  // a refresh while one is running is skipped
  let calls = 0;
  fetchImpl = () => { calls += 1; return new Promise(() => {}); };
  context.refreshHaDevices(true);
  context.refreshHaDevices(true);
  assert.equal(calls, 1, 'overlapping refreshes do not stack');
  // the refresh tile: shown by default, hidden when turned off
  assert.ok(context.haRefreshButtonOn(), 'refresh tile is shown by default');
  assert.ok(context.haRefreshCardHtml().includes('data-refresh="1"'), 'and it marks itself as the refresh tile');
  context.ST.ha_refresh_button = false;
  assert.equal(context.haRefreshButtonOn(), false, 'the setting hides it');
  // the Smart Home row flag: on by default, off hides it (the devices themselves are kept)
  assert.equal(context.haShowOn(), true, 'the Smart Home row is shown by default');
  context.ST.ha_show = false;
  assert.equal(context.haShowOn(), false, 'the flag hides the row');
  context.ST.ha_show = true;
  assert.equal(context.haShowOn(), true, 'and shows it again');
  console.log('Home Assistant refresh tests passed.');
}).catch(err => { console.error(err); process.exit(1); });
