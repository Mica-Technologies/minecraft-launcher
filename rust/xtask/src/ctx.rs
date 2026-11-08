// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Shared plumbing: command-line options, workspace paths, the product identity, and running
//! external tools with their full command line logged and their output passed through.

use std::collections::BTreeMap;
use std::ffi::OsStr;
use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};
use mmcl_core::{ACTIVE_IDENTITY, AppIdentity, Flavor};

use crate::version::{self, VersionSet};

/// The cargo package and binary that ship.
pub const APP_PACKAGE: &str = "mmcl-app";
pub const APP_BIN: &str = "mica-minecraft-launcher";
pub const MANUFACTURER: &str = mmcl_core::identity::VENDOR;
pub const HELP_URL: &str = "https://github.com/Mica-Technologies/minecraft-launcher/issues";
pub const ABOUT_URL: &str = "https://minecraft.micatechnologies.com/";
pub const RELEASES_URL: &str = "https://github.com/Mica-Technologies/minecraft-launcher/releases";
pub const LICENSE_URL: &str = "https://www.gnu.org/licenses/gpl-3.0.en.html";

/// `--key value` / `--key=value` options plus positional words. Flags listed in `switches`
/// take no value.
#[derive(Debug, Default)]
pub struct Args {
    pub positional: Vec<String>,
    options: BTreeMap<String, String>,
}

impl Args {
    pub fn parse(raw: &[String], switches: &[&str]) -> Result<Args> {
        let mut args = Args::default();
        let mut it = raw.iter();
        while let Some(word) = it.next() {
            if let Some(name) = word.strip_prefix("--") {
                if let Some((k, v)) = name.split_once('=') {
                    args.options.insert(k.to_string(), v.to_string());
                } else if switches.contains(&name) {
                    args.options.insert(name.to_string(), "true".to_string());
                } else {
                    let value = it
                        .next()
                        .with_context(|| format!("--{name} needs a value"))?;
                    args.options.insert(name.to_string(), value.clone());
                }
            } else {
                args.positional.push(word.clone());
            }
        }
        Ok(args)
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.options.get(key).map(String::as_str)
    }

    pub fn flag(&self, key: &str) -> bool {
        matches!(self.get(key), Some("true") | Some("1") | Some("yes"))
    }
}

/// Everything a dist run needs to know.
pub struct Ctx {
    /// `rust/` (the cargo workspace).
    pub workspace: PathBuf,
    /// The repository root (holds `LICENSE` and `deploy/`).
    pub repo: PathBuf,
    pub target_dir: PathBuf,
    /// `target/dist`.
    pub dist: PathBuf,
    /// Final, renamed artifacts (`target/dist/release` unless `--out`).
    pub out: PathBuf,
    pub identity: AppIdentity,
    pub flavor: Flavor,
    pub versions: VersionSet,
    pub target: String,
    /// A prebuilt binary to package instead of running `cargo build`.
    pub prebuilt: Option<PathBuf>,
    pub no_build: bool,
}

impl Ctx {
    pub fn new(args: &Args, default_target: &str) -> Result<Ctx> {
        let workspace = workspace_dir();
        let repo = workspace
            .parent()
            .context("the workspace has no parent directory")?
            .to_path_buf();
        let target_dir = std::env::var_os("CARGO_TARGET_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| workspace.join("target"));
        let dist = target_dir.join("dist");
        let out = args
            .get("out")
            .map(|o| absolutize(&workspace, Path::new(o)))
            .unwrap_or_else(|| dist.join("release"));
        let flavor = match args.get("flavor").unwrap_or("release") {
            f if f.eq_ignore_ascii_case("release") => Flavor::Release,
            f if f.eq_ignore_ascii_case("dev") => Flavor::Dev,
            other => bail!("--flavor must be release or dev, not '{other}'"),
        };
        let identity = AppIdentity::of(ACTIVE_IDENTITY, flavor);
        let version = match args.get("version") {
            Some(v) if !v.trim().is_empty() => v.trim().to_string(),
            _ => local_version(&repo, identity.release_tag_prefix),
        };
        let versions = VersionSet::new(&version, args.get("win-version"), chrono::Utc::now());
        let target = args.get("target").unwrap_or(default_target).to_string();
        Ok(Ctx {
            workspace: workspace.clone(),
            repo,
            target_dir,
            dist,
            out,
            identity,
            flavor,
            versions,
            target,
            prebuilt: args
                .get("exe")
                .map(|e| absolutize(&workspace, Path::new(e))),
            no_build: args.flag("no-build"),
        })
    }

    /// `rust/packaging/<sub>`.
    pub fn packaging(&self, sub: &str) -> PathBuf {
        self.workspace.join("packaging").join(sub)
    }

    /// `deploy/<sub>` (the Java launcher's icons and bitmaps, reused).
    pub fn deploy(&self, sub: &str) -> PathBuf {
        self.repo.join("deploy").join(sub)
    }

    pub fn license(&self) -> PathBuf {
        self.repo.join("LICENSE")
    }

    /// A clean staging folder under `target/dist/stage/<name>-<flavor>`.
    pub fn fresh_stage(&self, name: &str) -> Result<PathBuf> {
        let dir = self.dist.join("stage").join(format!(
            "{name}-{}",
            self.flavor.as_str().to_ascii_lowercase()
        ));
        if dir.exists() {
            std::fs::remove_dir_all(&dir)
                .with_context(|| format!("cannot clear {}", dir.display()))?;
        }
        std::fs::create_dir_all(&dir)?;
        Ok(dir)
    }

    pub fn packaging_out(&self) -> PathBuf {
        self.dist.join("packaging")
    }

    /// Builds (or locates) the release binary for the target.
    pub fn app_binary(&self) -> Result<PathBuf> {
        if let Some(exe) = &self.prebuilt {
            if !exe.is_file() {
                bail!("--exe {} does not exist", exe.display());
            }
            return Ok(exe.clone());
        }
        let exe_name = if self.target.contains("windows") {
            format!("{APP_BIN}.exe")
        } else {
            APP_BIN.to_string()
        };
        let built = self
            .target_dir
            .join(&self.target)
            .join("release")
            .join(exe_name);
        if !self.no_build {
            let mut cmd = Command::new(std::env::var("CARGO").unwrap_or_else(|_| "cargo".into()));
            cmd.current_dir(&self.workspace)
                .args([
                    "build",
                    "--release",
                    "--locked",
                    "-p",
                    APP_PACKAGE,
                    "--target",
                ])
                .arg(&self.target)
                .envs(self.build_env());
            run(&mut cmd)?;
        }
        if !built.is_file() {
            bail!("the launcher binary was not found at {}", built.display());
        }
        Ok(built)
    }

    /// Environment that stamps version and flavour into the binary (read by mmcl-core's and
    /// mmcl-app's build scripts).
    pub fn build_env(&self) -> Vec<(String, String)> {
        vec![
            ("MMCL_VERSION".into(), self.versions.version.clone()),
            ("MMCL_VERSION_NUMERIC".into(), self.versions.numeric.clone()),
            ("MMCL_WIN_VERSION".into(), self.versions.win_version.clone()),
            ("MMCL_ENVIRONMENT".into(), self.flavor.as_str().to_string()),
        ]
    }
}

/// The cargo workspace (`rust/`), from this crate's manifest directory.
pub fn workspace_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("xtask lives inside the workspace")
        .to_path_buf()
}

fn absolutize(base: &Path, p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|cwd| cwd.join(p))
            .unwrap_or_else(|_| base.join(p))
    }
}

/// `MMCL_VERSION`, else `git describe` on the identity's tags, else the unstamped sentinel.
pub fn local_version(repo: &Path, tag_prefix: &str) -> String {
    let env = std::env::var("MMCL_VERSION").ok();
    let describe = git_describe(repo, tag_prefix);
    version::resolve(env.as_deref(), describe.as_deref(), tag_prefix)
}

/// `git describe --tags --match '<prefix>*' --dirty=.dirty`, or None without such a tag.
pub fn git_describe(repo: &Path, tag_prefix: &str) -> Option<String> {
    let pattern = format!("{tag_prefix}*");
    let out = Command::new("git")
        .current_dir(repo)
        .args(["describe", "--tags", "--dirty=.dirty", "--match"])
        .arg(pattern)
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    (!text.is_empty()).then_some(text)
}

/// Tags matching `<prefix>*`, prefix removed.
pub fn git_tags(repo: &Path, tag_prefix: &str) -> Vec<String> {
    let out = Command::new("git")
        .current_dir(repo)
        .args(["tag", "--list"])
        .arg(format!("{tag_prefix}*"))
        .output();
    match out {
        Ok(o) if o.status.success() => String::from_utf8_lossy(&o.stdout)
            .lines()
            .map(|l| version::strip_tag_prefix(l.trim(), tag_prefix).to_string())
            .filter(|l| !l.is_empty())
            .collect(),
        _ => Vec::new(),
    }
}

pub fn git_short_sha(repo: &Path) -> Option<String> {
    let out = Command::new("git")
        .current_dir(repo)
        .args(["rev-parse", "--short", "HEAD"])
        .output()
        .ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
}

/// Renders a command line for the log, quoting words with spaces.
pub fn display_command(cmd: &Command) -> String {
    let mut words = vec![quote(cmd.get_program())];
    words.extend(cmd.get_args().map(quote));
    words.join(" ")
}

fn quote(word: &OsStr) -> String {
    let s = word.to_string_lossy();
    if s.is_empty() || s.contains([' ', '"', '\'']) {
        format!("\"{}\"", s.replace('"', "\\\""))
    } else {
        s.into_owned()
    }
}

/// Runs a tool with inherited stdout/stderr and fails on a non-zero exit.
pub fn run(cmd: &mut Command) -> Result<()> {
    let line = display_command(cmd);
    eprintln!("xtask: $ {line}");
    let status = cmd
        .status()
        .with_context(|| format!("could not start: {line}"))?;
    if !status.success() {
        bail!("command failed ({status}): {line}");
    }
    Ok(())
}

/// Runs a tool and returns its stdout (stderr passes through).
pub fn run_capture(cmd: &mut Command) -> Result<String> {
    let line = display_command(cmd);
    eprintln!("xtask: $ {line}");
    let out = cmd
        .stderr(std::process::Stdio::inherit())
        .output()
        .with_context(|| format!("could not start: {line}"))?;
    if !out.status.success() {
        bail!("command failed ({}): {line}", out.status);
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

/// Finds a tool: an explicit environment override first, then each name on `PATH`.
pub fn find_tool(env_override: &str, names: &[&str]) -> Option<PathBuf> {
    if let Some(p) = std::env::var_os(env_override).map(PathBuf::from)
        && p.is_file()
    {
        return Some(p);
    }
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for name in names {
            let candidate = dir.join(name);
            if candidate.is_file() {
                return Some(candidate);
            }
            if cfg!(windows) {
                let exe = dir.join(format!("{name}.exe"));
                if exe.is_file() {
                    return Some(exe);
                }
            }
        }
    }
    None
}

/// Copies a file, creating the destination's parent folders.
pub fn copy_file(from: &Path, to: &Path) -> Result<()> {
    if let Some(parent) = to.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::copy(from, to)
        .with_context(|| format!("cannot copy {} to {}", from.display(), to.display()))?;
    Ok(())
}

/// Escapes text for XML content and attribute values.
pub fn xml_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

/// Seconds since the epoch for archive timestamps: `SOURCE_DATE_EPOCH` when set
/// (reproducible builds), else now.
pub fn source_date_epoch() -> u64 {
    std::env::var("SOURCE_DATE_EPOCH")
        .ok()
        .and_then(|v| v.trim().parse().ok())
        .unwrap_or_else(|| chrono::Utc::now().timestamp().max(0) as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words(s: &str) -> Vec<String> {
        s.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn parses_options_switches_and_positionals() {
        let a = Args::parse(
            &words("windows --version 1.2.3 --skip-installers --flavor=dev extra"),
            &["skip-installers"],
        )
        .unwrap();
        assert_eq!(a.positional, vec!["windows", "extra"]);
        assert_eq!(a.get("version"), Some("1.2.3"));
        assert_eq!(a.get("flavor"), Some("dev"));
        assert!(a.flag("skip-installers"));
        assert!(!a.flag("notarize"));
        assert!(Args::parse(&words("--version"), &[]).is_err());
    }

    #[test]
    fn xml_escaping() {
        assert_eq!(
            xml_escape("a & <b> \"c\" 'd'"),
            "a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;"
        );
    }

    #[test]
    fn command_display_quotes_spaces() {
        let mut c = Command::new("wix");
        c.args(["build", "-d", "AppName=Mica Minecraft Launcher (Rust)"]);
        assert_eq!(
            display_command(&c),
            "wix build -d \"AppName=Mica Minecraft Launcher (Rust)\""
        );
    }
}
