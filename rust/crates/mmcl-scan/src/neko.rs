// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Port of the vendored Nekodetector (`me.cortex.jarscanner`, MIT, original at
//! `github.com/MCRcortex/nekodetector`): Fractureiser stage 1 bytecode signatures and the stage 2
//! filesystem indicators of compromise.
//!
//! Differences from the Java code, all deliberate:
//!
//! * the class reader does not gate on the class-file major version (ASM rejected classes newer
//!   than it knew, a blind spot);
//! * class reads go through the same 256 MiB zip-bomb ceiling as the supplemental scanner;
//! * a truncated or malformed class counts as clean and the rest of the jar is still scanned (in
//!   Java an unexpected parser exception aborted the whole jar, reporting it clean);
//! * exclusions are matched with the supplemental scanner's normalised, separator-independent
//!   rule (Java's Neko pass never matched `a/b` against `a\b` on Windows);
//! * the stage 2 checks look at the **real** Startup folder and the **real**
//!   `$HOME/.config/.data/lib.jar` (Java looked at paths relative to the working directory and
//!   never found anything);
//! * stage 1 results are in walk order, not thread completion order.

use std::fs::File;
use std::io::{self, Read, Seek};
use std::path::{Path, PathBuf};
use std::time::Instant;

use mmcl_i18n::{t, tf};
use mmcl_net::CancelToken;
use parking_lot::Mutex;
use rayon::prelude::*;
use thiserror::Error;
use zip::ZipArchive;

use crate::classfile::{self, ClassFile, Insn, Operand, op};
use crate::exclusion;
use crate::supplemental::{self, has_jar_suffix, normalize_exclusions};
use crate::walk::{self, WalkItem};

/// What one Neko run found. Entries are path strings: stage 1 the infected jars as walked,
/// stage 2 the absolute paths of the indicators found on this machine.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Results {
    pub stage1: Vec<String>,
    pub stage2: Vec<String>,
}

impl Results {
    /// Nothing was found.
    pub fn is_clean(&self) -> bool {
        self.stage1.is_empty() && self.stage2.is_empty()
    }
}

/// Why a run could not complete.
#[derive(Debug, Error)]
pub enum NekoError {
    #[error("{}", tf!("scan.error.dirMissing", .0.display().to_string()))]
    DirMissing(PathBuf),
    #[error("{}", tf!("scan.error.notDirectory", .0.display().to_string()))]
    NotADirectory(PathBuf),
    #[error("{}", t!("scan.error.threads"))]
    NoThreads,
    #[error("{}", t!("scan.error.cancelled"))]
    Cancelled,
    #[error("{}", tf!("scan.error.pool", .0.to_string()))]
    Pool(String),
}

/// The environment the stage 2 check reads, injected so tests can point it at a temp folder.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Stage2Env {
    /// `%APPDATA%`, when set.
    pub appdata: Option<PathBuf>,
    /// The user's home folder.
    pub home: Option<PathBuf>,
    /// Run the Linux check (Java: `os.name` contains `linux`).
    pub is_linux: bool,
}

impl Stage2Env {
    /// The current process's environment.
    pub fn from_system() -> Self {
        Stage2Env {
            appdata: std::env::var_os("APPDATA")
                .filter(|v| !v.is_empty())
                .map(PathBuf::from),
            home: dirs::home_dir(),
            is_linux: cfg!(target_os = "linux"),
        }
    }
}

/// File names Fractureiser stage 2 drops in the Windows Startup folder.
pub const STAGE2_STARTUP_FILES: [&str; 5] =
    [".ref", "client.jar", "lib.dll", "libWebGL64.jar", "run.bat"];

/// Runs both stages over `dir` (Java `Main.run`), using this machine's environment for stage 2.
///
/// `log` receives each progress line (localized, no ANSI codes). `progress` receives
/// `(files_processed, total_files)`. Cancelling is checked between files and jars and returns
/// [`NekoError::Cancelled`].
pub fn run<S: AsRef<str>>(
    threads: usize,
    dir: &Path,
    emit_walk_errors: bool,
    excludes: &[S],
    log: &(dyn Fn(&str) + Sync),
    progress: Option<&(dyn Fn(f64, f64) + Sync)>,
    cancel: &CancelToken,
) -> Result<Results, NekoError> {
    run_with_env(
        threads,
        dir,
        emit_walk_errors,
        excludes,
        log,
        progress,
        cancel,
        &Stage2Env::from_system(),
    )
}

/// [`run`] with an explicit stage 2 environment.
#[allow(clippy::too_many_arguments)]
pub fn run_with_env<S: AsRef<str>>(
    threads: usize,
    dir: &Path,
    emit_walk_errors: bool,
    excludes: &[S],
    log: &(dyn Fn(&str) + Sync),
    progress: Option<&(dyn Fn(f64, f64) + Sync)>,
    cancel: &CancelToken,
    env: &Stage2Env,
) -> Result<Results, NekoError> {
    // Defence in depth: the exclusion list may come from an attacker-controlled manifest.
    let exclusions = normalize_exclusions(&exclusion::filter_untrusted(excludes));

    let start = Instant::now();
    log(&t!("scan.neko.starting"));
    if !dir.exists() {
        return Err(NekoError::DirMissing(dir.to_path_buf()));
    }
    if !dir.is_dir() {
        return Err(NekoError::NotADirectory(dir.to_path_buf()));
    }
    if threads < 1 {
        return Err(NekoError::NoThreads);
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(threads)
        .thread_name(|i| format!("mmcl-scan-neko-{i}"))
        .build()
        .map_err(|e| NekoError::Pool(e.to_string()))?;

    log(&t!("scan.neko.preparing"));
    let prep = Instant::now();
    let mut total = 0u64;
    walk::walk(dir, &exclusions, |item| {
        if matches!(item, WalkItem::File(_)) {
            total += 1;
        }
    });
    log(&tf!("scan.neko.prepared", ms(prep)));

    let stage1_start = Instant::now();
    log(&t!("scan.neko.stage1Running"));
    let mut jars: Vec<PathBuf> = Vec::new();
    let mut processed = 0u64;
    walk::walk(dir, &exclusions, |item| match item {
        WalkItem::File(path) => {
            if cancel.is_cancelled() {
                return;
            }
            processed += 1;
            if has_jar_suffix(&path) {
                jars.push(path);
            }
            if let Some(p) = progress {
                p(processed as f64, total as f64);
            }
        }
        WalkItem::Error { path, is_dir, .. } => {
            if emit_walk_errors {
                let shown = path
                    .unwrap_or_else(|| dir.to_path_buf())
                    .display()
                    .to_string();
                let key = if is_dir {
                    "scan.neko.accessDirFailed"
                } else {
                    "scan.neko.accessFileFailed"
                };
                log(&tf!(key, shown));
            }
        }
    });
    if cancel.is_cancelled() {
        return Err(NekoError::Cancelled);
    }

    let hits: Mutex<Vec<usize>> = Mutex::new(Vec::new());
    pool.install(|| {
        jars.par_iter().enumerate().for_each(|(i, jar)| {
            if cancel.is_cancelled() {
                return;
            }
            let archive = File::open(jar)
                .map_err(|e| e.to_string())
                .and_then(|f| ZipArchive::new(f).map_err(|e| e.to_string()));
            match archive {
                Ok(mut zip) => {
                    let name = jar
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    log(&tf!("scan.neko.scanningJar", name));
                    if scan_jar_archive(&mut zip, jar, log) {
                        hits.lock().push(i);
                    }
                }
                Err(e) => {
                    if emit_walk_errors {
                        log(&tf!("scan.neko.jarFailed", jar.display().to_string(), e));
                    }
                }
            }
        });
    });
    if cancel.is_cancelled() {
        return Err(NekoError::Cancelled);
    }
    let mut hits = hits.into_inner();
    hits.sort_unstable();
    let stage1: Vec<String> = hits
        .into_iter()
        .map(|i| jars[i].to_string_lossy().into_owned())
        .collect();
    log(&tf!("scan.neko.stage1Complete", ms(stage1_start)));

    let stage2_start = Instant::now();
    log(&t!("scan.neko.stage2Running"));
    let stage2 = check_stage2(env);
    log(&tf!("scan.neko.stage2Complete", ms(stage2_start)));
    log(&tf!("scan.neko.complete", ms(start)));

    Ok(Results { stage1, stage2 })
}

/// Milliseconds since `t`, as a string (no locale digit grouping).
fn ms(t: Instant) -> String {
    t.elapsed().as_millis().to_string()
}

/// Opens and scans one jar (Java `Detector.scan`). `Err` when it cannot be opened as a zip.
pub fn scan_jar(path: &Path, log: &(dyn Fn(&str) + Sync)) -> io::Result<bool> {
    let file = File::open(path)?;
    let mut zip = ZipArchive::new(file)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    Ok(scan_jar_archive(&mut zip, path, log))
}

/// Scans the `.class` entries (case-sensitive suffix, as Java) of an open jar, stopping at the
/// first infected class. Unreadable classes are logged and count as clean.
pub fn scan_jar_archive<R: Read + Seek>(
    zip: &mut ZipArchive<R>,
    path: &Path,
    log: &(dyn Fn(&str) + Sync),
) -> bool {
    for i in 0..zip.len() {
        let name = match zip.by_index_raw(i) {
            Ok(e) => e.name().to_owned(),
            Err(_) => continue,
        };
        if !name.ends_with(".class") {
            continue;
        }
        match supplemental::read_entry_capped(zip, i) {
            Ok(bytes) => {
                if scan_class(&bytes) {
                    return true;
                }
            }
            Err(e) => {
                log(&tf!(
                    "scan.neko.classReadFailed",
                    path.display().to_string(),
                    name,
                    e.to_string()
                ));
            }
        }
    }
    false
}

/// `true` when any method of the class matches one of the three Fractureiser signatures. A
/// class that does not parse is clean.
pub fn scan_class(bytes: &[u8]) -> bool {
    match classfile::parse(bytes) {
        Ok(class) => scan_class_file(&class),
        Err(_) => false,
    }
}

/// [`scan_class`] on an already parsed class.
pub fn scan_class_file(class: &ClassFile) -> bool {
    class.methods.iter().any(|m| {
        matches_sig1(class, &m.code) || matches_sig2(class, &m.code) || matches_sig3(&m.code)
    })
}

// ---------------------------------------------------------------------------------------------
// Signatures
// ---------------------------------------------------------------------------------------------

/// One expected instruction of a signature.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sig {
    /// A type instruction (`NEW`) and its class.
    Type(u8, &'static str),
    /// A method instruction and its owner, name and descriptor.
    Method(u8, &'static str, &'static str, &'static str),
    /// An instruction without operands.
    Plain(u8),
    /// `BIPUSH` / `SIPUSH` and its value.
    Int(u8, i32),
}

impl Sig {
    /// The expected opcode.
    pub fn op(&self) -> u8 {
        match *self {
            Sig::Type(o, _) | Sig::Method(o, ..) | Sig::Plain(o) | Sig::Int(o, _) => o,
        }
    }
}

const STRING: &str = "java/lang/String";
const STRING_INIT_BYTES: Sig = Sig::Method(op::INVOKESPECIAL, STRING, "<init>", "([B)V");

/// The hard detect: Fractureiser's stage 0 loader.
pub const SIG1: [Sig; 15] = [
    Sig::Type(op::NEW, STRING),
    STRING_INIT_BYTES,
    Sig::Type(op::NEW, STRING),
    STRING_INIT_BYTES,
    Sig::Method(
        op::INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;)Ljava/lang/Class;",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/lang/Class",
        "getConstructor",
        "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;",
    ),
    STRING_INIT_BYTES,
    STRING_INIT_BYTES,
    STRING_INIT_BYTES,
    Sig::Method(
        op::INVOKESPECIAL,
        "java/net/URL",
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/lang/reflect/Constructor",
        "newInstance",
        "([Ljava/lang/Object;)Ljava/lang/Object;",
    ),
    Sig::Method(
        op::INVOKESTATIC,
        "java/lang/Class",
        "forName",
        "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
    ),
    STRING_INIT_BYTES,
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/lang/Class",
        "getMethod",
        "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/lang/reflect/Method",
        "invoke",
        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
    ),
];

/// The near-hard detect: Base64-decoded `Runtime.exec`.
pub const SIG2: [Sig; 7] = [
    Sig::Method(
        op::INVOKESTATIC,
        "java/lang/Runtime",
        "getRuntime",
        "()Ljava/lang/Runtime;",
    ),
    Sig::Method(
        op::INVOKESTATIC,
        "java/util/Base64",
        "getDecoder",
        "()Ljava/util/Base64$Decoder;",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        STRING,
        "concat",
        "(Ljava/lang/String;)Ljava/lang/String;",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/util/Base64$Decoder",
        "decode",
        "(Ljava/lang/String;)[B",
    ),
    STRING_INIT_BYTES,
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/io/File",
        "getPath",
        "()Ljava/lang/String;",
    ),
    Sig::Method(
        op::INVOKEVIRTUAL,
        "java/lang/Runtime",
        "exec",
        "([Ljava/lang/String;)Ljava/lang/Process;",
    ),
];

const fn bi(v: i32) -> Sig {
    Sig::Int(op::BIPUSH, v)
}
const BAS: Sig = Sig::Plain(op::BASTORE);
const DUP: Sig = Sig::Plain(op::DUP);

/// A byte array spelling the C2 address `85.217.144.130` (copied verbatim from `Detector.SIG3`;
/// 53 instructions).
pub const SIG3: [Sig; 53] = [
    bi(56),
    BAS,
    DUP,
    Sig::Plain(op::ICONST_1),
    bi(53),
    BAS,
    DUP,
    Sig::Plain(op::ICONST_2),
    bi(46),
    BAS,
    DUP,
    Sig::Plain(op::ICONST_3),
    bi(50),
    BAS,
    DUP,
    Sig::Plain(op::ICONST_4),
    bi(49),
    BAS,
    DUP,
    Sig::Plain(op::ICONST_5),
    bi(55),
    BAS,
    DUP,
    bi(6),
    bi(46),
    BAS,
    DUP,
    bi(7),
    bi(49),
    BAS,
    DUP,
    bi(8),
    bi(52),
    BAS,
    DUP,
    bi(9),
    bi(52),
    BAS,
    DUP,
    bi(10),
    bi(46),
    BAS,
    DUP,
    bi(11),
    bi(49),
    BAS,
    DUP,
    bi(12),
    bi(51),
    BAS,
    DUP,
    bi(13),
    bi(48),
];

/// Java's `same(a, b)`, called only when the opcodes are equal.
fn same(class: &ClassFile, insn: &Insn, sig: &Sig) -> bool {
    match (*sig, insn.operand) {
        (Sig::Type(_, desc), Operand::Cp(idx)) => class.class_name(idx) == Some(desc),
        (Sig::Method(_, owner, name, desc), Operand::Cp(idx)) => class
            .member_ref(idx)
            .is_some_and(|m| m.owner == owner && m.name == name && m.desc == desc),
        (Sig::Plain(_), _) => true,
        (Sig::Int(_, v), Operand::Int(x)) => v == x,
        _ => false,
    }
}

/// SIG1: an in-order subsequence where the **first** later instruction with the expected opcode
/// must also match the operands; any mismatch fails the method at once.
pub fn matches_sig1(class: &ClassFile, insns: &[Insn]) -> bool {
    let mut j = 0;
    for insn in insns {
        if j == SIG1.len() {
            break;
        }
        if insn.op == SIG1[j].op() {
            if !same(class, insn, &SIG1[j]) {
                return false;
            }
            j += 1;
        }
    }
    j == SIG1.len()
}

/// SIG2 as Java tries it from every start index. A start can only succeed if the first
/// `INVOKESTATIC` at or after it is `Runtime.getRuntime()`, and every start before such a call
/// behaves exactly like starting on the call, so only those calls need trying: same result,
/// without Java's quadratic scan.
pub fn matches_sig2(class: &ClassFile, insns: &[Insn]) -> bool {
    let first = SIG2[0];
    (0..insns.len())
        .filter(|&q| insns[q].op == first.op() && same(class, &insns[q], &first))
        .any(|q| sig2_from(class, &insns[q..]))
}

fn sig2_from(class: &ClassFile, insns: &[Insn]) -> bool {
    let mut j = 0;
    for insn in insns {
        if j == SIG2.len() {
            break;
        }
        if insn.op != SIG2[j].op() {
            continue;
        }
        if !same(class, insn, &SIG2[j]) {
            return false;
        }
        j += 1;
    }
    j == SIG2.len()
}

/// SIG3, ported with its quirks: `matched` turns true after any single opcode-matching
/// instruction and is reset only by an instruction with a different opcode; a `BIPUSH` with the
/// right opcode but the wrong value neither advances nor resets; a reset does not retest the
/// current instruction as a new start. So a method whose **last** instruction has the opcode the
/// signature expects next reports a hit even when the signature is incomplete (in practice
/// methods end in a return or throw, so this almost never fires).
pub fn matches_sig3(insns: &[Insn]) -> bool {
    let mut matched = false;
    let mut pos = 0;
    for insn in insns {
        if pos == SIG3.len() {
            break;
        }
        let sig = SIG3[pos];
        if insn.op == sig.op() {
            match (sig, insn.operand) {
                (Sig::Int(_, v), operand) => {
                    if operand == Operand::Int(v) {
                        matched = true;
                        pos += 1;
                    }
                }
                _ => {
                    matched = true;
                    pos += 1;
                }
            }
        } else {
            matched = false;
            pos = 0;
        }
    }
    matched
}

// ---------------------------------------------------------------------------------------------
// Stage 2
// ---------------------------------------------------------------------------------------------

fn absolute_string(p: &Path) -> String {
    std::path::absolute(p)
        .unwrap_or_else(|_| p.to_path_buf())
        .to_string_lossy()
        .into_owned()
}

fn is_writable_dir(p: &Path) -> bool {
    std::fs::metadata(p)
        .map(|m| !m.permissions().readonly())
        .unwrap_or(false)
}

/// Fractureiser stage 2 indicators on this machine (absolute paths). Any hit blocks every launch,
/// since it means the machine, not the pack, is infected.
///
/// * Windows (when `<appdata>\Microsoft\Windows\Start Menu\Programs\Startup` is a writable
///   folder): `<appdata>\Microsoft Edge` (the real Edge lives under `LOCALAPPDATA`), and files in
///   that Startup folder named exactly as in [`STAGE2_STARTUP_FILES`];
/// * Linux: `$HOME/.config/.data/lib.jar`.
///
/// `<appdata>` is `%APPDATA%`, or `~/AppData/Roaming` when it is unset.
pub fn check_stage2(env: &Stage2Env) -> Vec<String> {
    let mut found = Vec::new();
    let appdata = env
        .appdata
        .clone()
        .or_else(|| env.home.as_ref().map(|h| h.join("AppData").join("Roaming")));
    if let Some(appdata) = appdata {
        let startup = appdata
            .join("Microsoft")
            .join("Windows")
            .join("Start Menu")
            .join("Programs")
            .join("Startup");
        if startup.is_dir() && is_writable_dir(&startup) {
            let edge = appdata.join("Microsoft Edge");
            if edge.exists() {
                found.push(absolute_string(&edge));
            }
            if let Ok(rd) = std::fs::read_dir(&startup) {
                let mut hits: Vec<PathBuf> = rd
                    .flatten()
                    .filter(|e| {
                        let name = e.file_name();
                        let name = name.to_string_lossy();
                        STAGE2_STARTUP_FILES.contains(&name.as_ref())
                    })
                    .map(|e| e.path())
                    .collect();
                hits.sort();
                found.extend(hits.iter().map(|p| absolute_string(p)));
            }
        }
    }
    if env.is_linux
        && let Some(home) = &env.home
    {
        let lib = home.join(".config").join(".data").join("lib.jar");
        if lib.exists() {
            found.push(absolute_string(&lib));
        }
    }
    found
}
