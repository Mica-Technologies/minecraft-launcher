// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The pack side of the pre-launch security scan (Java `GameModPack.scanModPackRootFolder`).
//!
//! The scanners live in `mmcl-scan`; this module filters the manifest's (untrusted) exclusions,
//! applies scan acknowledgements to heuristic findings, logs paste-ready acknowledgement
//! snippets, and decides what blocks the launch: Nekodetector stage-1 and stage-2 hits and
//! supplemental HIGH findings. Supplemental MEDIUM findings never block, and Nekodetector hits
//! can never be acknowledged.

use std::path::Path;
use std::sync::Arc;

use mmcl_i18n::{t, tf};
use mmcl_manifest::{FindingKey, ScanAcknowledgement};
use mmcl_net::CancelToken;
use mmcl_progress::ProgressSink;
use mmcl_scan::neko::Stage2Env;
use mmcl_scan::{Finding, Results, ScanBlocked, Severity};

use crate::error::{ModpackError, Result};
use crate::pack::GameModPack;

/// Scan threads: half the cores, at least one.
pub fn scan_threads() -> usize {
    let cores = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2);
    (cores / 2).max(1)
}

fn key_of(f: &Finding) -> FindingKey<'_> {
    FindingKey {
        kind: Some(f.kind.as_str()),
        file_sha256: f.file_sha256.as_deref(),
        inner_sha256: f.inner_sha256.as_deref(),
        locator: Some(f.locator.as_str()),
    }
}

/// The acknowledgement matching `f`, if any.
pub fn acknowledgement_for<'a>(
    acks: &'a [ScanAcknowledgement],
    f: &Finding,
) -> Option<&'a ScanAcknowledgement> {
    acks.iter().find(|a| a.matches(Some(&key_of(f))))
}

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// The paste-ready acknowledgement lines for a finding (empty `Vec` plus the "no signature"
/// line when neither hash is known).
pub fn acknowledgement_hint(f: &Finding) -> Vec<String> {
    let file_sha = f.file_sha256.as_deref().filter(|s| !s.trim().is_empty());
    let inner_sha = f.inner_sha256.as_deref().filter(|s| !s.trim().is_empty());
    if file_sha.is_none() && inner_sha.is_none() {
        return vec![t!("log.gameModPack.noAckSignature").into_owned()];
    }
    let mut out = vec![
        t!("log.gameModPack.silenceHintHeader").into_owned(),
        "  {".into(),
    ];
    match inner_sha {
        Some(i) => {
            out.push(format!(
                "    // {}",
                t!("log.gameModPack.silenceHintRecommended")
            ));
            out.push(format!("    \"innerSha256\": \"{}\",", json_escape(i)));
        }
        None => out.push(format!(
            "    // {}",
            t!("log.gameModPack.silenceHintInnerUnavailable")
        )),
    }
    if let Some(fs) = file_sha {
        out.push(format!(
            "    // {}",
            t!("log.gameModPack.silenceHintAlternative")
        ));
        out.push(format!("    // \"fileSha256\": \"{}\",", json_escape(fs)));
    }
    out.push(format!(
        "    \"kind\":        \"{}\",",
        json_escape(f.kind.as_str())
    ));
    out.push(format!(
        "    \"locator\":     \"{}\",",
        json_escape(&f.locator)
    ));
    out.push("    \"reason\":      \"\"".into());
    out.push("  }".into());
    out
}

/// Decides the outcome from finished scanner results (pure apart from logging).
pub fn evaluate(
    pack_name: &str,
    pack_root: &Path,
    neko: &Results,
    supplemental: Vec<Finding>,
    acks: &[ScanAcknowledgement],
    sink: &dyn ProgressSink,
) -> Result<()> {
    let mut remaining = Vec::new();
    for f in supplemental {
        match acknowledgement_for(acks, &f) {
            Some(a) => {
                let suffix = a
                    .reason
                    .as_deref()
                    .filter(|r| !r.trim().is_empty())
                    .map(|r| tf!("log.gameModPack.ackReasonSuffix", r))
                    .unwrap_or_default();
                let line = tf!("log.gameModPack.acknowledgedFinding", f.to_string(), suffix);
                sink.set_curr_text(&line);
                mmcl_log::std(&line);
            }
            None => remaining.push(f),
        }
    }
    for hit in neko.stage1.iter().chain(neko.stage2.iter()) {
        mmcl_log::warn_silent(&tf!("log.gameModPack.scanFlagged", hit));
        mmcl_log::std(&t!("log.gameModPack.nekoCannotAckHint"));
    }
    for f in &remaining {
        let key = if f.severity == Severity::High {
            "log.gameModPack.supplementalInfection"
        } else {
            "log.gameModPack.supplementalWarning"
        };
        sink.set_curr_text(&tf!(key, f.to_string()));
        mmcl_log::warn_silent(&tf!(
            "log.gameModPack.supplementalScanFlagged",
            f.to_string()
        ));
        for line in acknowledgement_hint(f) {
            mmcl_log::std(&line);
        }
    }
    match ScanBlocked::from_results(
        neko,
        &remaining,
        Some(pack_name.to_string()),
        Some(pack_root.to_path_buf()),
    ) {
        None => Ok(()),
        Some(b) => {
            let total = b.stage1_and_high.len() + b.stage2.len();
            let key = if total == 1 {
                "log.gameModPack.scanBlockedLaunchOne"
            } else {
                "log.gameModPack.scanBlockedLaunchMany"
            };
            sink.set_curr_text(&tf!(key, total.to_string()));
            Err(ModpackError::ScanDetection(Box::new(b)))
        }
    }
}

/// Scans the pack folder with both scanners and applies the pack's acknowledgements.
pub async fn scan_pack_root(
    pack: &GameModPack,
    sink: Arc<dyn ProgressSink>,
    cancel: CancelToken,
) -> Result<()> {
    let root = pack.checked_pack_root()?;
    let exclusions = mmcl_scan::filter_untrusted(pack.manifest().scan_exclusions());
    let threads = scan_threads();
    let (r, ex, s, c) = (
        root.clone(),
        exclusions.clone(),
        sink.clone(),
        cancel.clone(),
    );
    let neko = crate::managed::blocking(move || {
        let log = |line: &str| s.set_curr_text(&mmcl_scan::strip_ansi(line));
        mmcl_scan::neko::run_with_env(
            threads,
            &r,
            true,
            &ex,
            &log,
            None,
            &c,
            &Stage2Env::from_system(),
        )
    })
    .await?
    .map_err(|e| ModpackError::Scan(e.to_string()))?;
    let (r, ex) = (root.clone(), exclusions);
    let supplemental = crate::managed::blocking(move || {
        mmcl_scan::supplemental::scan_folder(&r, &ex, threads, &cancel)
    })
    .await?
    .unwrap_or_else(|e| {
        mmcl_log::warn_silent(&tf!(
            "log.gameModPack.supplementalScanIoError",
            e.kind().to_string()
        ));
        Vec::new()
    });
    evaluate(
        &pack.name(),
        &root,
        &neko,
        supplemental,
        pack.manifest().scan_acknowledgements(),
        sink.as_ref(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use mmcl_progress::NullSink;
    use mmcl_scan::Kind;
    use std::path::PathBuf;

    fn finding(sev: Severity, inner: Option<&str>, file: Option<&str>) -> Finding {
        Finding {
            severity: sev,
            kind: Kind::LauncherCredentialFileRef,
            file: PathBuf::from("/p/mods/x.jar"),
            file_sha256: file.map(str::to_string),
            inner_sha256: inner.map(str::to_string),
            locator: "optifine/Installer.updateLauncherJson".into(),
            message: "m".into(),
        }
    }

    #[test]
    fn acknowledged_high_does_not_block() {
        let ack = ScanAcknowledgement {
            inner_sha256: Some("AA".into()),
            kind: Some("launcher_credential_file_ref".into()),
            locator: Some("optifine/Installer.updateLauncherJson".into()),
            reason: Some("ok".into()),
            ..Default::default()
        };
        let r = evaluate(
            "P",
            Path::new("/p"),
            &Results::default(),
            vec![finding(Severity::High, Some("aa"), None)],
            &[ack],
            &NullSink,
        );
        assert!(r.is_ok());
    }

    #[test]
    fn unacknowledged_high_blocks_medium_does_not() {
        let r = evaluate(
            "P",
            Path::new("/p"),
            &Results::default(),
            vec![finding(Severity::Medium, None, None)],
            &[],
            &NullSink,
        );
        assert!(r.is_ok());
        let r = evaluate(
            "P",
            Path::new("/p"),
            &Results::default(),
            vec![finding(Severity::High, Some("aa"), Some("bb"))],
            &[],
            &NullSink,
        );
        assert!(matches!(r, Err(ModpackError::ScanDetection(_))));
    }

    #[test]
    fn neko_hits_always_block() {
        let neko = Results {
            stage1: vec!["/p/mods/evil.jar".into()],
            stage2: vec![],
        };
        let ack = ScanAcknowledgement {
            file_sha256: Some("x".into()),
            kind: Some("x".into()),
            ..Default::default()
        };
        let r = evaluate("P", Path::new("/p"), &neko, vec![], &[ack], &NullSink);
        let Err(ModpackError::ScanDetection(b)) = r else {
            panic!("blocked expected")
        };
        assert_eq!(b.stage1_and_high, ["/p/mods/evil.jar"]);
    }

    #[test]
    fn hint_prefers_inner_hash() {
        let h = acknowledgement_hint(&finding(Severity::High, Some("in\"ner"), Some("outer")));
        assert!(h.iter().any(|l| l == "    \"innerSha256\": \"in\\\"ner\","));
        assert!(h.iter().any(|l| l == "    // \"fileSha256\": \"outer\","));
        let none = acknowledgement_hint(&finding(Severity::High, None, None));
        assert_eq!(none.len(), 1);
    }

    #[tokio::test]
    async fn scans_a_clean_folder() {
        let d = tempfile::tempdir().unwrap();
        let paths = Arc::new(mmcl_core::DataPaths::at_root(
            d.path(),
            mmcl_core::GameMode::Client,
        ));
        let p = GameModPack::from_manifest(
            paths,
            mmcl_manifest::PackManifest {
                pack_name: Some("Clean".into()),
                ..Default::default()
            },
            None,
            None,
        );
        p.prepare_environment().unwrap();
        std::fs::write(p.mods_dir().join("notajar.txt"), "x").unwrap();
        scan_pack_root(&p, Arc::new(NullSink), CancelToken::new())
            .await
            .unwrap();
    }
}
