/* Injector unit tests: extract the REAL injectInputShim (desktop settings.js)
 * and chInjectInputShim (phone phone_adapter.js) from the repo, run them
 * against the original blockworld HTML, and assert injection behaviour.
 * Run: node tools/test_input_shim_inject.cjs (from the repo root). */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'app/src/main/assets');
const SETTINGS = path.join(ASSETS, 'canghai/desktop/js/settings.js');
const ADAPTER = path.join(ASSETS, 'canghai/shared/phone_adapter.js');
const SHIM_SRC = path.join(ASSETS, 'canghai/shared/input-shim-src.js');
const SHIM = path.join(ASSETS, 'canghai/shared/input-shim.js');
const ORIG = '/home/hatch/workspace/review/blockworld/blockworld.orig.html';

function extract(file, fname) {
  const s = fs.readFileSync(file, 'utf8');
  const i = s.indexOf('function ' + fname + '(');
  if (i < 0) throw new Error(fname + ' not found in ' + file);
  const j = s.indexOf('{', i);
  let depth = 0;
  for (let k = j; k < s.length; k++) {
    if (s[k] === '{') depth++;
    else if (s[k] === '}') { depth--; if (depth === 0) return s.slice(i, k + 1); }
  }
  throw new Error('unbalanced braces for ' + fname);
}

global.window = {};
global.document = { getElementById: () => ({ checked: true }) };
const orig = fs.readFileSync(ORIG, 'utf8');
let fails = 0;
const check = (n, ok, info = '') => { console.log((ok ? 'PASS' : 'FAIL') + ' | ' + n + (info ? ' | ' + info : '')); if (!ok) fails++; };

 (0, eval)(fs.readFileSync(SHIM_SRC, 'utf8'));           // sets window.__CH_INPUT_SHIM_SRC
 (0, eval)(extract(SETTINGS, 'injectInputShim'));
let out = injectInputShim(orig);
check('desktop: shim injected once', (out.match(/__CH_INPUT_SHIM__/g) || []).length === 1);
check('desktop: before </body>', out.indexOf('__CH_INPUT_SHIM__') < out.toLowerCase().indexOf('</body>'));
check('desktop: still one </body>', (out.toLowerCase().match(/<\/body>/g) || []).length === 1);
check('desktop: idempotent', injectInputShim(out) === out);
global.document = { getElementById: () => ({ checked: false }) };
check('desktop: unchecked skips', injectInputShim(orig) === orig);
global.document = { getElementById: () => ({ checked: true }) };

 (0, eval)(extract(ADAPTER, 'chInjectInputShim'));
out = chInjectInputShim(orig);
check('phone: shim injected once', (out.match(/__CH_INPUT_SHIM__/g) || []).length === 1);
check('phone: before </body>', out.indexOf('__CH_INPUT_SHIM__') < out.toLowerCase().indexOf('</body>'));
check('phone: idempotent', chInjectInputShim(out) === out);

global.window = {}; global.document = { getElementById: () => null };
check('desktop: no shim src installs clean', injectInputShim(orig) === orig);
global.window = { __CH_INPUT_SHIM_SRC: fs.readFileSync(SHIM, 'utf8') };
const noBody = '<html><head></head><p>hi</p></html>';
check('desktop: no </body> appends', injectInputShim(noBody).indexOf('__CH_INPUT_SHIM__') > 0);

console.log(fails ? 'INJECTOR TESTS FAILED: ' + fails : 'INJECTOR TESTS ALL PASS');
process.exit(fails ? 1 : 0);
