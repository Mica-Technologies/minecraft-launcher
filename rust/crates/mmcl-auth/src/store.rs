// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Per-account credential folders (`AccountStore`), byte-compatible with the Java launcher.
//!
//! ```text
//! <config>/profiles/<uuid>/
//!   player.mica        encrypted gzip'd AuthFile, raw envelope bytes
//!   cached_user.json   Base64( encrypted( UTF-8 JSON of User ) )
//!   renewal.timestamp  Base64( encrypted( ASCII decimal epoch millis ) )
//!   profile.json       plaintext {"uuid","displayName","lastUsedMs"}
//! ```
//!
//! The uuid becomes a path segment, so one that is not `[0-9a-fA-F-]{1,64}` is refused rather
//! than sanitized. Folders are tightened to owner-only before credentials are written into them,
//! and every file is written atomically through an owner-only temporary sibling.
//!
//! Not thread-safe on its own: [`crate::AccountManager`] serializes access.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use mmcl_i18n::tf;
use mmcl_secure::Cipher;
use mmcl_secure::cipher::{decode_base64, encode_base64};
use serde_json::{Map, Value};

use crate::user::User;

/// The store folder under the config folder.
pub const PROFILES_DIR: &str = "profiles";
/// The encrypted authentication file.
pub const AUTH_FILE: &str = "player.mica";
/// The encrypted cached user.
pub const CACHED_USER_FILE: &str = "cached_user.json";
/// The encrypted renewal time.
pub const RENEWAL_FILE: &str = "renewal.timestamp";
/// The plaintext metadata.
pub const META_FILE: &str = "profile.json";

/// The gzip magic that marks a legacy unencrypted `player.mica`.
const GZIP_MAGIC: [u8; 2] = [0x1f, 0x8b];

/// One stored account, as [`AccountStore::list`] reports it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub uuid: String,
    /// `""` when the metadata is missing or unreadable.
    pub display_name: String,
    /// `0` when unknown.
    pub last_used_ms: i64,
}

/// `true` when `uuid` is safe to use as a folder name: `^[0-9a-fA-F-]{1,64}$`.
pub fn is_safe_uuid(uuid: &str) -> bool {
    !uuid.is_empty() && uuid.len() <= 64 && uuid.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
}

/// The per-account store rooted at `<config>/profiles`.
#[derive(Clone)]
pub struct AccountStore {
    root: PathBuf,
    cipher: Arc<dyn Cipher>,
}

impl std::fmt::Debug for AccountStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AccountStore")
            .field("root", &self.root)
            .finish()
    }
}

impl AccountStore {
    /// A store at `root` (normally `DataPaths::profiles_dir()`), encrypting with `cipher`.
    pub fn new(root: impl Into<PathBuf>, cipher: Arc<dyn Cipher>) -> AccountStore {
        AccountStore {
            root: root.into(),
            cipher,
        }
    }

    /// The store folder.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The cipher.
    pub fn cipher(&self) -> &Arc<dyn Cipher> {
        &self.cipher
    }

    /// An account's folder. An unsafe uuid resolves to a path that never exists, so reads see
    /// "absent" and never error.
    fn folder(&self, uuid: &str) -> PathBuf {
        if is_safe_uuid(uuid) {
            self.root.join(uuid)
        } else {
            self.root.join("invalid-uuid").join("never")
        }
    }

    fn file(&self, uuid: &str, name: &str) -> PathBuf {
        self.folder(uuid).join(name)
    }

    // ------------------------------------------------------------------ listing

    /// Every stored account, most recently used first. Folders without `player.mica` and
    /// unsafe names are skipped; unreadable metadata costs only the name and sort position.
    pub fn list(&self) -> Vec<Entry> {
        let mut out = Vec::new();
        if !self.root.is_dir() {
            return out;
        }
        let dir = match fs::read_dir(&self.root) {
            Ok(d) => d,
            Err(e) => {
                mmcl_log::warn_silent(&tf!("log.accountStore.listFailed", io_kind(&e)));
                return out;
            }
        };
        for item in dir {
            let item = match item {
                Ok(i) => i,
                Err(e) => {
                    mmcl_log::warn_silent(&tf!("log.accountStore.listFailed", io_kind(&e)));
                    break;
                }
            };
            let path = item.path();
            let Some(name) = item.file_name().to_str().map(str::to_string) else {
                continue;
            };
            if !is_safe_uuid(&name) || !path.is_dir() || !path.join(AUTH_FILE).is_file() {
                continue;
            }
            let (display_name, last_used_ms) = read_meta(&path.join(META_FILE), &name);
            out.push(Entry {
                uuid: name,
                display_name,
                last_used_ms,
            });
        }
        // Name first so ties have a stable order on every platform, then most recent first.
        out.sort_by(|a, b| a.uuid.cmp(&b.uuid));
        out.sort_by_key(|e| std::cmp::Reverse(e.last_used_ms));
        out
    }

    // ------------------------------------------------------------------ auth file

    /// The gzip'd authentication file of `uuid`, or `None` when absent or unreadable here.
    pub fn read_auth_file(&self, uuid: &str) -> Option<Vec<u8>> {
        read_auth_file_at(&self.file(uuid, AUTH_FILE), self.cipher.as_ref())
    }

    /// Writes the gzip'd authentication file of `uuid`, encrypted.
    pub fn write_auth_file(&self, uuid: &str, gzipped: &[u8]) -> io::Result<()> {
        self.write_secret(uuid, AUTH_FILE, gzipped, false)
    }

    // ------------------------------------------------------------------ cached user

    /// The cached user of `uuid`, or `None` when absent, unreadable or nameless.
    pub fn read_cached_user(&self, uuid: &str) -> Option<User> {
        read_cached_user_at(&self.file(uuid, CACHED_USER_FILE), self.cipher.as_ref())
    }

    /// Writes the cached user into its own folder.
    pub fn write_cached_user(&self, user: &User) -> io::Result<()> {
        let json = serde_json::to_vec(user).map_err(|e| io::Error::other(e.to_string()))?;
        self.write_secret(&user.uuid, CACHED_USER_FILE, &json, true)
    }

    // ------------------------------------------------------------------ renewal time

    /// When `uuid` was last renewed, epoch millis; `0` when unknown.
    pub fn read_renewal_ms(&self, uuid: &str) -> i64 {
        read_renewal_ms_at(&self.file(uuid, RENEWAL_FILE), self.cipher.as_ref())
    }

    /// Writes the renewal time of `uuid`.
    pub fn write_renewal_ms(&self, uuid: &str, ms: i64) -> io::Result<()> {
        self.write_secret(uuid, RENEWAL_FILE, ms.to_string().as_bytes(), true)
    }

    // ------------------------------------------------------------------ metadata

    /// Writes the plaintext metadata of `uuid`.
    pub fn write_meta(&self, uuid: &str, display_name: &str, last_used_ms: i64) -> io::Result<()> {
        let folder = self.ensure_folder(uuid)?;
        let mut obj = Map::new();
        obj.insert("uuid".into(), Value::from(uuid));
        obj.insert("displayName".into(), Value::from(display_name));
        obj.insert("lastUsedMs".into(), Value::from(last_used_ms));
        write_atomically(
            &folder.join(META_FILE),
            Value::Object(obj).to_string().as_bytes(),
        )
    }

    // ------------------------------------------------------------------ removal

    /// Deletes `uuid`'s folder. Symbolic links inside are removed as links, never followed.
    /// Returns `true` when the folder is gone (also when it never existed); `false` for an
    /// unsafe uuid.
    pub fn remove(&self, uuid: &str) -> bool {
        if !is_safe_uuid(uuid) {
            return false;
        }
        let folder = self.root.join(uuid);
        if fs::symlink_metadata(&folder).is_err() {
            return true;
        }
        let mut paths = Vec::new();
        collect_no_follow(&folder, &mut paths);
        paths.sort();
        let mut failure: Option<io::Error> = None;
        for p in paths.iter().rev() {
            if let Err(e) = remove_one(p) {
                failure.get_or_insert(e);
            }
        }
        if let Some(e) = failure {
            mmcl_log::warn_silent(&tf!("log.accountStore.removeFailed", uuid, io_kind(&e)));
        }
        fs::symlink_metadata(&folder).is_err()
    }

    // ------------------------------------------------------------------ internals

    /// Creates `root/uuid` and tightens the root and the folder to owner-only before anything
    /// is written into them (new Windows files inherit the folder's ACL).
    fn ensure_folder(&self, uuid: &str) -> io::Result<PathBuf> {
        if !is_safe_uuid(uuid) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Refusing to store an account under an unsafe uuid",
            ));
        }
        let folder = self.root.join(uuid);
        fs::create_dir_all(&folder)?;
        mmcl_secure::perms::owner_only_dir(&self.root);
        mmcl_secure::perms::owner_only_dir(&folder);
        Ok(folder)
    }

    fn write_secret(&self, uuid: &str, name: &str, plain: &[u8], base64: bool) -> io::Result<()> {
        let folder = self.ensure_folder(uuid)?;
        let envelope = self
            .cipher
            .encrypt(plain)
            .map_err(|_| io::Error::other(format!("Could not encrypt {name}")))?;
        let bytes = if base64 {
            encode_base64(&envelope).into_bytes()
        } else {
            envelope
        };
        write_atomically(&folder.join(name), &bytes)
    }
}

/// Reads a gzip'd authentication file at `path` (also the legacy flat file): missing or empty
/// is `None`; bytes starting with the gzip magic are a legacy unencrypted file, returned as-is;
/// anything else is decrypted, and a failure is `None`.
pub fn read_auth_file_at(path: &Path, cipher: &dyn Cipher) -> Option<Vec<u8>> {
    if !path.is_file() {
        return None;
    }
    let bytes = match fs::read(path) {
        Ok(b) => b,
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.authManager.loadAuthFileFailed", io_kind(&e)));
            return None;
        }
    };
    if bytes.is_empty() {
        return None;
    }
    if bytes.starts_with(&GZIP_MAGIC) {
        return Some(bytes);
    }
    match cipher.decrypt(&bytes) {
        Ok(Some(plain)) => Some(plain),
        Ok(None) => {
            mmcl_log::warn_silent(&tf!("log.authManager.loadAuthFileFailed", "TooShort"));
            None
        }
        Err(e) => {
            mmcl_log::warn_silent(&tf!("log.authManager.loadAuthFileFailed", e.kind_name()));
            None
        }
    }
}

/// Reads a cached user at `path`: Base64, decrypt, UTF-8 JSON. A user without a uuid or name
/// counts as missing.
pub fn read_cached_user_at(path: &Path, cipher: &dyn Cipher) -> Option<User> {
    if !path.is_file() {
        return None;
    }
    let result = (|| -> Result<Option<User>, &'static str> {
        let text = fs::read_to_string(path).map_err(|_| "IOException")?;
        let envelope = decode_base64(text.trim()).map_err(|e| e.kind_name())?;
        let plain = cipher
            .decrypt(&envelope)
            .map_err(|e| e.kind_name())?
            .ok_or("TooShort")?;
        let json = String::from_utf8(plain).map_err(|_| "InvalidUtf8")?;
        let obj: Map<String, Value> = serde_json::from_str(&json).map_err(|_| "JsonParse")?;
        let field = |k: &str| obj.get(k).and_then(Value::as_str).map(str::to_string);
        let (Some(uuid), Some(name)) = (field("uuid"), field("name")) else {
            return Ok(None);
        };
        if uuid.trim().is_empty() || name.trim().is_empty() {
            return Ok(None);
        }
        Ok(Some(User {
            uuid,
            name,
            access_token: field("accessToken"),
            user_type: field("type"),
            xuid: field("xuid"),
            client_id: field("clientId"),
        }))
    })();
    match result {
        Ok(u) => u,
        Err(kind) => {
            mmcl_log::warn_silent(&tf!("log.authManager.loadCachedUserFailed", kind));
            None
        }
    }
}

/// Reads a renewal time at `path`; `0` when missing or unreadable.
pub fn read_renewal_ms_at(path: &Path, cipher: &dyn Cipher) -> i64 {
    match fs::read_to_string(path) {
        Ok(s) => parse_renewal_timestamp(s.trim(), cipher),
        Err(_) => 0,
    }
}

/// Parses `renewal.timestamp` contents: the encrypted Base64 form, else the legacy plain
/// decimal (negative numbers included), else `0`.
pub fn parse_renewal_timestamp(raw: &str, cipher: &dyn Cipher) -> i64 {
    if raw.trim().is_empty() {
        return 0;
    }
    if let Ok(envelope) = decode_base64(raw)
        && let Ok(Some(plain)) = cipher.decrypt(&envelope)
        && let Ok(text) = std::str::from_utf8(&plain)
        && let Ok(v) = text.trim().parse::<i64>()
    {
        return v;
    }
    raw.parse::<i64>().unwrap_or(0)
}

/// Writes `bytes` through an owner-only `<name>.tmp` sibling and an atomic rename, then
/// tightens the target again.
pub fn write_atomically(target: &Path, bytes: &[u8]) -> io::Result<()> {
    // A failure to tighten the temporary file is not fatal; the call below tightens the target
    // again and logs when that fails too.
    let _outcome = mmcl_core::fsx::atomic_write(target, bytes, true)?;
    mmcl_secure::perms::owner_only(target);
    Ok(())
}

fn read_meta(path: &Path, uuid: &str) -> (String, i64) {
    if !path.is_file() {
        return (String::new(), 0);
    }
    let parsed = fs::read_to_string(path)
        .map_err(|_| "IOException")
        .and_then(|s| serde_json::from_str::<Value>(&s).map_err(|_| "JsonParse"))
        .and_then(|v| match v {
            Value::Object(o) => Ok(o),
            _ => Err("NotAnObject"),
        });
    match parsed {
        Ok(o) => (
            o.get("displayName")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string(),
            o.get("lastUsedMs").and_then(Value::as_i64).unwrap_or(0),
        ),
        Err(kind) => {
            mmcl_log::warn_silent(&tf!("log.accountStore.metaUnreadable", uuid, kind));
            (String::new(), 0)
        }
    }
}

/// Collects `path` and everything below it without following symbolic links.
fn collect_no_follow(path: &Path, out: &mut Vec<PathBuf>) {
    out.push(path.to_path_buf());
    let Ok(meta) = fs::symlink_metadata(path) else {
        return;
    };
    if meta.file_type().is_symlink() || !meta.is_dir() {
        return;
    }
    if let Ok(dir) = fs::read_dir(path) {
        for item in dir.flatten() {
            collect_no_follow(&item.path(), out);
        }
    }
}

/// Removes one path: a link as a link, a folder (already emptied) with `remove_dir`.
fn remove_one(path: &Path) -> io::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if meta.is_dir() && !meta.file_type().is_symlink() {
        fs::remove_dir(path)
    } else {
        // A Windows directory symlink is removed with remove_dir; try both.
        fs::remove_file(path).or_else(|e| fs::remove_dir(path).map_err(|_| e))
    }
}

/// A data-free description of an IO error for logs.
pub(crate) fn io_kind(e: &io::Error) -> String {
    format!("{:?}", e.kind())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{FAKE_PREFIX, FakeCipher, OtherMachine, auth_file, user, uuid};

    fn setup() -> (tempfile::TempDir, PathBuf, AccountStore) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join(PROFILES_DIR);
        let store = AccountStore::new(&root, Arc::new(FakeCipher));
        (dir, root, store)
    }

    #[test]
    fn every_file_round_trips() {
        let (_d, _root, store) = setup();
        let steve = user('a', "token-a");
        store.write_auth_file(&steve.uuid, &auth_file("a")).unwrap();
        store.write_cached_user(&steve).unwrap();
        store
            .write_renewal_ms(&steve.uuid, 1_700_000_000_000)
            .unwrap();
        store.write_meta(&steve.uuid, &steve.name, 42).unwrap();

        assert_eq!(store.read_auth_file(&steve.uuid), Some(auth_file("a")));
        assert_eq!(store.read_cached_user(&steve.uuid), Some(steve.clone()));
        assert_eq!(store.read_renewal_ms(&steve.uuid), 1_700_000_000_000);
        assert_eq!(
            store.list(),
            vec![Entry {
                uuid: steve.uuid.clone(),
                display_name: steve.name.clone(),
                last_used_ms: 42
            }]
        );
    }

    #[test]
    fn credentials_are_encrypted_on_disk() {
        let (_d, root, store) = setup();
        let steve = user('a', "secret-token");
        store.write_auth_file(&steve.uuid, &auth_file("a")).unwrap();
        store.write_cached_user(&steve).unwrap();
        let raw_auth = fs::read(root.join(&steve.uuid).join(AUTH_FILE)).unwrap();
        let raw_user = fs::read_to_string(root.join(&steve.uuid).join(CACHED_USER_FILE)).unwrap();
        assert_ne!(raw_auth, auth_file("a"));
        assert!(raw_auth.starts_with(FAKE_PREFIX));
        assert!(!raw_user.contains("secret-token"));
    }

    #[test]
    fn writes_leave_no_temp_files_behind() {
        let (_d, root, store) = setup();
        let steve = user('a', "t");
        store.write_auth_file(&steve.uuid, &auth_file("a")).unwrap();
        store.write_cached_user(&steve).unwrap();
        store.write_renewal_ms(&steve.uuid, 1).unwrap();
        store.write_meta(&steve.uuid, &steve.name, 1).unwrap();
        for item in fs::read_dir(root.join(&steve.uuid)).unwrap() {
            let name = item.unwrap().file_name();
            assert!(!name.to_string_lossy().ends_with(".tmp"), "{name:?}");
        }
    }

    #[test]
    fn list_is_most_recently_used_first() {
        let (_d, _root, store) = setup();
        for d in ['a', 'b', 'c'] {
            store
                .write_auth_file(&uuid(d), &auth_file(&d.to_string()))
                .unwrap();
        }
        store.write_meta(&uuid('a'), "A", 10).unwrap();
        store.write_meta(&uuid('b'), "B", 30).unwrap();
        store.write_meta(&uuid('c'), "C", 20).unwrap();
        let order: Vec<String> = store.list().into_iter().map(|e| e.uuid).collect();
        assert_eq!(order, vec![uuid('b'), uuid('c'), uuid('a')]);
    }

    #[test]
    fn reads_a_folder_in_the_old_profile_archive_layout() {
        let (_d, root, store) = setup();
        let folder = root.join(uuid('a'));
        fs::create_dir_all(&folder).unwrap();
        fs::write(
            folder.join("player.mica"),
            FakeCipher.encrypt(&auth_file("a")).unwrap(),
        )
        .unwrap();
        fs::write(
            folder.join("profile.json"),
            format!(
                "{{\"uuid\":\"{}\",\"displayName\":\"Steve\",\"lastUsedMs\":7}}",
                uuid('a')
            ),
        )
        .unwrap();
        assert_eq!(
            store.list(),
            vec![Entry {
                uuid: uuid('a'),
                display_name: "Steve".into(),
                last_used_ms: 7
            }]
        );
        assert_eq!(store.read_auth_file(&uuid('a')), Some(auth_file("a")));
    }

    #[test]
    fn accepts_a_legacy_unencrypted_gzip_auth_file() {
        let (_d, root, store) = setup();
        let gzip = vec![0x1f, 0x8b, 8, 0, 1, 2, 3];
        let folder = root.join(uuid('a'));
        fs::create_dir_all(&folder).unwrap();
        fs::write(folder.join(AUTH_FILE), &gzip).unwrap();
        assert_eq!(store.read_auth_file(&uuid('a')), Some(gzip));
    }

    #[test]
    fn accepts_a_legacy_plain_decimal_renewal_timestamp() {
        assert_eq!(
            parse_renewal_timestamp("1735689600000", &FakeCipher),
            1_735_689_600_000
        );
        assert_eq!(parse_renewal_timestamp("not a number", &FakeCipher), 0);
        assert_eq!(parse_renewal_timestamp("  ", &FakeCipher), 0);
    }

    #[test]
    fn metadata_is_optional_for_listing() {
        let (_d, _root, store) = setup();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        assert_eq!(
            store.list(),
            vec![Entry {
                uuid: uuid('a'),
                display_name: String::new(),
                last_used_ms: 0
            }]
        );
    }

    #[test]
    fn corrupt_metadata_keeps_the_account() {
        let (_d, root, store) = setup();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        fs::write(root.join(uuid('a')).join(META_FILE), "{not json").unwrap();
        let ids: Vec<String> = store.list().into_iter().map(|e| e.uuid).collect();
        assert_eq!(ids, vec![uuid('a')]);
    }

    #[test]
    fn list_skips_folders_without_credentials_and_unsafe_names() {
        let (_d, root, store) = setup();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        fs::create_dir_all(root.join(uuid('b'))).unwrap();
        let odd = root.join("not-a-uuid!");
        fs::create_dir_all(&odd).unwrap();
        fs::write(odd.join(AUTH_FILE), [1u8]).unwrap();
        let ids: Vec<String> = store.list().into_iter().map(|e| e.uuid).collect();
        assert_eq!(ids, vec![uuid('a')]);
    }

    #[test]
    fn unsafe_uuids_cannot_escape_the_store() {
        let (dir, _root, store) = setup();
        for bad in ["..", "../x", "a/b", "a\\b", "", "zz"] {
            assert!(!is_safe_uuid(bad), "should refuse {bad}");
            assert!(store.write_auth_file(bad, &auth_file("x")).is_err());
            assert_eq!(store.read_auth_file(bad), None);
            assert!(!store.remove(bad));
        }
        assert!(!dir.path().join("x").exists());
        assert!(!is_safe_uuid(&"a".repeat(65)));
        assert!(is_safe_uuid(&"a".repeat(64)));
        assert!(is_safe_uuid("4C4C4544-0042"));
    }

    #[test]
    fn credentials_from_another_machine_are_refused() {
        let (_d, root, store) = setup();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        store.write_cached_user(&user('a', "t")).unwrap();
        store.write_renewal_ms(&uuid('a'), 5).unwrap();
        let elsewhere = AccountStore::new(&root, Arc::new(OtherMachine));
        assert_eq!(elsewhere.read_auth_file(&uuid('a')), None);
        assert_eq!(elsewhere.read_cached_user(&uuid('a')), None);
        assert_eq!(elsewhere.read_renewal_ms(&uuid('a')), 0);
    }

    #[test]
    fn a_cached_user_without_a_name_is_treated_as_missing() {
        let (_d, root, store) = setup();
        let folder = root.join(uuid('a'));
        fs::create_dir_all(&folder).unwrap();
        let json = format!("{{\"uuid\":\"{}\"}}", uuid('a'));
        fs::write(
            folder.join(CACHED_USER_FILE),
            encode_base64(&FakeCipher.encrypt(json.as_bytes()).unwrap()),
        )
        .unwrap();
        assert_eq!(store.read_cached_user(&uuid('a')), None);
    }

    #[test]
    fn missing_files_read_as_absent() {
        let (_d, _root, store) = setup();
        assert_eq!(store.read_auth_file(&uuid('a')), None);
        assert_eq!(store.read_cached_user(&uuid('a')), None);
        assert_eq!(store.read_renewal_ms(&uuid('a')), 0);
        assert!(store.list().is_empty());
    }

    #[test]
    fn remove_deletes_the_whole_folder() {
        let (_d, root, store) = setup();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        store.write_cached_user(&user('a', "t")).unwrap();
        fs::create_dir_all(root.join(uuid('a')).join("nested")).unwrap();
        assert!(store.remove(&uuid('a')));
        assert!(!root.join(uuid('a')).exists());
        assert!(
            store.remove(&uuid('a')),
            "removing an absent account succeeds"
        );
    }

    #[cfg(unix)]
    #[test]
    fn remove_does_not_follow_symlinks() {
        let (d, root, store) = setup();
        let outside = d.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        fs::write(outside.join("keep.txt"), "keep").unwrap();
        store.write_auth_file(&uuid('a'), &auth_file("a")).unwrap();
        std::os::unix::fs::symlink(&outside, root.join(uuid('a')).join("link")).unwrap();
        assert!(store.remove(&uuid('a')));
        assert!(outside.join("keep.txt").exists());
    }

    #[test]
    fn the_cached_user_json_matches_the_java_layout() {
        let (_d, root, store) = setup();
        let mut u = user('a', "tok");
        u.client_id = None;
        store.write_cached_user(&u).unwrap();
        let raw = fs::read_to_string(root.join(uuid('a')).join(CACHED_USER_FILE)).unwrap();
        let plain = FakeCipher
            .decrypt(&decode_base64(&raw).unwrap())
            .unwrap()
            .unwrap();
        assert_eq!(
            String::from_utf8(plain).unwrap(),
            format!(
                "{{\"uuid\":\"{}\",\"name\":\"Playera\",\"accessToken\":\"tok\",\"type\":\"msa\",\"xuid\":\"xuida\"}}",
                uuid('a')
            )
        );
    }

    #[test]
    fn renewal_timestamp_parsing_matches_java() {
        assert_eq!(parse_renewal_timestamp("", &FakeCipher), 0);
        assert_eq!(parse_renewal_timestamp("   ", &FakeCipher), 0);
        assert_eq!(parse_renewal_timestamp("not-a-number", &FakeCipher), 0);
        assert_eq!(parse_renewal_timestamp("-5", &FakeCipher), -5);
        // Base64-shaped, decodes too short for an envelope: falls back to the raw string.
        assert_eq!(
            parse_renewal_timestamp("12345678901234", &FakeCipher),
            12_345_678_901_234
        );
        assert_eq!(parse_renewal_timestamp("1234", &FakeCipher), 1234);
        let enc = encode_base64(&FakeCipher.encrypt(b"1700000000123").unwrap());
        assert_eq!(
            parse_renewal_timestamp(&enc, &FakeCipher),
            1_700_000_000_123
        );
    }

    #[test]
    fn real_cipher_renewal_timestamp_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let cipher = mmcl_secure::MachineCipher::with_source(Arc::new(
            mmcl_secure::FixedKeySource("u|Linux|unknown|install:00".into()),
        ));
        let store = AccountStore::new(dir.path().join(PROFILES_DIR), Arc::new(cipher));
        store
            .write_renewal_ms(&uuid('a'), 1_700_000_000_999)
            .unwrap();
        assert_eq!(store.read_renewal_ms(&uuid('a')), 1_700_000_000_999);
    }
}
