// Checks the iPhone app's translations (docs/spec.md section 13). No dependencies.
//
// It reads every Swift file of the app, the widget extension and Shared/, pulls out the strings that go through
// localization (SwiftUI's LocalizedStringKey initializers and modifiers, String(localized:), LocalizedStringResource,
// App Intents titles, dialogs and summaries, and the `title:` / `detail:` / `message:` / `prompt:` arguments of the
// app's own views), turns each into the key iOS looks up (`\(value)` becomes %@, `\(\.$param)` in a parameter
// summary becomes ${param}), and compares them with ios/Shared/Localization/<lang>.lproj/Localizable.strings.
//
// Usage (from the repository root):
//   node tools/check_ios_strings.js             report missing, mismatched and unused keys
//   node tools/check_ios_strings.js --check     the same, and exit 1 when a language misses a key or a placeholder
//   node tools/check_ios_strings.js --write-en  rewrite en.lproj/Localizable.strings from the sources (key = text)
//   node tools/check_ios_strings.js --keys      print the keys and where they're used, as JSON
//
// Conventions that keep the extraction exact: interpolate only Strings into localized text (format numbers and
// dates first), use Text(verbatim:) for text that must not be translated, and pass literals to custom views as
// Text, LocalizedStringKey or String(localized:).
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const IOS = path.join(ROOT, 'ios');
const LOCALIZATION = path.join(IOS, 'Shared', 'Localization');
const SOURCE_DIRS = ['PocketCash', 'PocketCashWidget', 'Shared'];
const SKIP_FILES = new Set(['Models+xtool.swift']);
const LANGS = ['en', 'fil', 'es', 'id', 'pt', 'hi', 'vi'];

// A literal is localized when the code right before it ends like one of these.
const CONTEXTS = [
  /\b(?:Text|Button|Label|Toggle|Section|TextField|SecureField|Picker|LabeledContent|ContentUnavailableView|NavigationLink|DatePicker|Stepper|Menu|Link|ProgressView)\($/,
  /\.(?:navigationTitle|alert|confirmationDialog|accessibilityLabel|accessibilityHint|accessibilityValue|configurationDisplayName|description|displayName|help)\($/,
  /\b(?:LocalizedStringKey|LocalizedStringResource|IntentDescription|IntentDialog|Summary)\($/,
  /\bString\(localized:$/,
  /\bDisplayRepresentation\(title:$/,
  /:\s*(?:LocalizedStringKey|LocalizedStringResource|TypeDisplayRepresentation|IntentDescription)\s*=$/,
  /(?:^|[(,\s])(?:title|detail|message|prompt|dialog|shortTitle):$/,
];

// ---- Swift sources ----

function swiftFiles() {
  const out = [];
  const walk = dir => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith('.swift') && !SKIP_FILES.has(entry.name)) out.push(full);
    }
  };
  for (const dir of SOURCE_DIRS) walk(path.join(IOS, dir));
  return out.sort();
}

const SIMPLE_ESCAPES = { n: '\n', t: '\t', r: '\r', '0': '\0', '"': '"', "'": "'", '\\': '\\' };

/**
 * Scans Swift source and returns every string literal: { start, key, hasInterpolation }. Comments are skipped,
 * interpolations are scanned as code (so literals nested inside them are found too), and raw / multi-line
 * literals are skipped as never localized here.
 */
function literals(src) {
  const found = [];
  let i = 0;

  function skipComment() {
    if (src.startsWith('//', i)) {
      const end = src.indexOf('\n', i);
      i = end < 0 ? src.length : end;
      return true;
    }
    if (src.startsWith('/*', i)) {
      let depth = 1;
      i += 2;
      while (i < src.length && depth > 0) {
        if (src.startsWith('/*', i)) { depth++; i += 2; }
        else if (src.startsWith('*/', i)) { depth--; i += 2; }
        else i++;
      }
      return true;
    }
    return false;
  }

  // Code until an unmatched `)` (when inInterpolation) or the end.
  function code(inInterpolation) {
    let depth = 0;
    while (i < src.length) {
      if (skipComment()) continue;
      const c = src[i];
      if (c === '#' && src[i + 1] === '"') { skipRaw(); continue; }
      if (c === '"') { stringLiteral(); continue; }
      if (c === '(') depth++;
      if (c === ')') {
        if (inInterpolation && depth === 0) return;
        depth--;
      }
      i++;
    }
  }

  function skipRaw() {
    let hashes = 0;
    while (src[i] === '#') { hashes++; i++; }
    const close = '"' + '#'.repeat(hashes);
    const multi = src.startsWith('"""', i);
    i += multi ? 3 : 1;
    const end = src.indexOf(multi ? '"""' + '#'.repeat(hashes) : close, i);
    i = end < 0 ? src.length : end + (multi ? 3 : 1) + hashes;
  }

  function stringLiteral() {
    const start = i;
    if (src.startsWith('"""', i)) {
      const end = src.indexOf('"""', i + 3);
      i = end < 0 ? src.length : end + 3;
      return;
    }
    i++;
    let text = '';
    let hasInterpolation = false;
    const segments = [];
    while (i < src.length && src[i] !== '"' && src[i] !== '\n') {
      if (src[i] === '\\') {
        const n = src[i + 1];
        if (n === '(') {
          i += 2;
          const exprStart = i;
          code(true);
          const expr = src.slice(exprStart, i).trim();
          i++; // the closing parenthesis
          hasInterpolation = true;
          if (text) segments.push(text);
          text = '';
          const param = /^\\\.\$(\w+)$/.exec(expr);
          segments.push({ placeholder: param ? '${' + param[1] + '}' : '%@' });
          continue;
        }
        if (n === 'u' && src[i + 2] === '{') {
          const close = src.indexOf('}', i);
          text += String.fromCodePoint(parseInt(src.slice(i + 3, close), 16));
          i = close + 1;
          continue;
        }
        text += SIMPLE_ESCAPES[n] ?? n;
        i += 2;
        continue;
      }
      text += src[i++];
    }
    if (text) segments.push(text);
    i++; // closing quote
    // With interpolations, a literal % becomes %% in the key (the key is a format string then).
    const key = segments.map(s => typeof s === 'string' ? (hasInterpolation ? s.replace(/%/g, '%%') : s) : s.placeholder).join('');
    found.push({ start, key, hasInterpolation });
  }

  code(false);
  return found;
}

function lineOf(src, index) {
  let line = 1;
  for (let k = 0; k < index; k++) if (src[k] === '\n') line++;
  return line;
}

/** Map of key -> [file:line, ...] for every localized literal in the sources. */
function extractKeys() {
  const keys = new Map();
  for (const file of swiftFiles()) {
    const src = fs.readFileSync(file, 'utf8');
    for (const lit of literals(src)) {
      const before = src.slice(Math.max(0, lit.start - 120), lit.start).replace(/\s+$/, '');
      if (!CONTEXTS.some(re => re.test(before))) continue;
      if (!/\p{L}/u.test(lit.key.replace(/%@|\$\{\w+\}/g, ''))) continue; // nothing to translate
      const where = `${path.relative(IOS, file).split(path.sep).join('/')}:${lineOf(src, lit.start)}`;
      if (!keys.has(lit.key)) keys.set(lit.key, []);
      keys.get(lit.key).push(where);
    }
  }
  return keys;
}

// ---- .strings files ----

/** Parses a .strings file into { entries: Map, duplicates: [], error }. */
function parseStrings(text) {
  const entries = new Map();
  const duplicates = [];
  let i = 0;
  const ws = () => {
    for (;;) {
      while (i < text.length && /\s/.test(text[i])) i++;
      if (text.startsWith('/*', i)) { const e = text.indexOf('*/', i + 2); if (e < 0) throw new Error('unclosed comment'); i = e + 2; continue; }
      if (text.startsWith('//', i)) { const e = text.indexOf('\n', i); i = e < 0 ? text.length : e + 1; continue; }
      return;
    }
  };
  const str = () => {
    if (text[i] !== '"') throw new Error(`expected a quoted string at offset ${i}`);
    i++;
    let out = '';
    while (i < text.length && text[i] !== '"') {
      if (text[i] === '\\') {
        const n = text[i + 1];
        if (n === 'U' || n === 'u') { out += String.fromCharCode(parseInt(text.slice(i + 2, i + 6), 16)); i += 6; continue; }
        out += SIMPLE_ESCAPES[n] ?? n;
        i += 2;
        continue;
      }
      out += text[i++];
    }
    if (text[i] !== '"') throw new Error('unterminated string');
    i++;
    return out;
  };
  try {
    if (text.charCodeAt(0) === 0xfeff) i = 1;
    ws();
    while (i < text.length) {
      const key = str();
      ws();
      if (text[i] !== '=') throw new Error(`expected = after "${key}"`);
      i++;
      ws();
      const value = str();
      ws();
      if (text[i] !== ';') throw new Error(`expected ; after "${key}"`);
      i++;
      if (entries.has(key)) duplicates.push(key);
      entries.set(key, value);
      ws();
    }
  } catch (e) {
    return { entries, duplicates, error: e.message };
  }
  return { entries, duplicates, error: null };
}

const quote = s => '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\t/g, '\\t') + '"';

/** Placeholders by position: ['@', '@'] for "%@ of %@"; positional %2$@ is honoured. ${name} is kept by name. */
function placeholders(s) {
  const byPosition = [];
  const named = [];
  let next = 1;
  const re = /%(?:(\d+)\$)?(@|lld|ld|lu|llu|d|i|u|lf|f|g|e|%)|\$\{(\w+)\}/g;
  let m;
  while ((m = re.exec(s))) {
    if (m[3]) { named.push(m[3]); continue; }
    if (m[2] === '%') continue;
    const pos = m[1] ? Number(m[1]) : next++;
    byPosition[pos - 1] = m[2].replace(/^l+/, '').replace(/^ld$/, 'd');
  }
  return { byPosition: Array.from(byPosition, t => t ?? '?').join(','), named: named.sort().join(',') };
}

function samePlaceholders(key, value) {
  const a = placeholders(key), b = placeholders(value);
  return a.byPosition === b.byPosition && a.named === b.named;
}

function stringsPath(lang, table = 'Localizable') {
  return path.join(LOCALIZATION, `${lang}.lproj`, `${table}.strings`);
}

function writeEnglish(keys) {
  const lines = ['/* English (development language). Generated by `node tools/check_ios_strings.js --write-en` from the',
    '   Swift sources: every key is its own English text. Translations live next to this folder. */', ''];
  for (const key of [...keys.keys()].sort((a, b) => a.localeCompare(b, 'en'))) {
    lines.push(`/* ${keys.get(key)[0]} */`, `${quote(key)} = ${quote(key)};`, '');
  }
  fs.mkdirSync(path.dirname(stringsPath('en')), { recursive: true });
  fs.writeFileSync(stringsPath('en'), lines.join('\n'));
  console.log(`wrote ${path.relative(ROOT, stringsPath('en')).split(path.sep).join('/')} (${keys.size} keys)`);
}

function main() {
  const args = process.argv.slice(2);
  const keys = extractKeys();
  if (args.includes('--keys')) {
    console.log(JSON.stringify(Object.fromEntries(keys), null, 2));
    return;
  }
  if (args.includes('--write-en')) writeEnglish(keys);

  let problems = 0;
  const report = (msg) => { problems++; console.log('  ' + msg); };
  console.log(`${keys.size} localized keys in the Swift sources`);

  const infoKeys = (() => {
    const p = stringsPath('en', 'InfoPlist');
    return fs.existsSync(p) ? [...parseStrings(fs.readFileSync(p, 'utf8')).entries.keys()] : [];
  })();

  for (const lang of LANGS) {
    for (const [table, wanted] of [['Localizable', [...keys.keys()]], ['InfoPlist', infoKeys]]) {
      if (!wanted.length) continue;
      const file = stringsPath(lang, table);
      const rel = path.relative(ROOT, file).split(path.sep).join('/');
      if (!fs.existsSync(file)) { console.log(`${rel}: missing`); problems++; continue; }
      const { entries, duplicates, error } = parseStrings(fs.readFileSync(file, 'utf8'));
      const before = problems;
      console.log(`${rel}: ${entries.size} entries`);
      if (error) report(`syntax error: ${error}`);
      for (const d of duplicates) report(`duplicate key ${quote(d)}`);
      for (const key of wanted) {
        if (!entries.has(key)) report(`missing ${quote(key)}${table === 'Localizable' ? `  (${keys.get(key)[0]})` : ''}`);
        else if (!samePlaceholders(key, entries.get(key))) report(`placeholder mismatch for ${quote(key)}: ${quote(entries.get(key))}`);
        else if (!entries.get(key).trim()) report(`empty translation for ${quote(key)}`);
      }
      if (table === 'Localizable') {
        const unused = [...entries.keys()].filter(k => !keys.has(k));
        if (unused.length) console.log(`  note: ${unused.length} unused key(s): ${unused.slice(0, 5).map(quote).join(', ')}${unused.length > 5 ? ', …' : ''}`);
      }
      if (problems === before) console.log('  ok');
    }
  }

  if (problems) {
    console.log(`\n${problems} problem(s). Add the missing keys (English text as the key) to every language.`);
    if (args.includes('--check')) process.exit(1);
  } else {
    console.log('\nAll languages have every key with matching placeholders.');
  }
}

main();
