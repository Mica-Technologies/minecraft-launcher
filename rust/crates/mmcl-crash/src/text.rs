// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Localizable text carried by a [`CrashDiagnosis`](crate::CrashDiagnosis).
//!
//! The analyzer sits below the localization crate, so it never renders strings itself. Every
//! title, summary and suggestion label is a [`Text`]: a bundle key, a key plus MessageFormat
//! arguments, or a concatenation of those. A front end renders it with [`Text::resolve`] and a
//! [`TextResolver`] over its localizer, so the diagnosis follows a later language change too.

use std::borrow::Cow;

/// A localizable piece of text, rendered by a [`TextResolver`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Text {
    /// A plain lookup: the bundle value of the key, with no MessageFormat processing (so a
    /// literal `{name}` or `'` in the value stays as written, as with Java's `get`).
    Key(&'static str),
    /// A MessageFormat template filled with `args` (Java's `format`).
    Format(&'static str, Vec<TextArg>),
    /// Several pieces rendered one after another, with nothing between them.
    Concat(Vec<Text>),
}

/// One MessageFormat argument of a [`Text::Format`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TextArg {
    /// Text inserted as-is (a mod id, a version, a class name).
    Str(String),
    /// An integer, which the localizer formats with locale digit grouping, like Java.
    Int(i64),
    /// A nested localizable text, rendered first and inserted as a string.
    Text(Box<Text>),
}

/// An argument after nested texts are rendered, as handed to [`TextResolver::format`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResolvedArg<'a> {
    /// Text inserted as-is.
    Str(Cow<'a, str>),
    /// An integer to format with locale digit grouping.
    Int(i64),
}

/// Renders bundle keys; implemented by the front end over its localizer.
pub trait TextResolver {
    /// The value of `key`, without MessageFormat processing.
    fn get(&self, key: &str) -> String;
    /// The template of `key` filled with `args`.
    fn format(&self, key: &str, args: &[ResolvedArg<'_>]) -> String;
}

impl Text {
    /// A plain key lookup.
    pub fn key(key: &'static str) -> Self {
        Text::Key(key)
    }

    /// A formatted key.
    pub fn format(key: &'static str, args: Vec<TextArg>) -> Self {
        Text::Format(key, args)
    }

    /// The empty text (renders as `""`).
    pub fn empty() -> Self {
        Text::Concat(Vec::new())
    }

    /// Renders this text.
    pub fn resolve(&self, resolver: &dyn TextResolver) -> String {
        match self {
            Text::Key(key) => resolver.get(key),
            Text::Format(key, args) => {
                let resolved: Vec<ResolvedArg<'_>> = args
                    .iter()
                    .map(|arg| match arg {
                        TextArg::Str(s) => ResolvedArg::Str(Cow::Borrowed(s.as_str())),
                        TextArg::Int(n) => ResolvedArg::Int(*n),
                        TextArg::Text(t) => ResolvedArg::Str(Cow::Owned(t.resolve(resolver))),
                    })
                    .collect();
                resolver.format(key, &resolved)
            }
            Text::Concat(parts) => parts.iter().map(|p| p.resolve(resolver)).collect(),
        }
    }

    /// The outermost key, if this text is a single key (for tests and MCP labels).
    pub fn primary_key(&self) -> Option<&'static str> {
        match self {
            Text::Key(key) | Text::Format(key, _) => Some(key),
            Text::Concat(parts) => parts.first().and_then(Text::primary_key),
        }
    }

    /// Every bundle key this text uses, outermost first.
    pub fn keys(&self) -> Vec<&'static str> {
        let mut out = Vec::new();
        self.collect_keys(&mut out);
        out
    }

    fn collect_keys(&self, out: &mut Vec<&'static str>) {
        match self {
            Text::Key(key) => out.push(key),
            Text::Format(key, args) => {
                out.push(key);
                for arg in args {
                    if let TextArg::Text(t) = arg {
                        t.collect_keys(out);
                    }
                }
            }
            Text::Concat(parts) => parts.iter().for_each(|p| p.collect_keys(out)),
        }
    }
}

impl From<&str> for TextArg {
    fn from(s: &str) -> Self {
        TextArg::Str(s.to_owned())
    }
}

impl From<String> for TextArg {
    fn from(s: String) -> Self {
        TextArg::Str(s)
    }
}

impl From<i64> for TextArg {
    fn from(n: i64) -> Self {
        TextArg::Int(n)
    }
}

impl From<Text> for TextArg {
    fn from(t: Text) -> Self {
        TextArg::Text(Box::new(t))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Echo;
    impl TextResolver for Echo {
        fn get(&self, key: &str) -> String {
            format!("[{key}]")
        }
        fn format(&self, key: &str, args: &[ResolvedArg<'_>]) -> String {
            let parts: Vec<String> = args
                .iter()
                .map(|a| match a {
                    ResolvedArg::Str(s) => s.to_string(),
                    ResolvedArg::Int(n) => n.to_string(),
                })
                .collect();
            format!("[{key}:{}]", parts.join(","))
        }
    }

    #[test]
    fn nested_and_concat_render_in_order() {
        let t = Text::Concat(vec![
            Text::format(
                "a",
                vec![Text::format("b", vec!["x".into()]).into(), 3i64.into()],
            ),
            Text::key("c"),
        ]);
        assert_eq!(t.resolve(&Echo), "[a:[b:x],3][c]");
        assert_eq!(t.keys(), vec!["a", "b", "c"]);
        assert_eq!(t.primary_key(), Some("a"));
        assert_eq!(Text::empty().resolve(&Echo), "");
    }
}
