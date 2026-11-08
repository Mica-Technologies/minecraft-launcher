// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The supplemental heuristic scanner (port of Java's `SupplementalScanner`).
//!
//! Runs beside the Nekodetector pass over every `.jar` in the pack folder and flags embedded
//! executables, natives in odd places, Discord webhook and paste-host URLs, references to other
//! launchers' credential files, hard-coded IPv4 literals, and the AWT Robot + Clipboard
//! clipboard-stealer pattern. HIGH findings block a launch; MEDIUM ones are warnings.
//!
//! The [`Kind`] names are part of the public manifest schema (`packScanAcknowledgements`) and
//! serialize as the exact Java enum names.

use std::fmt;
use std::fs::File;
use std::io::{self, Read, Seek};
use std::path::{Component, Path, PathBuf};
use std::sync::LazyLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use mmcl_i18n::tf;
use mmcl_net::CancelToken;
use parking_lot::Mutex;
use rayon::prelude::*;
use regex::Regex;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zip::ZipArchive;

use crate::classfile::{self, Operand, op};
use crate::exclusion;
use crate::walk::{self, WalkItem};

/// Entry suffixes flagged as embedded executables / scripts. `.js` / `.jse` are deliberately
/// absent: FAWE, MaryTTS and KubeJS ship them legitimately and the JVM never auto-runs them.
pub const FORBIDDEN_EMBEDDED_SUFFIXES: [&str; 13] = [
    ".exe", ".scr", ".com", ".bat", ".cmd", ".ps1", ".psm1", ".vbs", ".vbe", ".wsf", ".msi",
    ".lnk", ".reg",
];

/// Substrings that mark a native binary's path inside a jar as conventional.
pub const LEGIT_NATIVE_PATH_PREFIXES: [&str; 47] = [
    "meta-inf/native",
    "natives/",
    "native/",
    "jni/",
    "lib/",
    "libs/",
    "assets/",
    "org/lwjgl/",
    "lwjgl/native",
    "com/sun/jna/",
    "linux/",
    "windows/",
    "macos/",
    "mac/",
    "osx/",
    "freebsd/",
    "netbsd/",
    "openbsd/",
    "solaris/",
    "aix/",
    "x86/",
    "x86_64/",
    "x64/",
    "i686/",
    "i386/",
    "aarch64/",
    "arm64/",
    "arm/",
    "armv7/",
    "armv6/",
    "amd64/",
    "ppc/",
    "ppc64/",
    "ppc64le/",
    "riscv64/",
    "loongarch64/",
    "mips/",
    "mips64/",
    "s390x/",
    "sparc/",
    "darwin-",
    "linux-",
    "windows-",
    "macos-",
    "-macosx-",
    "-linux-",
    "-windows-",
];

/// Native-binary suffixes checked by the "native outside a known path" rule.
pub const NATIVE_BINARY_SUFFIXES: [&str; 4] = [".dll", ".so", ".dylib", ".jnilib"];

/// Other launchers' credential stores. `usercache.json` is deliberately absent: it is the vanilla
/// server's username cache. Java iterates a `Set`, so its order is unspecified; this order is
/// fixed.
pub const LAUNCHER_CREDENTIAL_FILES: [&str; 4] = [
    "launcher_profiles.json",
    "launcher_accounts.json",
    "launcher_msa_credentials.bin",
    "credentials.json",
];

/// Folders the launcher itself fills with hash-verified content; never scanned, and a manifest
/// cannot remove them.
pub const BUILT_IN_EXCLUSIONS: [&str; 3] = ["libraries", "bin", "runtime"];

/// Inflated-size ceiling for one jar entry read (zip-bomb guard).
pub const MAX_SCAN_ENTRY_BYTES: u64 = 256 * 1024 * 1024;

/// How long [`scan_folder`] keeps starting jars before giving up (Java's `awaitTermination`).
pub const SCAN_TIMEOUT: Duration = Duration::from_secs(30 * 60);

const AWT_ROBOT_OWNER: &str = "java/awt/Robot";
const CLIPBOARD_OWNER: &str = "java/awt/datatransfer/Clipboard";

const DISCORD_WEBHOOK_SRC: &str =
    r"https?://(?:[a-z0-9.-]*\.)?discord(?:app)?\.com/api/webhooks/[\w-]+/[\w-]+";

/// The Java pattern source texts; the message quotes the one that matched.
const SUSPICIOUS_HOST_SRCS: [&str; 7] = [
    r"https?://(?:www\.)?pastebin\.com/raw/[\w-]+",
    r"https?://(?:www\.)?anonfiles\.com/[\w./?=&-]+",
    r"https?://(?:www\.)?transfer\.sh/[\w./?=&-]+",
    r"https?://(?:www\.)?tmpfiles\.org/[\w./?=&-]+",
    r"https?://(?:www\.)?bashupload\.com/[\w./?=&-]+",
    r"https?://(?:www\.)?file\.io/[\w./?=&-]+",
    r"https?://(?:www\.)?gofile\.io/[\w./?=&-]+",
];

/// Java's `CASE_INSENSITIVE` without `UNICODE_CASE` folds ASCII only, and its `\w` is ASCII:
/// `(?i-u)` gives exactly that.
fn java_regex(src: &str) -> Regex {
    Regex::new(&format!("(?i-u){src}")).expect("built-in scanner pattern")
}

static DISCORD_WEBHOOK: LazyLock<Regex> = LazyLock::new(|| java_regex(DISCORD_WEBHOOK_SRC));
static SUSPICIOUS_HOSTS: LazyLock<Vec<(Regex, &'static str)>> = LazyLock::new(|| {
    SUSPICIOUS_HOST_SRCS
        .iter()
        .map(|s| (java_regex(s), *s))
        .collect()
});

/// How serious a finding is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Severity {
    /// Blocks the launch.
    High,
    /// Shown as a warning.
    Medium,
}

impl Severity {
    /// The Java enum name.
    pub fn as_str(self) -> &'static str {
        match self {
            Severity::High => "HIGH",
            Severity::Medium => "MEDIUM",
        }
    }
}

impl fmt::Display for Severity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// What a finding is about. The names are manifest schema: never rename them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Kind {
    EmbeddedExecutable,
    NativeOutsideKnownPath,
    DiscordWebhookUrl,
    SuspiciousHostUrl,
    LauncherCredentialFileRef,
    Ipv4Literal,
    ClipboardStealerPattern,
}

impl Kind {
    /// Every kind, in Java's declaration order.
    pub const ALL: [Kind; 7] = [
        Kind::EmbeddedExecutable,
        Kind::NativeOutsideKnownPath,
        Kind::DiscordWebhookUrl,
        Kind::SuspiciousHostUrl,
        Kind::LauncherCredentialFileRef,
        Kind::Ipv4Literal,
        Kind::ClipboardStealerPattern,
    ];

    /// The Java enum name (`EMBEDDED_EXECUTABLE`, ...).
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::EmbeddedExecutable => "EMBEDDED_EXECUTABLE",
            Kind::NativeOutsideKnownPath => "NATIVE_OUTSIDE_KNOWN_PATH",
            Kind::DiscordWebhookUrl => "DISCORD_WEBHOOK_URL",
            Kind::SuspiciousHostUrl => "SUSPICIOUS_HOST_URL",
            Kind::LauncherCredentialFileRef => "LAUNCHER_CREDENTIAL_FILE_REF",
            Kind::Ipv4Literal => "IPV4_LITERAL",
            Kind::ClipboardStealerPattern => "CLIPBOARD_STEALER_PATTERN",
        }
    }

    /// Parses a Java enum name (exact, as `Enum.valueOf`).
    pub fn from_name(name: &str) -> Option<Kind> {
        Kind::ALL.into_iter().find(|k| k.as_str() == name)
    }
}

impl fmt::Display for Kind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

/// One supplemental-scanner finding.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finding {
    pub severity: Severity,
    pub kind: Kind,
    /// The jar the finding is in, as walked.
    pub file: PathBuf,
    /// Lowercase hex SHA-256 of the whole jar, or `None` when it could not be computed (then no
    /// acknowledgement can match).
    pub file_sha256: Option<String>,
    /// Lowercase hex SHA-256 of the entry (class or file) the finding is about, when known.
    pub inner_sha256: Option<String>,
    /// Entry name, `<class>.<method>`, or class internal name.
    pub locator: String,
    /// English diagnostic text (as in Java; it is also what acknowledgement hints quote).
    pub message: String,
}

impl fmt::Display for Finding {
    /// `[HIGH] <jar path> — <message>`, exactly as Java's `Finding.toString()`.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "[{}] {} \u{2014} {}",
            self.severity,
            self.file.display(),
            self.message
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Folder scan
// ---------------------------------------------------------------------------------------------

/// Scans every non-excluded `.jar` under `root` on `threads` worker threads (at least one).
///
/// `excludes` is the untrusted manifest list: it goes through
/// [`exclusion::filter_untrusted`] and [`normalize_exclusions`], then [`BUILT_IN_EXCLUSIONS`]
/// are appended. A jar that fails to open logs a silent warning. Unreadable entries below the
/// root are logged and skipped; an unreadable root is an `Err`. Cancelling (checked between
/// jars) returns `ErrorKind::Interrupted`. After [`SCAN_TIMEOUT`] no further jars are started
/// and the findings so far are returned, as Java does.
pub fn scan_folder<S: AsRef<str>>(
    root: &Path,
    excludes: &[S],
    threads: usize,
    cancel: &CancelToken,
) -> io::Result<Vec<Finding>> {
    let mut exclusions = normalize_exclusions(&exclusion::filter_untrusted(excludes));
    exclusions.extend(BUILT_IN_EXCLUSIONS.iter().map(|s| (*s).to_owned()));

    let mut jars = Vec::new();
    let mut root_error: Option<io::Error> = None;
    walk::walk(root, &exclusions, |item| match item {
        WalkItem::File(path) => {
            if has_jar_suffix(&path) {
                jars.push(path);
            }
        }
        WalkItem::Error {
            path,
            is_root,
            error,
            ..
        } => {
            if is_root {
                if root_error.is_none() {
                    root_error = Some(walk_error_to_io(error));
                }
            } else {
                let shown = path.unwrap_or_else(|| root.to_path_buf());
                mmcl_log::warn_silent(&tf!(
                    "log.supplementalScanner.walkFailed",
                    shown.display().to_string(),
                    error.to_string()
                ));
            }
        }
    });
    if let Some(e) = root_error {
        return Err(e);
    }

    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads.max(1))
        .thread_name(|i| format!("mmcl-scan-supplemental-{i}"))
        .build()
        .map_err(io::Error::other)?;

    let started = Instant::now();
    let timed_out = AtomicBool::new(false);
    let results: Mutex<Vec<(usize, Vec<Finding>)>> = Mutex::new(Vec::new());
    pool.install(|| {
        jars.par_iter().enumerate().for_each(|(i, jar)| {
            if cancel.is_cancelled() {
                return;
            }
            if started.elapsed() > SCAN_TIMEOUT {
                timed_out.store(true, Ordering::Relaxed);
                return;
            }
            match scan_jar(jar) {
                Ok(f) => {
                    if !f.is_empty() {
                        results.lock().push((i, f));
                    }
                }
                Err(e) => {
                    let name = jar
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    mmcl_log::warn_silent(&tf!(
                        "log.supplementalScanner.openFailed",
                        name,
                        e.to_string()
                    ));
                }
            }
        });
    });
    if cancel.is_cancelled() {
        return Err(io::Error::new(
            io::ErrorKind::Interrupted,
            mmcl_i18n::t!("scan.error.cancelled").into_owned(),
        ));
    }
    if timed_out.load(Ordering::Relaxed) {
        mmcl_log::warn_silent(&tf!(
            "log.supplementalScanner.timedOut",
            (SCAN_TIMEOUT.as_secs() / 60).to_string()
        ));
    }
    // Deterministic order: walk order of the jars, then entry order inside each.
    let mut results = results.into_inner();
    results.sort_by_key(|(i, _)| *i);
    Ok(results.into_iter().flat_map(|(_, f)| f).collect())
}

fn walk_error_to_io(e: walkdir::Error) -> io::Error {
    let msg = e.to_string();
    match e.into_io_error() {
        Some(io) => io,
        None => io::Error::other(msg),
    }
}

pub(crate) fn has_jar_suffix(path: &Path) -> bool {
    path.file_name()
        .map(|n| n.to_string_lossy().to_lowercase().ends_with(".jar"))
        .unwrap_or(false)
}

// ---------------------------------------------------------------------------------------------
// Jar scan
// ---------------------------------------------------------------------------------------------

/// Opens and scans one jar. An `Err` means the jar could not be opened as a zip.
pub fn scan_jar(path: &Path) -> io::Result<Vec<Finding>> {
    let file = File::open(path)?;
    let mut zip = ZipArchive::new(file).map_err(zip_to_io)?;
    Ok(scan_jar_archive(&mut zip, path))
}

fn zip_to_io(e: zip::result::ZipError) -> io::Error {
    match e {
        zip::result::ZipError::Io(io) => io,
        other => io::Error::new(io::ErrorKind::InvalidData, other.to_string()),
    }
}

/// A once-computed optional value (Java's `Lazy`).
struct Lazy<F: FnMut() -> Option<String>> {
    compute: F,
    value: Option<Option<String>>,
}

impl<F: FnMut() -> Option<String>> Lazy<F> {
    fn new(compute: F) -> Self {
        Lazy {
            compute,
            value: None,
        }
    }

    fn get(&mut self) -> Option<String> {
        if self.value.is_none() {
            self.value = Some((self.compute)());
        }
        self.value.clone().flatten()
    }
}

/// `true` when the jar's file name marks it as a Mojang natives jar (`*-natives-*.jar`).
pub fn is_natives_jar_name(file_name: &str) -> bool {
    let lower = file_name.to_lowercase();
    lower.ends_with(".jar") && lower.contains("-natives-")
}

/// Scans an already-open jar. `jar_path` is stamped on every finding and hashed (lazily) for
/// `file_sha256`. Entries are visited once each, in central-directory order.
pub fn scan_jar_archive<R: Read + Seek>(zip: &mut ZipArchive<R>, jar_path: &Path) -> Vec<Finding> {
    let mut findings = Vec::new();
    let mut file_sha = Lazy::new(|| compute_file_sha256(jar_path));
    let is_natives_jar = jar_path
        .file_name()
        .map(|n| is_natives_jar_name(&n.to_string_lossy()))
        .unwrap_or(false);

    for i in 0..zip.len() {
        // The name comes from the raw entry, which needs no decryption or decompression.
        let name = match zip.by_index_raw(i) {
            Ok(e) => e.name().to_owned(),
            Err(_) => continue,
        };
        if name.ends_with('/') {
            continue;
        }
        let lower = name.to_lowercase();
        let normalized = lower.replace('\\', "/");

        // (1) Embedded executable / script. Only the first matching suffix counts.
        if FORBIDDEN_EMBEDDED_SUFFIXES
            .iter()
            .any(|s| lower.ends_with(s))
        {
            let inner = entry_sha256(zip, i);
            findings.push(Finding {
                severity: Severity::High,
                kind: Kind::EmbeddedExecutable,
                file: jar_path.to_path_buf(),
                file_sha256: file_sha.get(),
                inner_sha256: inner,
                locator: name.clone(),
                message: format!("embedded executable / script entry: {name}"),
            });
        }

        // (2) Native binary outside a recognised natives path (not for natives jars).
        if !is_natives_jar
            && NATIVE_BINARY_SUFFIXES.iter().any(|s| lower.ends_with(s))
            && !is_in_legit_native_path(&normalized)
            && !has_legit_native_filename(&normalized)
        {
            let inner = entry_sha256(zip, i);
            findings.push(Finding {
                severity: Severity::Medium,
                kind: Kind::NativeOutsideKnownPath,
                file: jar_path.to_path_buf(),
                file_sha256: file_sha.get(),
                inner_sha256: inner,
                locator: name.clone(),
                message: format!("native binary outside a known natives path: {name}"),
            });
        }

        // (3) Class content.
        if lower.ends_with(".class") {
            let Ok(bytes) = read_entry_capped(zip, i) else {
                continue;
            };
            scan_class_bytes(&bytes, jar_path, &mut || file_sha.get(), &mut findings);
        }
    }
    findings
}

/// Reads entry `i` fully, failing past [`MAX_SCAN_ENTRY_BYTES`] or on an encrypted / unsupported
/// entry.
pub(crate) fn read_entry_capped<R: Read + Seek>(
    zip: &mut ZipArchive<R>,
    i: usize,
) -> io::Result<Vec<u8>> {
    let entry = zip.by_index(i).map_err(zip_to_io)?;
    let mut out = Vec::with_capacity(entry.size().min(1 << 20) as usize);
    let n = entry.take(MAX_SCAN_ENTRY_BYTES + 1).read_to_end(&mut out)?;
    if n as u64 > MAX_SCAN_ENTRY_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            mmcl_i18n::tf!("scan.error.entryTooLarge", MAX_SCAN_ENTRY_BYTES.to_string()),
        ));
    }
    Ok(out)
}

/// Streams entry `i` through SHA-256 under the size ceiling; `None` on any error.
fn entry_sha256<R: Read + Seek>(zip: &mut ZipArchive<R>, i: usize) -> Option<String> {
    let entry = zip.by_index(i).ok()?;
    let mut limited = entry.take(MAX_SCAN_ENTRY_BYTES + 1);
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 16384];
    let mut total: u64 = 0;
    loop {
        let n = limited.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        total += n as u64;
        if total > MAX_SCAN_ENTRY_BYTES {
            return None;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex::encode(hasher.finalize()))
}

/// Lowercase hex SHA-256 of `bytes`.
pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// Streaming lowercase hex SHA-256 of a file, or `None` on any error.
pub fn compute_file_sha256(path: &Path) -> Option<String> {
    let mut f = File::open(path).ok()?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 16384];
    loop {
        let n = f.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(hex::encode(hasher.finalize()))
}

/// Inspects one class: `LDC` string constants and the Robot + Clipboard pair. A class that does
/// not parse is skipped silently.
fn scan_class_bytes(
    bytes: &[u8],
    jar_path: &Path,
    file_sha: &mut dyn FnMut() -> Option<String>,
    findings: &mut Vec<Finding>,
) {
    let Ok(class) = classfile::parse(bytes) else {
        return;
    };
    let mut inner = Lazy::new(|| Some(sha256_hex(bytes)));
    let mut saw_robot = false;
    let mut saw_clipboard = false;
    for method in &class.methods {
        for insn in &method.code {
            match (insn.op, insn.operand) {
                (op::LDC, Operand::Cp(idx)) => {
                    if let Some(s) = class.ldc_string(idx)
                        && let Some((severity, kind, message_fn)) = inspect_string(s)
                    {
                        let locator = format!("{}.{}", class.name, method.name);
                        let message = message_fn(&locator);
                        findings.push(Finding {
                            severity,
                            kind,
                            file: jar_path.to_path_buf(),
                            file_sha256: file_sha(),
                            inner_sha256: inner.get(),
                            locator,
                            message,
                        });
                    }
                }
                (
                    op::INVOKEVIRTUAL | op::INVOKESPECIAL | op::INVOKESTATIC | op::INVOKEINTERFACE,
                    Operand::Cp(idx),
                ) => {
                    if let Some(m) = class.member_ref(idx) {
                        if m.owner == AWT_ROBOT_OWNER {
                            saw_robot = true;
                        }
                        if m.owner == CLIPBOARD_OWNER {
                            saw_clipboard = true;
                        }
                    }
                }
                _ => {}
            }
        }
    }
    if saw_robot && saw_clipboard {
        findings.push(Finding {
            severity: Severity::Medium,
            kind: Kind::ClipboardStealerPattern,
            file: jar_path.to_path_buf(),
            file_sha256: file_sha(),
            inner_sha256: inner.get(),
            locator: class.name.clone(),
            message: format!(
                "clipboard-stealer pattern (AWT Robot + Clipboard) in {}",
                class.name
            ),
        });
    }
}

type MessageFn = Box<dyn FnOnce(&str) -> String>;

/// Classifies one `LDC` string constant: `(severity, kind, message(locator))`, or `None`.
pub fn inspect_string(s: &str) -> Option<(Severity, Kind, MessageFn)> {
    if s.is_empty() {
        return None;
    }
    let url = s.contains("://");
    if !url && !s.contains('.') {
        return None;
    }
    // (A) Discord webhook.
    if url && DISCORD_WEBHOOK.is_match(s) {
        return Some((
            Severity::High,
            Kind::DiscordWebhookUrl,
            Box::new(|loc: &str| format!("Discord webhook URL in {loc}")),
        ));
    }
    // (B) Paste / file-host URL.
    if url {
        for (re, src) in SUSPICIOUS_HOSTS.iter() {
            if re.is_match(s) {
                let src: &'static str = src;
                return Some((
                    Severity::High,
                    Kind::SuspiciousHostUrl,
                    Box::new(move |loc: &str| format!("suspicious host URL in {loc} ({src})")),
                ));
            }
        }
    }
    // (C) Another launcher's credential file.
    let lower = s.to_lowercase();
    if let Some(cred) = LAUNCHER_CREDENTIAL_FILES
        .iter()
        .find(|c| lower.contains(*c))
    {
        let cred: &'static str = cred;
        return Some((
            Severity::High,
            Kind::LauncherCredentialFileRef,
            Box::new(move |loc: &str| {
                format!("reference to launcher credential file '{cred}' in {loc}")
            }),
        ));
    }
    // (D) A dotted quad needs at least three dots.
    if s.bytes().filter(|&b| b == b'.').count() < 3 {
        return None;
    }
    // (E) IPv4 literal, first match only.
    let m = find_ipv4_literal(s)?;
    if looks_like_real_ip(&m) && !is_false_positive_ip(s, &m) {
        let text = s[m.start..m.end].to_owned();
        return Some((
            Severity::Medium,
            Kind::Ipv4Literal,
            Box::new(move |loc: &str| format!("hard-coded IPv4 literal in {loc}: {text}")),
        ));
    }
    None
}

// ---------------------------------------------------------------------------------------------
// IPv4 literal matching (hand-rolled port of the Java regex, which uses look-around)
// ---------------------------------------------------------------------------------------------

/// A dotted-quad match: byte range in the string and the four octet values (each at most three
/// digits, so at most 999).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ipv4Match {
    pub start: usize,
    pub end: usize,
    pub octets: [u16; 4],
}

fn is_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// Reads a run of ASCII digits starting at `i`: `(value, end)` when it is 1 to 3 digits long.
fn octet_run(b: &[u8], i: usize) -> Option<(u16, usize)> {
    let mut j = i;
    while j < b.len() && b[j].is_ascii_digit() {
        j += 1;
    }
    let len = j - i;
    if len == 0 || len > 3 {
        return None;
    }
    let mut v: u16 = 0;
    for &d in &b[i..j] {
        v = v * 10 + u16::from(d - b'0');
    }
    Some((v, j))
}

/// The first match of Java's
/// `\b(?!127\.)(?!0\.)(?!22[4-9]\.)(?!23\d\.)(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b(?!\.\d)`
/// with ASCII `\b` / `\d`. Each group followed by a `.` must take its whole digit run (a shorter
/// group would leave a digit where the `.` belongs), and the last group must too (a shorter one
/// would put `\b` between two digits), so no backtracking is needed.
pub fn find_ipv4_literal(s: &str) -> Option<Ipv4Match> {
    let b = s.as_bytes();
    let starts_with = |i: usize, p: &[u8]| b.len() >= i + p.len() && &b[i..i + p.len()] == p;
    for start in 0..b.len() {
        if !b[start].is_ascii_digit() || (start > 0 && is_word(b[start - 1])) {
            continue;
        }
        // Negative look-aheads on the text prefix.
        if starts_with(start, b"127.") || starts_with(start, b"0.") {
            continue;
        }
        if b.len() >= start + 4
            && b[start] == b'2'
            && b[start + 1] == b'2'
            && (b'4'..=b'9').contains(&b[start + 2])
            && b[start + 3] == b'.'
        {
            continue;
        }
        if b.len() >= start + 4
            && b[start] == b'2'
            && b[start + 1] == b'3'
            && b[start + 2].is_ascii_digit()
            && b[start + 3] == b'.'
        {
            continue;
        }
        let mut octets = [0u16; 4];
        let mut i = start;
        let mut ok = true;
        for (k, slot) in octets.iter_mut().enumerate() {
            match octet_run(b, i) {
                Some((v, end)) => {
                    *slot = v;
                    i = end;
                    if k < 3 {
                        if i < b.len() && b[i] == b'.' {
                            i += 1;
                        } else {
                            ok = false;
                            break;
                        }
                    }
                }
                None => {
                    ok = false;
                    break;
                }
            }
        }
        if !ok {
            continue;
        }
        // Trailing \b: the last group ends in a digit, so the next byte must not be a word char.
        if i < b.len() && is_word(b[i]) {
            continue;
        }
        // (?!\.\d)
        if i + 1 < b.len() && b[i] == b'.' && b[i + 1].is_ascii_digit() {
            continue;
        }
        return Some(Ipv4Match {
            start,
            end: i,
            octets,
        });
    }
    None
}

/// All four octets are in 0..=255.
pub fn looks_like_real_ip(m: &Ipv4Match) -> bool {
    m.octets.iter().all(|&o| o <= 255)
}

/// Reserved / private / documentation ranges, public DNS resolvers, `X.Y.0.0` pads, and
/// version-string-shaped literals (leading octet below 32 outside a URL-host position).
pub fn is_false_positive_ip(full: &str, m: &Ipv4Match) -> bool {
    let [a, b, c, d] = m.octets;
    if a == 10
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 198 && (b == 18 || b == 19))
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113)
    {
        return true;
    }
    const RESOLVERS: [[u16; 4]; 8] = [
        [8, 8, 8, 8],
        [8, 8, 4, 4],
        [1, 1, 1, 1],
        [1, 0, 0, 1],
        [9, 9, 9, 9],
        [149, 112, 112, 112],
        [208, 67, 222, 222],
        [208, 67, 220, 220],
    ];
    if RESOLVERS.contains(&m.octets) {
        return true;
    }
    if c == 0 && d == 0 {
        return true;
    }
    a < 32 && !is_ip_url_host(full, m.start)
}

/// The IP at byte offset `ip_start` sits in a URL-host position: right after `://` or `@`.
pub fn is_ip_url_host(full: &str, ip_start: usize) -> bool {
    let b = full.as_bytes();
    if ip_start > b.len() {
        return false;
    }
    if ip_start >= 3 && &b[ip_start - 3..ip_start] == b"://" {
        return true;
    }
    ip_start >= 1 && b[ip_start - 1] == b'@'
}

// ---------------------------------------------------------------------------------------------
// Native path helpers
// ---------------------------------------------------------------------------------------------

/// `normalized` (lowercase, `/` separators) contains a conventional natives path fragment.
pub fn is_in_legit_native_path(normalized: &str) -> bool {
    LEGIT_NATIVE_PATH_PREFIXES
        .iter()
        .any(|p| normalized.contains(p))
}

/// The base name starts with `lib` or ends with `.dll` (so a `.dll` never fires the rule).
pub fn has_legit_native_filename(normalized: &str) -> bool {
    let base = normalized.rsplit('/').next().unwrap_or(normalized);
    base.starts_with("lib") || base.ends_with(".dll")
}

// ---------------------------------------------------------------------------------------------
// Exclusions
// ---------------------------------------------------------------------------------------------

/// Trims, strips every leading and trailing `/` and `\`, drops blanks, lowercases and turns `\`
/// into `/`.
pub fn normalize_exclusions<S: AsRef<str>>(raw: &[S]) -> Vec<String> {
    raw.iter()
        .filter_map(|x| {
            let s = x
                .as_ref()
                .trim()
                .trim_start_matches(['/', '\\'])
                .trim_end_matches(['/', '\\']);
            (!s.is_empty()).then(|| s.to_lowercase().replace('\\', "/"))
        })
        .collect()
}

/// `rel` (lowercase, `/` separators) equals an exclusion or lies under one.
pub(crate) fn is_excluded_rel(rel: &str, exclusions: &[String]) -> bool {
    exclusions.iter().any(|e| {
        rel == e
            || (rel.len() > e.len()
                && rel.starts_with(e.as_str())
                && rel.as_bytes()[e.len()] == b'/')
    })
}

/// Lexical `normalize()`: drops `.` and folds `..` into the previous component.
fn normalize_lexically(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => {
                if !out.pop() {
                    out.push("..");
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Java's `isExcluded(root, target, exclusions)`: never the root itself; otherwise the
/// pack-relative path of `target` (absolute, normalised) equals or lies under an exclusion
/// (which must be normalised already, see [`normalize_exclusions`]).
pub fn is_excluded(root: &Path, target: &Path, exclusions: &[String]) -> bool {
    if exclusions.is_empty() || root == target {
        return false;
    }
    let abs =
        |p: &Path| normalize_lexically(&std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf()));
    let root_abs = abs(root);
    let target_abs = abs(target);
    if root_abs == target_abs {
        return false;
    }
    match walk::rel_string(&root_abs, &target_abs) {
        Some(rel) => is_excluded_rel(&rel, exclusions),
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn quad(s: &str) -> Ipv4Match {
        // The Java test helper: a plain dotted quad, no boundaries.
        let re = Regex::new(r"([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})").unwrap();
        let c = re.captures(s).expect("fixture must contain a dotted quad");
        let g = |i: usize| c.get(i).unwrap().as_str().parse::<u16>().unwrap();
        Ipv4Match {
            start: c.get(0).unwrap().start(),
            end: c.get(0).unwrap().end(),
            octets: [g(1), g(2), g(3), g(4)],
        }
    }

    fn is_fp(s: &str) -> bool {
        is_false_positive_ip(s, &quad(s))
    }

    #[test]
    fn looks_like_real_ip_accepts_valid_octets() {
        assert!(looks_like_real_ip(&quad("1.2.3.4")));
        assert!(looks_like_real_ip(&quad("255.255.255.255")));
        assert!(looks_like_real_ip(&quad("0.0.0.0")));
    }

    #[test]
    fn looks_like_real_ip_rejects_out_of_range_octet() {
        assert!(!looks_like_real_ip(&quad("256.1.1.1")));
        assert!(!looks_like_real_ip(&quad("999.1.1.1")));
    }

    #[test]
    fn false_positive_reserved_ranges() {
        for s in [
            "10.0.0.5",
            "192.168.1.5",
            "172.20.3.4",
            "169.254.1.1",
            "100.64.0.1",
            "192.0.2.5",
            "198.51.100.7",
            "203.0.113.9",
            "198.18.0.1",
            "192.0.0.5",
        ] {
            assert!(is_fp(s), "{s}");
        }
    }

    #[test]
    fn false_positive_dns_resolvers() {
        for s in [
            "8.8.8.8",
            "8.8.4.4",
            "1.1.1.1",
            "1.0.0.1",
            "9.9.9.9",
            "149.112.112.112",
            "208.67.222.222",
            "208.67.220.220",
        ] {
            assert!(is_fp(s), "{s}");
        }
    }

    #[test]
    fn false_positive_trailing_zero_pad_and_version_strings() {
        assert!(is_fp("114.0.0.0"));
        assert!(is_fp("1.7.0.0"));
        assert!(is_fp("6.1.7.2"));
        assert!(!is_fp("http://6.1.7.2/payload"));
        assert!(!is_fp("45.33.32.156"));
    }

    #[test]
    fn ip_url_host_positions() {
        let s = "http://1.2.3.4/x";
        assert!(is_ip_url_host(s, s.find('1').unwrap()));
        let s = "user:pass@1.2.3.4";
        assert!(is_ip_url_host(s, s.find('1').unwrap()));
        let s = "path/to/1.2.3.4/file";
        assert!(!is_ip_url_host(s, s.find('1').unwrap()));
        assert!(!is_ip_url_host("1.2.3.4", 0));
        assert!(!is_ip_url_host("x", 5));
    }

    #[test]
    fn ipv4_regex_port_matches_java_semantics() {
        let f = |s: &str| find_ipv4_literal(s).map(|m| s[m.start..m.end].to_owned());
        assert_eq!(f("45.33.32.156").as_deref(), Some("45.33.32.156"));
        assert_eq!(f("x 45.33.32.156:80").as_deref(), Some("45.33.32.156"));
        assert_eq!(f("127.0.0.1"), None);
        assert_eq!(f("0.1.2.3"), None);
        assert_eq!(f("224.0.2.60"), None);
        assert_eq!(f("229.1.1.1"), None);
        assert_eq!(f("230.1.1.1"), None);
        assert_eq!(f("239.1.1.1"), None);
        // 240+ is not excluded by the Java regex, despite its comment.
        assert_eq!(f("240.1.1.1").as_deref(), Some("240.1.1.1"));
        // "00." is not the "0." prefix.
        assert_eq!(f("00.1.2.3").as_deref(), Some("00.1.2.3"));
        // No \b inside a digit run.
        assert_eq!(f("1234.1.1.1"), None);
        assert_eq!(f("1.2.3.4567"), None);
        // Followed by ".<digit>" the first quad fails, but the regex then finds the later one.
        assert_eq!(f("1.2.3.4.5").as_deref(), Some("2.3.4.5"));
        assert_eq!(f("1.2.3.4.5.6.7"), Some("4.5.6.7".into()));
        assert_eq!(f("v1.2.3.4"), None);
        assert_eq!(f("_1.2.3.4"), None);
        assert_eq!(f("1.2.3.4a"), None);
        assert_eq!(f("1.2.3.4."), Some("1.2.3.4".into()));
        // First match only, later candidates are found when earlier ones fail.
        assert_eq!(
            f("127.0.0.1 and 45.33.32.156").as_deref(),
            Some("45.33.32.156")
        );
        assert_eq!(f("é45.33.32.156").as_deref(), Some("45.33.32.156"));
        assert_eq!(f("https://12.34.56.78/x").as_deref(), Some("12.34.56.78"));
    }

    #[test]
    fn legit_native_path_and_filename() {
        assert!(is_in_legit_native_path("natives/foo.dll"));
        assert!(is_in_legit_native_path("lib/x86_64-linux/jni/libfoo.so"));
        assert!(is_in_legit_native_path(
            "assets/opencomputers/lib/libfoo.so"
        ));
        assert!(is_in_legit_native_path("org/lwjgl/system/libfoo.so"));
        assert!(is_in_legit_native_path(
            "aarch64-macosx-clang/jni/libfoo.dylib"
        ));
        assert!(!is_in_legit_native_path("weird.dll"));
        assert!(!is_in_legit_native_path("com/evil/payload.dll"));
        assert!(has_legit_native_filename("libasyncprofiler.so"));
        assert!(has_legit_native_filename("path/to/libfoo.dylib"));
        assert!(has_legit_native_filename("sdl2gdx64.dll"));
        assert!(!has_legit_native_filename("random.jnilib"));
        assert!(!has_legit_native_filename("path/random.so"));
    }

    #[test]
    fn normalize_exclusions_trims_and_lowercases() {
        assert_eq!(
            normalize_exclusions(&[
                " /Mods/ ",
                "\\Libraries\\",
                "",
                "   ",
                "custom-Tools",
                "a\\B"
            ]),
            vec!["mods", "libraries", "custom-tools", "a/b"]
        );
        assert!(normalize_exclusions::<&str>(&[]).is_empty());
    }

    #[test]
    fn is_excluded_cases() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path();
        std::fs::create_dir_all(root.join("Libraries/foo")).unwrap();
        std::fs::create_dir_all(root.join("libraries-extra")).unwrap();
        let ex = vec!["libraries".to_owned()];
        assert!(is_excluded(root, &root.join("Libraries"), &ex));
        assert!(is_excluded(root, &root.join("Libraries/foo/bar.jar"), &ex));
        assert!(!is_excluded(root, &root.join("libraries-extra"), &ex));
        assert!(!is_excluded(root, root, &ex));
        assert!(!is_excluded(root, &root.join("mods"), &[]));
        // Separator style never matters.
        let ex = vec!["a/b".to_owned()];
        assert!(is_excluded(
            root,
            &root.join("a").join("b").join("c.jar"),
            &ex
        ));
        assert!(is_excluded(root, &root.join("x/../a/./b"), &ex));
    }

    #[test]
    fn sha256_helpers() {
        let a = sha256_hex(b"hello");
        assert_eq!(a, sha256_hex(b"hello"));
        assert_ne!(a, sha256_hex(b"world"));
        assert_eq!(
            a,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
        let tmp = tempfile::tempdir().unwrap();
        assert_eq!(compute_file_sha256(&tmp.path().join("missing.jar")), None);
        let p = tmp.path().join("sample.bin");
        std::fs::write(&p, b"some jar-shaped bytes").unwrap();
        assert_eq!(
            compute_file_sha256(&p),
            Some(sha256_hex(b"some jar-shaped bytes"))
        );
    }

    #[test]
    fn kind_names_are_the_manifest_schema() {
        for k in Kind::ALL {
            let json = serde_json::to_string(&k).unwrap();
            assert_eq!(json, format!("\"{}\"", k.as_str()));
            assert_eq!(serde_json::from_str::<Kind>(&json).unwrap(), k);
            assert_eq!(Kind::from_name(k.as_str()), Some(k));
        }
        assert_eq!(Kind::Ipv4Literal.as_str(), "IPV4_LITERAL");
        assert_eq!(serde_json::to_string(&Severity::High).unwrap(), "\"HIGH\"");
    }

    #[test]
    fn natives_jar_name() {
        assert!(is_natives_jar_name(
            "lwjgl-platform-2.9.1-natives-windows.jar"
        ));
        assert!(is_natives_jar_name("X-NATIVES-Y.JAR"));
        assert!(!is_natives_jar_name("natives-windows.jar"));
        assert!(!is_natives_jar_name("foo-natives-x.zip"));
    }
}
