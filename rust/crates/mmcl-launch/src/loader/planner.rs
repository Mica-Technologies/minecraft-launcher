// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure Forge/NeoForge library planning (Java `ForgeLibraryPlanner`): which jar an installer
//! library entry really is (base, universal substitute, base plus universal, or the legacy
//! top-level universal jar) and where it lands under `libraries/`.

/// The plan for one library entry.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Plan {
    /// The repository path to fetch (maybe the universal substitute).
    pub repo_path: String,
    /// Modern Forge: both the base and the universal jar go on the classpath.
    pub add_universal_as_extra: bool,
    /// The universal path considered (`None` when not a loader artifact needing it).
    pub universal_repo_path: Option<String>,
    /// Legacy Forge: the jar is the installer's top-level `<stem>-universal.jar`.
    pub legacy_top_level_universal: bool,
    pub legacy_top_level_entry: Option<String>,
    /// The declared hash describes another jar, so it must not be checked.
    pub suppress_sha1: bool,
}

/// The file-name stem of a coordinate: everything after the first `:` with `:` turned into
/// `-` (`net.minecraftforge:forge:1.7.10-10.13.4.1614` → `forge-1.7.10-10.13.4.1614`).
/// `None` for a string without `:`.
pub fn inferred_repo_path(coordinate: &str) -> Option<String> {
    let i = coordinate.find(':')?;
    Some(coordinate[i + 1..].replace(':', "-"))
}

/// Plans one entry. `has_maven(p)` = the installer has `maven/<p>`; `has_top(n)` = it has `<n>`.
pub fn plan(
    name: &str,
    initial_repo_path: &str,
    inferred: &str,
    specified: bool,
    loader_prefix: &str,
    has_maven: impl Fn(&str) -> bool,
    has_top: impl Fn(&str) -> bool,
) -> Plan {
    let mut p = Plan {
        repo_path: initial_repo_path.to_string(),
        ..Plan::default()
    };
    let is_loader = name.starts_with(loader_prefix);
    if specified && is_loader && !p.repo_path.contains("-universal") {
        // Java String.replace: every ".jar" occurrence (B-16, kept).
        let uni = p.repo_path.replace(".jar", "-universal.jar");
        if has_maven(&uni) && has_maven(&p.repo_path) {
            p.add_universal_as_extra = true;
        } else if has_maven(&uni) {
            p.repo_path = uni.clone();
            p.suppress_sha1 = true;
        }
        p.universal_repo_path = Some(uni);
    }
    if !specified && is_loader {
        let top = format!("{inferred}-universal.jar");
        if has_top(&top) {
            p.legacy_top_level_universal = true;
            p.legacy_top_level_entry = Some(top);
            p.suppress_sha1 = true;
        }
    }
    p
}

/// The local path under `libraries/`: the repository path for a specified entry, else
/// `group/(rest with : as dirs)/<inferred><ext>` (B-15: a classifier becomes a directory,
/// kept for on-disk parity).
pub fn local_path(
    name: &str,
    repo_path: &str,
    specified: bool,
    inferred: &str,
    sep: &str,
    ext: &str,
) -> String {
    if specified {
        return repo_path.replace('/', sep);
    }
    let colon = name.find(':').unwrap_or(name.len());
    let group = name[..colon].replace('.', sep);
    let rest = name.get(colon + 1..).unwrap_or("").replace(':', sep);
    format!("{group}{sep}{rest}{sep}{inferred}{ext}")
}

/// The Maven repository URL of an entry without an explicit path or URL (spec 8.2 step 4).
pub fn maven_url(
    name: &str,
    lib_url: Option<&str>,
    repo_path: &str,
    specified: bool,
    inferred: &str,
) -> String {
    let mut repo = match lib_url.map(str::trim).filter(|u| !u.is_empty()) {
        Some(u) => u.to_string(),
        None if name.contains("net.minecraft:") => "https://libraries.minecraft.net/".to_string(),
        None if name.contains("net.minecraftforge:") => {
            "https://maven.minecraftforge.net/".to_string()
        }
        None => "https://repo1.maven.org/maven2/".to_string(),
    };
    if !repo.ends_with('/') {
        repo.push('/');
    }
    if specified {
        return format!("{repo}{repo_path}");
    }
    format!(
        "{repo}{}",
        local_path(name, "", false, inferred, "/", ".jar")
    )
}

/// Forge 1.7-1.12 needs `lzma:lzma:0.0.1`, which only SpongePowered still hosts.
pub fn apply_url_overrides(url: String) -> String {
    if url.contains("lzma/lzma/0.0.1") && !url.contains("spongepowered") {
        "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar".to_string()
    } else {
        url
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FORGE_PREFIX: &str = "net.minecraftforge:forge:";
    const FORGE_COORD: &str = "net.minecraftforge:forge:1.20.1-47.2.0";
    const BASE: &str = "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar";
    const UNI: &str = "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar";

    fn has<'a>(names: &'a [&'a str]) -> impl Fn(&str) -> bool + 'a {
        move |n| names.contains(&n)
    }
    fn nothing(_: &str) -> bool {
        false
    }

    #[test]
    fn coordinate_stems() {
        assert_eq!(
            inferred_repo_path(FORGE_COORD).unwrap(),
            "forge-1.20.1-47.2.0"
        );
        assert_eq!(
            inferred_repo_path("org.ow2.asm:asm:9.5").unwrap(),
            "asm-9.5"
        );
        assert_eq!(
            inferred_repo_path("org.lwjgl:lwjgl:3.3.1:natives-macos").unwrap(),
            "lwjgl-3.3.1-natives-macos"
        );
        assert!(inferred_repo_path("no-colons-here").is_none());
    }

    #[test]
    fn both_bundled_adds_universal_alongside() {
        let p = plan(
            FORGE_COORD,
            BASE,
            "forge-1.20.1-47.2.0",
            true,
            FORGE_PREFIX,
            has(&[BASE, UNI]),
            nothing,
        );
        assert_eq!(p.repo_path, BASE);
        assert!(p.add_universal_as_extra);
        assert_eq!(p.universal_repo_path.as_deref(), Some(UNI));
        assert!(!p.suppress_sha1);
        assert!(!p.legacy_top_level_universal);
    }

    #[test]
    fn only_universal_substitutes_and_drops_hash() {
        let p = plan(
            FORGE_COORD,
            BASE,
            "forge-1.20.1-47.2.0",
            true,
            FORGE_PREFIX,
            has(&[UNI]),
            nothing,
        );
        assert_eq!(p.repo_path, UNI);
        assert!(!p.add_universal_as_extra);
        assert!(p.suppress_sha1);
    }

    #[test]
    fn neither_bundled_is_untouched() {
        let p = plan(
            FORGE_COORD,
            BASE,
            "forge-1.20.1-47.2.0",
            true,
            FORGE_PREFIX,
            nothing,
            nothing,
        );
        assert_eq!(p.repo_path, BASE);
        assert!(!p.add_universal_as_extra && !p.suppress_sha1);
    }

    #[test]
    fn already_universal_is_left_alone() {
        let p = plan(
            FORGE_COORD,
            UNI,
            "forge-1.20.1-47.2.0",
            true,
            FORGE_PREFIX,
            has(&[UNI]),
            nothing,
        );
        assert_eq!(p.repo_path, UNI);
        assert!(!p.add_universal_as_extra);
        assert!(p.universal_repo_path.is_none());
    }

    #[test]
    fn top_level_universal_is_detected() {
        let coord = "net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10";
        let stem = inferred_repo_path(coord).unwrap();
        let top = format!("{stem}-universal.jar");
        let p = plan(
            coord,
            &stem,
            &stem,
            false,
            FORGE_PREFIX,
            nothing,
            |n: &str| n == top,
        );
        assert!(p.legacy_top_level_universal);
        assert_eq!(p.legacy_top_level_entry.as_deref(), Some(top.as_str()));
        assert!(p.suppress_sha1);
        assert!(!p.add_universal_as_extra);
        let p = plan(coord, &stem, &stem, false, FORGE_PREFIX, nothing, nothing);
        assert!(
            !p.legacy_top_level_universal && p.legacy_top_level_entry.is_none() && !p.suppress_sha1
        );
    }

    #[test]
    fn ordinary_libraries_are_never_rewritten() {
        let asm = "org/ow2/asm/asm/9.5/asm-9.5.jar";
        let p = plan(
            "org.ow2.asm:asm:9.5",
            asm,
            "asm-9.5",
            true,
            FORGE_PREFIX,
            has(&[asm, "org/ow2/asm/asm/9.5/asm-9.5-universal.jar"]),
            nothing,
        );
        assert_eq!(p.repo_path, asm);
        assert!(!p.add_universal_as_extra && p.universal_repo_path.is_none() && !p.suppress_sha1);
        let p = plan(
            "org.ow2.asm:asm:9.5",
            "asm-9.5",
            "asm-9.5",
            false,
            FORGE_PREFIX,
            nothing,
            has(&["asm-9.5-universal.jar"]),
        );
        assert!(!p.legacy_top_level_universal);
    }

    #[test]
    fn loader_prefix_is_respected() {
        let coord = "net.neoforged:neoforge:20.4.100";
        let path = "net/neoforged/neoforge/20.4.100/neoforge-20.4.100.jar";
        let uni = "net/neoforged/neoforge/20.4.100/neoforge-20.4.100-universal.jar";
        assert!(
            !plan(
                coord,
                path,
                "neoforge-20.4.100",
                true,
                FORGE_PREFIX,
                has(&[path, uni]),
                nothing
            )
            .add_universal_as_extra
        );
        assert!(
            plan(
                coord,
                path,
                "neoforge-20.4.100",
                true,
                "net.neoforged:neoforge:",
                has(&[path, uni]),
                nothing
            )
            .add_universal_as_extra
        );
    }

    #[test]
    fn local_paths() {
        assert_eq!(
            local_path(FORGE_COORD, BASE, true, "forge-1.20.1-47.2.0", "/", ".jar"),
            BASE
        );
        assert_eq!(
            local_path(FORGE_COORD, BASE, true, "forge-1.20.1-47.2.0", "\\", ".jar"),
            "net\\minecraftforge\\forge\\1.20.1-47.2.0\\forge-1.20.1-47.2.0.jar"
        );
        assert_eq!(
            local_path(
                "org.ow2.asm:asm:9.5",
                "asm-9.5",
                false,
                "asm-9.5",
                "/",
                ".jar"
            ),
            "org/ow2/asm/asm/9.5/asm-9.5.jar"
        );
        assert_eq!(
            local_path(
                "org.lwjgl:lwjgl:3.3.1:natives-macos",
                "lwjgl-3.3.1-natives-macos",
                false,
                "lwjgl-3.3.1-natives-macos",
                "/",
                ".jar"
            ),
            "org/lwjgl/lwjgl/3.3.1/natives-macos/lwjgl-3.3.1-natives-macos.jar"
        );
    }

    #[test]
    fn maven_urls_and_overrides() {
        assert_eq!(
            maven_url("org.ow2.asm:asm:9.5", None, "", false, "asm-9.5"),
            "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.5/asm-9.5.jar"
        );
        assert_eq!(
            maven_url(
                "net.minecraft:launchwrapper:1.12",
                None,
                "",
                false,
                "launchwrapper-1.12"
            ),
            "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar"
        );
        assert_eq!(
            maven_url("net.minecraftforge:forge:1", None, "", false, "forge-1"),
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1/forge-1.jar"
        );
        assert_eq!(
            maven_url(
                "a:b:1",
                Some("https://m.example/r"),
                "a/b/1/b-1.jar",
                true,
                "b-1"
            ),
            "https://m.example/r/a/b/1/b-1.jar"
        );
        assert_eq!(
            apply_url_overrides(
                "https://libraries.minecraft.net/lzma/lzma/0.0.1/lzma-0.0.1.jar".into()
            ),
            "https://repo.spongepowered.org/maven/lzma/lzma/0.0.1/lzma-0.0.1.jar"
        );
        assert_eq!(
            apply_url_overrides("https://x/y.jar".into()),
            "https://x/y.jar"
        );
    }
}
