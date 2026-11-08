// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! `ColdStartProfiler`: opt-in startup phase timing for the cold-start benchmark scripts.
//!
//! Enabled when `MMCL_PROFILE_OUTPUT` names a file. Phases are marked with [`mark`]; when
//! the first frame has painted the app calls [`write_and_maybe_exit`], which appends one CSV
//! row per phase and, when `MMCL_EXIT_AFTER_PAINT` is `true` (any case) or `1`, exits ~100 ms
//! later. The CSV columns are unchanged from the Java launcher so the existing analysis
//! scripts keep working (`jdk_version` carries the Rust compiler, `cds_enabled` is `false`).

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::LazyLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use indexmap::IndexMap;
use parking_lot::Mutex;

/// Environment variable naming the CSV output file.
pub const ENV_OUTPUT: &str = "MMCL_PROFILE_OUTPUT";
/// Environment variable that asks for an exit after the write.
pub const ENV_EXIT_AFTER_PAINT: &str = "MMCL_EXIT_AFTER_PAINT";

/// The CSV header.
pub const CSV_HEADER: &str =
    "ts_iso,run_id,jvm_start_epoch_ms,jdk_version,os,arch,cds_enabled,phase,offset_ms";

/// A profiler: an anchor, ordered phase marks and an output file.
pub struct Profiler {
    anchor: Instant,
    start_epoch_ms: u128,
    output: Option<PathBuf>,
    exit_after_paint: bool,
    marks: Mutex<IndexMap<String, u128>>,
    flushed: AtomicBool,
}

fn non_blank(v: Option<String>) -> Option<String> {
    v.filter(|s| !s.trim().is_empty())
}

impl Profiler {
    /// A profiler anchored now. Disabled when `output` is `None`.
    pub fn new(output: Option<PathBuf>, exit_after_paint: bool) -> Self {
        Profiler {
            anchor: Instant::now(),
            start_epoch_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0),
            exit_after_paint: exit_after_paint && output.is_some(),
            output,
            marks: Mutex::new(IndexMap::new()),
            flushed: AtomicBool::new(false),
        }
    }

    /// Configured from the environment (`env` is the lookup, injectable for tests).
    pub fn from_env(env: &dyn Fn(&str) -> Option<String>) -> Self {
        let output = non_blank(env(ENV_OUTPUT)).map(PathBuf::from);
        let exit = non_blank(env(ENV_EXIT_AFTER_PAINT))
            .is_some_and(|v| v.trim().eq_ignore_ascii_case("true") || v.trim() == "1");
        Profiler::new(output, exit)
    }

    /// `true` when an output file is configured.
    pub fn is_enabled(&self) -> bool {
        self.output.is_some()
    }

    /// `true` when the process should exit after writing.
    pub fn exits_after_paint(&self) -> bool {
        self.exit_after_paint
    }

    /// Records `phase` at the current offset. A repeated phase keeps its first position and
    /// takes the latest offset.
    pub fn mark(&self, phase: &str) {
        if self.is_enabled() {
            let ns = self.anchor.elapsed().as_nanos();
            self.marks.lock().insert(phase.to_string(), ns);
        }
    }

    /// The recorded phases and their offsets in nanoseconds, in insertion order.
    pub fn marks(&self) -> Vec<(String, u128)> {
        self.marks
            .lock()
            .iter()
            .map(|(k, v)| (k.clone(), *v))
            .collect()
    }

    /// Appends the CSV rows once (later calls do nothing). Returns `true` when the caller
    /// should exit the process.
    pub fn write(&self) -> bool {
        let Some(out) = &self.output else {
            return false;
        };
        if self
            .flushed
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return false;
        }
        if let Err(e) = self.flush_csv(out) {
            // Profiling I/O must never take the launcher down; the benchmark wrapper detects
            // the missing row and re-runs.
            eprintln!(
                "cold-start profiler: could not write {}: {e}",
                out.display()
            );
        }
        self.exit_after_paint
    }

    fn flush_csv(&self, out: &Path) -> std::io::Result<()> {
        let snapshot = self.marks();
        if snapshot.is_empty() {
            return Ok(());
        }
        if let Some(parent) = out.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent)?;
        }
        let new_file = std::fs::metadata(out).map(|m| m.len() == 0).unwrap_or(true);
        let ts = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
        let run_id = self.start_epoch_ms.to_string();
        let compiler = escape_csv(mmcl_core::build::info().compiler);
        let os = escape_csv(std::env::consts::OS);
        let arch = escape_csv(std::env::consts::ARCH);
        let mut text = String::new();
        if new_file {
            text.push_str(CSV_HEADER);
            text.push('\n');
        }
        for (phase, ns) in snapshot {
            text.push_str(&format!(
                "{ts},{run_id},{},{compiler},{os},{arch},false,{},{}\n",
                self.start_epoch_ms,
                escape_csv(&phase),
                ns_to_ms(ns)
            ));
        }
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(out)?;
        f.write_all(text.as_bytes())
    }
}

/// Rounds nanoseconds to the nearest millisecond.
pub fn ns_to_ms(ns: u128) -> u128 {
    (ns + 500_000) / 1_000_000
}

/// CSV escaping: quoted (with doubled inner quotes) when the field holds `,`, `"` or `\n`.
pub fn escape_csv(s: &str) -> String {
    if s.contains([',', '"', '\n']) {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_string()
    }
}

static GLOBAL: LazyLock<Profiler> =
    LazyLock::new(|| Profiler::from_env(&|k| std::env::var(k).ok()));

/// Captures the anchor. Call as early as possible in `main`; any other call also anchors
/// on first use.
pub fn anchor() {
    LazyLock::force(&GLOBAL);
}

/// The process-wide profiler.
pub fn global() -> &'static Profiler {
    &GLOBAL
}

/// `true` when profiling is on for this process.
pub fn is_enabled() -> bool {
    GLOBAL.is_enabled()
}

/// Marks a phase on the process-wide profiler.
pub fn mark(phase: &str) {
    GLOBAL.mark(phase);
}

/// Writes the CSV rows once. When an exit was requested, a helper thread waits ~100 ms (so
/// the current frame finishes), runs `before_exit` (the app passes a closure that asks the
/// UI to quit) and then exits the process with status 0.
pub fn write_and_maybe_exit(before_exit: impl FnOnce() + Send + 'static) {
    if !GLOBAL.write() {
        return;
    }
    let spawned = std::thread::Builder::new()
        .name("mmcl-profile-exit".into())
        .spawn(move || {
            std::thread::sleep(Duration::from_millis(100));
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(before_exit));
            std::process::exit(0);
        });
    if spawned.is_err() {
        std::process::exit(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_without_output() {
        let p = Profiler::from_env(&|_| None);
        assert!(!p.is_enabled());
        p.mark("x");
        assert!(p.marks().is_empty());
        assert!(!p.write());
        let p = Profiler::from_env(&|k| (k == ENV_OUTPUT).then(|| "   ".to_string()));
        assert!(!p.is_enabled());
    }

    #[test]
    fn exit_flag_parsing() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("p.csv").display().to_string();
        for (v, want) in [("TRUE", true), ("1", true), ("yes", false), ("0", false)] {
            let p = Profiler::from_env(&|k| match k {
                ENV_OUTPUT => Some(out.clone()),
                ENV_EXIT_AFTER_PAINT => Some(v.to_string()),
                _ => None,
            });
            assert_eq!(p.exits_after_paint(), want, "{v}");
        }
        let p = Profiler::from_env(&|k| (k == ENV_EXIT_AFTER_PAINT).then(|| "true".to_string()));
        assert!(!p.exits_after_paint(), "exit needs profiling enabled");
    }

    #[test]
    fn marks_keep_first_position_and_last_value() {
        let p = Profiler::new(Some(PathBuf::from("unused.csv")), false);
        p.mark("a");
        p.mark("b");
        std::thread::sleep(Duration::from_millis(2));
        p.mark("a");
        let m = p.marks();
        assert_eq!(
            m.iter().map(|(k, _)| k.as_str()).collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert!(m[0].1 > m[1].1);
    }

    #[test]
    fn writes_header_once_and_rows_per_phase() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("nested").join("p.csv");
        let p = Profiler::new(Some(out.clone()), true);
        p.mark("locale_ready");
        p.mark("main,menu \"painted\"");
        assert!(p.write());
        assert!(!p.write(), "runs once");
        let q = Profiler::new(Some(out.clone()), false);
        q.mark("second_run");
        assert!(!q.write());
        let text = std::fs::read_to_string(&out).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines[0], CSV_HEADER);
        assert_eq!(lines.len(), 4);
        assert_eq!(lines.iter().filter(|l| **l == CSV_HEADER).count(), 1);
        assert!(lines[1].contains(",false,locale_ready,"));
        assert!(lines[2].contains(",false,\"main,menu \"\"painted\"\"\","));
        assert_eq!(lines[1].split(',').count(), 9);
    }

    #[test]
    fn rounding_and_escaping() {
        assert_eq!(ns_to_ms(499_999), 0);
        assert_eq!(ns_to_ms(500_000), 1);
        assert_eq!(ns_to_ms(1_499_999), 1);
        assert_eq!(escape_csv("plain"), "plain");
        assert_eq!(escape_csv("a\nb"), "\"a\nb\"");
    }
}
