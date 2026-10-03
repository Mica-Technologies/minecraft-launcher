# Mica Launcher i18n tooling

`translate-locales.js` reads
`src/main/resources/lang/DisplayStrings.properties` (the English
source-of-truth bundle) and produces
`src/main/resources/lang/DisplayStrings_<tag>.properties` for every locale
in the `TARGET_LOCALES` list inside the script.

It uses
[`google-translate-api-x`](https://www.npmjs.com/package/google-translate-api-x)
which hits the free public Google Translate endpoint — fine for the
~89 keys × 16 locales workload, rate-limited and unreliable for much
more.

## Setup

```bash
cd tools/i18n
npm install
```

## Usage

```bash
# Incremental: only translate keys missing from each target file.
npm run translate

# Re-translate every key (overwrites existing translations).
npm run translate:force

# Print what would change without writing any files.
npm run translate:dry
```

> **Don't run `translate:force` on the shipped bundles.** The user-facing strings (everything
> outside `log.*`) were reviewed by hand, with context and a per-language glossary, after the
> machine translation. `--force` replaces every one of them with a context-free machine
> translation again. The incremental `npm run translate` only fills in keys that are missing, so
> it never touches reviewed strings. New strings it adds are machine-translated and worth the same
> review before a release.

Write apostrophes normally in every bundle (`it's`, `l'outil`). `LocalizationManager.format`
escapes them for `MessageFormat`; a doubled `''` would show up doubled wherever a string isn't
formatted.

## How the script handles MessageFormat placeholders

`{0}`, `{1, number}`, etc. would get mangled if translated literally. The
script swaps them for `__MMCL_PH0__` sentinels around each API call and
restores them afterwards (case-insensitively, since some target
languages lowercase ALL-CAPS markers).

## Keep the target list in sync

`TARGET_LOCALES` in `translate-locales.js` mirrors `SupportedLocales.ENTRIES`
in
`src/main/java/com/micatechnologies/minecraft/launcher/consts/localization/SupportedLocales.java`.
When you add or drop a locale, edit both.

## Note on quality

Auto-translation is good enough for a usability floor — every UI string
shows in the target language and the launcher doesn't fall back to
English mid-screen. It is **not** a substitute for human review. The
generated `.properties` files are meant to be the starting point for
community translation PRs, not the final word.
