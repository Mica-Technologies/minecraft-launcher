// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Spotting the OAuth redirect among the web view's navigations.
//!
//! Port of `MicrosoftSignIn.attach`: the first navigation whose location starts with the
//! redirect prefix ends the wait. The redirect is reported exactly once, whichever web-view hook
//! sees it first (the navigation handler and the page-load handler both feed the watcher), so a
//! single-use code is never handed back twice. That was a real Java bug (listeners stacked per
//! page load redeemed one code several times).

use std::sync::atomic::{AtomicBool, Ordering};

/// What the web view should do with one navigation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NavDecision {
    /// Let the page load.
    Allow,
    /// This is the redirect: stop the navigation and hand the URL to the parent.
    Capture(String),
    /// Stop the navigation (the flow is over, or the scheme is not one a web page may open).
    Block,
}

/// `true` when `url` is the redirect: it starts with `prefix` and the prefix ends on a URL
/// boundary (end of string, `?`, `#` or `/`), so `https://login.live.com/oauth20_desktop.srfX`
/// does not count. A prefix that itself ends in a separator matches anything after it.
pub fn is_redirect(url: &str, prefix: &str) -> bool {
    if prefix.is_empty() {
        return false;
    }
    let Some(rest) = url.strip_prefix(prefix) else {
        return false;
    };
    if prefix.ends_with(['?', '#', '/', '&', '=']) {
        return true;
    }
    rest.is_empty() || rest.starts_with(['?', '#', '/'])
}

/// Schemes the sign-in page may navigate to. Anything else (`ms-settings:`, `mailto:`, custom
/// app protocols) would hand control to another program, which a sign-in window has no reason
/// to do.
fn is_allowed_scheme(url: &str) -> bool {
    let scheme = match url.split_once(':') {
        Some((s, _)) => s.to_ascii_lowercase(),
        None => return false,
    };
    matches!(
        scheme.as_str(),
        "https" | "http" | "about" | "data" | "blob"
    )
}

/// Watches navigations for the redirect and reports it once.
#[derive(Debug)]
pub struct RedirectWatcher {
    prefix: String,
    done: AtomicBool,
}

impl RedirectWatcher {
    /// A watcher for `prefix` (normally [`crate::DEFAULT_REDIRECT_PREFIX`]).
    pub fn new(prefix: impl Into<String>) -> RedirectWatcher {
        RedirectWatcher {
            prefix: prefix.into(),
            done: AtomicBool::new(false),
        }
    }

    /// `true` once the redirect has been seen.
    pub fn is_done(&self) -> bool {
        self.done.load(Ordering::Acquire)
    }

    /// Looks at one location: returns the URL the first time it is the redirect, `None`
    /// otherwise (and always `None` after that).
    pub fn observe(&self, url: &str) -> Option<String> {
        if !is_redirect(url, &self.prefix) {
            return None;
        }
        self.done
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .ok()
            .map(|_| url.to_string())
    }

    /// Decides one navigation of the main frame.
    pub fn decide(&self, url: &str) -> NavDecision {
        if let Some(captured) = self.observe(url) {
            return NavDecision::Capture(captured);
        }
        if self.is_done() || !is_allowed_scheme(url) {
            return NavDecision::Block;
        }
        NavDecision::Allow
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const REDIRECT: &str = "https://login.live.com/oauth20_desktop.srf";

    #[test]
    fn the_redirect_with_a_code_is_captured_unchanged() {
        let w = RedirectWatcher::new(REDIRECT);
        let url = format!("{REDIRECT}?code=M.C123_abc&lc=1033");
        assert_eq!(w.decide(&url), NavDecision::Capture(url.clone()));
    }

    #[test]
    fn the_redirect_with_an_error_or_fragment_is_captured_too() {
        for url in [
            format!(
                "{REDIRECT}?error=access_denied&error_description=The+user+has+denied+access+to+the+scope"
            ),
            format!("{REDIRECT}?code=abc#frag"),
            format!("{REDIRECT}#code=abc"),
            REDIRECT.to_string(),
        ] {
            let w = RedirectWatcher::new(REDIRECT);
            assert_eq!(w.observe(&url), Some(url.clone()), "{url}");
        }
    }

    #[test]
    fn microsofts_own_pages_load_normally() {
        let w = RedirectWatcher::new(REDIRECT);
        for url in [
            "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328",
            "https://login.live.com/ppsecure/post.srf",
            "https://account.live.com/proofs/Add",
            "https://login.microsoftonline.com/consumers/",
            "about:blank",
        ] {
            assert_eq!(w.decide(url), NavDecision::Allow, "{url}");
        }
        assert!(!w.is_done());
    }

    #[test]
    fn a_lookalike_path_is_not_the_redirect() {
        assert!(!is_redirect(
            "https://login.live.com/oauth20_desktop.srfX?code=1",
            REDIRECT
        ));
        assert!(!is_redirect(
            "https://evil.test/?u=https://login.live.com/oauth20_desktop.srf?code=1",
            REDIRECT
        ));
        assert!(!is_redirect(
            "http://login.live.com/oauth20_desktop.srf?code=1",
            REDIRECT
        ));
        assert!(!is_redirect(REDIRECT, ""));
    }

    #[test]
    fn a_prefix_ending_in_a_separator_matches_anything_after_it() {
        assert!(is_redirect(
            "https://example.test/cb?code=1",
            "https://example.test/cb?"
        ));
        assert!(is_redirect(
            "https://example.test/cb/x",
            "https://example.test/cb/"
        ));
    }

    #[test]
    fn the_redirect_is_reported_only_once() {
        let w = RedirectWatcher::new(REDIRECT);
        let url = format!("{REDIRECT}?code=one");
        assert!(matches!(w.decide(&url), NavDecision::Capture(_)));
        // The page-load hook sees the same location afterwards; a code is single-use.
        assert_eq!(w.observe(&url), None);
        assert_eq!(w.decide(&url), NavDecision::Block);
        assert!(w.is_done());
    }

    #[test]
    fn nothing_loads_after_the_redirect() {
        let w = RedirectWatcher::new(REDIRECT);
        w.observe(&format!("{REDIRECT}?code=x")).unwrap();
        assert_eq!(w.decide("https://login.live.com/"), NavDecision::Block);
    }

    #[test]
    fn other_programs_cannot_be_launched_from_the_page() {
        let w = RedirectWatcher::new(REDIRECT);
        for url in [
            "ms-settings:privacy",
            "mailto:a@b.test",
            "file:///C:/x",
            "steam://run/1",
            "nocolon",
        ] {
            assert_eq!(w.decide(url), NavDecision::Block, "{url}");
        }
        assert!(!w.is_done());
    }

    #[test]
    fn the_watcher_is_safe_to_share_between_threads() {
        let w = std::sync::Arc::new(RedirectWatcher::new(REDIRECT));
        let url = format!("{REDIRECT}?code=race");
        let hits: usize = (0..8)
            .map(|_| {
                let w = w.clone();
                let url = url.clone();
                std::thread::spawn(move || w.observe(&url).is_some() as usize)
            })
            .map(|h| h.join().unwrap())
            .sum();
        assert_eq!(hits, 1);
    }
}
