#!/usr/bin/env python3
"""
Merge hand-written translations into the locale bundles.

Takes a JSON file of raw UTF-8 translations:

    { "es": { "some.key": "Alguna cadena con {0}" }, "fr": { ... } }

and merges each into src/main/resources/lang/DisplayStrings_<tag>.properties,
applying the bundle conventions so a translator never has to think about them:

  * values are escaped to \\uXXXX, because the bundles are ASCII-only;
  * keys are written in English-source order, matching translate-locales.js;
  * the file's leading comment block is preserved verbatim.

It REFUSES to write a value whose MessageFormat placeholders differ from the
English source. That is the same guard translate-locales.js now applies, and it
matters more here, not less: a human or a model writing these by hand is at
least as likely to drop a {0} as a translation API is, and the result is a
string that renders a literal "{0}" to the user in a language nobody on the
team reads.

Usage:
    python3 apply-translations.py translations.json [--check]

    --check  verify and report without writing anything.
"""

import json
import os
import re
import sys

LANG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        '..', '..', 'src', 'main', 'resources', 'lang')
SOURCE = os.path.join(LANG_DIR, 'DisplayStrings.properties')
SLOT = re.compile(r'\{\d+(?:,[^}]+)?\}')


def slots(text):
    """The multiset of MessageFormat placeholders, sorted so reordering is allowed."""
    return tuple(sorted(SLOT.findall(text)))


def read_bundle(path):
    """Parse a .properties file into (leading comment lines, ordered keys, values).

    Values are returned with \\uXXXX escapes already decoded, so callers compare
    and write real text.
    """
    comments, keys, values = [], [], {}
    if not os.path.exists(path):
        return comments, keys, values

    in_header = True
    pending_key, buf = None, ''
    with open(path, encoding='utf-8') as handle:
        for raw in handle:
            line = raw.rstrip('\n')
            if pending_key is not None:
                buf += line[:-1] if line.endswith('\\') else line
                if not line.endswith('\\'):
                    values[pending_key] = decode(buf)
                    keys.append(pending_key)
                    pending_key, buf = None, ''
                continue
            stripped = line.strip()
            if in_header and (stripped.startswith('#') or stripped == ''):
                comments.append(line)
                continue
            in_header = False
            if not stripped or stripped.startswith('#') or stripped.startswith('!'):
                continue
            if '=' not in line:
                continue
            key, value = line.split('=', 1)
            key = key.strip()
            if value.endswith('\\'):
                pending_key, buf = key, value[:-1]
            else:
                values[key] = decode(value)
                keys.append(key)
    return comments, keys, values


def decode(value):
    r"""Decode \uXXXX escapes and the \n / \t / \\ escapes properties files use."""
    out, i = [], 0
    while i < len(value):
        ch = value[i]
        if ch != '\\' or i + 1 >= len(value):
            out.append(ch)
            i += 1
            continue
        nxt = value[i + 1]
        if nxt == 'u' and i + 5 < len(value):
            try:
                out.append(chr(int(value[i + 2:i + 6], 16)))
                i += 6
                continue
            except ValueError:
                pass
        out.append({'n': '\n', 't': '\t', 'r': '\r', '\\': '\\'}.get(nxt, nxt))
        i += 2
    return ''.join(out)


def encode(value):
    r"""Escape a value for a .properties file: newlines, backslashes, non-ASCII."""
    out = []
    for ch in value:
        if ch == '\\':
            out.append('\\\\')
        elif ch == '\n':
            out.append('\\n')
        elif ch == '\t':
            out.append('\\t')
        elif ch == '\r':
            out.append('\\r')
        elif ord(ch) < 0x20 or ord(ch) > 0x7E:
            out.append('\\u%04X' % ord(ch))
        else:
            out.append(ch)
    return ''.join(out)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    check_only = '--check' in sys.argv
    with open(sys.argv[1], encoding='utf-8') as handle:
        incoming = json.load(handle)

    _, source_keys, source_values = read_bundle(SOURCE)
    print('English source: %d keys' % len(source_keys))

    rejected, written = 0, 0
    for tag, translations in sorted(incoming.items()):
        path = os.path.join(LANG_DIR, 'DisplayStrings_%s.properties' % tag.replace('-', '_'))
        if not os.path.exists(path):
            print('  %-6s SKIP: no bundle at %s' % (tag, path))
            continue

        comments, _, values = read_bundle(path)
        applied, bad = 0, []
        for key, value in translations.items():
            if key not in source_values:
                bad.append('%s (not an English key)' % key)
                continue
            if slots(source_values[key]) != slots(value):
                bad.append('%s (placeholder mismatch: want %s, got %s)'
                           % (key, list(slots(source_values[key])), list(slots(value))))
                continue
            values[key] = value
            applied += 1

        rejected += len(bad)
        for problem in bad:
            print('  %-6s REJECT %s' % (tag, problem))

        missing = [k for k in source_keys if k not in values or not values[k].strip()]
        body = '\n'.join('%s=%s' % (k, encode(values[k]))
                         for k in source_keys if k in values and values[k].strip())
        content = '\n'.join(comments).rstrip('\n') + '\n' + body + '\n'

        if not check_only:
            with open(path, 'w', encoding='utf-8') as handle:
                handle.write(content)
            written += 1
        print('  %-6s applied %3d, still missing %3d' % (tag, applied, len(missing)))

    print('\n%s. %d rejected.' % ('Checked' if check_only else 'Wrote %d bundle(s)' % written,
                                  rejected))
    return 1 if rejected else 0


if __name__ == '__main__':
    sys.exit(main())
