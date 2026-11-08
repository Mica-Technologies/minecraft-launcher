// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! "Allow for this session" grants (Java `McpGrantStore`), in memory for one run.
//!
//! Keyed to the client's name, not its connection: clients reconnect often, and
//! connection-scoped grants would re-prompt constantly and train click-through. All nameless
//! clients share the `Unknown client` key, an accepted consequence of untrusted names.

use std::collections::HashMap;

use parking_lot::Mutex;

/// One live grant.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Grant {
    /// Normalized (trimmed, lower-case).
    pub client_name: String,
    /// Normalized (trimmed, lower-case).
    pub tool_name: String,
    pub expires_at_ms: u64,
}

/// The grants.
#[derive(Default)]
pub struct GrantStore {
    grants: Mutex<HashMap<String, u64>>,
}

fn normalize(s: &str) -> Option<String> {
    let n = s.trim().to_lowercase();
    if n.is_empty() { None } else { Some(n) }
}

/// `client \0 tool`: NUL because a printable separator is ambiguous, and tool names cannot
/// contain it.
fn key(client: &str, tool: &str) -> Option<String> {
    Some(format!("{}\0{}", normalize(client)?, normalize(tool)?))
}

impl GrantStore {
    pub fn new() -> GrantStore {
        GrantStore::default()
    }

    /// Grants `tool` to `client` until `expires_at_ms`. Ignored for a blank name.
    pub fn grant(&self, client: &str, tool: &str, expires_at_ms: u64) {
        if let Some(k) = key(client, tool) {
            self.grants.lock().insert(k, expires_at_ms);
        }
    }

    /// When the grant expires; `0` when there is none.
    pub fn expiry_for(&self, client: &str, tool: &str) -> u64 {
        key(client, tool)
            .and_then(|k| self.grants.lock().get(&k).copied())
            .unwrap_or(0)
    }

    /// Removes grants expiring at or before `now_ms`.
    pub fn purge_expired(&self, now_ms: u64) {
        self.grants.lock().retain(|_, &mut e| e > now_ms);
    }

    pub fn revoke_all(&self) {
        self.grants.lock().clear();
    }

    /// Removes every grant held by `client`.
    pub fn revoke_client(&self, client: &str) {
        let Some(c) = normalize(client) else {
            return;
        };
        let prefix = format!("{c}\0");
        self.grants.lock().retain(|k, _| !k.starts_with(&prefix));
    }

    /// The unexpired grants.
    pub fn live_grants(&self, now_ms: u64) -> Vec<Grant> {
        let mut v: Vec<Grant> = self
            .grants
            .lock()
            .iter()
            .filter(|(_, e)| **e > now_ms)
            .filter_map(|(k, e)| {
                let (c, t) = k.split_once('\0')?;
                Some(Grant {
                    client_name: c.to_string(),
                    tool_name: t.to_string(),
                    expires_at_ms: *e,
                })
            })
            .collect();
        v.sort_by(|a, b| (&a.client_name, &a.tool_name).cmp(&(&b.client_name, &b.tool_name)));
        v
    }

    /// Every stored grant, expired ones included.
    pub fn len(&self) -> usize {
        self.grants.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grants_are_case_and_space_insensitive() {
        let g = GrantStore::new();
        g.grant(" Claude ", "List_Modpacks", 100);
        assert_eq!(g.expiry_for("claude", "list_modpacks"), 100);
        assert_eq!(g.expiry_for("other", "list_modpacks"), 0);
        assert_eq!(g.expiry_for("claude", "other"), 0);
    }

    #[test]
    fn blank_names_are_unusable() {
        let g = GrantStore::new();
        g.grant("", "t", 100);
        g.grant("c", "  ", 100);
        assert!(g.is_empty());
        assert_eq!(g.expiry_for("", "t"), 0);
    }

    #[test]
    fn the_separator_is_unambiguous() {
        let g = GrantStore::new();
        g.grant("a b", "c", 100);
        assert_eq!(g.expiry_for("a", "b c"), 0);
    }

    #[test]
    fn purge_and_live_grants_skip_expired() {
        let g = GrantStore::new();
        g.grant("c", "a", 10);
        g.grant("c", "b", 20);
        assert_eq!(g.live_grants(10).len(), 1);
        assert_eq!(g.len(), 2);
        g.purge_expired(10);
        assert_eq!(g.len(), 1);
        assert_eq!(g.live_grants(0)[0].tool_name, "b");
    }

    #[test]
    fn revoke_client_and_all() {
        let g = GrantStore::new();
        g.grant("one", "t", 10);
        g.grant("one two", "t", 10);
        g.grant("two", "t", 10);
        g.revoke_client("ONE");
        assert_eq!(g.expiry_for("one", "t"), 0);
        assert_eq!(g.expiry_for("one two", "t"), 10);
        g.revoke_all();
        assert!(g.is_empty());
    }
}
