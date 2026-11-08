// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The developer manifest diagnostic (`--diag-manifest <url>`, Java
//! `LauncherCore.runManifestDiagnostic`): fetches a pack manifest, prints its resolved state,
//! resolves the pack's Minecraft library manifest twice and reports whether the second
//! resolve was memoized (client.json parsed once per pack). It never signs in or starts a game.
//!
//! The output is English developer text, as in Java (the spec keeps it unlocalized).

use std::io::Write;
use std::sync::Arc;
use std::time::Instant;

use mmcl_core::GameMode;
use mmcl_net::CancelToken;

use super::{Engine, EngineOptions, RuntimeChoice};
use crate::model::ManifestDiagnostic;

const PREFIX: &str = "[manifest-diag]";

/// The timing and verdict lines of the two resolves (Java's format).
pub fn resolve_lines(first_ms: f64, second_ms: f64, memoized: bool) -> Vec<String> {
    vec![
        format!("{PREFIX} 1st resolve: {first_ms:.1} ms (downloads + parses client.json)"),
        format!("{PREFIX} 2nd resolve: {second_ms:.3} ms"),
        format!("{PREFIX} same instance (memoized -> parsed once): {memoized}"),
        if memoized {
            format!("{PREFIX} RESULT: PASS - library manifest resolved once per pack")
        } else {
            format!("{PREFIX} RESULT: FAIL - second resolve rebuilt the manifest (re-parse)")
        },
    ]
}

fn ms_since(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

impl Engine {
    /// Runs the manifest diagnostic for `url` (an `https:` manifest URL or a `file:` URL) and
    /// returns its lines and exit code (0 memoized, 1 not memoized, 2 fetch / resolve failed).
    pub async fn manifest_diagnostic(&self, url: &str) -> ManifestDiagnostic {
        let mut lines = vec![format!("{PREFIX} resolving manifest: {}", url.trim())];
        let fail = |mut lines: Vec<String>, what: String| {
            lines.push(what);
            ManifestDiagnostic {
                lines,
                exit_code: 2,
            }
        };
        let pack = self.inner.packs.fetcher().get(url.trim(), false).await;
        if let Some(e) = pack.failed_error() {
            lines.push(format!(
                "{PREFIX} FAILED: could not fetch / parse the manifest"
            ));
            return fail(lines, format!("{PREFIX} cause: {e}"));
        }
        let mc = match pack.minecraft_version().await {
            Ok(Some(v)) => v,
            Ok(None) => return fail(lines, format!("{PREFIX} ERROR: no Minecraft version")),
            Err(e) => return fail(lines, format!("{PREFIX} ERROR: {e}")),
        };
        lines.push(format!(
            "{PREFIX} pack: {}  (MC {mc})",
            pack.friendly_name().unwrap_or_else(|| pack.name())
        ));
        let loader = match (
            pack.mod_loader_type(),
            pack.loader_version().await.ok().flatten(),
        ) {
            (Some(t), Some(v)) => format!("{t} {v}"),
            (Some(t), None) => t,
            (None, _) => "none (vanilla)".to_string(),
        };
        let m = pack.manifest();
        lines.push(format!("{PREFIX} loader: {loader}"));
        lines.push(format!(
            "{PREFIX} files: {} mods, min RAM {} GB, manifest sha256 {}",
            m.mods().len(),
            pack.min_ram_gb(),
            pack.manifest_sha256().unwrap_or("-")
        ));
        lines.push(format!("{PREFIX} folder: {}", pack.pack_root().display()));

        let run = mmcl_launch::run::PackRun::new(
            self.inner.launch_env.clone(),
            pack.clone(),
            CancelToken::new(),
        );
        let t0 = Instant::now();
        let m1 = match mmcl_launch::vanilla::library_manifest(&run, &mc).await {
            Ok(m) => m,
            Err(e) => return fail(lines, format!("{PREFIX} ERROR: {e}")),
        };
        let first = ms_since(t0);
        let t1 = Instant::now();
        let m2 = match mmcl_launch::vanilla::library_manifest(&run, &mc).await {
            Ok(m) => m,
            Err(e) => return fail(lines, format!("{PREFIX} ERROR: {e}")),
        };
        let second = ms_since(t1);
        let memoized = Arc::ptr_eq(&m1, &m2);
        lines.extend(resolve_lines(first, second, memoized));
        ManifestDiagnostic {
            lines,
            exit_code: if memoized { 0 } else { 1 },
        }
    }
}

/// `--diag-manifest <url>` for `mmcl-app`: builds a quiet client-mode engine over the
/// standard data folder (no background work, no sign-in), runs
/// [`Engine::manifest_diagnostic`], writes each line to `out` and returns the exit code. Call
/// it from `main`, never from inside a tokio runtime.
pub fn run_manifest_diagnostic(url: &str, out: &mut dyn Write) -> i32 {
    let mut o = EngineOptions::for_mode(GameMode::Client);
    o.runtime = RuntimeChoice::Own {
        worker_threads: Some(2),
    };
    o.start_background_work = false;
    o.follow_running_games = false;
    let engine = match Engine::new(o) {
        Ok(e) => e,
        Err(e) => {
            let _ = writeln!(out, "{PREFIX} ERROR: {e:#}");
            return 2;
        }
    };
    let report = engine.block_on(engine.manifest_diagnostic(url));
    for l in &report.lines {
        let _ = writeln!(out, "{l}");
    }
    let _ = out.flush();
    report.exit_code
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_core::DataPaths;

    #[test]
    fn resolve_lines_match_the_java_format() {
        let l = resolve_lines(12.345, 0.0123, true);
        assert_eq!(
            l[0],
            "[manifest-diag] 1st resolve: 12.3 ms (downloads + parses client.json)"
        );
        assert_eq!(l[1], "[manifest-diag] 2nd resolve: 0.012 ms");
        assert_eq!(
            l[2],
            "[manifest-diag] same instance (memoized -> parsed once): true"
        );
        assert!(l[3].contains("PASS"));
        assert!(resolve_lines(1.0, 1.0, false)[3].contains("FAIL"));
    }

    #[test]
    fn a_missing_manifest_fails_with_exit_code_2() {
        let d = tempfile::tempdir().unwrap();
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .unwrap();
        let e = Engine::new(EngineOptions::offline_for_tests(
            DataPaths::at_root(d.path(), GameMode::Client),
            rt.handle().clone(),
        ))
        .unwrap();
        let url = mmcl_modpack::fetch::path_to_file_url(&d.path().join("none.json"));
        let r = futures::executor::block_on(e.spawn({
            let e = e.clone();
            async move { e.manifest_diagnostic(&url).await }
        }))
        .unwrap();
        assert_eq!(r.exit_code, 2);
        assert!(r.lines[0].starts_with("[manifest-diag] resolving manifest: file:"));
        assert!(r.lines.iter().any(|l| l.contains("FAILED")));
    }

    #[test]
    fn a_manifest_without_a_loaded_minecraft_version_reports_an_error_offline() {
        let d = tempfile::tempdir().unwrap();
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .unwrap();
        let e = Engine::new(EngineOptions::offline_for_tests(
            DataPaths::at_root(d.path().join("data"), GameMode::Client),
            rt.handle().clone(),
        ))
        .unwrap();
        let file = d.path().join("p.json");
        std::fs::write(
            &file,
            r#"{"packName":"Diag","packVersion":"1","packModLoader":"fabric","packMods":[]}"#,
        )
        .unwrap();
        let url = mmcl_modpack::fetch::path_to_file_url(&file);
        let r = futures::executor::block_on(e.spawn({
            let e = e.clone();
            async move { e.manifest_diagnostic(&url).await }
        }))
        .unwrap();
        // Offline, the loader cannot be resolved, so the diagnostic stops with an error.
        assert_eq!(r.exit_code, 2, "{:?}", r.lines);
        assert!(r.lines.last().unwrap().contains("ERROR"), "{:?}", r.lines);
    }
}
