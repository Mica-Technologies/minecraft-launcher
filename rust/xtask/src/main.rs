// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Packaging, versioning and release tasks for the Rust launcher. Not shipped.
//!
//! ```text
//! cargo xtask version   [--mode local|pr|release|pre|tag] [--tag rs-X] [--sha S]
//!                       [--existing-tags FILE] [--github-output]
//! cargo xtask dist windows [--version V] [--win-version W] [--flavor release|dev]
//!                          [--exe PATH | --no-build] [--skip-installers] [--out DIR]
//! cargo xtask dist macos   [--version V] [--target T] [--sign ID] [--installer-sign ID]
//!                          [--notarize] [--flavor ..] [--exe PATH | --no-build] [--out DIR]
//! cargo xtask dist linux   [--version V] [--target T] [--formats deb,rpm,tar,appimage]
//!                          [--flavor ..] [--exe PATH | --no-build] [--out DIR]
//! cargo xtask checksums <dir> [--out FILE]
//! cargo xtask win-manifest <exe> [--manifest FILE] [--check]
//! ```
//! (`cargo xtask` is `cargo run -p xtask --`.)

mod checksums;
mod ctx;
mod linux;
mod macos;
mod version;
mod win_manifest;
mod windows;
#[cfg(test)]
mod wix_check;

use std::io::Write;
use std::path::PathBuf;

use anyhow::{Context, Result, bail};
use mmcl_core::{ACTIVE_IDENTITY, AppIdentity, Flavor};

use ctx::Args;

const SWITCHES: &[&str] = &[
    "skip-installers",
    "no-build",
    "notarize",
    "github-output",
    "check",
];

const USAGE: &str = "usage:
  cargo xtask version [--mode local|pr|release|pre|tag] [--tag TAG] [--sha SHA] [--existing-tags FILE] [--github-output]
  cargo xtask dist windows|macos|linux [--version V] [--win-version W] [--flavor release|dev] [--target T]
                                       [--exe PATH | --no-build] [--out DIR]
                                       windows: [--skip-installers]
                                       macos:   [--sign ID] [--installer-sign ID] [--notarize]
                                       linux:   [--formats deb,rpm,tar,appimage]
  cargo xtask checksums <dir> [--out FILE]
  cargo xtask win-manifest <exe> [--manifest FILE] [--check]";

fn main() {
    let raw: Vec<String> = std::env::args().skip(1).collect();
    if let Err(e) = run(&raw) {
        eprintln!("xtask: error: {e:#}");
        std::process::exit(1);
    }
}

fn run(raw: &[String]) -> Result<()> {
    let args = Args::parse(raw, SWITCHES)?;
    let words: Vec<&str> = args.positional.iter().map(String::as_str).collect();
    match words.as_slice() {
        ["version", ..] => cmd_version(&args),
        ["dist", "windows", ..] => {
            let ctx = ctx::Ctx::new(&args, windows::DEFAULT_TARGET)?;
            log_versions(&ctx);
            windows::dist(&ctx, &args)
        }
        ["dist", "macos", ..] => {
            let ctx = ctx::Ctx::new(&args, macos::default_target())?;
            log_versions(&ctx);
            macos::dist(&ctx, &args)
        }
        ["dist", "linux", ..] => {
            let ctx = ctx::Ctx::new(&args, linux::DEFAULT_TARGET)?;
            log_versions(&ctx);
            linux::dist(&ctx, &args)
        }
        ["win-manifest", exe, ..] => {
            let exe = PathBuf::from(exe);
            if args.flag("check") {
                win_manifest::verify(&exe, None)?;
                eprintln!(
                    "xtask: {} carries the launcher manifest (one RT_MANIFEST #1, every required setting)",
                    exe.display()
                );
                Ok(())
            } else {
                let manifest = args.get("manifest").map_or_else(
                    || ctx::workspace_dir().join(win_manifest::SOURCE),
                    PathBuf::from,
                );
                win_manifest::apply(&exe, &manifest)
            }
        }
        ["checksums", dir, ..] => {
            let sums = checksums::collect(&PathBuf::from(dir))?;
            let text = checksums::render(&sums);
            match args.get("out") {
                Some(out) => {
                    std::fs::write(out, &text).with_context(|| format!("cannot write {out}"))?
                }
                None => print!("{text}"),
            }
            Ok(())
        }
        ["help", ..] | [] => {
            println!("{USAGE}");
            Ok(())
        }
        other => bail!("unknown command {:?}\n{USAGE}", other.join(" ")),
    }
}

fn log_versions(ctx: &ctx::Ctx) {
    let v = &ctx.versions;
    eprintln!(
        "xtask: {} ({}) version {} | numeric {} | msi {} | bundle {} | mac {} ({}) | target {}",
        ctx.identity.display_name,
        ctx.flavor.as_str(),
        v.version,
        v.numeric,
        v.win_version,
        v.bundle_version,
        v.mac_short_version,
        v.mac_bundle_version,
        ctx.target
    );
}

/// Computes the version for a CI mode and prints `key=value` lines (also appended to
/// `$GITHUB_OUTPUT` with `--github-output`).
fn cmd_version(args: &Args) -> Result<()> {
    let identity = AppIdentity::of(ACTIVE_IDENTITY, Flavor::Release);
    let prefix = identity.release_tag_prefix;
    let repo = ctx::workspace_dir()
        .parent()
        .context("workspace without a parent")?
        .to_path_buf();
    let now = chrono::Utc::now();
    let base = version::base_date(now);
    let sha = || {
        args.get("sha")
            .map(|s| s.chars().take(7).collect::<String>())
            .or_else(|| ctx::git_short_sha(&repo))
            .unwrap_or_else(|| "unknown".into())
    };
    let mode = args.get("mode").unwrap_or("local");
    let (ver, prerelease) = match mode {
        "local" => (ctx::local_version(&repo, prefix), true),
        "pr" => {
            let v = ctx::local_version(&repo, prefix);
            if v == version::UNSTAMPED {
                (format!("{base}-dev+{}", sha()), true)
            } else {
                (v, true)
            }
        }
        "release" => {
            let tags = match args.get("existing-tags") {
                Some(file) => std::fs::read_to_string(file)
                    .with_context(|| format!("cannot read {file}"))?
                    .lines()
                    .map(|l| version::strip_tag_prefix(l.trim(), prefix).to_string())
                    .collect(),
                None => ctx::git_tags(&repo, prefix),
            };
            (
                version::release_version(&base, &tags),
                identity.releases_are_prereleases,
            )
        }
        "pre" => (version::pre_release_version(&base, now, &sha()), true),
        "tag" => {
            let tag = args
                .get("tag")
                .map(str::to_string)
                .or_else(|| std::env::var("GITHUB_REF_NAME").ok())
                .context("--mode tag needs --tag (or GITHUB_REF_NAME)")?;
            let v = version::strip_tag_prefix(tag.trim(), prefix).to_string();
            if v.is_empty() {
                bail!("tag '{tag}' has no version after the '{prefix}' prefix");
            }
            (v, identity.releases_are_prereleases)
        }
        other => bail!("--mode must be local, pr, release, pre or tag, not '{other}'"),
    };
    let set = version::VersionSet::new(&ver, args.get("win-version"), now);
    let lines = [
        format!("version={}", set.version),
        format!("numeric={}", set.numeric),
        format!("win_version={}", set.win_version),
        format!("bundle_version={}", set.bundle_version),
        format!("mac_short_version={}", set.mac_short_version),
        format!("tag={prefix}{}", set.version),
        format!("prerelease={prerelease}"),
    ];
    for l in &lines {
        println!("{l}");
    }
    if args.flag("github-output") {
        let path = std::env::var("GITHUB_OUTPUT").context("GITHUB_OUTPUT is not set")?;
        let mut f = std::fs::OpenOptions::new()
            .append(true)
            .create(true)
            .open(&path)?;
        for l in &lines {
            writeln!(f, "{l}")?;
        }
    }
    Ok(())
}
