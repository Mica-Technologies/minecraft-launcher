#!/usr/bin/env node
/*
 * Mica Minecraft Launcher — auto-translation tool
 *
 * Reads src/main/resources/lang/DisplayStrings.properties as the English
 * source-of-truth and writes DisplayStrings_<java-locale>.properties for each
 * (BCP-47 tag with '-' replaced by '_', per ResourceBundle naming)
 * locale in TARGET_LOCALES below.
 *
 * Usage:
 *   npm install        # one-time, fetches google-translate-api-x
 *   npm run translate            # incremental: only translate keys missing from each target
 *   npm run translate:force      # re-translate every key, overwriting existing values
 *   npm run translate:dry        # print what would change without writing any files
 *
 * The script is idempotent: existing translations are preserved on re-runs
 * unless --force is passed. It uses google-translate-api-x which hits the
 * free public Google Translate endpoint — rate-limited and unreliable for
 * very large batches, but fine for this 89-key × 16-locale workload.
 *
 * MessageFormat placeholders ({0}, {1}, ...) are protected from being
 * translated by swapping them for ASCII sentinels around each API call.
 *
 * Keep TARGET_LOCALES in sync with SupportedLocales.ENTRIES in
 * src/main/java/com/micatechnologies/minecraft/launcher/consts/localization/SupportedLocales.java
 */

import { translate } from 'google-translate-api-x';
import { readFile, writeFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..');
const LANG_DIR = join(REPO_ROOT, 'src', 'main', 'resources', 'lang');
const SOURCE_FILE = join(LANG_DIR, 'DisplayStrings.properties');

const TARGET_LOCALES = [
    { tag: 'es',    googleCode: 'es',     name: 'Spanish' },
    { tag: 'fr',    googleCode: 'fr',     name: 'French' },
    { tag: 'de',    googleCode: 'de',     name: 'German' },
    { tag: 'pt-BR', googleCode: 'pt',     name: 'Portuguese (Brazil)' },
    { tag: 'it',    googleCode: 'it',     name: 'Italian' },
    { tag: 'ru',    googleCode: 'ru',     name: 'Russian' },
    { tag: 'ja',    googleCode: 'ja',     name: 'Japanese' },
    { tag: 'ko',    googleCode: 'ko',     name: 'Korean' },
    { tag: 'zh-CN', googleCode: 'zh-CN',  name: 'Simplified Chinese' },
    { tag: 'zh-TW', googleCode: 'zh-TW',  name: 'Traditional Chinese' },
    { tag: 'ar',    googleCode: 'ar',     name: 'Arabic' },
    { tag: 'hi',    googleCode: 'hi',     name: 'Hindi' },
    { tag: 'nl',    googleCode: 'nl',     name: 'Dutch' },
    { tag: 'pl',    googleCode: 'pl',     name: 'Polish' },
    { tag: 'tr',    googleCode: 'tr',     name: 'Turkish' },
    { tag: 'sv',    googleCode: 'sv',     name: 'Swedish' },
];

const FORCE = process.argv.includes('--force');
const DRY_RUN = process.argv.includes('--dry-run');
// Re-encode existing locale files using the current escape rules, without
// hitting the translation API. Useful after changing the writer (e.g.
// switching from UTF-8 bytes to \uXXXX escapes) — preserves all
// translated values, only changes the on-disk encoding form.
const REENCODE_ONLY = process.argv.includes('--reencode-only');
const DELAY_MS = 250;   // be polite to Google's free endpoint — 120ms triggered occasional
                        // "Partial Translation Request Fail" rate-limit responses; 250ms keeps
                        // the rate well below where Google starts pushing back

const CONTROL_ESCAPES = { '\n': '\\n', '\t': '\\t', '\r': '\\r', '\f': '\\f' };

/**
 * Encodes non-ASCII characters as Java {@code \\uXXXX} escapes so the resulting
 * .properties file is portable across Java versions (pre-Java 9 defaults to
 * ISO-8859-1 for PropertyResourceBundle) and looks correct in editors that
 * default to Latin-1 / Windows-1252 on Windows. Plain ASCII chars pass
 * through unchanged. Backslashes are doubled so they survive the .properties
 * format's own escape parsing.
 */
function escapeNonAscii(str) {
    let out = '';
    let first = true;
    for (const ch of str) {
        const code = ch.codePointAt(0);
        if (code < 0x80) {
            // Plain ASCII — escape literal backslashes and the control characters
            // java.util.Properties decodes (\n, \t, ...) so the value round-trips.
            // Writing a decoded newline raw would end the entry mid-value; writing
            // it as a backslash plus 'n' after doubling the backslash is how 14
            // multi-line strings shipped showing a literal "\n" in every locale.
            if (ch === '\\') {
                out += '\\\\';
            }
            else if (ch in CONTROL_ESCAPES) {
                out += CONTROL_ESCAPES[ch];
            }
            else if (ch === ' ' && first) {
                // Java strips leading whitespace from a value unless it is escaped.
                out += '\\ ';
            }
            else {
                out += ch;
            }
        }
        else if (code <= 0xFFFF) {
            out += '\\u' + code.toString(16).padStart(4, '0').toUpperCase();
        }
        else {
            // Supratrans-BMP — encode as a UTF-16 surrogate pair (two \uXXXX).
            const adj = code - 0x10000;
            const hi = 0xD800 + (adj >> 10);
            const lo = 0xDC00 + (adj & 0x3FF);
            out += '\\u' + hi.toString(16).padStart(4, '0').toUpperCase();
            out += '\\u' + lo.toString(16).padStart(4, '0').toUpperCase();
        }
        first = false;
    }
    return out;
}

// True when a raw value ends in an odd number of backslashes, meaning the entry
// continues on the next physical line.
function endsWithContinuation(rawValue) {
    let backslashes = 0;
    for (let i = rawValue.length - 1; i >= 0 && rawValue[i] === '\\'; i--) backslashes++;
    return backslashes % 2 === 1;
}

function parseProperties(text) {
    // Returns { keysInOrder: [...], values: { key: value }, leadingComments: '...' }.
    // Mirrors the way java.util.Properties handles the file but preserves comment
    // ordering by capturing the leading comment block separately. Decodes
    // \uXXXX escapes on read so the in-memory value matches what Java
    // would see — the writer re-escapes on output, so round-trips are
    // lossless for existing-translation passthrough.
    const lines = text.split(/\r?\n/);
    const values = {};
    const keysInOrder = [];
    let leadingComments = '';
    let seenFirstKey = false;
    // Physical lines joined so far for an entry still awaiting its continuation.
    let pending = null;
    for (const rawLine of lines) {
        const line = rawLine;
        if (pending !== null) {
            // Java strips leading whitespace from a continuation line.
            const continued = line.replace(/^\s+/, '');
            if (endsWithContinuation(continued)) {
                pending.value += continued.slice(0, -1);
                continue;
            }
            pending.value += continued;
            if (!(pending.key in values)) keysInOrder.push(pending.key);
            values[pending.key] = decodeUnicodeEscapes(pending.value);
            pending = null;
            continue;
        }
        if (!seenFirstKey && (line.trim() === '' || line.trim().startsWith('#') || line.trim().startsWith('!'))) {
            leadingComments += line + '\n';
            continue;
        }
        if (line.trim() === '' || line.trim().startsWith('#') || line.trim().startsWith('!')) {
            continue;
        }
        const eq = line.indexOf('=');
        if (eq < 0) continue;
        const key = line.substring(0, eq).trim();
        const rawValue = line.substring(eq + 1);
        seenFirstKey = true;
        // A value ending in an ODD number of backslashes continues onto the next line;
        // an even number is one or more escaped literal backslashes and ends the entry.
        // Reading each physical line as a complete entry -- which this did -- kept the
        // trailing backslash and dropped the rest of the value, which is how 134 truncated
        // strings ending in a stray "\" reached the shipped bundles.
        if (endsWithContinuation(rawValue)) {
            pending = { key, value: rawValue.slice(0, -1) };
            continue;
        }
        if (!(key in values)) keysInOrder.push(key);
        values[key] = decodeUnicodeEscapes(rawValue);
    }
    if (pending !== null) {
        if (!(pending.key in values)) keysInOrder.push(pending.key);
        values[pending.key] = decodeUnicodeEscapes(pending.value);
    }
    return { keysInOrder, values, leadingComments };
}

/** Reverse of escapeNonAscii — decodes a raw value exactly as
 *  java.util.Properties does, so the script's in-memory state matches what
 *  Java sees when loading the bundle: \uXXXX becomes its character, \n \t
 *  \r \f become control characters, and any other backslash-x becomes x. */
function decodeUnicodeEscapes(text) {
    if (!text.includes('\\')) return text;
    let out = '';
    let i = 0;
    while (i < text.length) {
        const c = text[i];
        if (c === '\\' && i + 1 < text.length) {
            const next = text[i + 1];
            if (next === 'u' && i + 5 < text.length) {
                const hex = text.substring(i + 2, i + 6);
                if (/^[0-9a-fA-F]{4}$/.test(hex)) {
                    out += String.fromCharCode(parseInt(hex, 16));
                    i += 6;
                    continue;
                }
            }
            const control = { n: '\n', t: '\t', r: '\r', f: '\f' }[next];
            out += control !== undefined ? control : next;
            i += 2;
            continue;
        }
        out += c;
        i++;
    }
    return out;
}

function writeProperties(filePath, { keysInOrder, values, leadingComments }, sourceLocaleTag) {
    let out = leadingComments;
    if (!out.endsWith('\n')) out += '\n';
    out += `# Locale: ${sourceLocaleTag}\n`;
    out += `# Auto-generated by tools/i18n/translate-locales.js — re-run after\n`;
    out += `# DisplayStrings.properties changes to refresh translations. Use\n`;
    out += `# --force to overwrite existing values.\n\n`;
    for (const key of keysInOrder) {
        const v = values[key];
        if (v === undefined) continue;
        out += `${key}=${escapeNonAscii(v)}\n`;
    }
    return writeFile(filePath, out, 'utf8');
}

// MessageFormat placeholders ({0}, {1}, ...) and standalone format chars
// shouldn't be translated. Swap them for ASCII sentinels around each API
// call so Google doesn't reformat / translate them. The sentinels are
// chosen to be plain ASCII tokens unlikely to appear in any natural-
// language sentence.
function protectPlaceholders(text) {
    const placeholders = [];
    const protectedText = text.replace(/\{(\d+(?:,[^}]+)?)\}/g, (match) => {
        placeholders.push(match);
        return `__MMCL_PH${placeholders.length - 1}__`;
    });
    return { protectedText, placeholders };
}

function restorePlaceholders(translated, placeholders) {
    let restored = translated;
    for (let i = 0; i < placeholders.length; i++) {
        // Match the sentinel tolerantly. Translation engines do not treat these
        // as opaque: they lowercase ALL-CAPS markers (Turkish), insert spaces
        // around or inside the underscores, and occasionally split the digits
        // off. Anything stricter than this silently loses the placeholder, which
        // is exactly how the shipped es / pt-BR / zh-TW slot mismatches happened.
        // The trailing `_\\s*_` is load-bearing as a boundary: it stops PH1 matching inside
        // PH10, and it must NOT be followed by `\\s*`, or the match eats the space that
        // separates the placeholder from the next word.
        const sentinel = new RegExp(`_\\s*_\\s*MMCL\\s*_?\\s*PH\\s*${i}\\s*_\\s*_`, 'gi');
        restored = restored.replace(sentinel, placeholders[i]);
    }
    return restored;
}

// The multiset of MessageFormat placeholders in a string, sorted so a
// translation that legitimately REORDERS them still compares equal — only a
// lost, duplicated, or invented placeholder should count as a mismatch.
function placeholderSignature(text) {
    const found = text.match(/\{(\d+(?:,[^}]+)?)\}/g) || [];
    return found.slice().sort().join('\u0000');
}

// A translated value is only usable if it carries exactly the placeholders the
// English source did. MessageFormat.format throws on an unmatched brace and
// renders a literal "{0}" for a slot with no argument, so a mangled value is
// not a cosmetic problem -- it is a broken string in the user's language, and
// one that no English-locale test run would ever surface.
//
// Falling back to English keeps the bundle correct and loadable, and leaves the
// key visibly untranslated so it gets picked up on a later pass.
function verifyPlaceholders(englishValue, translatedValue) {
    return placeholderSignature(englishValue) === placeholderSignature(translatedValue);
}

// Values that must ship exactly as written in English -- product names the
// translation API otherwise renders as common nouns ("Codex" -> "Kodex",
// "Cursor" -> the Japanese word for a pointer).
const VERBATIM_KEYS = new Set([
    'settings.mcp.connect.client.claudeCode',
    'settings.mcp.connect.client.cursor',
    'settings.mcp.connect.client.codex',
]);

// Multi-line values are translated one line at a time. Sent whole, the API is
// free to merge, drop or re-space the line breaks, and the dialogs built from
// these strings depend on them.
async function translateString(text, targetCode) {
    if (!text.includes('\n')) return translateLine(text, targetCode);
    const lines = [];
    for (const line of text.split('\n')) {
        lines.push(await translateLine(line, targetCode));
    }
    return lines.join('\n');
}

async function translateLine(text, targetCode) {
    if (text === '' || text.trim() === '') return text;
    const { protectedText, placeholders } = protectPlaceholders(text);
    const result = await translate(protectedText, { from: 'en', to: targetCode });
    const restored = restorePlaceholders(result.text, placeholders);
    if (!verifyPlaceholders(text, restored)) {
        // Signal rather than return: the caller logs the key and falls back to
        // English, so a mangled placeholder can never reach a shipped bundle.
        const err = new Error(
            `placeholder mismatch (expected ${placeholderSignature(text).split('\u0000').join(' ')}, `
            + `got ${placeholderSignature(restored).split('\u0000').join(' ')})`);
        err.placeholderMismatch = true;
        throw err;
    }
    return restored;
}

async function fileExists(path) {
    try { await access(path); return true; }
    catch { return false; }
}

async function main() {
    const sourceText = await readFile(SOURCE_FILE, 'utf8');
    const source = parseProperties(sourceText);
    console.log(`Source ${SOURCE_FILE}: ${source.keysInOrder.length} keys`);

    let totalCalls = 0;
    let totalSkipped = 0;
    let totalFailures = 0;
    let totalMismatched = 0;

    for (const locale of TARGET_LOCALES) {
        // Java ResourceBundle looks up bundles by Locale.toString(), which uses an
        // UNDERSCORE between language and region (pt_BR), not the BCP-47 hyphen (pt-BR).
        // Emitting the hyphenated name means getBundle never finds the file and silently
        // falls back to English -- which is exactly what shipped for pt-BR, zh-CN and
        // zh-TW until this was fixed. Keep tags hyphenated everywhere else; convert only
        // here, at the filename.
        const bundleSuffix = locale.tag.replace('-', '_');
        const targetPath = join(LANG_DIR, `DisplayStrings_${bundleSuffix}.properties`);
        let existing = { keysInOrder: [], values: {}, leadingComments: '' };
        if (await fileExists(targetPath)) {
            existing = parseProperties(await readFile(targetPath, 'utf8'));
        }
        if (REENCODE_ONLY) {
            if (existing.keysInOrder.length === 0) {
                console.log(`  → ${locale.name} (${locale.tag})  skipped — no existing file`);
                continue;
            }
            const reencoded = {
                keysInOrder: existing.keysInOrder,
                values: existing.values,
                leadingComments: existing.leadingComments,
            };
            await writeProperties(targetPath, reencoded, locale.tag);
            console.log(`  → ${locale.name} (${locale.tag})  re-encoded ${existing.keysInOrder.length} keys`);
            continue;
        }
        console.log(`\n→ ${locale.name} (${locale.tag})  [existing: ${existing.keysInOrder.length} keys]`);
        const merged = {
            keysInOrder: source.keysInOrder.slice(),
            values: {},
            leadingComments: source.leadingComments,
        };
        let translated = 0;
        let skipped = 0;
        let failed = 0;
        let mismatched = 0;
        for (const key of source.keysInOrder) {
            const englishValue = source.values[key];
            const existingValue = existing.values[key];
            if (VERBATIM_KEYS.has(key)) {
                merged.values[key] = englishValue;
                skipped++;
                totalSkipped++;
                continue;
            }
            if (!FORCE && existingValue !== undefined && existingValue !== '') {
                merged.values[key] = existingValue;
                skipped++;
                totalSkipped++;
                continue;
            }
            if (DRY_RUN) {
                console.log(`  [dry] ${key} :: would translate "${englishValue.slice(0, 60)}..."`);
                merged.values[key] = englishValue;
                continue;
            }
            try {
                const result = await translateString(englishValue, locale.googleCode);
                merged.values[key] = result;
                translated++;
                totalCalls++;
            }
            catch (err) {
                console.warn(`  ✗ ${key} :: ${err.message || err}`);
                merged.values[key] = englishValue;   // fall back to English so the bundle is still loadable
                failed++;
                totalFailures++;
                if (err.placeholderMismatch) {
                    mismatched++;
                    totalMismatched++;
                }
            }
            await new Promise((res) => setTimeout(res, DELAY_MS));
        }
        if (!DRY_RUN) {
            await writeProperties(targetPath, merged, locale.tag);
        }
        console.log(`  done: ${translated} translated, ${skipped} kept, ${failed} failed`
                    + (mismatched ? `  (${mismatched} rejected for placeholder mismatch)` : ''));
    }

    console.log(`\nTotals: ${totalCalls} API calls, ${totalSkipped} kept, ${totalFailures} failed`);
    if (totalMismatched) {
        console.log(`${totalMismatched} value(s) were rejected because the translation lost or `
                    + `altered a {0}-style placeholder, and were left in English. Re-run to retry `
                    + `them; a key that keeps failing needs its English source simplified.`);
    }
    if (DRY_RUN) console.log(`(dry-run — no files written)`);
}

// Only run when invoked directly. Importing this module must not start a
// translation run -- placeholder-guard.test.mjs imports it to exercise the
// placeholder helpers below without touching the network.
const invokedDirectly = process.argv[1]
    && import.meta.url === new URL(`file://${process.argv[1]}`).href;
if (invokedDirectly) {
    main().catch((err) => {
        console.error('FATAL:', err);
        process.exit(1);
    });
}

export { protectPlaceholders, restorePlaceholders, placeholderSignature, verifyPlaceholders,
         parseProperties, endsWithContinuation, escapeNonAscii };
