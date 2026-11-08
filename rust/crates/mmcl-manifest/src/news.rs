// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! News items (`packNews`) and links (`packLinks`). All text is author-supplied and must be
//! rendered as plain text only.

use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

use crate::lenient;
use crate::url_validation::sanitized_http_url;

const MAX_NEWS_TITLE: usize = 200;
const MAX_NEWS_BODY: usize = 2000;
const MAX_LINK_TITLE: usize = 120;
const MAX_LINK_DESCRIPTION: usize = 500;

/// Trimmed, `None` when blank, cut to at most `max` characters (on a char boundary).
fn clamp(value: Option<&str>, max: usize) -> Option<String> {
    let t = crate::non_blank(value)?;
    Some(match t.char_indices().nth(max) {
        Some((cut, _)) => t[..cut].to_string(),
        None => t.to_string(),
    })
}

/// `yyyy-MM-dd`, else an ISO offset date-time converted to UTC and truncated to its date.
pub fn parse_news_date(raw: Option<&str>) -> Option<NaiveDate> {
    let s = crate::non_blank(raw)?;
    let b = s.as_bytes();
    let plain_date = b.len() == 10
        && b[4] == b'-'
        && b[7] == b'-'
        && b.iter()
            .enumerate()
            .all(|(i, c)| i == 4 || i == 7 || c.is_ascii_digit());
    if plain_date {
        return NaiveDate::parse_from_str(s, "%Y-%m-%d").ok();
    }
    if let Ok(dt) = DateTime::parse_from_rfc3339(s) {
        return Some(dt.with_timezone(&Utc).date_naive());
    }
    // ISO_OFFSET_DATE_TIME also allows the seconds to be left out.
    DateTime::parse_from_str(s, "%Y-%m-%dT%H:%M%:z")
        .ok()
        .or_else(|| {
            s.strip_suffix(['Z', 'z']).and_then(|rest| {
                DateTime::parse_from_str(&format!("{rest}+00:00"), "%Y-%m-%dT%H:%M%:z").ok()
            })
        })
        .map(|dt| dt.with_timezone(&Utc).date_naive())
}

/// The kind of a news item, which picks its glyph and chip style.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum NewsKind {
    Info,
    Update,
    Warning,
    Event,
}

impl NewsKind {
    /// `info` / `update` / `warning` / `event`, case-insensitive; anything else is `Info`.
    pub fn parse(raw: Option<&str>) -> Self {
        match crate::non_blank(raw)
            .map(str::to_ascii_lowercase)
            .as_deref()
        {
            Some("update") => NewsKind::Update,
            Some("warning") => NewsKind::Warning,
            Some("event") => NewsKind::Event,
            _ => NewsKind::Info,
        }
    }

    /// The glyph shown before the title.
    pub fn glyph(self) -> &'static str {
        match self {
            NewsKind::Info => "\u{2139}",
            NewsKind::Update => "\u{2B06}",
            NewsKind::Warning => "\u{26A0}",
            NewsKind::Event => "\u{2605}",
        }
    }

    /// The Java launcher's chip style class name; the Rust UI maps it to its own tokens.
    pub fn chip_style(self) -> &'static str {
        match self {
            NewsKind::Info | NewsKind::Event => "stat-chip",
            NewsKind::Update => "stat-chip-success",
            NewsKind::Warning => "stat-chip-warn",
        }
    }
}

/// One `packNews` entry, raw as written in the manifest. Use the accessors to read it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NewsItem {
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub id: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub title: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub body: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub date: Option<String>,
    #[serde(
        rename = "type",
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub kind: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub url: Option<String>,
    #[serde(deserialize_with = "lenient::boolean")]
    pub pinned: bool,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub expires: Option<String>,
}

impl NewsItem {
    /// Trimmed title, at most 200 characters; `None` when blank.
    pub fn title(&self) -> Option<String> {
        clamp(self.title.as_deref(), MAX_NEWS_TITLE)
    }

    /// Trimmed body, at most 2000 characters; `None` when blank.
    pub fn body(&self) -> Option<String> {
        clamp(self.body.as_deref(), MAX_NEWS_BODY)
    }

    /// Trimmed id; `None` when blank (such items never count as unread).
    pub fn id(&self) -> Option<&str> {
        crate::non_blank(self.id.as_deref())
    }

    /// The item's kind.
    pub fn kind(&self) -> NewsKind {
        NewsKind::parse(self.kind.as_deref())
    }

    /// Whether the author pinned it to the top.
    pub fn is_pinned(&self) -> bool {
        self.pinned
    }

    /// The link, only when it is a well-formed `http`/`https` URL.
    pub fn url(&self) -> Option<String> {
        sanitized_http_url(self.url.as_deref())
    }

    /// The parsed `date`.
    pub fn date(&self) -> Option<NaiveDate> {
        parse_news_date(self.date.as_deref())
    }

    /// The parsed `expires`.
    pub fn expiry(&self) -> Option<NaiveDate> {
        parse_news_date(self.expires.as_deref())
    }

    /// True when the expiry parses and `today` is on or after it.
    pub fn is_expired(&self, today: NaiveDate) -> bool {
        self.expiry().is_some_and(|exp| today >= exp)
    }

    /// Seconds since the epoch of `date` (midnight UTC), or `i64::MIN` when undated.
    pub fn sort_epoch(&self) -> i64 {
        self.date()
            .and_then(|d| d.and_hms_opt(0, 0, 0))
            .map(|dt| dt.and_utc().timestamp())
            .unwrap_or(i64::MIN)
    }

    /// True when it has a title to show.
    pub fn is_renderable(&self) -> bool {
        self.title().is_some()
    }
}

/// One `packLinks` entry.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LinkItem {
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub title: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub url: Option<String>,
    #[serde(
        deserialize_with = "lenient::string",
        skip_serializing_if = "Option::is_none"
    )]
    pub description: Option<String>,
}

impl LinkItem {
    /// Trimmed title, at most 120 characters; `None` when blank.
    pub fn title(&self) -> Option<String> {
        clamp(self.title.as_deref(), MAX_LINK_TITLE)
    }

    /// Trimmed description, at most 500 characters; `None` when blank.
    pub fn description(&self) -> Option<String> {
        clamp(self.description.as_deref(), MAX_LINK_DESCRIPTION)
    }

    /// The link, only when it is a well-formed `http`/`https` URL.
    pub fn url(&self) -> Option<String> {
        sanitized_http_url(self.url.as_deref())
    }

    /// True when it has both a title and a usable URL.
    pub fn is_renderable(&self) -> bool {
        self.title().is_some() && self.url().is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn d(y: i32, m: u32, day: u32) -> NaiveDate {
        NaiveDate::from_ymd_opt(y, m, day).unwrap()
    }

    #[test]
    fn dates_parse_plain_and_offset_forms() {
        assert_eq!(parse_news_date(Some("2026-11-04")), Some(d(2026, 11, 4)));
        assert_eq!(parse_news_date(Some(" 2026-11-04 ")), Some(d(2026, 11, 4)));
        assert_eq!(
            parse_news_date(Some("2026-11-04T23:30:00-02:00")),
            Some(d(2026, 11, 5))
        );
        assert_eq!(
            parse_news_date(Some("2026-11-04T10:15Z")),
            Some(d(2026, 11, 4))
        );
        assert_eq!(
            parse_news_date(Some("2026-11-04T10:15+01:00")),
            Some(d(2026, 11, 4))
        );
        assert_eq!(parse_news_date(Some("2026-1-4")), None);
        assert_eq!(parse_news_date(Some("2026-02-30")), None);
        assert_eq!(parse_news_date(Some("soon")), None);
        assert_eq!(parse_news_date(None), None);
    }

    #[test]
    fn title_and_body_are_trimmed_and_clamped() {
        let item = NewsItem {
            title: Some(format!("  {}  ", "\u{e9}".repeat(250))),
            body: Some("   ".into()),
            ..Default::default()
        };
        assert_eq!(item.title().unwrap().chars().count(), 200);
        assert_eq!(item.body(), None);
        assert!(item.is_renderable());
        assert!(!NewsItem::default().is_renderable());
    }

    #[test]
    fn kinds_parse_case_insensitively() {
        assert_eq!(NewsKind::parse(Some("UPDATE")), NewsKind::Update);
        assert_eq!(NewsKind::parse(Some(" warning ")), NewsKind::Warning);
        assert_eq!(NewsKind::parse(Some("event")), NewsKind::Event);
        assert_eq!(NewsKind::parse(Some("other")), NewsKind::Info);
        assert_eq!(NewsKind::parse(None), NewsKind::Info);
        assert_eq!(NewsKind::Update.chip_style(), "stat-chip-success");
        assert_eq!(NewsKind::Warning.glyph(), "\u{26A0}");
    }

    #[test]
    fn expiry_is_inclusive_of_the_day() {
        let item = NewsItem {
            expires: Some("2026-11-04".into()),
            ..Default::default()
        };
        assert!(!item.is_expired(d(2026, 11, 3)));
        assert!(item.is_expired(d(2026, 11, 4)));
        assert!(item.is_expired(d(2026, 11, 5)));
        assert!(!NewsItem::default().is_expired(d(2026, 11, 5)));
    }

    #[test]
    fn sort_epoch() {
        let item = NewsItem {
            date: Some("1970-01-02".into()),
            ..Default::default()
        };
        assert_eq!(item.sort_epoch(), 86_400);
        assert_eq!(NewsItem::default().sort_epoch(), i64::MIN);
    }

    #[test]
    fn id_and_url() {
        let item = NewsItem {
            id: Some("  x ".into()),
            url: Some("javascript:alert(1)".into()),
            ..Default::default()
        };
        assert_eq!(item.id(), Some("x"));
        assert_eq!(item.url(), None);
    }

    #[test]
    fn links_need_title_and_http_url() {
        let ok = LinkItem {
            title: Some("Website".into()),
            url: Some("https://example.com".into()),
            description: Some("d".repeat(600)),
        };
        assert!(ok.is_renderable());
        assert_eq!(ok.description().unwrap().len(), 500);
        let bad = LinkItem {
            title: Some("Website".into()),
            url: Some("file:///x".into()),
            description: None,
        };
        assert!(!bad.is_renderable());
        let long = LinkItem {
            title: Some("t".repeat(130)),
            ..Default::default()
        };
        assert_eq!(long.title().unwrap().len(), 120);
    }
}
