// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Mojang/Forge rule evaluation and launch-argument flattening
//! (`ManifestRuleUtilities`, spec section 4).

use std::collections::HashMap;
use std::sync::{Arc, LazyLock};

use parking_lot::RwLock;
use serde_json::{Map, Value};

use crate::args::split_command_line;
use crate::error::json::{as_bool, primitive_string};
use crate::host::{HostInfo, Os};

/// Evaluate a `rules` array against `host`.
///
/// `None` or an empty array allows. Otherwise the decision starts at "deny";
/// each rule whose `os` and `features` blocks match the host sets it from the
/// rule's `action` (default `allow`, compared case-insensitively). Non-object
/// entries are skipped. The last matching rule wins.
pub fn evaluate_rules(rules: Option<&[Value]>, host: &HostInfo) -> bool {
    let Some(rules) = rules else { return true };
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        let Some(rule) = rule.as_object() else {
            continue;
        };
        if !rule_matches(rule, host) {
            continue;
        }
        let action = rule
            .get("action")
            .and_then(primitive_string)
            .unwrap_or_else(|| "allow".to_string());
        allowed = action.eq_ignore_ascii_case("allow");
    }
    allowed
}

/// [`evaluate_rules`] for a `rules` value that may be absent or not an array
/// (treated as absent).
pub fn evaluate_rules_value(rules: Option<&Value>, host: &HostInfo) -> bool {
    evaluate_rules(rules.and_then(Value::as_array).map(Vec::as_slice), host)
}

fn rule_matches(rule: &Map<String, Value>, host: &HostInfo) -> bool {
    if let Some(os) = rule.get("os") {
        match os {
            Value::Object(os) => {
                if !os_matches(os, host) {
                    return false;
                }
            }
            Value::Null => {}
            // Java threw on a non-object `os`; never let a malformed block match.
            _ => return false,
        }
    }
    match rule.get("features") {
        None | Some(Value::Null) => true,
        Some(Value::Object(features)) => features_match(features),
        Some(_) => false,
    }
}

fn os_matches(os: &Map<String, Value>, host: &HostInfo) -> bool {
    if let Some(name) = os.get("name") {
        match primitive_string(name) {
            Some(name) if os_name_matches(&name, host) => {}
            _ => return false,
        }
    }
    if let Some(version) = os.get("version") {
        match primitive_string(version) {
            Some(p) if regex_find(&p, &host.java_os_version) => {}
            _ => return false,
        }
    }
    if let Some(arch) = os.get("arch") {
        match primitive_string(arch) {
            Some(p) if regex_find(&p, &host.java_os_arch) => {}
            _ => return false,
        }
    }
    match os.get("versionRange") {
        None | Some(Value::Null) => {}
        Some(Value::Object(range)) => {
            if !version_range_matches(range, &host.java_os_version) {
                return false;
            }
        }
        // Java threw on a non-object range; like a malformed `os` block, it
        // must not silently count as "no constraint".
        Some(_) => return false,
    }
    true
}

/// Match a Mojang `os.name` token against the host. `windows|win|bindoj` →
/// Windows, `osx|mac|macos` → macOS, `linux|unix` → Linux, anything else → no
/// match. A BSD host ([`Os::Other`]) matches nothing.
pub fn os_name_matches(name: &str, host: &HostInfo) -> bool {
    match name.trim().to_lowercase().as_str() {
        "bindoj" | "windows" | "win" => host.os == Os::Windows,
        "osx" | "mac" | "macos" => host.os == Os::MacOs,
        "linux" | "unix" => host.os == Os::Linux,
        _ => false,
    }
}

/// The launcher implements no Mojang feature toggles: every feature is `false`,
/// so the block matches only when every listed feature expects `false`.
fn features_match(features: &Map<String, Value>) -> bool {
    features.values().all(|v| !as_bool(v).unwrap_or(false))
}

/// Inclusive numeric `versionRange {min?, max?}` check. An empty host version
/// matches.
fn version_range_matches(range: &Map<String, Value>, os_version: &str) -> bool {
    if os_version.is_empty() {
        return true;
    }
    let current = parse_version_components(os_version);
    if let Some(min) = range.get("min").and_then(primitive_string)
        && compare_version_components(&current, &parse_version_components(&min)).is_lt()
    {
        return false;
    }
    if let Some(max) = range.get("max").and_then(primitive_string)
        && compare_version_components(&current, &parse_version_components(&max)).is_gt()
    {
        return false;
    }
    true
}

/// Split on `.` (Java `String.split` drops trailing empty parts), strip
/// non-digits from each part; an unparseable part is `0`.
pub fn parse_version_components(version: &str) -> Vec<i32> {
    let mut parts: Vec<&str> = version.split('.').collect();
    while parts.len() > 1 && parts.last().is_some_and(|p| p.is_empty()) {
        parts.pop();
    }
    parts
        .iter()
        .map(|p| {
            let digits: String = p.chars().filter(char::is_ascii_digit).collect();
            digits.parse::<i32>().unwrap_or(0)
        })
        .collect()
}

/// Lexicographic compare where missing trailing components count as `0`.
pub fn compare_version_components(a: &[i32], b: &[i32]) -> std::cmp::Ordering {
    let len = a.len().max(b.len());
    for i in 0..len {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        if x != y {
            return x.cmp(&y);
        }
    }
    std::cmp::Ordering::Equal
}

enum CompiledPattern {
    Std(regex::Regex),
    Fancy(Box<fancy_regex::Regex>),
}

impl CompiledPattern {
    fn find(&self, value: &str) -> bool {
        match self {
            CompiledPattern::Std(r) => r.is_match(value),
            CompiledPattern::Fancy(r) => r.is_match(value).unwrap_or(false),
        }
    }
}

/// Compiled rule patterns by source string, failures included, so each
/// pattern compiles once per process. Patterns the `regex` crate rejects (for
/// example lookaround) are retried with `fancy-regex`; only if both reject it
/// is the pattern a permanent non-match.
pub struct RulePatternCache {
    patterns: RwLock<HashMap<String, Option<Arc<CompiledPattern>>>>,
}

impl RulePatternCache {
    /// An empty cache.
    pub fn new() -> Self {
        RulePatternCache {
            patterns: RwLock::new(HashMap::new()),
        }
    }

    /// The process-wide cache used by [`evaluate_rules`].
    pub fn global() -> &'static RulePatternCache {
        static GLOBAL: LazyLock<RulePatternCache> = LazyLock::new(RulePatternCache::new);
        &GLOBAL
    }

    /// Regex **find** of `pattern` in `value`. An invalid pattern is `false`.
    pub fn find(&self, pattern: &str, value: &str) -> bool {
        let cached = self.patterns.read().get(pattern).cloned();
        let compiled = match cached {
            Some(c) => c,
            None => {
                let c = compile(pattern).map(Arc::new);
                self.patterns
                    .write()
                    .entry(pattern.to_string())
                    .or_insert(c)
                    .clone()
            }
        };
        compiled.is_some_and(|c| c.find(value))
    }

    /// Whether `pattern` is cached (compiled or failed). For tests.
    pub fn contains(&self, pattern: &str) -> bool {
        self.patterns.read().contains_key(pattern)
    }

    /// Whether `pattern` compiled under either engine.
    pub fn is_valid(&self, pattern: &str) -> bool {
        self.find(pattern, "");
        self.patterns
            .read()
            .get(pattern)
            .is_some_and(Option::is_some)
    }
}

impl Default for RulePatternCache {
    fn default() -> Self {
        Self::new()
    }
}

fn compile(pattern: &str) -> Option<CompiledPattern> {
    if let Ok(r) = regex::Regex::new(pattern) {
        return Some(CompiledPattern::Std(r));
    }
    fancy_regex::Regex::new(pattern)
        .ok()
        .map(|r| CompiledPattern::Fancy(Box::new(r)))
}

fn regex_find(pattern: &str, value: &str) -> bool {
    RulePatternCache::global().find(pattern, value)
}

/// Wrap `arg` in double quotes when it contains a space and no `${`
/// placeholder, unless it is empty or already starts and ends with `"`.
pub fn quote_if_needed(arg: &str) -> String {
    if arg.is_empty() || (arg.starts_with('"') && arg.ends_with('"')) {
        return arg.to_string();
    }
    if arg.contains(' ') && !arg.contains("${") {
        return format!("\"{arg}\"");
    }
    arg.to_string()
}

/// The quoted tokens that [`flatten_arguments`] joins with spaces.
fn flatten_pieces(args: Option<&[Value]>, host: &HostInfo) -> Vec<String> {
    let mut out = Vec::new();
    let Some(args) = args else { return out };
    for el in args {
        match el {
            Value::Null => {}
            Value::Object(obj) => {
                if !evaluate_rules_value(obj.get("rules"), host) {
                    continue;
                }
                match obj.get("value") {
                    Some(Value::Array(values)) => {
                        out.extend(
                            values
                                .iter()
                                .filter_map(primitive_string)
                                .map(|v| quote_if_needed(&v)),
                        );
                    }
                    Some(v) => {
                        if let Some(s) = primitive_string(v) {
                            out.push(quote_if_needed(&s));
                        }
                    }
                    None => {}
                }
            }
            Value::Array(_) => {}
            primitive => {
                if let Some(s) = primitive_string(primitive) {
                    out.push(quote_if_needed(&s));
                }
            }
        }
    }
    out
}

/// Flatten a modern (1.13+) `arguments.jvm` / `arguments.game` array into one
/// space-separated string (the Java API). Plain entries are included; object
/// entries only when their `rules` allow, with a string or array `value`. Each
/// token goes through [`quote_if_needed`].
pub fn flatten_arguments(args: Option<&[Value]>, host: &HostInfo) -> String {
    flatten_pieces(args, host).join(" ")
}

/// The argv tokens of [`flatten_arguments`], without a string round trip.
///
/// Always equal to `split_command_line(&flatten_arguments(args, host))`: each
/// token is split on its own, unless a token has an unbalanced `"`, in which
/// case the quote state would cross token boundaries and the joined string is
/// split instead.
pub fn flatten_arguments_vec(args: Option<&[Value]>, host: &HostInfo) -> Vec<String> {
    let pieces = flatten_pieces(args, host);
    if pieces.iter().any(|p| p.matches('"').count() % 2 == 1) {
        return split_command_line(&pieces.join(" "));
    }
    pieces.iter().flat_map(|p| split_command_line(p)).collect()
}

/// `"windows"`, `"osx"` or `"linux"` for `host`.
pub fn current_platform_name(host: &HostInfo) -> &'static str {
    host.platform_name()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::presets::*;
    use serde_json::json;

    fn rules(v: Value) -> Vec<Value> {
        v.as_array().unwrap().clone()
    }

    fn eval(v: Value, host: &HostInfo) -> bool {
        let r = rules(v);
        evaluate_rules(Some(&r), host)
    }

    fn not_current(host: &HostInfo) -> &'static str {
        if host.platform_name() == "windows" {
            "linux"
        } else {
            "windows"
        }
    }

    fn quote_regex(s: &str) -> String {
        regex::escape(s)
    }

    // ---- defaults ----

    #[test]
    fn null_rules_allow_the_item() {
        assert!(evaluate_rules(None, &linux_x64()));
    }

    #[test]
    fn empty_rules_allow_the_item() {
        assert!(evaluate_rules(Some(&[]), &linux_x64()));
    }

    #[test]
    fn current_platform_name_is_reported() {
        for h in all() {
            assert!(!current_platform_name(&h).is_empty());
        }
    }

    // ---- unrestricted ----

    #[test]
    fn unrestricted_allow_rule_allows() {
        assert!(eval(json!([{"action": "allow"}]), &windows_x64()));
    }

    #[test]
    fn unrestricted_disallow_rule_denies() {
        assert!(!eval(json!([{"action": "disallow"}]), &windows_x64()));
    }

    #[test]
    fn missing_action_defaults_to_allow() {
        assert!(eval(json!([{}]), &mac_arm()));
    }

    #[test]
    fn action_comparison_is_case_insensitive() {
        assert!(eval(json!([{"action": "ALLOW"}]), &mac_arm()));
        assert!(!eval(json!([{"action": "DisAllow"}]), &mac_arm()));
    }

    // ---- OS scoping ----

    #[test]
    fn rule_scoped_to_current_platform_applies() {
        for h in [windows_x64(), mac_arm(), linux_x64()] {
            assert!(eval(
                json!([{"action": "allow", "os": {"name": h.platform_name()}}]),
                &h
            ));
        }
    }

    #[test]
    fn rule_scoped_to_another_platform_is_skipped_leaving_item_disallowed() {
        for h in [windows_x64(), mac_arm(), linux_x64()] {
            assert!(!eval(
                json!([{"action": "allow", "os": {"name": not_current(&h)}}]),
                &h
            ));
        }
    }

    #[test]
    fn disallow_rule_for_another_platform_does_not_affect_this_one() {
        for h in [windows_x64(), mac_arm(), linux_x64()] {
            assert!(eval(
                json!([{"action": "allow"}, {"action": "disallow", "os": {"name": not_current(&h)}}]),
                &h
            ));
        }
    }

    #[test]
    fn os_name_aliases() {
        for n in ["windows", "win", "bindoj", " WINDOWS "] {
            assert!(os_name_matches(n, &windows_x64()), "{n}");
            assert!(!os_name_matches(n, &linux_x64()), "{n}");
        }
        for n in ["osx", "mac", "macos", "MacOS"] {
            assert!(os_name_matches(n, &mac_intel()), "{n}");
        }
        for n in ["linux", "unix"] {
            assert!(os_name_matches(n, &linux_arm64()), "{n}");
            assert!(
                !os_name_matches(n, &freebsd_x64()),
                "BSD matches nothing: {n}"
            );
        }
        assert!(!os_name_matches("solaris", &linux_x64()));
        assert!(!os_name_matches("freebsd", &freebsd_x64()));
    }

    // ---- last matching rule wins ----

    #[test]
    fn later_matching_rule_overrides_earlier_one() {
        for h in [windows_x64(), mac_arm(), linux_x64()] {
            let cur = h.platform_name();
            assert!(!eval(
                json!([{"action": "allow"}, {"action": "disallow", "os": {"name": cur}}]),
                &h
            ));
            assert!(eval(
                json!([{"action": "disallow"}, {"action": "allow", "os": {"name": cur}}]),
                &h
            ));
        }
    }

    // ---- malformed ----

    #[test]
    fn non_object_rule_entries_are_skipped() {
        assert!(eval(json!(["garbage", {"action": "allow"}]), &linux_x64()));
        assert!(!eval(
            json!([{"action": "allow"}, 42, null, {"action": "disallow"}]),
            &linux_x64()
        ));
    }

    #[test]
    fn non_object_os_block_never_matches() {
        assert!(!eval(
            json!([{"action": "allow", "os": "windows"}]),
            &windows_x64()
        ));
    }

    #[test]
    fn non_array_rules_value_is_treated_as_absent() {
        assert!(evaluate_rules_value(Some(&json!("nope")), &linux_x64()));
        assert!(evaluate_rules_value(None, &linux_x64()));
    }

    // ---- os.version / os.arch regex ----

    #[test]
    fn os_version_regex_matching_the_host_version_applies() {
        for h in all() {
            let p = quote_regex(&h.java_os_version);
            assert!(eval(json!([{"action": "allow", "os": {"version": p}}]), &h));
        }
    }

    #[test]
    fn os_version_regex_not_matching_is_skipped() {
        assert!(!eval(
            json!([{"action": "allow", "os": {"version": "this-will-never-match-a-real-os-version-string"}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn os_version_regex_is_a_find_not_a_full_match() {
        assert!(eval(
            json!([{"action": "allow", "os": {"version": "^10\\."}}]),
            &windows_x64()
        ));
        assert!(eval(
            json!([{"action": "allow", "os": {"version": "5\\.0"}}]),
            &linux_x64()
        ));
        assert!(!eval(
            json!([{"action": "allow", "os": {"version": "^Microsoft Windows XP"}}]),
            &windows_x64()
        ));
    }

    #[test]
    fn os_arch_regex_matching_the_host_arch_applies() {
        for h in all() {
            let p = quote_regex(&h.java_os_arch);
            assert!(eval(json!([{"action": "allow", "os": {"arch": p}}]), &h));
        }
    }

    #[test]
    fn os_arch_regex_not_matching_is_skipped() {
        assert!(!eval(
            json!([{"action": "allow", "os": {"arch": "not-a-real-architecture-token"}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn legacy_x86_rule_matches_intel_mac_and_32_bit_windows_but_not_amd64() {
        let r = json!([{"action": "allow", "os": {"arch": "x86"}}]);
        assert!(eval(r.clone(), &mac_intel()), "x86_64 contains x86");
        assert!(eval(r.clone(), &windows_x86()));
        assert!(!eval(r.clone(), &windows_x64()));
        assert!(!eval(r.clone(), &linux_x64()));
        assert!(!eval(r, &mac_arm()));
    }

    #[test]
    fn invalid_regex_is_treated_as_a_non_match_rather_than_throwing() {
        assert!(!eval(
            json!([{"action": "allow", "os": {"version": "[unclosed-character-class"}}]),
            &linux_x64()
        ));
        let cache = RulePatternCache::new();
        assert!(!cache.find("[unclosed", "x"));
        assert!(cache.contains("[unclosed"), "the failure is cached too");
        assert!(!cache.is_valid("[unclosed"));
    }

    #[test]
    fn lookaround_patterns_fall_back_to_fancy_regex() {
        let cache = RulePatternCache::new();
        assert!(cache.is_valid("^(?!x86)amd"));
        assert!(cache.find("^(?!x86)amd", "amd64"));
        assert!(!cache.find("^(?!amd)", "amd64"));
        assert!(eval(
            json!([{"action": "allow", "os": {"arch": "^(?!x86)amd64$"}}]),
            &windows_x64()
        ));
    }

    // ---- versionRange ----

    fn range_rule(min: Option<&str>, max: Option<&str>) -> Value {
        let mut range = serde_json::Map::new();
        if let Some(m) = min {
            range.insert("min".into(), json!(m));
        }
        if let Some(m) = max {
            range.insert("max".into(), json!(m));
        }
        json!([{"action": "allow", "os": {"versionRange": range}}])
    }

    #[test]
    fn version_range_matches_when_within_min_and_max() {
        for h in all() {
            assert!(eval(range_rule(Some("0"), Some("999999")), &h));
        }
    }

    #[test]
    fn version_range_fails_below_min() {
        for h in all() {
            assert!(!eval(range_rule(Some("999999"), None), &h));
        }
    }

    #[test]
    fn version_range_fails_above_max() {
        for h in all() {
            assert!(!eval(range_rule(None, Some("0.0")), &h));
        }
    }

    #[test]
    fn version_range_with_only_min_ignores_max() {
        assert!(eval(range_rule(Some("0"), None), &mac_arm()));
    }

    #[test]
    fn version_range_is_inclusive_at_the_boundary() {
        for h in all() {
            let v = h.java_os_version.clone();
            assert!(eval(range_rule(Some(&v), Some(&v)), &h));
        }
    }

    #[test]
    fn non_object_version_range_never_matches() {
        for bad in [json!("10.0"), json!(10), json!(["10.0"])] {
            assert!(!eval(
                json!([{"action": "allow", "os": {"versionRange": bad}}]),
                &windows_x64()
            ));
        }
        assert!(eval(
            json!([{"action": "allow", "os": {"versionRange": null}}]),
            &windows_x64()
        ));
    }

    #[test]
    fn version_range_empty_host_version_matches() {
        let h = HostInfo::emulated(Os::Linux, crate::host::Arch::X86_64, "");
        assert!(eval(range_rule(Some("999"), None), &h));
    }

    #[test]
    fn windows_java_version_never_reaches_a_build_number_minimum() {
        // Java reports "10.0" on Windows 10 and 11: [10,0] < [10,0,17134] (B-18, Q-1).
        assert!(!eval(range_rule(Some("10.0.17134"), None), &windows_x64()));
        assert!(eval(range_rule(Some("10.0"), None), &windows_x64()));
        assert!(eval(range_rule(None, Some("10.0.17134")), &windows_x64()));
    }

    #[test]
    fn version_components_strip_non_digits_and_pad_with_zero() {
        assert_eq!(parse_version_components("6.5.0-35-generic"), vec![6, 5, 35]);
        assert_eq!(parse_version_components("10.0."), vec![10, 0]);
        assert_eq!(parse_version_components("abc"), vec![0]);
        assert_eq!(parse_version_components(""), vec![0]);
        assert_eq!(
            parse_version_components("99999999999.1"),
            vec![0, 1],
            "overflow → 0"
        );
        assert!(compare_version_components(&[10, 0], &[10, 0, 0]).is_eq());
        assert!(compare_version_components(&[10, 0], &[10, 0, 1]).is_lt());
        assert!(compare_version_components(&[14, 5], &[11]).is_gt());
    }

    // ---- features ----

    #[test]
    fn rule_requiring_a_feature_to_be_enabled_never_matches() {
        assert!(!eval(
            json!([{"action": "allow", "features": {"is_demo_user": true}}]),
            &linux_x64()
        ));
        assert!(!eval(
            json!([{"action": "allow", "features": {"has_custom_resolution": true}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn rule_requiring_a_feature_to_be_disabled_matches() {
        assert!(eval(
            json!([{"action": "allow", "features": {"is_demo_user": false}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn empty_features_block_does_not_block_the_rule() {
        assert!(eval(
            json!([{"action": "allow", "features": {}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn mixed_features_need_all_false() {
        assert!(!eval(
            json!([{"action": "allow", "features": {"a": false, "is_quick_play_singleplayer": true}}]),
            &linux_x64()
        ));
    }

    #[test]
    fn os_and_features_must_both_match() {
        let r = json!([{"action": "allow", "os": {"name": "osx"}, "features": {"x": false}}]);
        assert!(eval(r.clone(), &mac_arm()));
        assert!(!eval(r, &linux_x64()));
    }

    // ---- flatten ----

    fn flat(v: Value, host: &HostInfo) -> String {
        let a = rules(v);
        flatten_arguments(Some(&a), host)
    }

    #[test]
    fn flatten_null_and_empty_yield_empty_string() {
        assert_eq!(flatten_arguments(None, &linux_x64()), "");
        assert_eq!(flatten_arguments(Some(&[]), &linux_x64()), "");
    }

    #[test]
    fn flatten_includes_plain_string_arguments() {
        assert_eq!(
            flat(json!(["-Xmx2G", "-Dfoo=bar"]), &linux_x64()),
            "-Xmx2G -Dfoo=bar"
        );
    }

    #[test]
    fn flatten_stringifies_number_and_bool_primitives_and_skips_nulls() {
        assert_eq!(
            flat(json!(["--width", 854, null, true]), &linux_x64()),
            "--width 854 true"
        );
    }

    #[test]
    fn flatten_includes_conditional_argument_when_rules_allow() {
        let h = mac_arm();
        assert_eq!(
            flat(
                json!([{"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": "-XstartOnFirstThread"}]),
                &h
            ),
            "-XstartOnFirstThread"
        );
    }

    #[test]
    fn flatten_excludes_conditional_argument_when_rules_deny() {
        assert_eq!(
            flat(
                json!([{"rules": [{"action": "allow", "os": {"name": "windows"}}], "value": "-XotherPlatformOnly"}]),
                &linux_x64()
            ),
            ""
        );
    }

    #[test]
    fn flatten_expands_array_valued_arguments() {
        assert_eq!(
            flat(
                json!([{"action": "allow", "value": ["-Done=1", "-Dtwo=2"]}]),
                &linux_x64()
            ),
            "-Done=1 -Dtwo=2"
        );
    }

    #[test]
    fn flatten_skips_objects_with_no_value() {
        assert_eq!(
            flat(json!([{"rules": [{"action": "allow"}]}]), &linux_x64()),
            ""
        );
        assert_eq!(
            flat(
                json!([{"rules": [{"action": "allow"}], "value": null}]),
                &linux_x64()
            ),
            ""
        );
    }

    #[test]
    fn flatten_drops_feature_gated_arguments() {
        let args = json!([
            "--username", "${auth_player_name}",
            {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"},
            {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
             "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]}
        ]);
        assert_eq!(flat(args, &windows_x64()), "--username ${auth_player_name}");
    }

    // ---- quoting ----

    #[test]
    fn arguments_containing_whitespace_are_quoted() {
        assert_eq!(
            flat(json!(["-Dos.name=Windows 10"]), &windows_x64()),
            "\"-Dos.name=Windows 10\""
        );
    }

    #[test]
    fn arguments_without_whitespace_are_not_quoted() {
        assert_eq!(flat(json!(["-Xmx4G"]), &windows_x64()), "-Xmx4G");
    }

    #[test]
    fn arguments_containing_placeholders_are_left_unquoted() {
        let out = flat(
            json!(["-Dpath=${library_directory}/foo bar"]),
            &windows_x64(),
        );
        assert!(!out.starts_with('"'));
    }

    #[test]
    fn already_quoted_arguments_are_kept() {
        assert_eq!(quote_if_needed("\"a b\""), "\"a b\"");
        assert_eq!(quote_if_needed(""), "");
        assert_eq!(
            quote_if_needed("a\tb"),
            "a\tb",
            "only a space triggers quoting"
        );
    }

    // ---- vec form agrees with string + split ----

    #[test]
    fn flatten_vec_matches_split_of_flatten() {
        let cases = vec![
            json!([
                "-Xmx2G",
                "-Dos.name=Windows 10",
                "-Dpath=${library_directory}/foo bar",
                "",
                "\"\""
            ]),
            json!([{"rules": [{"action": "allow", "os": {"name": "windows"}}],
                    "value": ["-Dos.name=Windows 10", "-Dos.version=10.0"]},
                   {"rules": [{"action": "allow", "os": {"arch": "x86"}}], "value": "-Xss1M"},
                   "-cp", "${classpath}"]),
            json!(["odd\"quote", "a b", "c"]),
            json!(["tab\tsep", "x"]),
        ];
        for host in all() {
            for c in &cases {
                let a = rules(c.clone());
                let joined = flatten_arguments(Some(&a), &host);
                assert_eq!(
                    flatten_arguments_vec(Some(&a), &host),
                    split_command_line(&joined),
                    "{c} on {host:?}"
                );
            }
        }
    }

    #[test]
    fn flatten_vec_keeps_spaced_values_as_one_token() {
        let a = rules(json!(["-Dos.name=Windows 10"]));
        assert_eq!(
            flatten_arguments_vec(Some(&a), &windows_x64()),
            vec!["-Dos.name=Windows 10"]
        );
    }
}
