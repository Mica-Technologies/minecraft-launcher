// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The editor's document model (`ModPackDocument`, `ModPackFileEntry`).
//!
//! The document wraps the raw, order-preserving JSON object rather than [`PackManifest`],
//! so keys the launcher does not model (and future keys) survive an edit untouched, and it
//! prints with Gson's pretty printer so a file saved here diffs cleanly against one saved by
//! the Java editor.
//!
//! Note the deliberate difference from the runtime model: an absent `clientReq` /
//! `serverReq` reads as **true** here (the editor's long-standing default) but as false in
//! [`crate::ManifestFile`]. [`ModPackDocument::write_file_list`] always writes both flags, so
//! a manifest saved by the editor means the same thing to both readers.

use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};

use crate::error::ManifestParseError;
use crate::gson;
use crate::lenient::{coerce_bool, coerce_string, parse_java_double};
use crate::pack::PackManifest;

/// `manifestFormat` stamped on new documents. Format 2 added per-entry `modrinthSlug`.
pub const CURRENT_MANIFEST_FORMAT: i64 = 2;
/// Manifest key of the pack's display name.
pub const KEY_PACK_NAME: &str = "packName";
/// Manifest key of the pack's version.
pub const KEY_PACK_VERSION: &str = "packVersion";
/// Manifest key of the minimum RAM in GB (a string).
pub const KEY_PACK_MIN_RAM_GB: &str = "packMinRAMGB";
/// Manifest key of the scan-exclusion list.
pub const KEY_SCAN_EXCLUSIONS: &str = "packScanExclusions";
/// File-name prefix of manifests written by [`ModPackDocument::write_local_manifest`].
pub const LOCAL_MANIFEST_PREFIX: &str = "created-";

/// Errors from document operations. Each maps to a localization key.
#[derive(Debug, thiserror::Error)]
pub enum DocError {
    /// The text is not JSON, or not an object.
    #[error(transparent)]
    Parse(#[from] ManifestParseError),
    /// A fork needs a non-blank name.
    #[error("a fork needs a non-blank name")]
    ForkNameBlank,
    /// The pack name has no letters or digits to build a file name from.
    #[error("the pack name has no characters usable in a file name")]
    NameUnusable,
    /// The target file exists and overwriting was refused.
    #[error("a manifest already exists at {0}")]
    AlreadyExists(PathBuf),
    /// Writing the file failed.
    #[error("could not write the manifest: {0}")]
    Io(#[from] io::Error),
}

impl DocError {
    /// Localization key for showing this error to a user.
    pub fn key(&self) -> &'static str {
        match self {
            DocError::Parse(e) => e.key(),
            DocError::ForkNameBlank => "editor.document.error.forkNameBlank",
            DocError::NameUnusable => "editor.document.error.nameUnusable",
            DocError::AlreadyExists(_) => "editor.document.error.alreadyExists",
            DocError::Io(_) => "editor.document.error.io",
        }
    }

    /// Arguments for [`Self::key`].
    pub fn args(&self) -> Vec<String> {
        match self {
            DocError::Parse(e) => e.args(),
            DocError::AlreadyExists(p) => vec![p.display().to_string()],
            DocError::Io(e) => vec![e.to_string()],
            _ => Vec::new(),
        }
    }
}

/// One of the manifest's file lists and the shape of its entries.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum FileList {
    Mods,
    Configs,
    ResourcePacks,
    ShaderPacks,
    InitialFiles,
}

impl FileList {
    /// Every list, in manifest order.
    pub const ALL: [FileList; 5] = [
        FileList::Mods,
        FileList::Configs,
        FileList::ResourcePacks,
        FileList::ShaderPacks,
        FileList::InitialFiles,
    ];

    /// The manifest key, e.g. `packMods`.
    pub fn key(self) -> &'static str {
        match self {
            FileList::Mods => "packMods",
            FileList::Configs => "packConfigs",
            FileList::ResourcePacks => "packResourcePacks",
            FileList::ShaderPacks => "packShaderPacks",
            FileList::InitialFiles => "packInitialFiles",
        }
    }

    /// Whether entries carry a `name` (mods only).
    pub fn has_name(self) -> bool {
        self == FileList::Mods
    }

    /// Whether entries carry `clientReq` / `serverReq`.
    pub fn has_side_flags(self) -> bool {
        !matches!(self, FileList::ResourcePacks | FileList::ShaderPacks)
    }

    /// Localization key naming the list in validation messages.
    pub fn label_key(self) -> &'static str {
        match self {
            FileList::Mods => "editor.validate.label.mods",
            FileList::Configs => "editor.validate.label.configs",
            FileList::ResourcePacks => "editor.validate.label.resourcePacks",
            FileList::ShaderPacks => "editor.validate.label.shaderPacks",
            FileList::InitialFiles => "editor.validate.label.initialFiles",
        }
    }
}

/// One editable file-list entry. Exactly the state that round-trips through the manifest.
///
/// One hash is primary (`hash` + `hash_type`); the other usable slots are kept in
/// `extra_hashes` so an edit cycle preserves them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FileEntry {
    name: String,
    remote: String,
    local: String,
    hash: String,
    hash_type: String,
    client_req: bool,
    server_req: bool,
    modrinth_slug: String,
    extra_hashes: BTreeMap<String, String>,
}

impl Default for FileEntry {
    fn default() -> Self {
        FileEntry {
            name: String::new(),
            remote: String::new(),
            local: String::new(),
            hash: String::new(),
            hash_type: "sha1".into(),
            client_req: true,
            server_req: true,
            modrinth_slug: String::new(),
            extra_hashes: BTreeMap::new(),
        }
    }
}

impl FileEntry {
    /// A fully populated entry. A `None` hash type means `sha1`.
    pub fn new(
        name: impl Into<String>,
        remote: impl Into<String>,
        local: impl Into<String>,
        hash: impl Into<String>,
        hash_type: Option<&str>,
        client_req: bool,
        server_req: bool,
    ) -> Self {
        FileEntry {
            name: name.into(),
            remote: remote.into(),
            local: local.into(),
            hash: hash.into(),
            hash_type: hash_type.unwrap_or("sha1").to_string(),
            client_req,
            server_req,
            ..Default::default()
        }
    }

    pub fn name(&self) -> &str {
        &self.name
    }
    pub fn set_name(&mut self, v: impl Into<String>) {
        self.name = v.into();
    }
    pub fn remote(&self) -> &str {
        &self.remote
    }
    pub fn set_remote(&mut self, v: impl Into<String>) {
        self.remote = v.into();
    }
    pub fn local(&self) -> &str {
        &self.local
    }
    pub fn set_local(&mut self, v: impl Into<String>) {
        self.local = v.into();
    }
    /// The primary hash, or `""` when there is none.
    pub fn hash(&self) -> &str {
        &self.hash
    }
    pub fn set_hash(&mut self, v: impl Into<String>) {
        self.hash = v.into();
    }
    /// The primary hash's algorithm: `sha1` (default), `md5` or `sha256`.
    pub fn hash_type(&self) -> &str {
        &self.hash_type
    }
    /// Sets the primary algorithm; `None` restores `sha1`.
    pub fn set_hash_type(&mut self, v: Option<&str>) {
        self.hash_type = v.unwrap_or("sha1").to_string();
    }
    pub fn client_req(&self) -> bool {
        self.client_req
    }
    pub fn set_client_req(&mut self, v: bool) {
        self.client_req = v;
    }
    pub fn server_req(&self) -> bool {
        self.server_req
    }
    pub fn set_server_req(&mut self, v: bool) {
        self.server_req = v;
    }
    /// Modrinth project slug, or `""`.
    pub fn modrinth_slug(&self) -> &str {
        &self.modrinth_slug
    }
    pub fn set_modrinth_slug(&mut self, v: impl Into<String>) {
        self.modrinth_slug = v.into();
    }

    /// Stores a non-primary hash under the lower-cased algorithm; a `None` or blank value
    /// clears that slot.
    pub fn put_extra_hash(&mut self, algo: &str, value: Option<&str>) {
        let key = algo.to_lowercase();
        match value.filter(|v| !v.trim().is_empty()) {
            Some(v) => {
                self.extra_hashes.insert(key, v.to_string());
            }
            None => {
                self.extra_hashes.remove(&key);
            }
        }
    }

    /// The stored non-primary hash for an algorithm (case-insensitive).
    pub fn extra_hash(&self, algo: &str) -> Option<&str> {
        self.extra_hashes
            .get(&algo.to_lowercase())
            .map(String::as_str)
    }

    /// Every stored non-primary hash, keyed by lower-case algorithm.
    pub fn extra_hashes(&self) -> &BTreeMap<String, String> {
        &self.extra_hashes
    }
}

/// Resolves a localization key and its arguments to display text. Production passes the
/// localizer; tests pass an echo so assertions stay locale-independent.
pub type MessageResolver<'a> = &'a dyn Fn(&str, &[String]) -> String;

/// Gson's `JsonElement.getAsString` for scalar reads: primitives as text, a one-element
/// array as its element, anything else (null, objects, longer arrays) as `None`.
fn element_string(v: &Value) -> Option<String> {
    match v {
        Value::Array(items) if items.len() == 1 => element_string(&items[0]),
        other => coerce_string(other),
    }
}

/// Gson's `getAsBoolean`: booleans, `parseBoolean` of text, a one-element array's element.
fn element_bool(v: &Value) -> Option<bool> {
    match v {
        Value::Bool(b) => Some(*b),
        Value::String(s) => Some(s.eq_ignore_ascii_case("true")),
        Value::Number(_) => Some(false),
        Value::Array(items) if items.len() == 1 => element_bool(&items[0]),
        _ => None,
    }
}

/// Trimmed, non-blank lines of `text` (split on `\n`).
fn non_blank_lines(text: &str) -> Vec<String> {
    text.split('\n')
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(str::to_string)
        .collect()
}

/// A hash slot's value when it is a usable hash (non-blank, not `-1`, readable as text).
fn read_hash(obj: &Map<String, Value>, key: &str) -> Option<String> {
    let v = element_string(obj.get(key)?)?;
    (!v.trim().is_empty() && v != "-1").then_some(v)
}

/// A headless, editable modpack manifest.
#[derive(Clone, Debug, PartialEq)]
pub struct ModPackDocument {
    doc: Map<String, Value>,
}

impl ModPackDocument {
    /// A new pack: empty name, version `1.0.0`, 2 GB minimum RAM, Forge, every list empty.
    pub fn blank() -> Self {
        let mut doc = Map::new();
        doc.insert(
            "manifestFormat".into(),
            Value::from(CURRENT_MANIFEST_FORMAT),
        );
        for (key, value) in [
            (KEY_PACK_NAME, Value::from("")),
            (KEY_PACK_VERSION, Value::from("1.0.0")),
            ("packURL", Value::from("")),
            ("packUnstable", Value::from(false)),
            ("packCustomDiscordRpc", Value::from(false)),
            (KEY_PACK_MIN_RAM_GB, Value::from("2")),
            ("packLogoURL", Value::from("")),
            ("packLogoSha1", Value::from("")),
            ("packBackgroundURL", Value::from("")),
            ("packBackgroundSha1", Value::from("")),
            ("packForgeURL", Value::from("")),
            ("packForgeHash", Value::from("")),
            ("packModLoader", Value::from(crate::pack::MOD_LOADER_FORGE)),
            ("packModLoaderURL", Value::from("")),
            ("packModLoaderHash", Value::from("")),
            ("packMinecraftVersion", Value::from("")),
            (KEY_SCAN_EXCLUSIONS, Value::Array(Vec::new())),
        ] {
            doc.insert(key.into(), value);
        }
        for list in FileList::ALL {
            doc.insert(list.key().into(), Value::Array(Vec::new()));
        }
        ModPackDocument { doc }
    }

    /// Parses manifest text (leniently, as Gson does). Fails when it is not JSON or not an
    /// object (empty text included).
    pub fn from_json(text: &str) -> Result<Self, DocError> {
        if text.trim().is_empty() {
            return Err(ManifestParseError::NotAnObject.into());
        }
        let (doc, _) = gson::parse_object(text)?;
        Ok(ModPackDocument { doc })
    }

    /// Adopts an already-parsed manifest object.
    pub fn wrapping(doc: Map<String, Value>) -> Self {
        ModPackDocument { doc }
    }

    /// The underlying object, for keys this type does not model.
    pub fn json(&self) -> &Map<String, Value> {
        &self.doc
    }

    /// Mutable access to the underlying object.
    pub fn json_mut(&mut self) -> &mut Map<String, Value> {
        &mut self.doc
    }

    /// Gives back the underlying object.
    pub fn into_json(self) -> Map<String, Value> {
        self.doc
    }

    /// The document as a JSON value.
    pub fn to_value(&self) -> Value {
        Value::Object(self.doc.clone())
    }

    /// Gson-identical pretty JSON (two-space indent, HTML-safe escaping, no trailing newline).
    pub fn to_pretty_json(&self) -> String {
        gson::to_pretty_string(&Value::Object(self.doc.clone()))
    }

    /// The runtime model of this document.
    pub fn to_manifest(&self) -> PackManifest {
        PackManifest::from_value(&self.to_value()).unwrap_or_default()
    }

    /// A scalar as text; `""` when absent, null, or not a scalar.
    pub fn get_string(&self, key: &str) -> String {
        self.doc
            .get(key)
            .and_then(element_string)
            .unwrap_or_default()
    }

    /// Writes a string value.
    pub fn put_string(&mut self, key: &str, value: &str) {
        self.doc.insert(key.into(), Value::from(value));
    }

    /// A scalar as a boolean; false when absent or null.
    pub fn get_bool(&self, key: &str) -> bool {
        self.doc.get(key).and_then(element_bool).unwrap_or(false)
    }

    /// Writes a boolean value.
    pub fn put_bool(&mut self, key: &str, value: bool) {
        self.doc.insert(key.into(), Value::from(value));
    }

    /// A `string | string[]` value as newline-joined lines (null array elements skipped);
    /// `""` when absent or null.
    pub fn get_string_or_array_lines(&self, key: &str) -> String {
        match self.doc.get(key) {
            None | Some(Value::Null) => String::new(),
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(element_string)
                .collect::<Vec<_>>()
                .join("\n"),
            Some(other) => element_string(other).unwrap_or_default(),
        }
    }

    /// Writes multi-line text as `string | string[]`: lines trimmed, blanks dropped; one line
    /// is a bare string, several an array, none `""`.
    pub fn put_string_or_array(&mut self, key: &str, text: &str) {
        let mut lines = non_blank_lines(text);
        let value = match lines.len() {
            0 => Value::from(""),
            1 => Value::from(lines.remove(0)),
            _ => Value::Array(lines.into_iter().map(Value::from).collect()),
        };
        self.doc.insert(key.into(), value);
    }

    /// An always-array value as newline-joined lines; `""` when absent or not an array.
    pub fn get_array_lines(&self, key: &str) -> String {
        match self.doc.get(key) {
            Some(Value::Array(items)) => items
                .iter()
                .filter_map(element_string)
                .collect::<Vec<_>>()
                .join("\n"),
            _ => String::new(),
        }
    }

    /// Writes multi-line text as an array (always an array; empty input is `[]`).
    pub fn put_array_lines(&mut self, key: &str, text: &str) {
        let items = non_blank_lines(text).into_iter().map(Value::from).collect();
        self.doc.insert(key.into(), Value::Array(items));
    }

    /// Reads a file list. Absent or non-array is empty; non-object elements are skipped.
    /// The primary hash is the strongest usable of sha256 > sha1 > md5 and the others become
    /// extra hashes. Side flags default to true when absent (and are always true for lists
    /// without flags).
    pub fn read_file_list(&self, list: FileList) -> Vec<FileEntry> {
        let Some(Value::Array(items)) = self.doc.get(list.key()) else {
            return Vec::new();
        };
        items
            .iter()
            .filter_map(Value::as_object)
            .map(|obj| {
                let text = |k: &str| obj.get(k).and_then(element_string).unwrap_or_default();
                // An absent flag is the editor's default, true. A flag that is present reads
                // exactly as the runtime model reads it (so `null` is false), because the
                // editor always writes both flags back: reading `null` as true would turn a
                // server-only mod into a client download on the next save.
                let flag = |k: &str| !list.has_side_flags() || obj.get(k).is_none_or(coerce_bool);
                let sha1 = read_hash(obj, "sha1");
                let md5 = read_hash(obj, "md5");
                let sha256 = read_hash(obj, "sha256");
                let (hash, hash_type) = if let Some(h) = &sha256 {
                    (h.clone(), "sha256")
                } else if let Some(h) = &sha1 {
                    (h.clone(), "sha1")
                } else if let Some(h) = &md5 {
                    (h.clone(), "md5")
                } else {
                    (String::new(), "sha1")
                };
                let mut entry = FileEntry::new(
                    if list.has_name() {
                        text("name")
                    } else {
                        String::new()
                    },
                    text("remote"),
                    text("local"),
                    hash,
                    Some(hash_type),
                    flag("clientReq"),
                    flag("serverReq"),
                );
                for (algo, value) in [("sha1", &sha1), ("md5", &md5), ("sha256", &sha256)] {
                    if hash_type != algo {
                        entry.put_extra_hash(algo, value.as_deref());
                    }
                }
                if obj.contains_key("modrinthSlug") {
                    entry.set_modrinth_slug(text("modrinthSlug"));
                }
                entry
            })
            .collect()
    }

    /// Replaces a file list. Each entry writes `name` (mods only), `remote`, `local`, all
    /// three hash slots (primary in its slot, the others from extras, `"-1"` when empty), the
    /// side flags (lists that have them) and `modrinthSlug` (when set), in that order.
    pub fn write_file_list(&mut self, list: FileList, entries: &[FileEntry]) {
        let array = entries
            .iter()
            .map(|e| {
                let mut obj = Map::new();
                if list.has_name() {
                    obj.insert("name".into(), Value::from(e.name()));
                }
                obj.insert("remote".into(), Value::from(e.remote()));
                obj.insert("local".into(), Value::from(e.local()));
                let ht = e.hash_type().to_lowercase();
                let primary = (!e.hash().is_empty()).then(|| e.hash());
                let sha1 = if ht == "md5" || ht == "sha256" {
                    e.extra_hash("sha1")
                } else {
                    primary
                };
                let md5 = if ht == "md5" {
                    primary
                } else {
                    e.extra_hash("md5")
                };
                let sha256 = if ht == "sha256" {
                    primary
                } else {
                    e.extra_hash("sha256")
                };
                for (key, value) in [("sha1", sha1), ("md5", md5), ("sha256", sha256)] {
                    let v = value.filter(|v| !v.trim().is_empty()).unwrap_or("-1");
                    obj.insert(key.into(), Value::from(v));
                }
                if list.has_side_flags() {
                    obj.insert("clientReq".into(), Value::from(e.client_req()));
                    obj.insert("serverReq".into(), Value::from(e.server_req()));
                }
                if !e.modrinth_slug().is_empty() {
                    obj.insert("modrinthSlug".into(), Value::from(e.modrinth_slug()));
                }
                Value::Object(obj)
            })
            .collect();
        self.doc.insert(list.key().into(), Value::Array(array));
    }

    /// An independent copy under a new (trimmed) name with the minor version bumped.
    /// Everything else carries over verbatim.
    pub fn fork_of(&self, new_name: &str) -> Result<Self, DocError> {
        let name = new_name.trim();
        if name.is_empty() {
            return Err(DocError::ForkNameBlank);
        }
        let mut fork = self.clone();
        fork.put_string(KEY_PACK_NAME, name);
        let bumped = bump_version(Some(&self.get_string(KEY_PACK_VERSION)), 1);
        fork.put_string(KEY_PACK_VERSION, &bumped);
        Ok(fork)
    }

    /// The mod list.
    pub fn mods(&self) -> Vec<FileEntry> {
        self.read_file_list(FileList::Mods)
    }

    /// Appends a mod unless its local path is blank or equals (ignoring case) an existing
    /// mod's local path.
    pub fn add_mod(&mut self, entry: FileEntry) -> bool {
        if entry.local().trim().is_empty() {
            return false;
        }
        let mut mods = self.mods();
        if mods
            .iter()
            .any(|m| crate::eq_ignore_case(m.local(), entry.local()))
        {
            return false;
        }
        mods.push(entry);
        self.write_file_list(FileList::Mods, &mods);
        true
    }

    /// Removes the first mod whose local path, name or Modrinth slug equals the trimmed
    /// identifier, ignoring case. At most one is removed.
    pub fn remove_mod(&mut self, identifier: &str) -> bool {
        let wanted = identifier.trim();
        if wanted.is_empty() {
            return false;
        }
        let mut mods = self.mods();
        let Some(index) = mods.iter().position(|m| {
            crate::eq_ignore_case(wanted, m.local())
                || crate::eq_ignore_case(wanted, m.name())
                || crate::eq_ignore_case(wanted, m.modrinth_slug())
        }) else {
            return false;
        };
        mods.remove(index);
        self.write_file_list(FileList::Mods, &mods);
        true
    }

    /// The part of a pack name that survives into its local manifest's file name: its ASCII
    /// letters and digits, lower-cased. Equal keys mean the same file.
    pub fn local_manifest_key_of(pack_name: Option<&str>) -> String {
        crate::ascii_alnum_only(pack_name.unwrap_or("")).to_ascii_lowercase()
    }

    /// Where [`Self::write_local_manifest`] would write: `dir/created-<key>.json`.
    pub fn local_manifest_path_in(&self, dir: &Path) -> Result<PathBuf, DocError> {
        let key = Self::local_manifest_key_of(Some(&self.get_string(KEY_PACK_NAME)));
        if key.is_empty() {
            return Err(DocError::NameUnusable);
        }
        Ok(dir.join(format!("{LOCAL_MANIFEST_PREFIX}{key}.json")))
    }

    /// Writes the pretty JSON into `dir` (created if missing) and returns its `file:` URL,
    /// which the pack manager installs from. With `overwrite` false an existing file is an
    /// [`DocError::AlreadyExists`] error, so a near-duplicate name cannot replace another
    /// pack's manifest.
    pub fn write_local_manifest(&self, dir: &Path, overwrite: bool) -> Result<String, DocError> {
        let path = self.local_manifest_path_in(dir)?;
        fs::create_dir_all(dir)?;
        let text = self.to_pretty_json();
        if overwrite {
            fs::write(&path, text.as_bytes())?;
        } else {
            let mut file = match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(f) => f,
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                    return Err(DocError::AlreadyExists(path));
                }
                Err(e) => return Err(e.into()),
            };
            if let Err(e) = file
                .write_all(text.as_bytes())
                .and_then(|()| file.sync_all())
            {
                // Do not leave a half-written file behind: it would make every later save of
                // this pack fail with "already exists".
                drop(file);
                let _ = fs::remove_file(&path);
                return Err(e.into());
            }
        }
        let absolute = std::path::absolute(&path)?;
        url::Url::from_file_path(&absolute)
            .map(|u| u.to_string())
            .map_err(|()| {
                io::Error::new(io::ErrorKind::InvalidInput, "path is not absolute").into()
            })
    }

    /// The pack name with everything outside ASCII `[A-Za-z0-9]` removed (case kept), to
    /// seed a save dialog.
    pub fn sanitized_file_base_name(&self) -> String {
        crate::ascii_alnum_only(&self.get_string(KEY_PACK_NAME))
    }

    /// Validation messages, in order: required name and version; a minimum RAM that does
    /// not parse as a number; blank remote / local per file-list entry; finally anything the
    /// Java launcher's Gson binding would reject. Empty means valid.
    pub fn validate(&self, resolve: MessageResolver<'_>) -> Vec<String> {
        let mut issues = Vec::new();
        for (key, label_key) in [
            (KEY_PACK_NAME, "editor.validate.label.packName"),
            (KEY_PACK_VERSION, "editor.validate.label.packVersion"),
        ] {
            let present = self
                .doc
                .get(key)
                .and_then(element_string)
                .is_some_and(|v| !v.trim().is_empty());
            if !present {
                let label = resolve(label_key, &[]);
                issues.push(resolve("editor.validate.required", &[label]));
            }
        }

        match self.doc.get(KEY_PACK_MIN_RAM_GB) {
            None | Some(Value::Null) => {}
            Some(v) => {
                if element_string(v)
                    .and_then(|s| parse_java_double(&s))
                    .is_none()
                {
                    issues.push(resolve("editor.validate.invalidMinRam", &[]));
                }
            }
        }

        for list in FileList::ALL {
            let label = resolve(list.label_key(), &[]);
            for (i, entry) in self.read_file_list(list).iter().enumerate() {
                let entry_label = if list.has_name() && !entry.name().trim().is_empty() {
                    format!("{label} \"{}\"", entry.name())
                } else {
                    format!("{label} #{}", i + 1)
                };
                if entry.remote().trim().is_empty() {
                    issues.push(resolve(
                        "editor.validate.remoteEmpty",
                        std::slice::from_ref(&entry_label),
                    ));
                }
                if entry.local().trim().is_empty() {
                    issues.push(resolve("editor.validate.localEmpty", &[entry_label]));
                }
            }
        }

        if let Some(problem) = gson::compat_problems(&self.to_value()).into_iter().next() {
            issues.push(resolve("editor.validate.roundTripFailed", &[problem]));
        }
        issues
    }
}

/// Bumps one segment of a dotted version and zeroes every segment to its right. Blank is
/// `0.0.0`; non-numeric segments are 0; a position past the end changes nothing. The result
/// always has exactly three segments (0 major, 1 minor, 2 patch).
pub fn bump_version(current: Option<&str>, position: usize) -> String {
    let current = match current {
        Some(c) if !c.trim().is_empty() => c,
        _ => "0.0.0",
    };
    let mut parts: Vec<&str> = current.split('.').collect();
    // Java's String.split drops trailing empty strings.
    while parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    let mut segments: Vec<i32> = parts.iter().map(|p| p.parse().unwrap_or(0)).collect();
    if segments.len() < 3 {
        segments.resize(3, 0);
    }
    if position < segments.len() {
        segments[position] = segments[position].wrapping_add(1);
        for s in &mut segments[position + 1..] {
            *s = 0;
        }
    }
    format!("{}.{}.{}", segments[0], segments[1], segments[2])
}

/// The first trimmed non-blank line, or `""`.
pub fn first_line(text: Option<&str>) -> String {
    text.and_then(|t| t.split('\n').map(str::trim).find(|l| !l.is_empty()))
        .unwrap_or("")
        .to_string()
}

#[cfg(test)]
mod tests;
