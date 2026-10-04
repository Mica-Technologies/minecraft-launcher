// Copyright (c) 2026 Mica Technologies
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License,
// or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// Derives Material 3 colour roles (-md-*) from each theme's own -color-* palette and writes them
// into the theme's token sheet, between the GENERATED markers inside its .root block.
//
// The themes keep their colours: a role that has a direct counterpart (primary, error, surface,
// on-surface, ...) IS that colour. The library only fills in what the palettes don't have: the
// container tones and their on-colours, the five surface-container levels, outlines, the inverse
// roles and state-layer colours. Each comes from the hue and chroma of the theme colour it belongs
// to, at the tones Material 3's dark and light schemes use, so nothing drifts to another hue.
//
//   npm run generate   rewrite the generated block in every token sheet
//   npm run check      exit 1 if any sheet's block is out of date (no writes)

import { readFileSync, writeFileSync } from 'node:fs';
import { argbFromHex, hexFromArgb, Hct, TonalPalette } from '@material/material-color-utilities';

const UI = new URL('../../src/main/resources/ui/', import.meta.url).pathname;

/** dark: tone layout; translucent: Native theme, surfaces are see-through overlays. */
export const THEMES = [
    { sheet: 'ui-tokens-dark.css', dark: true },
    { sheet: 'ui-tokens-light.css', dark: false },
    { sheet: 'ui-tokens-bluegray.css', dark: true },
    { sheet: 'ui-tokens-orangepurple.css', dark: true },
    { sheet: 'ui-tokens-creeper.css', dark: true },
    { sheet: 'ui-tokens-native.css', dark: true, translucent: true },
    { sheet: 'ui-tokens-native-light.css', dark: false, translucent: true },
];

const START = '/* ==== GENERATED: Material 3 colour roles (tools/theme-roles) — do not edit by hand ==== */';
const END = '/* ==== END GENERATED ==== */';

// ---------------------------------------------------------------------------- colour helpers

const NAMED = { white: '#FFFFFF', black: '#000000', transparent: 'rgba(0, 0, 0, 0)' };

/** Parses a CSS colour (hex, rgb[a], white/black) to { argb, alpha }. */
export function parseColour(text) {
    const value = (NAMED[text.trim().toLowerCase()] ?? text).trim();
    const rgba = value.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/i);
    if (rgba) {
        const [r, g, b] = rgba.slice(1, 4).map(Number);
        const alpha = rgba[4] === undefined ? 1 : Number(rgba[4]);
        return { argb: (0xff << 24 | r << 16 | g << 8 | b) >>> 0, alpha };
    }
    if (/^#[0-9a-f]{6}$/i.test(value)) {
        return { argb: argbFromHex(value), alpha: 1 };
    }
    throw new Error(`Unsupported colour: ${text}`);
}

const hex = argb => hexFromArgb(argb).toUpperCase();
const channels = argb => [(argb >> 16) & 255, (argb >> 8) & 255, argb & 255];
const rgba = (argb, alpha) => `rgba(${channels(argb).join(', ')}, ${alpha})`;

function luminance(argb) {
    const [r, g, b] = channels(argb).map(c => {
        const s = c / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio of two opaque colours. */
export function contrast(a, b) {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
}

/** Of the candidates, the one with the highest contrast against the background. */
const bestOn = (background, ...candidates) =>
    candidates.reduce((best, c) => (contrast(c, background) > contrast(best, background) ? c : best));

// ---------------------------------------------------------------------------- the derivation

/** Reads the -color-* tokens of a sheet's .root block. */
export function readTokens(css) {
    const root = css.replace(/\/\*[\s\S]*?\*\//g, '').match(/\.root\s*\{([^}]*)\}/)[1];
    return Object.fromEntries([...root.matchAll(/(-color-[a-z0-9-]+)\s*:\s*([^;]+);/g)].map(m => [m[1], m[2].trim()]));
}

/**
 * An accent role group (X, on-X, X-container, on-X-container) anchored on a theme colour. X is the
 * theme's colour itself; the containers come from its hue and chroma at Material 3's tones (30/90
 * dark, 90/10 light). on-X keeps the theme's own choice when it reads (4.5:1), else the better of
 * the palette's tone 10 and white, else black.
 */
function accentGroup(name, anchorText, dark, preferredOn) {
    const anchor = parseColour(anchorText).argb;
    const hct = Hct.fromInt(anchor);
    const palette = TonalPalette.fromHueAndChroma(hct.hue, Math.max(hct.chroma, 16));
    let on = preferredOn === undefined ? undefined : parseColour(preferredOn).argb;
    if (on === undefined || contrast(on, anchor) < 4.5) {
        on = bestOn(anchor, palette.tone(10), 0xffffffff);
        // A mid-luminance colour (a bright red) can miss 4.5:1 with both; black is the fallback.
        if (contrast(on, anchor) < 4.5) {
            on = 0xff000000;
        }
    }
    return {
        [`-md-${name}`]: hex(anchor),
        [`-md-on-${name}`]: hex(on),
        [`-md-${name}-container`]: hex(palette.tone(dark ? 30 : 90)),
        [`-md-on-${name}-container`]: hex(palette.tone(dark ? 90 : 10)),
    };
}

/** Every -md-* role for one theme, as an ordered { name: cssValue } map. */
export function deriveRoles(tokens, { dark, translucent = false }) {
    const roles = {};
    const text = parseColour(tokens['-color-text']).argb;

    // Neutral palette: the hue and tint of the theme's card surface (its background is often pure
    // white or pure transparent, which carries no hue). The Native themes use their opaque popup.
    const neutralSource = parseColour(tokens[translucent ? '-color-popup' : '-color-surface']).argb;
    const neutralHct = Hct.fromInt(neutralSource);
    const neutral = TonalPalette.fromHueAndChroma(neutralHct.hue, neutralHct.chroma);

    Object.assign(roles,
                  accentGroup('primary', tokens['-color-primary'], dark, tokens['-color-text-on-primary']),
                  accentGroup('secondary', tokens['-color-secondary'], dark),
                  accentGroup('error', tokens['-color-danger'], dark),
                  accentGroup('success', tokens['-color-success'], dark),
                  accentGroup('warning', tokens['-color-warning'], dark));

    // Tertiary: Material's tonal-spot rule, the primary's hue turned 60 degrees at low chroma.
    const primaryHct = Hct.fromInt(parseColour(tokens['-color-primary']).argb);
    const tertiary = TonalPalette.fromHueAndChroma((primaryHct.hue + 60) % 360, 24);
    Object.assign(roles, {
        '-md-tertiary': hex(tertiary.tone(dark ? 80 : 40)),
        '-md-on-tertiary': hex(tertiary.tone(dark ? 20 : 100)),
        '-md-tertiary-container': hex(tertiary.tone(dark ? 30 : 90)),
        '-md-on-tertiary-container': hex(tertiary.tone(dark ? 90 : 10)),
    });

    // Surfaces. The theme's background IS the surface; the container levels step away from it the
    // way Material 3's schemes do (dark: surface 6 -> 4/10/12/17/22, light: 98 -> 100/96/94/92/90).
    const levels = ['lowest', 'low', '', 'high', 'highest'];
    roles['-md-surface'] = tokens['-color-bg'];
    if (translucent) {
        const base = dark ? 0xffffffff : 0xff000000;
        [0.02, 0.05, 0.08, 0.11, 0.14].forEach((alpha, i) => {
            roles[`-md-surface-container${levels[i] ? '-' + levels[i] : ''}`] = rgba(base, alpha);
        });
    }
    else {
        const surfaceTone = Hct.fromInt(parseColour(tokens['-color-bg']).argb).tone;
        const offsets = dark ? [-2, 4, 6, 11, 16] : [2, -2, -4, -6, -8];
        offsets.forEach((offset, i) => {
            const tone = Math.min(100, Math.max(0, surfaceTone + offset));
            roles[`-md-surface-container${levels[i] ? '-' + levels[i] : ''}`] = hex(neutral.tone(tone));
        });
    }
    roles['-md-on-surface'] = hex(text);
    roles['-md-on-surface-variant'] = hex(parseColour(tokens['-color-text-muted']).argb);
    roles['-md-outline'] = hex(neutral.tone(dark ? 60 : 50));
    roles['-md-outline-variant'] = hex(neutral.tone(dark ? 30 : 80));

    // Inverse roles (snackbars, tooltips on the opposite scheme) and the always-black scrim.
    const primaryPalette = TonalPalette.fromHueAndChroma(primaryHct.hue, Math.max(primaryHct.chroma, 16));
    roles['-md-inverse-surface'] = hex(neutral.tone(dark ? 90 : 20));
    roles['-md-inverse-on-surface'] = hex(neutral.tone(dark ? 20 : 95));
    roles['-md-inverse-primary'] = hex(primaryPalette.tone(dark ? 40 : 80));
    roles['-md-scrim'] = '#000000';

    // State layers: Material overlays the content colour at 8% (hover) and 10% (focus, pressed).
    // CSS can't apply an alpha to a lookup, so they are spelled out per content colour.
    const primary = parseColour(tokens['-color-primary']).argb;
    const onPrimary = parseColour(roles['-md-on-primary']).argb;
    for (const [name, argb] of [['on-surface', text], ['primary', primary], ['on-primary', onPrimary]]) {
        roles[`-md-state-hover-${name}`] = rgba(argb, 0.08);
        roles[`-md-state-pressed-${name}`] = rgba(argb, 0.10);
    }
    return roles;
}

/** The generated block, indented to sit inside .root. */
function renderBlock(roles) {
    const width = Math.max(...Object.keys(roles).map(k => k.length)) + 2;
    const lines = Object.entries(roles).map(([k, v]) => `    ${(k + ':').padEnd(width)}${v};`);
    return [`    ${START}`, ...lines, `    ${END}`].join('\n');
}

/** Returns the sheet with its generated block replaced, or inserted at the end of .root. */
export function applyBlock(css, block) {
    const start = css.indexOf(`    ${START}`);
    if (start >= 0) {
        const end = css.indexOf(END, start) + END.length;
        return css.slice(0, start) + block + css.slice(end);
    }
    const rootStart = css.search(/\.root\s*\{/);
    const rootEnd = css.indexOf('\n}', rootStart);
    return css.slice(0, rootEnd) + '\n\n' + block + css.slice(rootEnd);
}

// ---------------------------------------------------------------------------- main

if (import.meta.url === `file://${process.argv[1]}`) {
    const check = process.argv.includes('--check');
    let stale = 0;
    for (const theme of THEMES) {
        const path = UI + theme.sheet;
        const css = readFileSync(path, 'utf8');
        const updated = applyBlock(css, renderBlock(deriveRoles(readTokens(css), theme)));
        if (updated === css) {
            console.log(`${theme.sheet}: up to date`);
        }
        else if (check) {
            console.log(`${theme.sheet}: OUT OF DATE`);
            stale++;
        }
        else {
            writeFileSync(path, updated);
            console.log(`${theme.sheet}: written`);
        }
    }
    process.exit(stale ? 1 : 0);
}
