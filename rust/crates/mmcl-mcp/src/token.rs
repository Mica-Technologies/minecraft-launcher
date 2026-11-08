// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The persisted bearer token (Java `McpAccessToken`).
//!
//! Persisted rather than per-launch because client configs are hand-edited; a rotating token
//! would make the feature unusable. Mitigations: loopback only, owner-only storage, and the
//! Settings page's Regenerate button.

use rand::RngCore;

/// 32 bytes as lower-case hex.
pub const TOKEN_LENGTH: usize = 64;

/// A new token from the OS CSPRNG.
pub fn generate() -> String {
    let mut b = [0u8; 32];
    rand::rng().fill_bytes(&mut b);
    hex::encode(b)
}

/// Exactly 64 hex characters (either case).
pub fn is_well_formed(s: &str) -> bool {
    s.len() == TOKEN_LENGTH && s.bytes().all(|b| b.is_ascii_hexdigit())
}

/// The stored token when it is well formed; otherwise a new one, which is written back (a
/// write failure is ignored: this run still has a usable token).
pub fn ensure<E, R, W>(read: R, write: W) -> String
where
    R: FnOnce() -> Result<String, E>,
    W: FnOnce(&str) -> Result<(), E>,
{
    if let Ok(t) = read()
        && is_well_formed(&t)
    {
        return t;
    }
    let t = generate();
    let _ = write(&t);
    t
}

/// The first eight characters and the length, for display: `3f9a21c8… (64 characters)`.
pub fn mask(token: &str) -> String {
    if token.is_empty() {
        return String::new();
    }
    let len = token.chars().count();
    let head: String = token.chars().take(8.min(len)).collect();
    format!("{head}\u{2026} ({len} characters)")
}

#[cfg(test)]
mod tests {
    //! Port of `McpAccessTokenTest`.
    use super::*;
    use std::cell::RefCell;

    #[test]
    fn generated_tokens_are_64_lower_hex_and_distinct() {
        let a = generate();
        let b = generate();
        assert_ne!(a, b);
        assert_eq!(a.len(), TOKEN_LENGTH);
        assert!(a.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f')));
        assert!(is_well_formed(&a));
    }

    #[test]
    fn well_formed_accepts_upper_case() {
        assert!(is_well_formed(&"A".repeat(64)));
    }

    #[test]
    fn well_formed_refuses_bad_values() {
        assert!(!is_well_formed(""));
        assert!(!is_well_formed(&"a".repeat(63)));
        assert!(!is_well_formed(&"a".repeat(65)));
        assert!(!is_well_formed(&"g".repeat(64)));
        assert!(!is_well_formed(&format!(" {}", "a".repeat(63))));
    }

    #[test]
    fn ensure_keeps_a_well_formed_token() {
        let stored = "b".repeat(64);
        let wrote = RefCell::new(None::<String>);
        let t = ensure::<(), _, _>(
            || Ok(stored.clone()),
            |w| {
                *wrote.borrow_mut() = Some(w.to_string());
                Ok(())
            },
        );
        assert_eq!(t, stored);
        assert!(wrote.borrow().is_none());
    }

    #[test]
    fn ensure_replaces_a_malformed_or_empty_token() {
        for stored in ["", "short", "zz"] {
            let wrote = RefCell::new(None::<String>);
            let t = ensure::<(), _, _>(
                || Ok(stored.to_string()),
                |w| {
                    *wrote.borrow_mut() = Some(w.to_string());
                    Ok(())
                },
            );
            assert!(is_well_formed(&t));
            assert_eq!(wrote.borrow().as_deref(), Some(t.as_str()));
        }
    }

    #[test]
    fn ensure_treats_a_read_error_as_absent() {
        let t = ensure::<&str, _, _>(|| Err("boom"), |_| Ok(()));
        assert!(is_well_formed(&t));
    }

    #[test]
    fn ensure_survives_a_write_error() {
        let t = ensure::<&str, _, _>(|| Ok(String::new()), |_| Err("disk full"));
        assert!(is_well_formed(&t));
    }

    #[test]
    fn mask_shows_eight_characters_and_the_length() {
        let t = format!("3f9a21c8{}", "0".repeat(56));
        assert_eq!(mask(&t), "3f9a21c8\u{2026} (64 characters)");
        assert_eq!(mask(""), "");
        assert_eq!(mask("abc"), "abc\u{2026} (3 characters)");
    }
}
