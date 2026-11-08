// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Durable per-tool policies (Java `McpToolPolicyStore`), stored as a JSON object *string* in
//! the config key `mcpToolPolicies`.
//!
//! Every read failure means "unset", never a policy: a corrupted config must degrade to
//! "ask", never silently grant standing permission to delete modpacks.

use std::collections::BTreeMap;

use serde_json::{Map, Value};

use super::Policy;
use crate::BoxError;

/// Where the JSON string lives.
pub trait PolicyBacking: Send + Sync {
    fn read(&self) -> Result<String, BoxError>;
    fn write(&self, json: &str) -> Result<(), BoxError>;
}

/// The policy store.
pub struct PolicyStore {
    backing: Box<dyn PolicyBacking>,
}

fn normalize(tool: &str) -> Option<String> {
    let n = tool.trim().to_lowercase();
    if n.is_empty() { None } else { Some(n) }
}

impl PolicyStore {
    pub fn new(backing: Box<dyn PolicyBacking>) -> PolicyStore {
        PolicyStore { backing }
    }

    /// The user's policy for `tool`, if any.
    pub fn policy_for(&self, tool: &str) -> Option<Policy> {
        let key = normalize(tool)?;
        self.load().get(&key).copied()
    }

    /// Sets (or with `None` clears) the policy for `tool`. A blank name is ignored.
    pub fn set_policy(&self, tool: &str, policy: Option<Policy>) {
        let Some(key) = normalize(tool) else {
            return;
        };
        let mut all = self.load();
        match policy {
            Some(p) => {
                all.insert(key, p);
            }
            None => {
                all.remove(&key);
            }
        }
        self.save(&all);
    }

    /// Every stored policy, sorted by tool name.
    pub fn all(&self) -> BTreeMap<String, Policy> {
        self.load()
    }

    /// Removes every policy.
    pub fn clear(&self) {
        self.save(&BTreeMap::new());
    }

    fn load(&self) -> BTreeMap<String, Policy> {
        let mut out = BTreeMap::new();
        let Ok(raw) = self.backing.read() else {
            return out;
        };
        if raw.trim().is_empty() {
            return out;
        }
        let Ok(Value::Object(obj)) = serde_json::from_str::<Value>(&raw) else {
            return out;
        };
        for (k, v) in obj {
            let Some(key) = normalize(&k) else {
                continue;
            };
            let Value::String(s) = v else {
                continue;
            };
            if let Some(p) = Policy::parse(&s) {
                out.insert(key, p);
            }
        }
        out
    }

    fn save(&self, all: &BTreeMap<String, Policy>) {
        let mut m = Map::new();
        for (k, p) in all {
            m.insert(k.clone(), Value::String(p.name().to_string()));
        }
        let json = serde_json::to_string(&Value::Object(m)).unwrap_or_else(|_| "{}".into());
        let _ = self.backing.write(&json);
    }
}

#[cfg(test)]
mod tests {
    //! Port of `McpToolPolicyStoreTest`.
    use super::*;
    use crate::seam_error;
    use parking_lot::Mutex;
    use std::sync::Arc;

    #[derive(Clone, Default)]
    struct Mem {
        value: Arc<Mutex<String>>,
        fail_read: bool,
        fail_write: bool,
    }

    impl PolicyBacking for Mem {
        fn read(&self) -> Result<String, BoxError> {
            if self.fail_read {
                return Err(seam_error("read"));
            }
            Ok(self.value.lock().clone())
        }
        fn write(&self, json: &str) -> Result<(), BoxError> {
            if self.fail_write {
                return Err(seam_error("write"));
            }
            *self.value.lock() = json.to_string();
            Ok(())
        }
    }

    fn store_with(raw: &str) -> (PolicyStore, Mem) {
        let m = Mem::default();
        *m.value.lock() = raw.to_string();
        (PolicyStore::new(Box::new(m.clone())), m)
    }

    #[test]
    fn round_trip() {
        let (s, m) = store_with("");
        s.set_policy("uninstall_modpack", Some(Policy::Disabled));
        s.set_policy("list_modpacks", Some(Policy::AlwaysAllow));
        assert_eq!(s.policy_for("uninstall_modpack"), Some(Policy::Disabled));
        assert_eq!(
            *m.value.lock(),
            r#"{"list_modpacks":"ALWAYS_ALLOW","uninstall_modpack":"DISABLED"}"#
        );
        s.set_policy("uninstall_modpack", None);
        assert_eq!(s.policy_for("uninstall_modpack"), None);
    }

    #[test]
    fn keys_are_case_insensitive_and_trimmed() {
        let (s, _) = store_with(r#"{" Launch_Modpack ":"ask"}"#);
        assert_eq!(s.policy_for("launch_modpack"), Some(Policy::Ask));
        assert_eq!(s.policy_for("LAUNCH_MODPACK  "), Some(Policy::Ask));
    }

    #[test]
    fn blank_names_are_ignored() {
        let (s, m) = store_with("");
        s.set_policy("  ", Some(Policy::AlwaysAllow));
        assert_eq!(*m.value.lock(), "");
        assert_eq!(s.policy_for(""), None);
    }

    #[test]
    fn storage_is_sorted() {
        let (s, _) = store_with("");
        for t in ["c", "a", "b"] {
            s.set_policy(t, Some(Policy::Ask));
        }
        assert_eq!(s.all().keys().cloned().collect::<Vec<_>>(), ["a", "b", "c"]);
    }

    #[test]
    fn bad_reads_are_unset() {
        for raw in ["", "   ", "{", "[]", "\"x\"", "42", "null"] {
            let (s, _) = store_with(raw);
            assert!(s.all().is_empty(), "{raw}");
        }
        let m = Mem {
            fail_read: true,
            ..Mem::default()
        };
        assert!(PolicyStore::new(Box::new(m)).all().is_empty());
    }

    #[test]
    fn unknown_or_wrong_type_entries_are_dropped_but_good_ones_kept() {
        let (s, _) = store_with(
            r#"{"a":"ALWAYS_ALLOW","b":"SUPER_ALLOW","c":1,"d":{"x":"ALWAYS_ALLOW"},"e":["ASK"],"f":null,"g":"disabled"}"#,
        );
        let all = s.all();
        assert_eq!(all.len(), 2);
        assert_eq!(all["a"], Policy::AlwaysAllow);
        assert_eq!(all["g"], Policy::Disabled);
    }

    #[test]
    fn a_throwing_write_is_swallowed() {
        let m = Mem {
            fail_write: true,
            ..Mem::default()
        };
        let s = PolicyStore::new(Box::new(m));
        s.set_policy("x", Some(Policy::Ask));
        s.clear();
    }

    #[test]
    fn clear_saves_an_empty_object() {
        let (s, m) = store_with(r#"{"a":"ASK"}"#);
        s.clear();
        assert_eq!(*m.value.lock(), "{}");
    }

    #[test]
    fn no_hostile_input_ever_yields_always_allow() {
        // Deterministic fuzz: structural garbage around the permissive token.
        let pieces = [
            "{",
            "}",
            "[",
            "]",
            "\"",
            ":",
            ",",
            "ALWAYS_ALLOW",
            "always allow",
            "ALWAYS-ALLOW",
            "\\u0000",
            "null",
            "1",
            "true",
            "\"t\"",
            " ",
            "\"ALWAYS_ALLOW \"",
            "ALLOW",
        ];
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        for _ in 0..3000 {
            let mut raw = String::new();
            for _ in 0..(seed % 9 + 1) {
                seed = seed
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                raw.push_str(pieces[(seed >> 33) as usize % pieces.len()]);
            }
            let (s, _) = store_with(&raw);
            for (k, p) in s.all() {
                if p == Policy::AlwaysAllow {
                    // Only a well-formed object with a string value may carry it.
                    let v: Value = serde_json::from_str(&raw).expect("parsed");
                    let stored = v
                        .as_object()
                        .unwrap()
                        .iter()
                        .find(|(kk, _)| kk.trim().to_lowercase() == k)
                        .unwrap()
                        .1
                        .as_str()
                        .unwrap()
                        .trim()
                        .to_uppercase();
                    assert_eq!(stored, "ALWAYS_ALLOW");
                }
            }
        }
    }
}
