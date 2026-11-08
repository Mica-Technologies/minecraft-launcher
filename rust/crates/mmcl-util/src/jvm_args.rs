// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Custom JVM argument validation (`JvmArgsValidator`) and hardware-tuned defaults
//! (`HardwareTunedJvmArgs`).

use std::sync::OnceLock;

use mmcl_i18n::tf;

/// The templating opener that custom args may never contain.
pub const PLACEHOLDER_OPEN: &str = "${";

/// Why custom JVM args were refused.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum JvmArgsError {
    /// A character below U+0020 or U+007F at this char index. The args are split on
    /// whitespace, so a newline would smuggle in extra arguments.
    #[error("Custom JVM args contain a control character at position {0}")]
    ControlCharacter(usize),
    /// `${` found. Templating runs after the custom args are appended, so
    /// `${auth_access_token}` would leak the token into argv.
    #[error("Custom JVM args may not contain '${{...}}' placeholder syntax.")]
    Placeholder,
}

impl JvmArgsError {
    /// The localization key for this error.
    pub fn key(&self) -> &'static str {
        match self {
            JvmArgsError::ControlCharacter(_) => "settings.jvmArgs.error.controlCharacter",
            JvmArgsError::Placeholder => "settings.jvmArgs.error.placeholder",
        }
    }

    /// The localized message.
    pub fn localized(&self) -> String {
        match self {
            JvmArgsError::ControlCharacter(i) => tf!(self.key(), i.to_string()),
            JvmArgsError::Placeholder => mmcl_i18n::get(self.key()).into_owned(),
        }
    }
}

/// Returns the args unchanged when they are clean (`None` becomes `""`), else the first
/// problem found. The reported position is a char index, not a byte index.
pub fn require_clean(s: Option<&str>) -> Result<String, JvmArgsError> {
    let s = s.unwrap_or("");
    if let Some(i) = s
        .chars()
        .position(|c| (c as u32) < 0x20 || c as u32 == 0x7F)
    {
        return Err(JvmArgsError::ControlCharacter(i));
    }
    if s.contains(PLACEHOLDER_OPEN) {
        return Err(JvmArgsError::Placeholder);
    }
    Ok(s.to_string())
}

/// `true` when [`require_clean`] would accept the args. `None` and empty are clean.
pub fn is_clean(s: Option<&str>) -> bool {
    require_clean(s).is_ok()
}

/// Hardware-tuned G1 flags for a heap of `max_ram_gb`, using this machine's core count.
pub fn generate(max_ram_gb: i32) -> String {
    generate_for(max_ram_gb, available_cores())
}

/// [`generate`] for an explicit core count (the pure core; tests drive it directly).
pub fn generate_for(max_ram_gb: i32, cores: usize) -> String {
    let cores = cores.max(1);
    let mut flags: Vec<&str> = vec![
        "-XX:+UseG1GC",
        // G1NewSizePercent and friends are experimental; the unlock must come first.
        "-XX:+UnlockExperimentalVMOptions",
    ];
    if max_ram_gb <= 4 {
        flags.extend([
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1HeapRegionSize=2M",
            "-XX:G1NewSizePercent=20",
        ]);
    } else if max_ram_gb <= 12 {
        flags.extend([
            "-XX:MaxGCPauseMillis=200",
            "-XX:G1HeapRegionSize=4M",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1ReservePercent=20",
        ]);
    } else {
        flags.extend([
            "-XX:MaxGCPauseMillis=200",
            "-XX:G1HeapRegionSize=16M",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1ReservePercent=15",
        ]);
    }
    if cores >= 4 {
        flags.push("-XX:+ParallelRefProcEnabled");
    }
    if cores >= 8 || max_ram_gb > 12 {
        flags.push("-XX:+UseStringDeduplication");
    }
    // ExplicitGCInvokesConcurrent, not Aikar's DisableExplicitGC: disabling explicit GC led to
    // "OutOfMemoryError: Direct buffer memory".
    flags.extend(["-XX:+AlwaysPreTouch", "-XX:+ExplicitGCInvokesConcurrent"]);
    flags.join(" ")
}

/// A one-line, localized description of what the tuning was based on, e.g.
/// `8 cores / 32 GB system RAM / 6 GB max heap`.
pub fn summary(max_ram_gb: i32) -> String {
    summary_for(max_ram_gb, available_cores(), total_ram_bytes())
}

/// [`summary`] for explicit hardware figures.
pub fn summary_for(max_ram_gb: i32, cores: usize, total_ram_bytes: u64) -> String {
    let cores = cores.max(1);
    let total_gb = total_ram_bytes / (1024 * 1024 * 1024);
    let key = if cores == 1 {
        "settings.jvmArgs.summary.singleCore"
    } else {
        "settings.jvmArgs.summary.multiCore"
    };
    tf!(
        key,
        cores.to_string(),
        total_gb.to_string(),
        max_ram_gb.to_string()
    )
}

/// The number of logical processors, at least 1.
pub fn available_cores() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
        .max(1)
}

/// Total physical memory, probed once per session. A failed probe assumes 4 GiB.
pub fn total_ram_bytes() -> u64 {
    static TOTAL: OnceLock<u64> = OnceLock::new();
    *TOTAL.get_or_init(|| {
        let mut sys = sysinfo::System::new();
        sys.refresh_memory();
        match sys.total_memory() {
            0 => 4 * 1024 * 1024 * 1024,
            n => n,
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEAP_SIZES_GB: [i32; 6] = [2, 4, 8, 12, 16, 32];

    #[test]
    fn clean_args_are_accepted_unchanged() {
        let args = "-Xss1m -Dfoo=bar -XX:+UseG1GC";
        assert!(is_clean(Some(args)));
        assert_eq!(require_clean(Some(args)).unwrap(), args);
    }

    #[test]
    fn null_and_empty_are_clean() {
        assert!(is_clean(None));
        assert!(is_clean(Some("")));
        assert_eq!(require_clean(None).unwrap(), "");
    }

    #[test]
    fn control_characters_are_rejected() {
        assert!(!is_clean(Some("-Xmx2g\n-javaagent:/evil.jar")));
        assert_eq!(
            require_clean(Some("-Xmx2g\t-Dx=1")),
            Err(JvmArgsError::ControlCharacter(6))
        );
        assert!(!is_clean(Some("a\u{7f}")));
        // The index counts chars, not bytes.
        assert_eq!(
            require_clean(Some("\u{e9}\u{e9}\n")),
            Err(JvmArgsError::ControlCharacter(2))
        );
    }

    #[test]
    fn placeholder_syntax_is_rejected() {
        assert!(!is_clean(Some("-Dtoken=${auth_access_token}")));
        assert_eq!(
            require_clean(Some("-Dtoken=${auth_access_token}")),
            Err(JvmArgsError::Placeholder)
        );
        assert!(!is_clean(Some("-Dx=${")));
    }

    #[test]
    fn error_messages_match_java() {
        assert_eq!(
            JvmArgsError::ControlCharacter(3).to_string(),
            "Custom JVM args contain a control character at position 3"
        );
        assert_eq!(
            JvmArgsError::Placeholder.to_string(),
            "Custom JVM args may not contain '${...}' placeholder syntax."
        );
    }

    #[test]
    fn every_branch_uses_concurrent_explicit_gc() {
        for gb in HEAP_SIZES_GB {
            for cores in [1, 4, 8, 16] {
                let args = generate_for(gb, cores);
                assert!(args.contains("-XX:+ExplicitGCInvokesConcurrent"), "{args}");
                assert!(!args.contains("DisableExplicitGC"), "{args}");
            }
        }
    }

    #[test]
    fn generated_args_pass_the_validator() {
        for gb in HEAP_SIZES_GB {
            let args = generate(gb);
            assert!(is_clean(Some(&args)), "{args}");
            assert!(!args.ends_with(' ') && !args.contains("  "));
        }
    }

    #[test]
    fn exact_flag_order() {
        assert_eq!(
            generate_for(4, 2),
            "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=50 \
             -XX:G1HeapRegionSize=2M -XX:G1NewSizePercent=20 -XX:+AlwaysPreTouch \
             -XX:+ExplicitGCInvokesConcurrent"
        );
        assert_eq!(
            generate_for(16, 4),
            "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=200 \
             -XX:G1HeapRegionSize=16M -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
             -XX:G1ReservePercent=15 -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication \
             -XX:+AlwaysPreTouch -XX:+ExplicitGCInvokesConcurrent"
        );
        let mid = generate_for(8, 8);
        assert!(mid.contains(
            "-XX:G1ReservePercent=20 -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication"
        ));
        let unlock = mid.find("UnlockExperimentalVMOptions").unwrap();
        assert!(unlock < mid.find("G1NewSizePercent").unwrap());
    }

    #[test]
    fn summary_wording() {
        let en = mmcl_i18n::Localizer::new(mmcl_i18n::Locale::en_us());
        let total = 32u64 * 1024 * 1024 * 1024;
        // The global localizer may be on another locale in parallel tests; check the English
        // templates directly and the composed text through the free function's logic.
        assert_eq!(
            en.format(
                "settings.jvmArgs.summary.multiCore",
                &["8".into(), "32".into(), "6".into()]
            ),
            "8 cores / 32 GB system RAM / 6 GB max heap"
        );
        assert_eq!(
            en.format(
                "settings.jvmArgs.summary.singleCore",
                &["1".into(), "4".into(), "2".into()]
            ),
            "1 core / 4 GB system RAM / 2 GB max heap"
        );
        assert!(summary_for(6, 8, total).contains('8'));
    }
}
