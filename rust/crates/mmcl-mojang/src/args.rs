// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Command-line tokenising and `${placeholder}` substitution (spec 7.2, 7.5).

use std::borrow::{Borrow, Cow};
use std::collections::{BTreeMap, HashMap};
use std::hash::{BuildHasher, Hash};

use indexmap::IndexMap;

/// Java's `Character.isWhitespace`: Unicode space, line and paragraph
/// separators except the no-break spaces (U+00A0, U+2007, U+202F), plus
/// `\t \n \x0B \f \r` and `\x1C`-`\x1F`.
pub fn java_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{1C}'..='\u{1F}'
            | ' '
            | '\u{1680}'
            | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Split a command line the way `ProcessUtilities.splitCommandLine` does.
///
/// `"` toggles quoting and is dropped (also mid-token: `a"b c"d` → `ab cd`);
/// whitespace outside quotes ends a token; `""` yields an empty argument;
/// backslashes are literal (Windows paths). Not shell syntax: do not swap in
/// `shlex`.
pub fn split_command_line(s: &str) -> Vec<String> {
    let mut args = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut started = false;
    for c in s.chars() {
        if c == '"' {
            in_quotes = !in_quotes;
            started = true;
            continue;
        }
        if java_is_whitespace(c) && !in_quotes {
            if started {
                args.push(std::mem::take(&mut cur));
                started = false;
            }
        } else {
            cur.push(c);
            started = true;
        }
    }
    if started {
        args.push(cur);
    }
    args
}

/// A placeholder table keyed by the full token, e.g. `"${game_directory}"`.
pub trait PlaceholderLookup {
    /// The value for `token` (including `${` and `}`), if known.
    fn lookup(&self, token: &str) -> Option<&str>;
    /// `true` when the table has no entries.
    fn is_empty_table(&self) -> bool;
}

impl<K, V, S> PlaceholderLookup for HashMap<K, V, S>
where
    K: Borrow<str> + Hash + Eq,
    V: AsRef<str>,
    S: BuildHasher,
{
    fn lookup(&self, token: &str) -> Option<&str> {
        self.get(token).map(AsRef::as_ref)
    }
    fn is_empty_table(&self) -> bool {
        self.is_empty()
    }
}

impl<K, V, S> PlaceholderLookup for IndexMap<K, V, S>
where
    K: Borrow<str> + Hash + Eq,
    V: AsRef<str>,
    S: BuildHasher,
{
    fn lookup(&self, token: &str) -> Option<&str> {
        self.get(token).map(AsRef::as_ref)
    }
    fn is_empty_table(&self) -> bool {
        self.is_empty()
    }
}

impl<K, V> PlaceholderLookup for BTreeMap<K, V>
where
    K: Borrow<str> + Ord,
    V: AsRef<str>,
{
    fn lookup(&self, token: &str) -> Option<&str> {
        self.get(token).map(AsRef::as_ref)
    }
    fn is_empty_table(&self) -> bool {
        self.is_empty()
    }
}

/// Substitute known `${name}` tokens in one string in a single left-to-right
/// pass. A substituted value is **never rescanned** (bug B-2: Java's repeated
/// `String.replace` made the result depend on map order). Unknown tokens stay
/// verbatim. Strings without `$` are returned borrowed.
pub fn substitute_placeholders_str<'a, M: PlaceholderLookup + ?Sized>(
    s: &'a str,
    map: &M,
) -> Cow<'a, str> {
    if !s.contains("${") || map.is_empty_table() {
        return Cow::Borrowed(s);
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(start) = rest.find("${") {
        out.push_str(&rest[..start]);
        let tail = &rest[start..];
        match tail.find('}') {
            Some(end) => {
                let token = &tail[..=end];
                if let Some(value) = map.lookup(token) {
                    out.push_str(value);
                    rest = &tail[end + 1..];
                } else {
                    // Unknown: keep the `$` and keep scanning, so a known token
                    // later in the same text (`${x${a}`) is still replaced.
                    out.push('$');
                    rest = &tail[1..];
                }
            }
            None => {
                out.push_str(tail);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    Cow::Owned(out)
}

/// [`substitute_placeholders_str`] over every argv element.
pub fn substitute_placeholders<M: PlaceholderLookup + ?Sized>(argv: &mut [String], map: &M) {
    for arg in argv.iter_mut() {
        if let Cow::Owned(new) = substitute_placeholders_str(arg, map) {
            *arg = new;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argv(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    // ---- split_command_line ----

    #[test]
    fn split_basic_and_quotes() {
        assert_eq!(split_command_line("a b  c"), argv(&["a", "b", "c"]));
        assert_eq!(
            split_command_line("\"-Dos.name=Windows 10\" -x"),
            argv(&["-Dos.name=Windows 10", "-x"])
        );
        assert_eq!(split_command_line("a\"b c\"d"), argv(&["ab cd"]));
        assert_eq!(split_command_line("x \"\" y"), argv(&["x", "", "y"]));
        assert_eq!(split_command_line(""), Vec::<String>::new());
        assert_eq!(split_command_line("   "), Vec::<String>::new());
    }

    #[test]
    fn split_keeps_backslashes_literal() {
        assert_eq!(
            split_command_line("-cp C:\\libs\\a.jar;C:\\b\\"),
            argv(&["-cp", "C:\\libs\\a.jar;C:\\b\\"])
        );
    }

    #[test]
    fn split_on_tabs_newlines_and_java_whitespace() {
        assert_eq!(
            split_command_line("a\tb\nc\r\nd\u{0B}e\u{0C}f\u{1F}g"),
            argv(&["a", "b", "c", "d", "e", "f", "g"])
        );
        assert_eq!(
            split_command_line("a\u{3000}b\u{2028}c"),
            argv(&["a", "b", "c"])
        );
    }

    #[test]
    fn split_does_not_break_on_no_break_spaces() {
        assert_eq!(
            split_command_line("a\u{00A0}b c\u{2007}d\u{202F}e"),
            argv(&["a\u{00A0}b", "c\u{2007}d\u{202F}e"])
        );
    }

    #[test]
    fn split_unterminated_quote_runs_to_the_end() {
        assert_eq!(split_command_line("a \"b c"), argv(&["a", "b c"]));
    }

    #[test]
    fn java_whitespace_table() {
        for c in [
            '\t', '\n', '\u{0B}', '\u{0C}', '\r', '\u{1C}', '\u{1D}', '\u{1E}', '\u{1F}', ' ',
            '\u{1680}', '\u{2000}', '\u{200A}', '\u{2028}', '\u{2029}', '\u{205F}', '\u{3000}',
        ] {
            assert!(java_is_whitespace(c), "{:?}", c);
        }
        for c in [
            '\u{00A0}', '\u{2007}', '\u{202F}', 'a', '\u{85}', '\u{200B}', '\0',
        ] {
            assert!(!java_is_whitespace(c), "{:?}", c);
        }
    }

    // ---- placeholders (GameModPackLauncherPlaceholderTest) ----

    #[test]
    fn replaces_a_single_placeholder() {
        let mut a = argv(&["--gameDir", "${game_directory}"]);
        let mut p = IndexMap::new();
        p.insert("${game_directory}", "/packs/Foo");
        substitute_placeholders(&mut a, &p);
        assert_eq!(a, argv(&["--gameDir", "/packs/Foo"]));
    }

    #[test]
    fn replaces_several_placeholders_within_one_argument() {
        let mut a =
            argv(&["-Dpath=${library_directory}${classpath_separator}${natives_directory}"]);
        let mut p = HashMap::new();
        p.insert("${library_directory}", "/libs");
        p.insert("${classpath_separator}", ":");
        p.insert("${natives_directory}", "/natives");
        substitute_placeholders(&mut a, &p);
        assert_eq!(a[0], "-Dpath=/libs:/natives");
    }

    #[test]
    fn replaces_every_occurrence_of_the_same_placeholder() {
        let mut a = argv(&["${a}-${a}"]);
        let p: HashMap<&str, &str> = [("${a}", "X")].into();
        substitute_placeholders(&mut a, &p);
        assert_eq!(a[0], "X-X");
    }

    #[test]
    fn leaves_arguments_without_a_dollar_sign_untouched() {
        let mut a = argv(&["-Xmx8G", "--username", "Steve"]);
        let p: HashMap<&str, &str> = [("${game_directory}", "/packs/Foo")].into();
        substitute_placeholders(&mut a, &p);
        assert_eq!(a, argv(&["-Xmx8G", "--username", "Steve"]));
    }

    #[test]
    fn leaves_unknown_placeholders_verbatim() {
        let mut a = argv(&[
            "${not_a_known_placeholder}",
            "pre${x}post${y",
            "$",
            "${",
            "a$b",
        ]);
        let p: HashMap<&str, &str> = [("${game_directory}", "/packs/Foo")].into();
        substitute_placeholders(&mut a, &p);
        assert_eq!(
            a,
            argv(&[
                "${not_a_known_placeholder}",
                "pre${x}post${y",
                "$",
                "${",
                "a$b"
            ])
        );
    }

    #[test]
    fn tolerates_empty_inputs() {
        let mut empty: Vec<String> = Vec::new();
        let p: HashMap<&str, &str> = [("${a}", "b")].into();
        substitute_placeholders(&mut empty, &p);
        let mut a = argv(&["${a}"]);
        substitute_placeholders(&mut a, &HashMap::<String, String>::new());
        assert_eq!(a[0], "${a}");
    }

    #[test]
    fn substituted_values_are_never_rescanned_in_either_order() {
        // Inverts Java's substitutedValuesAreThemselvesRescannedWhichIsOrderDependent (B-2).
        let mut leaking = IndexMap::new();
        leaking.insert("${game_directory}", "/packs/${auth_access_token}");
        leaking.insert("${auth_access_token}", "SECRET-TOKEN");
        let mut a = argv(&["--gameDir", "${game_directory}"]);
        substitute_placeholders(&mut a, &leaking);
        assert_eq!(a[1], "/packs/${auth_access_token}");

        let mut safe = IndexMap::new();
        safe.insert("${auth_access_token}", "SECRET-TOKEN");
        safe.insert("${game_directory}", "/packs/${auth_access_token}");
        let mut b = argv(&["--gameDir", "${game_directory}"]);
        substitute_placeholders(&mut b, &safe);
        assert_eq!(b[1], "/packs/${auth_access_token}");
    }

    #[test]
    fn unknown_prefix_does_not_hide_a_later_known_token() {
        let p: HashMap<&str, &str> = [("${a}", "X")].into();
        assert_eq!(substitute_placeholders_str("${zz${a}", &p), "${zzX");
        assert_eq!(substitute_placeholders_str("$${a}", &p), "$X");
    }

    #[test]
    fn empty_values_produce_empty_strings() {
        let p: HashMap<&str, &str> = [("${clientid}", "")].into();
        let mut a = argv(&["--clientId", "${clientid}"]);
        substitute_placeholders(&mut a, &p);
        assert_eq!(a, argv(&["--clientId", ""]));
    }

    // The pack-name sanitiser test from GameModPackLauncherPlaceholderTest
    // belongs with the sanitiser itself (mmcl-modpack's pack root naming); a
    // copy here would only test an inline closure.
}
