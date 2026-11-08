// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Known-incompatible mod pairs, detected from jar file names. Scanning `mods/` and
//! disabling a jar live in `mmcl-modpack`; the rules are pure and live here.

use std::sync::LazyLock;

use regex::Regex;

/// Two installed jars that are known not to work together.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Conflict {
    /// Localization key of the short title.
    pub title_key: &'static str,
    /// Localization key of the explanation.
    pub description_key: &'static str,
    /// The jar matching the rule's first pattern.
    pub first_jar: String,
    /// The jar matching the rule's second pattern.
    pub second_jar: String,
}

struct Rule {
    title_key: &'static str,
    description_key: &'static str,
    first: Regex,
    second: Regex,
}

static RULES: LazyLock<Vec<Rule>> = LazyLock::new(|| {
    vec![
        Rule {
            title_key: "modConflict.optifineSodium.title",
            description_key: "modConflict.optifineSodium.description",
            first: Regex::new(r"(?i)\boptifine\b|\boptifabric\b").expect("valid regex"),
            second: Regex::new(r"(?i)\bsodium(?:fabric)?\b").expect("valid regex"),
        },
        Rule {
            title_key: "modConflict.jeiRei.title",
            description_key: "modConflict.jeiRei.description",
            first: Regex::new(r"(?i)\bjei\b|just[- ]?enough[- ]?items|mezz\.jei")
                .expect("valid regex"),
            second: Regex::new(r"(?i)\brei\b|roughly[- ]?enough[- ]?items|shedaniel\.rei")
                .expect("valid regex"),
        },
    ]
});

/// True for a name that the conflict scan considers (ends in `.jar`, any case; a
/// `.jar.disabled` file is already out of play).
pub fn is_active_jar(name: &str) -> bool {
    name.to_ascii_lowercase().ends_with(".jar")
}

/// Applies every rule, in order, to jar file names (in directory order). A rule fires when
/// both of its patterns match some name (the first such name each) and the names differ.
pub fn detect_from_names<S: AsRef<str>>(jar_file_names: &[S]) -> Vec<Conflict> {
    let first_match = |re: &Regex| {
        jar_file_names
            .iter()
            .map(AsRef::as_ref)
            .find(|n| re.is_match(n))
    };
    RULES
        .iter()
        .filter_map(|rule| {
            let a = first_match(&rule.first)?;
            let b = first_match(&rule.second)?;
            (a != b).then(|| Conflict {
                title_key: rule.title_key,
                description_key: rule.description_key,
                first_jar: a.to_string(),
                second_jar: b.to_string(),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_list_produces_no_conflicts() {
        assert!(detect_from_names::<&str>(&[]).is_empty());
    }

    #[test]
    fn optifine_plus_sodium() {
        let c = detect_from_names(&[
            "optifine-1.20.1.jar",
            "sodium-fabric-mc1.20.1.jar",
            "fabric-api-0.92.0.jar",
        ]);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].title_key, "modConflict.optifineSodium.title");
        assert_eq!(c[0].first_jar, "optifine-1.20.1.jar");
        assert_eq!(c[0].second_jar, "sodium-fabric-mc1.20.1.jar");
    }

    #[test]
    fn optifabric_plus_sodium() {
        let c = detect_from_names(&["optifabric-1.13.42.jar", "sodium-mc1.20.1.jar"]);
        assert_eq!(c.len(), 1);
    }

    #[test]
    fn jei_plus_rei() {
        let c = detect_from_names(&["jei-1.20.1-forge-15.2.0.27.jar", "rei-12.0.684-forge.jar"]);
        assert_eq!(c.len(), 1);
        assert_eq!(c[0].title_key, "modConflict.jeiRei.title");
    }

    #[test]
    fn long_forms() {
        let c = detect_from_names(&[
            "just-enough-items-1.20.1.jar",
            "roughly-enough-items-1.20.1.jar",
        ]);
        assert_eq!(c.len(), 1);
    }

    #[test]
    fn only_one_half_returns_nothing() {
        assert!(detect_from_names(&["optifine-1.20.1.jar", "fabric-api-0.92.0.jar"]).is_empty());
        assert!(detect_from_names(&["jei-1.20.1.jar"]).is_empty());
    }

    #[test]
    fn case_insensitive() {
        assert_eq!(
            detect_from_names(&["OPTIFINE-1.20.1.jar", "SODIUM-fabric.jar"]).len(),
            1
        );
    }

    #[test]
    fn multiple_conflicts_together() {
        let c = detect_from_names(&[
            "optifine-1.20.1.jar",
            "sodium-fabric.jar",
            "jei-1.20.1.jar",
            "rei-1.20.1.jar",
        ]);
        assert_eq!(c.len(), 2);
    }

    #[test]
    fn unrelated_mods() {
        assert!(
            detect_from_names(&[
                "create-1.20.1-0.5.1.jar",
                "journeymap-1.20.1-5.9.18.jar",
                "iron-chests-1.20.1.jar",
                "biomes-o-plenty-1.20.1.jar",
            ])
            .is_empty()
        );
    }

    #[test]
    fn active_jar_filter() {
        assert!(is_active_jar("a.JAR"));
        assert!(!is_active_jar("a.jar.disabled"));
    }
}
