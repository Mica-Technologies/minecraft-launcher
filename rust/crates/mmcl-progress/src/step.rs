// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The launch pipeline's step identifiers.

use std::fmt;
use std::str::FromStr;

/// One row of the launch progress view. The declaration order is the display-order contract.
#[derive(Copy, Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum StepId {
    /// Modpack content (mods, configs, resource packs, shader packs).
    ModpackContent,
    /// Modloader libraries.
    ForgeLibs,
    /// Minecraft libraries and assets.
    McLibsAssets,
    /// Java runtime.
    JreInstall,
    /// Game patching (Forge processors). Omitted when the loader has no post-install steps.
    ForgeProcessors,
    /// Security scan.
    SecurityScan,
}

impl StepId {
    /// Every step, in display order.
    pub const ALL: [StepId; 6] = [
        StepId::ModpackContent,
        StepId::ForgeLibs,
        StepId::McLibsAssets,
        StepId::JreInstall,
        StepId::ForgeProcessors,
        StepId::SecurityScan,
    ];

    /// The Java enum constant name (`MODPACK_CONTENT`, ...). Stable; used in localization keys
    /// and anywhere a step is serialised.
    pub const fn name(self) -> &'static str {
        match self {
            StepId::ModpackContent => "MODPACK_CONTENT",
            StepId::ForgeLibs => "FORGE_LIBS",
            StepId::McLibsAssets => "MC_LIBS_ASSETS",
            StepId::JreInstall => "JRE_INSTALL",
            StepId::ForgeProcessors => "FORGE_PROCESSORS",
            StepId::SecurityScan => "SECURITY_SCAN",
        }
    }

    /// The localization key of the row label: `launchStep.<NAME>` (keys already exist in the
    /// shared `DisplayStrings.properties`).
    pub const fn label_key(self) -> &'static str {
        match self {
            StepId::ModpackContent => "launchStep.MODPACK_CONTENT",
            StepId::ForgeLibs => "launchStep.FORGE_LIBS",
            StepId::McLibsAssets => "launchStep.MC_LIBS_ASSETS",
            StepId::JreInstall => "launchStep.JRE_INSTALL",
            StepId::ForgeProcessors => "launchStep.FORGE_PROCESSORS",
            StepId::SecurityScan => "launchStep.SECURITY_SCAN",
        }
    }

    /// The English label, the fallback when the localization key is missing.
    pub const fn english_label(self) -> &'static str {
        match self {
            StepId::ModpackContent => "Modpack content",
            StepId::ForgeLibs => "Modloader libraries",
            StepId::McLibsAssets => "Minecraft libraries & assets",
            StepId::JreInstall => "Java runtime",
            StepId::ForgeProcessors => "Game patching",
            StepId::SecurityScan => "Security scan",
        }
    }

    /// The step list a launch (or verify) builds its tracker from, exactly as the Java
    /// launcher assembles it: vanilla versions have no modpack content or modloader libraries,
    /// and the game patching row exists only when the loader uses post-install steps.
    pub fn launch_steps(is_vanilla: bool, uses_post_install_steps: bool) -> Vec<StepId> {
        let mut steps = Vec::with_capacity(6);
        if !is_vanilla {
            steps.push(StepId::ModpackContent);
            steps.push(StepId::ForgeLibs);
        }
        steps.push(StepId::McLibsAssets);
        steps.push(StepId::JreInstall);
        if uses_post_install_steps {
            steps.push(StepId::ForgeProcessors);
        }
        steps.push(StepId::SecurityScan);
        steps
    }
}

impl fmt::Display for StepId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.name())
    }
}

/// A string that names no [`StepId`].
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("unknown launch step name: {0}")]
pub struct ParseStepIdError(pub String);

impl FromStr for StepId {
    type Err = ParseStepIdError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        StepId::ALL
            .into_iter()
            .find(|id| id.name() == s)
            .ok_or_else(|| ParseStepIdError(s.to_owned()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_round_trip_and_keys_follow_the_java_convention() {
        for id in StepId::ALL {
            assert_eq!(id.name().parse::<StepId>().unwrap(), id);
            assert_eq!(id.label_key(), format!("launchStep.{}", id.name()));
        }
        assert!("nope".parse::<StepId>().is_err());
    }

    #[test]
    fn launch_steps_match_the_java_assembly() {
        use StepId::*;
        assert_eq!(
            StepId::launch_steps(false, true),
            vec![
                ModpackContent,
                ForgeLibs,
                McLibsAssets,
                JreInstall,
                ForgeProcessors,
                SecurityScan
            ]
        );
        assert_eq!(
            StepId::launch_steps(false, false),
            vec![
                ModpackContent,
                ForgeLibs,
                McLibsAssets,
                JreInstall,
                SecurityScan
            ]
        );
        assert_eq!(
            StepId::launch_steps(true, false),
            vec![McLibsAssets, JreInstall, SecurityScan]
        );
    }

    #[test]
    fn display_order_is_declaration_order() {
        let mut sorted = StepId::ALL;
        sorted.sort();
        assert_eq!(sorted, StepId::ALL);
    }
}
