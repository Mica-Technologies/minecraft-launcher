// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Pure command-line helpers of the launch (spec 7 and 8.7): splitting, placeholders, the
//! loader-priority classpath merge, heap flags, the Log4Shell plan and the window title.

use std::path::{Path, PathBuf};
use std::sync::LazyLock;

use indexmap::{IndexMap, IndexSet};
use regex::Regex;

pub use mmcl_mojang::args::{
    java_is_whitespace, split_command_line, substitute_placeholders, substitute_placeholders_str,
};

/// `${launcher_name}`.
pub const LAUNCHER_NAME: &str = "MicaMinecraftLauncher";
/// `${version_type}`.
pub const VERSION_TYPE: &str = "release";
/// The flag the JVM needs before any experimental G1 option.
pub const UNLOCK_EXPERIMENTAL: &str = "-XX:+UnlockExperimentalVMOptions";
/// Experimental options that abort the JVM without [`UNLOCK_EXPERIMENTAL`].
pub const EXPERIMENTAL_VM_OPTIONS: [&str; 3] = [
    "G1NewSizePercent",
    "G1MaxNewSizePercent",
    "G1MixedGCLiveThresholdPercent",
];
/// The vanilla main class used when client.json has none.
pub const VANILLA_MAIN_CLASS: &str = mmcl_mojang::client_json::DEFAULT_VANILLA_MAIN_CLASS;
/// Windows' command-line limit is 32,767 UTF-16 units; warn a little before it.
pub const WINDOWS_COMMAND_LINE_WARN_UNITS: usize = 32_000;

/// The classpath separator of this OS (`;` on Windows, `:` elsewhere).
pub const PATH_SEPARATOR: char = if cfg!(windows) { ';' } else { ':' };

/// Splits `classpath` on `sep`, dropping blank entries.
pub fn split_classpath(classpath: &str, sep: char) -> Vec<String> {
    classpath
        .split(sep)
        .filter(|e| !e.trim().is_empty())
        .map(str::to_string)
        .collect()
}

/// Joins classpath entries with `sep`.
pub fn join_classpath(entries: &[String], sep: char) -> String {
    let mut s = String::new();
    for (i, e) in entries.iter().enumerate() {
        if i > 0 {
            s.push(sep);
        }
        s.push_str(e);
    }
    s
}

/// The key that makes two versions of one Maven artifact collide: the artifact folder
/// (`.../org/ow2/asm/asm`) plus the file name with `-<version>` cut out once. Different
/// classifiers stay distinct (`lwjgl-natives-windows.jar`). Entries without a parent and a
/// grandparent are their own key.
pub fn maven_coord_dedup_key(entry: &str) -> String {
    let f = Path::new(entry);
    let (Some(parent), Some(file)) = (f.parent(), f.file_name()) else {
        return entry.to_string();
    };
    let Some(grand) = parent.parent().filter(|g| !g.as_os_str().is_empty()) else {
        return entry.to_string();
    };
    if parent.as_os_str().is_empty() {
        return entry.to_string();
    }
    let version_dir = parent
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let filename = file.to_string_lossy().into_owned();
    let tok = format!("-{version_dir}");
    let suffix = match filename.find(&tok) {
        Some(idx) if idx > 0 => format!("{}{}", &filename[..idx], &filename[idx + tok.len()..]),
        _ => filename,
    };
    let abs: PathBuf = std::path::absolute(grand).unwrap_or_else(|_| grand.to_path_buf());
    format!("{}:{suffix}", abs.display())
}

fn normalized(e: &str) -> String {
    e.replace('\\', "/")
}

/// `true` when the loader classpath carries a Forge / NeoForge patched client jar.
pub fn loader_provides_patched_client<'a>(entries: impl IntoIterator<Item = &'a String>) -> bool {
    entries.into_iter().any(|e| {
        let n = normalized(e);
        n.ends_with("-client.jar")
            && (n.contains("/net/minecraftforge/forge/") || n.contains("/net/neoforged/neoforge/"))
    })
}

/// Merges the loader and vanilla classpaths: the loader's entry wins for a shared Maven
/// artifact (keeping the first position), vanilla fills in the rest, and the vanilla
/// `bin/minecraft.jar` is dropped when the loader brings a patched client (both would become
/// module `minecraft`).
pub fn merge_classpaths_loader_priority_vec(loader: &[String], mc: &[String]) -> Vec<String> {
    let mut map: IndexMap<String, String> = IndexMap::new();
    for e in loader.iter().filter(|e| !e.trim().is_empty()) {
        map.insert(maven_coord_dedup_key(e), e.clone());
    }
    for e in mc.iter().filter(|e| !e.trim().is_empty()) {
        map.entry(maven_coord_dedup_key(e))
            .or_insert_with(|| e.clone());
    }
    let patched = loader_provides_patched_client(map.values());
    map.into_values()
        .filter(|v| !(patched && normalized(v).ends_with("/bin/minecraft.jar")))
        .collect()
}

/// [`merge_classpaths_loader_priority_vec`] over `sep`-joined strings (the Java shape).
pub fn merge_classpaths_loader_priority(loader: &str, mc: &str, sep: char) -> String {
    join_classpath(
        &merge_classpaths_loader_priority_vec(
            &split_classpath(loader, sep),
            &split_classpath(mc, sep),
        ),
        sep,
    )
}

/// The `--title` value: first 200 characters, without `"`, `\` and control characters.
pub fn sanitize_title(s: &str) -> String {
    s.chars()
        .take(200)
        .filter(|&c| c != '"' && c != '\\' && (c as u32) >= 0x20 && c != '\u{7f}')
        .collect()
}

fn is_experimental_vm_option(arg: &str) -> bool {
    let Some(name) = arg.strip_prefix("-XX:") else {
        return false;
    };
    let name = name
        .strip_prefix('+')
        .or_else(|| name.strip_prefix('-'))
        .unwrap_or(name);
    let name = name.split('=').next().unwrap_or(name);
    EXPERIMENTAL_VM_OPTIONS.contains(&name)
}

/// Inserts [`UNLOCK_EXPERIMENTAL`] at index 0 when an experimental option is used without it.
pub fn ensure_experimental_options_unlocked(args: &mut Vec<String>) {
    if args.iter().any(|a| a == UNLOCK_EXPERIMENTAL) {
        return;
    }
    if args.iter().any(|a| is_experimental_vm_option(a)) {
        args.insert(0, UNLOCK_EXPERIMENTAL.to_string());
    }
}

/// The custom JVM args as argv tokens: split, any token containing `${` dropped (a tampered
/// config cannot smuggle `${auth_access_token}` into substitution), experimental options
/// unlocked.
pub fn custom_jvm_tokens(custom: &str) -> Vec<String> {
    if custom.trim().is_empty() {
        return Vec::new();
    }
    let mut tokens: Vec<String> = split_command_line(custom)
        .into_iter()
        .filter(|t| !t.contains("${"))
        .collect();
    ensure_experimental_options_unlocked(&mut tokens);
    tokens
}

static HEAP_RE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?i)-Xm[sx](\d+)([gmk]?)").expect("heap regex"));

/// MB from a `-Xms` / `-Xmx` argument: `g` x1024, `m` x1, `k` /1024, none = bytes /1 MiB.
/// No match or an overflow gives 0.
pub fn parse_heap_arg_mb(arg: &str) -> u64 {
    let Some(c) = HEAP_RE.captures(arg) else {
        return 0;
    };
    let Ok(v) = c[1].parse::<u64>() else {
        return 0;
    };
    match c[2].to_ascii_lowercase().as_str() {
        "g" => v.saturating_mul(1024),
        "m" => v,
        "k" => v / 1024,
        _ => v / (1024 * 1024),
    }
}

/// The vanilla and loader game args combined (spec 7.3): a modern loader *adds* to vanilla
/// (`--launchTarget ...`), a legacy Forge string that names `${auth_player_name}` *replaces*
/// it, and without loader args vanilla stands.
pub fn combine_game_args(vanilla: &str, loader: &str) -> String {
    if !loader.is_empty() && !vanilla.is_empty() && !loader.contains("${auth_player_name}") {
        format!("{vanilla} {loader}")
    } else if !loader.is_empty() {
        loader.to_string()
    } else {
        vanilla.to_string()
    }
}

/// [`combine_game_args`] over argv tokens.
pub fn combine_game_args_vec(vanilla: Vec<String>, loader: Vec<String>) -> Vec<String> {
    let names_player = loader.iter().any(|t| t.contains("${auth_player_name}"));
    if !loader.is_empty() && !vanilla.is_empty() && !names_player {
        let mut v = vanilla;
        v.extend(loader);
        v
    } else if !loader.is_empty() {
        loader
    } else {
        vanilla
    }
}

/// The Log4Shell mitigation for one Minecraft version (besides the always-on
/// `-Dlog4j2.formatMsgNoLookups=true`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Log4jPlan {
    /// 1.17+ and every non-`1.x` version: the flag is enough.
    FlagOnly,
    /// 1.7-1.16: Mojang's patched config file.
    File {
        url: &'static str,
        sha1: &'static str,
        name: &'static str,
    },
}

/// The always-on Log4Shell flag.
pub const LOG4J_NO_LOOKUPS_FLAG: &str = "-Dlog4j2.formatMsgNoLookups=true";

/// The plan for `mc_version`. Fix over Java: a year-based version (`26.1`) or anything whose
/// major is not `1` is modern; an unparseable minor counts as 0 (no file).
pub fn log4j_plan(mc_version: &str) -> Log4jPlan {
    let mut parts = mc_version.split('.');
    let major = parts.next().and_then(|p| p.trim().parse::<u32>().ok());
    let minor = parts
        .next()
        .and_then(|p| p.trim().parse::<u32>().ok())
        .unwrap_or(0);
    if major != Some(1) {
        return Log4jPlan::FlagOnly;
    }
    match minor {
        12..=16 => Log4jPlan::File {
            url: "https://launcher.mojang.com/v1/objects/02937d122c86ce73319ef9975b58896fc1b491d1/log4j2_112-116.xml",
            sha1: "02937d122c86ce73319ef9975b58896fc1b491d1",
            name: "log4j2_112-116.xml",
        },
        7..=11 => Log4jPlan::File {
            url: "https://launcher.mojang.com/v1/objects/4bb89a97a66f350bc9f73b3ca8509632682aea2e/log4j2_17-111.xml",
            sha1: "4bb89a97a66f350bc9f73b3ca8509632682aea2e",
            name: "log4j2_17-111.xml",
        },
        _ => Log4jPlan::FlagOnly,
    }
}

/// The macOS dock and AWT flags (bug B-1: Java appended them after the main class, where they
/// reached Minecraft as ignored game args; here they belong to the JVM section).
pub fn mac_dock_jvm_args(logo: &str, title: &str) -> Vec<String> {
    vec![
        format!("-Xdock:icon={logo}"),
        format!("-Xdock:name={title}"),
        "-Dapple.laf.useScreenMenuBar=true".into(),
        "-Djdk.lang.Process.launchMechanism=vfork".into(),
    ]
}

/// The inputs of the placeholder table.
#[derive(Clone, Debug, Default)]
pub struct PlaceholderInputs {
    pub client: bool,
    pub natives_directory: String,
    pub classpath: String,
    pub launcher_version: String,
    pub classpath_separator: String,
    pub library_directory: String,
    pub auth_player_name: String,
    pub version_name: String,
    pub game_directory: String,
    pub assets_root: String,
    pub assets_index_name: String,
    pub auth_uuid: String,
    pub auth_access_token: String,
    pub user_type: String,
    pub client_id: String,
    pub auth_xuid: String,
    pub auth_session: String,
    pub game_assets: String,
}

/// The placeholder table (spec 7.5). Client-only keys are absent in server mode, so they
/// stay literal there.
pub fn placeholder_map(p: &PlaceholderInputs) -> IndexMap<&'static str, String> {
    let mut m = IndexMap::new();
    m.insert("${natives_directory}", p.natives_directory.clone());
    m.insert("${classpath}", p.classpath.clone());
    m.insert("${launcher_name}", LAUNCHER_NAME.to_string());
    m.insert("${launcher_version}", p.launcher_version.clone());
    m.insert("${version_type}", VERSION_TYPE.to_string());
    m.insert("${classpath_separator}", p.classpath_separator.clone());
    m.insert("${library_directory}", p.library_directory.clone());
    if p.client {
        m.insert("${auth_player_name}", p.auth_player_name.clone());
        m.insert("${version_name}", p.version_name.clone());
        m.insert("${game_directory}", p.game_directory.clone());
        m.insert("${assets_root}", p.assets_root.clone());
        m.insert("${assets_index_name}", p.assets_index_name.clone());
        m.insert("${auth_uuid}", p.auth_uuid.clone());
        m.insert("${auth_access_token}", p.auth_access_token.clone());
        m.insert("${user_type}", p.user_type.clone());
        m.insert("${clientid}", p.client_id.clone());
        m.insert("${auth_xuid}", p.auth_xuid.clone());
        m.insert("${user_properties}", "{}".to_string());
        m.insert("${auth_session}", p.auth_session.clone());
        m.insert("${game_assets}", p.game_assets.clone());
    }
    m
}

/// UTF-16 length of the argv joined with spaces (what Windows' limit counts).
pub fn command_line_utf16_len(argv: &[String]) -> usize {
    argv.iter().map(|a| a.encode_utf16().count() + 1).sum()
}

/// Deduplicates while keeping first positions (Java `LinkedHashSet`).
pub fn ordered_unique(entries: impl IntoIterator<Item = String>) -> Vec<String> {
    entries
        .into_iter()
        .collect::<IndexSet<String>>()
        .into_iter()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    // ---- GameModPackLauncherPlaceholderTest -----------------------------------------------

    fn subst(argv: &[&str], map: &[(&str, &str)]) -> Vec<String> {
        let mut a = v(argv);
        let m: IndexMap<&str, &str> = map.iter().copied().collect();
        substitute_placeholders(&mut a, &m);
        a
    }

    #[test]
    fn replaces_a_single_placeholder() {
        assert_eq!(
            subst(
                &["--gameDir", "${game_directory}"],
                &[("${game_directory}", "/packs/Foo")]
            ),
            v(&["--gameDir", "/packs/Foo"])
        );
    }

    #[test]
    fn replaces_several_placeholders_within_one_argument() {
        assert_eq!(
            subst(
                &["-Dpath=${library_directory}${classpath_separator}${natives_directory}"],
                &[
                    ("${library_directory}", "/libs"),
                    ("${classpath_separator}", ":"),
                    ("${natives_directory}", "/natives")
                ]
            ),
            v(&["-Dpath=/libs:/natives"])
        );
    }

    #[test]
    fn replaces_every_occurrence_of_the_same_placeholder() {
        assert_eq!(subst(&["${a}-${a}"], &[("${a}", "X")]), v(&["X-X"]));
    }

    #[test]
    fn leaves_arguments_without_a_dollar_sign_untouched() {
        assert_eq!(
            subst(
                &["-Xmx8G", "--username", "Steve"],
                &[("${game_directory}", "/p")]
            ),
            v(&["-Xmx8G", "--username", "Steve"])
        );
    }

    #[test]
    fn leaves_unknown_placeholders_verbatim() {
        assert_eq!(
            subst(
                &["${not_a_known_placeholder}"],
                &[("${game_directory}", "/p")]
            ),
            v(&["${not_a_known_placeholder}"])
        );
    }

    #[test]
    fn tolerates_empty_inputs() {
        let mut empty: Vec<String> = Vec::new();
        substitute_placeholders(&mut empty, &HashMap::from([("${a}", "b")]));
        assert_eq!(subst(&["${a}"], &[]), v(&["${a}"]));
    }

    #[test]
    fn substituted_values_are_never_rescanned_in_either_order() {
        // Inverted from Java's order-dependence test (bug B-2).
        let leaking = [
            ("${game_directory}", "/packs/${auth_access_token}"),
            ("${auth_access_token}", "SECRET-TOKEN"),
        ];
        let reversed = [leaking[1], leaking[0]];
        for map in [&leaking[..], &reversed[..]] {
            assert_eq!(
                subst(&["--gameDir", "${game_directory}"], map)[1],
                "/packs/${auth_access_token}"
            );
        }
    }

    #[test]
    fn pack_name_sanitisation_strips_placeholder_syntax() {
        let s: String = "${auth_access_token}"
            .chars()
            .filter(char::is_ascii_alphanumeric)
            .collect();
        assert_eq!(s, "authaccesstoken");
    }

    #[test]
    fn placeholder_map_server_mode_leaves_client_keys_literal() {
        let p = PlaceholderInputs {
            client: false,
            natives_directory: "/n".into(),
            ..Default::default()
        };
        let m = placeholder_map(&p);
        let mut a = v(&["${natives_directory}", "${auth_player_name}"]);
        substitute_placeholders(&mut a, &m);
        assert_eq!(a, v(&["/n", "${auth_player_name}"]));
        let m = placeholder_map(&PlaceholderInputs { client: true, ..p });
        assert_eq!(m["${user_properties}"], "{}");
        assert_eq!(m["${launcher_name}"], LAUNCHER_NAME);
    }

    // ---- split_command_line ---------------------------------------------------------------

    #[test]
    fn split_command_line_table() {
        assert_eq!(split_command_line(r#"a "b c" d"#), v(&["a", "b c", "d"]));
        assert_eq!(split_command_line(r#"a "" b"#), v(&["a", "", "b"]));
        assert_eq!(split_command_line(r#"a"b c"d"#), v(&["ab cd"]));
        assert_eq!(
            split_command_line(r"C:\Program Files\x"),
            v(&[r"C:\Program", r"Files\x"])
        );
        assert_eq!(split_command_line("a\tb\nc\r\nd"), v(&["a", "b", "c", "d"]));
        assert_eq!(split_command_line("a\u{2003}b"), v(&["a", "b"]));
        assert_eq!(split_command_line("a\u{00a0}b"), v(&["a\u{00a0}b"]));
        assert!(split_command_line("   ").is_empty());
    }

    // ---- classpath merge --------------------------------------------------------------------

    fn p(s: &str) -> String {
        s.replace('/', std::path::MAIN_SEPARATOR_STR)
    }

    #[test]
    fn asm_versions_collide_and_the_loader_wins() {
        let loader = vec![p("/l/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar")];
        let mc = vec![
            p("/l/org/ow2/asm/asm/9.6/asm-9.6.jar"),
            p("/l/com/x/y/1/y-1.jar"),
            p("/p/bin/minecraft.jar"),
        ];
        assert_eq!(
            merge_classpaths_loader_priority_vec(&loader, &mc),
            vec![loader[0].clone(), mc[1].clone(), mc[2].clone()]
        );
    }

    #[test]
    fn natives_classifiers_stay_distinct() {
        let mc = vec![
            p("/l/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar"),
            p("/l/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar"),
        ];
        assert_eq!(merge_classpaths_loader_priority_vec(&[], &mc), mc);
        assert_ne!(maven_coord_dedup_key(&mc[0]), maven_coord_dedup_key(&mc[1]));
    }

    #[test]
    fn vanilla_jar_dropped_only_with_a_patched_client() {
        let mc = vec![p("/p/bin/minecraft.jar")];
        let forge = vec![p(
            "/p/libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-client.jar",
        )];
        assert!(merge_classpaths_loader_priority_vec(&forge, &mc).len() == 1);
        let neo = vec![p(
            "/p/libraries/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-client.jar",
        )];
        assert_eq!(merge_classpaths_loader_priority_vec(&neo, &mc), neo);
        let legacy = vec![p(
            "/p/libraries/net/minecraftforge/forge/1.12.2-14.23/forge-1.12.2-14.23-universal.jar",
        )];
        assert_eq!(merge_classpaths_loader_priority_vec(&legacy, &mc).len(), 2);
        // Backslash entries are normalised for the check.
        assert!(loader_provides_patched_client(&[
            r"C:\p\libraries\net\minecraftforge\forge\1\forge-1-client.jar".to_string()
        ]));
    }

    #[test]
    fn loader_internal_duplicate_keeps_first_position_with_last_value() {
        let loader = vec![
            p("/l/a/b/1/b-1.jar"),
            p("/l/c/d/1/d-1.jar"),
            p("/l/a/b/2/b-2.jar"),
        ];
        assert_eq!(
            merge_classpaths_loader_priority_vec(&loader, &[]),
            vec![loader[2].clone(), loader[1].clone()]
        );
        let s = merge_classpaths_loader_priority("x;;y", "", ';');
        assert_eq!(s, "x;y");
    }

    #[test]
    fn dedup_key_edge_cases() {
        assert_eq!(maven_coord_dedup_key("minecraft.jar"), "minecraft.jar");
        // The version token at index 0 is not cut.
        let k = maven_coord_dedup_key(&p("/a/1/-1.jar"));
        assert!(k.ends_with(":-1.jar"), "{k}");
    }

    // ---- misc ------------------------------------------------------------------------------

    #[test]
    fn combine_game_args_truth_table() {
        assert_eq!(
            combine_game_args("v", "--launchTarget x"),
            "v --launchTarget x"
        );
        assert_eq!(
            combine_game_args("v", "--username ${auth_player_name}"),
            "--username ${auth_player_name}"
        );
        assert_eq!(combine_game_args("", "l"), "l");
        assert_eq!(combine_game_args("v", ""), "v");
        assert_eq!(combine_game_args("", ""), "");
        assert_eq!(
            combine_game_args_vec(v(&["v"]), v(&["l"])),
            split_command_line(&combine_game_args("v", "l"))
        );
        assert_eq!(
            combine_game_args_vec(v(&["v"]), v(&["${auth_player_name}"])),
            v(&["${auth_player_name}"])
        );
        assert_eq!(combine_game_args_vec(v(&["v"]), vec![]), v(&["v"]));
    }

    #[test]
    fn experimental_options_unlock() {
        let mut a = v(&["-XX:+UseG1GC", "-XX:G1NewSizePercent=30"]);
        ensure_experimental_options_unlocked(&mut a);
        assert_eq!(a[0], UNLOCK_EXPERIMENTAL);
        let mut b = v(&[
            "-XX:+UseG1GC",
            UNLOCK_EXPERIMENTAL,
            "-XX:G1NewSizePercent=30",
        ]);
        ensure_experimental_options_unlocked(&mut b);
        assert_eq!(b.len(), 3);
        let mut c = v(&["-XX:-G1MixedGCLiveThresholdPercent"]);
        ensure_experimental_options_unlocked(&mut c);
        assert_eq!(c.len(), 2);
        let mut d = v(&["-XX:G1HeapRegionSize=16M"]);
        ensure_experimental_options_unlocked(&mut d);
        assert_eq!(d.len(), 1);
        assert_eq!(
            custom_jvm_tokens("-Xss1M -Dx=${auth_access_token} -XX:G1MaxNewSizePercent=40"),
            v(&[UNLOCK_EXPERIMENTAL, "-Xss1M", "-XX:G1MaxNewSizePercent=40"])
        );
        assert!(custom_jvm_tokens("  ").is_empty());
    }

    #[test]
    fn heap_arg_parsing() {
        assert_eq!(parse_heap_arg_mb("-Xmx4g"), 4096);
        assert_eq!(parse_heap_arg_mb("-Xms512m"), 512);
        assert_eq!(parse_heap_arg_mb("-Xmx1048576k"), 1024);
        assert_eq!(parse_heap_arg_mb("-Xmx2147483648"), 2048);
        assert_eq!(parse_heap_arg_mb("-XMS2G"), 2048);
        assert_eq!(parse_heap_arg_mb("garbage"), 0);
        assert_eq!(parse_heap_arg_mb("-Xmx99999999999999999999999g"), 0);
    }

    #[test]
    fn log4j_plans() {
        let file = |v: &str| matches!(log4j_plan(v), Log4jPlan::File { .. });
        let name = |v: &str| match log4j_plan(v) {
            Log4jPlan::File { name, .. } => name,
            Log4jPlan::FlagOnly => "",
        };
        assert!(!file("1.6.4"));
        assert_eq!(name("1.7.10"), "log4j2_17-111.xml");
        assert_eq!(name("1.11.2"), "log4j2_17-111.xml");
        assert_eq!(name("1.12.2"), "log4j2_112-116.xml");
        assert_eq!(name("1.16.5"), "log4j2_112-116.xml");
        assert!(!file("1.17"));
        assert!(!file("1.21.5"));
        assert!(!file("26.1"));
        assert!(!file("20w14a"));
    }

    #[test]
    fn title_sanitising() {
        assert_eq!(sanitize_title("My \"Pack\"\\\u{7}\n!"), "My Pack!");
        assert_eq!(sanitize_title(&"x".repeat(300)).len(), 200);
    }

    #[test]
    fn mac_dock_args_shape() {
        let a = mac_dock_jvm_args("/l.png", "T");
        assert_eq!(a[0], "-Xdock:icon=/l.png");
        assert_eq!(a[1], "-Xdock:name=T");
        assert_eq!(a.len(), 4);
    }
}
