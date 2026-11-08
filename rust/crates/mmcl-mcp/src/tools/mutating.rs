// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The eight state-changing tools (Java `MutatingTools`), registered only when the second
//! opt-in (`mcpAllowStateChanges`) is on. Each one's content gate runs before the user is
//! asked, so a malformed request never becomes a prompt.

use std::sync::Arc;

use mmcl_util::InstallUrlVerdict;
use mmcl_util::deeplink::classify_install_url;
use serde_json::{Map, Value, json};

use super::{
    Args, CallContext, LauncherActions, LauncherView, Outcome, RunningGame, Tool, ToolRegistry,
    ToolResult, string_arg, unknown_pack,
};
use crate::approval::RiskClass;
use crate::{BoxError, McpError};

/// Registration order (appended after the read-only tools).
pub const TOOL_NAMES: [&str; 8] = [
    "install_modpack",
    "create_modpack",
    "fork_modpack",
    "add_mod_to_modpack",
    "remove_mod_from_modpack",
    "uninstall_modpack",
    "launch_modpack",
    "stop_game",
];

/// [`TOOL_NAMES`] as owned strings.
pub fn tool_names() -> Vec<String> {
    TOOL_NAMES.iter().map(|s| s.to_string()).collect()
}

/// Registers the eight tools in [`TOOL_NAMES`] order.
pub fn register_all(
    reg: &mut ToolRegistry,
    view: Arc<dyn LauncherView>,
    actions: Arc<dyn LauncherActions>,
) -> Result<(), McpError> {
    for kind in [
        Kind::Install,
        Kind::Create,
        Kind::Fork,
        Kind::AddMod,
        Kind::RemoveMod,
        Kind::Uninstall,
        Kind::Launch,
        Kind::Stop,
    ] {
        reg.register(Arc::new(MutatingTool {
            kind,
            view: view.clone(),
            actions: actions.clone(),
        }))?;
    }
    Ok(())
}

/// An object schema whose properties are all strings; `required` lists the required ones.
fn schema_of(props: &[(&str, &str)], required: &[&str]) -> Value {
    let mut p = Map::new();
    for (name, desc) in props {
        p.insert(
            (*name).to_string(),
            json!({"type":"string","description":desc}),
        );
    }
    let mut m = Map::new();
    m.insert("type".into(), Value::String("object".into()));
    m.insert("properties".into(), Value::Object(p));
    if !required.is_empty() {
        m.insert(
            "required".into(),
            Value::Array(
                required
                    .iter()
                    .map(|r| Value::String((*r).into()))
                    .collect(),
            ),
        );
    }
    Value::Object(m)
}

fn result_of(outcome: Option<Outcome>) -> ToolResult {
    match outcome {
        None => ToolResult::error("The operation reported no outcome."),
        Some(o) if o.ok => ToolResult::text(o.message),
        Some(o) => ToolResult::error(o.message),
    }
}

fn empty_to_none(s: &str) -> Option<&str> {
    if s.is_empty() { None } else { Some(s) }
}

/// ASCII letters and digits, lower-cased: the authored manifest file key.
pub fn local_manifest_key_of(name: &str) -> String {
    name.chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_ascii_lowercase()
}

/// Refuses a new pack name that has no usable characters or would share an authored manifest
/// file (`created-<key>.json`) with a known pack.
pub fn new_pack_name_rejection(view: &dyn LauncherView, name: &str) -> Option<String> {
    let key = local_manifest_key_of(name);
    if key.is_empty() {
        return Some(format!(
            "The name \"{name}\" has no letters or digits. Pick a name with at least one."
        ));
    }
    for p in view.packs() {
        if local_manifest_key_of(&p.friendly_name) != key {
            continue;
        }
        if p.friendly_name == name {
            return Some(format!(
                "A modpack named \"{name}\" already exists. Pick a different name."
            ));
        }
        return Some(format!(
            "\"{name}\" is too close to the existing modpack \"{}\": names that differ only in case, spacing or punctuation would share a manifest file. Pick a more distinct name.",
            p.friendly_name
        ));
    }
    None
}

/// Refuses an absolute, escaping or control-character-laden pack-relative path.
pub fn reject_unsafe_local_path(p: &str) -> Option<String> {
    let t = p.trim();
    if t.is_empty() {
        return Some("A localPath argument is required.".into());
    }
    if t.starts_with('/') || t.starts_with('\\') || t.chars().nth(1) == Some(':') {
        return Some(
            "localPath must be relative to the modpack folder, not an absolute path.".into(),
        );
    }
    if t.split(['/', '\\']).any(|seg| seg == "..") {
        return Some("localPath must stay inside the modpack folder.".into());
    }
    if p.chars().any(char::is_control) {
        return Some("localPath contains an illegal character.".into());
    }
    None
}

/// `512 bytes`, `1.0 KB`, `4.2 GB`, `512 MB`: one decimal below 10, none above, rounded half
/// up on the exact value like Java's `Formatter`.
pub fn describe_size(bytes: u64) -> String {
    if bytes < 1024 {
        return format!("{bytes} bytes");
    }
    const UNITS: [&str; 4] = ["KB", "MB", "GB", "TB"];
    let mut idx = 0usize;
    let mut div: u128 = 1024;
    let b = u128::from(bytes);
    while idx + 1 < UNITS.len() && b >= div * 1024 {
        idx += 1;
        div *= 1024;
    }
    let round = |num: u128, den: u128| -> u128 {
        let q = num / den;
        if (num % den) * 2 >= den { q + 1 } else { q }
    };
    if b < div * 10 {
        let tenths = round(b * 10, div);
        format!("{}.{} {}", tenths / 10, tenths % 10, UNITS[idx])
    } else {
        format!("{} {}", round(b, div), UNITS[idx])
    }
}

/// The game `stop_game` would stop: a named one (case-insensitive) or the only one running.
pub fn target_of(running: &[RunningGame], named: &str) -> Option<String> {
    if !named.is_empty() {
        return running
            .iter()
            .find(|g| g.friendly_name.to_lowercase() == named.to_lowercase())
            .map(|g| g.friendly_name.clone());
    }
    if running.len() == 1 {
        return Some(running[0].friendly_name.clone());
    }
    None
}

/// Why `stop_game` has no target; `None` when it has one.
pub fn stop_problem(running: &[RunningGame], named: &str) -> Option<String> {
    if running.is_empty() {
        return Some("No game is currently running.".into());
    }
    if target_of(running, named).is_some() {
        return None;
    }
    let names = running
        .iter()
        .map(|g| format!("\"{}\"", g.friendly_name))
        .collect::<Vec<_>>()
        .join(", ");
    if named.is_empty() {
        Some(format!(
            "Several games are running ({names}). Name the one to stop with friendlyName."
        ))
    } else {
        Some(format!("\"{named}\" is not running. Running now: {names}."))
    }
}

fn refused_url(url: &str) -> bool {
    classify_install_url(Some(url)) == InstallUrlVerdict::Reject
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    Install,
    Create,
    Fork,
    AddMod,
    RemoveMod,
    Uninstall,
    Launch,
    Stop,
}

struct MutatingTool {
    kind: Kind,
    view: Arc<dyn LauncherView>,
    actions: Arc<dyn LauncherActions>,
}

impl MutatingTool {
    fn known(&self, name: &str) -> bool {
        self.view.pack(name).is_some()
    }

    fn gate(&self, args: &Args) -> Option<String> {
        match self.kind {
            Kind::Install => {
                let url = string_arg(args, "url");
                if url.is_empty() {
                    return Some("A url argument is required.".into());
                }
                if refused_url(&url) {
                    return Some("That install URL was refused by the launcher's install-source rules. Modpack URLs must be well-formed https links.".into());
                }
                None
            }
            Kind::Create => {
                let name = string_arg(args, "name");
                if name.is_empty() {
                    return Some("A name argument is required.".into());
                }
                let loader_url = string_arg(args, "modLoaderUrl");
                if !loader_url.is_empty() && refused_url(&loader_url) {
                    return Some("That mod loader URL was refused by the launcher's install-source rules. Mod loader URLs must be well-formed https links.".into());
                }
                new_pack_name_rejection(self.view.as_ref(), &name)
            }
            Kind::Fork => {
                let src = string_arg(args, "friendlyName");
                let new = string_arg(args, "newName");
                if src.is_empty() || new.is_empty() {
                    return Some("Both friendlyName and newName are required.".into());
                }
                if !self.known(&src) {
                    return Some(unknown_pack(&src));
                }
                new_pack_name_rejection(self.view.as_ref(), &new)
            }
            Kind::AddMod => {
                let pack = string_arg(args, "friendlyName");
                let mod_name = string_arg(args, "modName");
                let remote = string_arg(args, "remoteUrl");
                let local = string_arg(args, "localPath");
                if pack.is_empty() || mod_name.is_empty() || remote.is_empty() || local.is_empty() {
                    return Some(
                        "friendlyName, modName, remoteUrl and localPath are all required.".into(),
                    );
                }
                if !self.known(&pack) {
                    return Some(unknown_pack(&pack));
                }
                if refused_url(&remote) {
                    return Some("That mod URL was refused by the launcher's install-source rules. Mod URLs must be well-formed https links.".into());
                }
                reject_unsafe_local_path(&local)
            }
            Kind::RemoveMod => {
                let pack = string_arg(args, "friendlyName");
                let id = string_arg(args, "mod");
                if pack.is_empty() || id.is_empty() {
                    return Some("Both friendlyName and mod are required.".into());
                }
                if !self.known(&pack) {
                    return Some(unknown_pack(&pack));
                }
                None
            }
            Kind::Uninstall => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return Some("A friendlyName argument is required.".into());
                }
                if !self.known(&name) {
                    return Some(unknown_pack(&name));
                }
                let lower = name.to_lowercase();
                if self
                    .view
                    .running_games()
                    .iter()
                    .any(|g| g.friendly_name.to_lowercase() == lower)
                {
                    return Some(format!(
                        "\"{name}\" is running. Stop it with stop_game before uninstalling it."
                    ));
                }
                None
            }
            Kind::Launch => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return Some("A friendlyName argument is required.".into());
                }
                let Some(p) = self.view.pack(&name) else {
                    return Some(unknown_pack(&name));
                };
                if !p.installed {
                    return Some(format!(
                        "\"{name}\" is not installed. Install it before launching."
                    ));
                }
                if !self.view.status().signed_in {
                    return Some("No account is signed in. Sign in through the launcher before launching a modpack.".into());
                }
                let account = string_arg(args, "account");
                self.actions
                    .why_launch_blocked(&name, empty_to_none(&account))
            }
            Kind::Stop => {
                let named = string_arg(args, "friendlyName");
                stop_problem(&self.view.running_games(), &named)
            }
        }
    }

    fn detail(&self, args: &Args) -> Option<String> {
        match self.kind {
            Kind::Uninstall => {
                let name = string_arg(args, "friendlyName");
                Some(match self.view.footprint_of(&name) {
                    None => "This permanently deletes the modpack's files.".to_string(),
                    Some(f) => {
                        let mut s = String::from("This permanently deletes ");
                        if f.approximate {
                            s.push_str("at least ");
                        }
                        s.push_str(&describe_size(f.size_bytes));
                        if f.world_count > 0 {
                            let noun = if f.world_count == 1 {
                                "saved world"
                            } else {
                                "saved worlds"
                            };
                            s.push_str(&format!(" including {} {noun}", f.world_count));
                        }
                        s.push_str(". This cannot be undone.");
                        s
                    }
                })
            }
            Kind::Stop => {
                let named = string_arg(args, "friendlyName");
                target_of(&self.view.running_games(), &named)
                    .map(|t| format!("This stops \"{t}\"."))
            }
            _ => None,
        }
    }

    fn run(&self, args: &Args) -> ToolResult {
        let a = &self.actions;
        let s = |k: &str| string_arg(args, k);
        match self.kind {
            Kind::Install => result_of(Some(a.install_from_url(&s("url")))),
            Kind::Create => {
                let loader = s("modLoader");
                let url = s("modLoaderUrl");
                result_of(Some(a.create_pack(
                    &s("name"),
                    empty_to_none(&loader),
                    empty_to_none(&url),
                )))
            }
            Kind::Fork => result_of(Some(a.fork_pack(&s("friendlyName"), &s("newName")))),
            Kind::AddMod => result_of(Some(a.add_mod(
                &s("friendlyName"),
                &s("modName"),
                &s("remoteUrl"),
                &s("localPath"),
            ))),
            Kind::RemoveMod => result_of(Some(a.remove_mod(&s("friendlyName"), &s("mod")))),
            Kind::Uninstall => result_of(Some(a.uninstall(&s("friendlyName")))),
            Kind::Launch => {
                let account = s("account");
                result_of(Some(a.launch(&s("friendlyName"), empty_to_none(&account))))
            }
            Kind::Stop => {
                let running = self.view.running_games();
                let named = s("friendlyName");
                match target_of(&running, &named) {
                    None => ToolResult::error(
                        stop_problem(&running, &named)
                            .unwrap_or_else(|| "No game is currently running.".into()),
                    ),
                    Some(t) => result_of(Some(a.stop_game(&t))),
                }
            }
        }
    }
}

impl Tool for MutatingTool {
    fn name(&self) -> &str {
        TOOL_NAMES[self.kind as usize]
    }

    fn title(&self) -> &str {
        match self.kind {
            Kind::Install => "Install modpack",
            Kind::Create => "Create modpack",
            Kind::Fork => "Fork modpack",
            Kind::AddMod => "Add mod to modpack",
            Kind::RemoveMod => "Remove mod from modpack",
            Kind::Uninstall => "Uninstall modpack",
            Kind::Launch => "Launch modpack",
            Kind::Stop => "Stop game",
        }
    }

    fn description(&self) -> &str {
        match self.kind {
            Kind::Install => {
                "Installs a modpack from a manifest URL. The URL must be https and is checked against the launcher's install-source rules before the user is asked to approve. Downloads mods and configuration from third-party hosts."
            }
            Kind::Create => {
                "Creates a new, empty modpack with the given name and installs it locally, ready to have mods added. Optionally sets the mod loader and its installer URL."
            }
            Kind::Fork => {
                "Copies an existing modpack under a new name and installs the copy, so it can be edited without touching the original. The copy keeps every mod, config and setting, with its minor version bumped."
            }
            Kind::AddMod => {
                "Adds a mod to a modpack's manifest, to be downloaded on the next sync. The remote URL must be https."
            }
            Kind::RemoveMod => {
                "Removes a mod from a modpack's manifest, identified by its local path, display name, or Modrinth slug. The file is removed on the next sync."
            }
            Kind::Uninstall => {
                "Permanently removes a modpack and deletes its files, including any worlds saved inside it. This cannot be undone."
            }
            Kind::Launch => {
                "Launches a modpack, starting Minecraft. It plays as the modpack's usual account (its own setting, else the default account) unless an account is named. Several games can run at once, one per modpack and one per account. This runs third-party mod code."
            }
            Kind::Stop => {
                "Stops a running game (or cancels one still preparing). Name the modpack when several games are running; list_running_games shows them. Unsaved progress since the last autosave is lost."
            }
        }
    }

    fn input_schema(&self) -> Value {
        match self.kind {
            Kind::Install => schema_of(
                &[("url", "The https URL of the modpack manifest to install.")],
                &["url"],
            ),
            Kind::Create => schema_of(
                &[
                    ("name", "The new modpack's name."),
                    (
                        "modLoader",
                        "Mod loader: forge, neoforge, or fabric. Defaults to forge.",
                    ),
                    ("modLoaderUrl", "URL of the mod loader installer."),
                ],
                &["name"],
            ),
            Kind::Fork => schema_of(
                &[
                    ("friendlyName", "The modpack to copy."),
                    ("newName", "The name for the copy."),
                ],
                &["friendlyName", "newName"],
            ),
            Kind::AddMod => schema_of(
                &[
                    ("friendlyName", "The modpack to edit."),
                    ("modName", "The mod's display name."),
                    ("remoteUrl", "The https URL to download the mod from."),
                    (
                        "localPath",
                        "Where it installs, relative to the pack root, e.g. mods/jei.jar",
                    ),
                ],
                &["friendlyName", "modName", "remoteUrl", "localPath"],
            ),
            Kind::RemoveMod => schema_of(
                &[
                    ("friendlyName", "The modpack to edit."),
                    (
                        "mod",
                        "The mod's local path, display name, or Modrinth slug.",
                    ),
                ],
                &["friendlyName", "mod"],
            ),
            Kind::Uninstall => schema_of(
                &[("friendlyName", "The modpack to uninstall.")],
                &["friendlyName"],
            ),
            Kind::Launch => schema_of(
                &[
                    ("friendlyName", "The modpack to launch."),
                    (
                        "account",
                        "Optional username of a signed-in account to play as.",
                    ),
                ],
                &["friendlyName"],
            ),
            Kind::Stop => schema_of(
                &[(
                    "friendlyName",
                    "The modpack whose game to stop. Optional when only one game is running.",
                )],
                &[],
            ),
        }
    }

    fn risk_class(&self) -> RiskClass {
        match self.kind {
            Kind::Uninstall => RiskClass::Destructive,
            Kind::Launch | Kind::Stop => RiskClass::Execute,
            _ => RiskClass::Mutating,
        }
    }

    fn invoke(&self, _ctx: &CallContext, args: &Args) -> Result<ToolResult, BoxError> {
        Ok(self.run(args))
    }

    fn validate_before_approval(&self, args: &Args) -> Option<String> {
        self.gate(args)
    }

    fn consent_detail(&self, args: &Args) -> Option<String> {
        self.detail(args)
    }
}

#[cfg(test)]
mod tests {
    //! Port of `MutatingToolsTest`.
    use super::*;
    use crate::tools::test_support::*;
    use crate::tools::{LauncherStatus, PackFootprint};

    struct Fx {
        reg: ToolRegistry,
        view: Arc<StubView>,
        actions: Arc<StubActions>,
    }

    fn fx_with(view: StubView, actions: StubActions) -> Fx {
        let view = Arc::new(view);
        let actions = Arc::new(actions);
        let mut reg = ToolRegistry::new();
        register_all(&mut reg, view.clone(), actions.clone()).unwrap();
        Fx { reg, view, actions }
    }

    fn fx() -> Fx {
        let v = StubView::with_packs(vec![pack("Alto", true), pack("Remote Pack", false)]);
        *v.status.lock() = LauncherStatus {
            signed_in: true,
            ..LauncherStatus::default()
        };
        fx_with(v, StubActions::default())
    }

    fn gate(f: &Fx, tool: &str, a: Value) -> Option<String> {
        f.reg.find(tool).unwrap().validate_before_approval(&args(a))
    }

    fn run(f: &Fx, tool: &str, a: Value) -> ToolResult {
        f.reg.find(tool).unwrap().invoke(&ctx(), &args(a)).unwrap()
    }

    fn detail(f: &Fx, tool: &str, a: Value) -> Option<String> {
        f.reg.find(tool).unwrap().consent_detail(&args(a))
    }

    const URL: &str = "https://example.com/pack.mmcjson";

    #[test]
    fn registration_order_names_and_risks() {
        let f = fx();
        let names: Vec<String> = f.reg.all().iter().map(|t| t.name().to_string()).collect();
        assert_eq!(names, tool_names());
        let risk = |n: &str| f.reg.find(n).unwrap().risk_class();
        assert_eq!(risk("uninstall_modpack"), RiskClass::Destructive);
        assert_eq!(risk("launch_modpack"), RiskClass::Execute);
        assert_eq!(risk("stop_game"), RiskClass::Execute);
        for n in [
            "install_modpack",
            "create_modpack",
            "fork_modpack",
            "add_mod_to_modpack",
            "remove_mod_from_modpack",
        ] {
            assert_eq!(risk(n), RiskClass::Mutating);
        }
    }

    #[test]
    fn every_tool_has_localized_title_and_description() {
        for n in TOOL_NAMES {
            for suffix in ["title", "description"] {
                let key = format!("mcp.tool.{n}.{suffix}");
                assert!(mmcl_i18n::english(&key).is_some(), "missing {key}");
            }
        }
    }

    #[test]
    fn install_gates() {
        let f = fx();
        assert_eq!(
            gate(&f, "install_modpack", json!({})).unwrap(),
            "A url argument is required."
        );
        for bad in ["http://example.com/x", "ftp://x", "not a url", "https://"] {
            assert!(
                gate(&f, "install_modpack", json!({"url": bad}))
                    .unwrap()
                    .contains("refused"),
                "{bad}"
            );
        }
        assert!(gate(&f, "install_modpack", json!({"url": URL})).is_none());
        let r = run(&f, "install_modpack", json!({"url": URL}));
        assert_eq!(r.raw_text_blocks()[0], format!("did install {URL}"));
    }

    #[test]
    fn create_gates_and_forwards_optional_args_as_none() {
        let f = fx();
        assert_eq!(
            gate(&f, "create_modpack", json!({})).unwrap(),
            "A name argument is required."
        );
        assert!(
            gate(
                &f,
                "create_modpack",
                json!({"name":"New","modLoaderUrl":"http://x"})
            )
            .unwrap()
            .starts_with("That mod loader URL was refused")
        );
        assert!(gate(&f, "create_modpack", json!({"name":"New"})).is_none());
        run(&f, "create_modpack", json!({"name":"New","modLoader":""}));
        assert_eq!(f.actions.calls.lock()[0], "create New None None");
        run(
            &f,
            "create_modpack",
            json!({"name":"New","modLoader":"fabric","modLoaderUrl":URL}),
        );
        assert_eq!(
            f.actions.calls.lock()[1],
            format!("create New Some(\"fabric\") Some(\"{URL}\")")
        );
    }

    #[test]
    fn name_sanitization_collisions() {
        let f = fx();
        assert_eq!(
            gate(&f, "create_modpack", json!({"name":"Alto"})).unwrap(),
            "A modpack named \"Alto\" already exists. Pick a different name."
        );
        assert!(
            gate(&f, "create_modpack", json!({"name":"a-l-t-o!"}))
                .unwrap()
                .contains("is too close to the existing modpack \"Alto\"")
        );
        assert!(
            gate(&f, "create_modpack", json!({"name":"remotepack"}))
                .unwrap()
                .contains("\"Remote Pack\"")
        );
        assert_eq!(
            gate(&f, "create_modpack", json!({"name":"!!!"})).unwrap(),
            "The name \"!!!\" has no letters or digits. Pick a name with at least one."
        );
        assert_eq!(local_manifest_key_of("My Pack 2!"), "mypack2");
    }

    #[test]
    fn fork_gates() {
        let f = fx();
        assert_eq!(
            gate(&f, "fork_modpack", json!({"friendlyName":"Alto"})).unwrap(),
            "Both friendlyName and newName are required."
        );
        assert_eq!(
            gate(
                &f,
                "fork_modpack",
                json!({"friendlyName":"X","newName":"Y"})
            )
            .unwrap(),
            unknown_pack("X")
        );
        assert!(
            gate(
                &f,
                "fork_modpack",
                json!({"friendlyName":"Alto","newName":"ALTO"})
            )
            .is_some()
        );
        assert!(
            gate(
                &f,
                "fork_modpack",
                json!({"friendlyName":"Alto","newName":"Alto Copy"})
            )
            .is_none()
        );
        run(
            &f,
            "fork_modpack",
            json!({"friendlyName":"Alto","newName":"Alto Copy"}),
        );
        assert_eq!(f.actions.calls.lock()[0], "fork Alto Alto Copy");
    }

    #[test]
    fn add_mod_gates() {
        let f = fx();
        let full = |lp: &str, url: &str| json!({"friendlyName":"Alto","modName":"JEI","remoteUrl":url,"localPath":lp});
        assert_eq!(
            gate(&f, "add_mod_to_modpack", json!({"friendlyName":"Alto"})).unwrap(),
            "friendlyName, modName, remoteUrl and localPath are all required."
        );
        assert_eq!(
            gate(&f, "add_mod_to_modpack", json!({"friendlyName":"Nope","modName":"m","remoteUrl":URL,"localPath":"mods/a.jar"})).unwrap(),
            unknown_pack("Nope")
        );
        assert!(
            gate(
                &f,
                "add_mod_to_modpack",
                full("mods/a.jar", "http://x/a.jar")
            )
            .unwrap()
            .starts_with("That mod URL was refused")
        );
        assert_eq!(
            gate(&f, "add_mod_to_modpack", full("../a.jar", URL)).unwrap(),
            "localPath must stay inside the modpack folder."
        );
        assert!(gate(&f, "add_mod_to_modpack", full("mods/jei.jar", URL)).is_none());
    }

    #[test]
    fn remove_mod_gates() {
        let f = fx();
        assert_eq!(
            gate(
                &f,
                "remove_mod_from_modpack",
                json!({"friendlyName":"Alto"})
            )
            .unwrap(),
            "Both friendlyName and mod are required."
        );
        assert!(
            gate(
                &f,
                "remove_mod_from_modpack",
                json!({"friendlyName":"Alto","mod":"jei"})
            )
            .is_none()
        );
        run(
            &f,
            "remove_mod_from_modpack",
            json!({"friendlyName":"Alto","mod":"jei"}),
        );
        assert_eq!(f.actions.calls.lock()[0], "remove Alto jei");
    }

    #[test]
    fn uninstall_refused_while_running_but_other_games_are_fine() {
        let f = fx();
        f.view.running.lock().push(running("ALTO"));
        assert_eq!(
            gate(&f, "uninstall_modpack", json!({"friendlyName":"Alto"})).unwrap(),
            "\"Alto\" is running. Stop it with stop_game before uninstalling it."
        );
        f.view.running.lock().clear();
        f.view.running.lock().push(running("Other"));
        assert!(gate(&f, "uninstall_modpack", json!({"friendlyName":"Alto"})).is_none());
        assert_eq!(
            gate(&f, "uninstall_modpack", json!({})).unwrap(),
            "A friendlyName argument is required."
        );
        assert_eq!(
            gate(&f, "uninstall_modpack", json!({"friendlyName":"X"})).unwrap(),
            unknown_pack("X")
        );
    }

    #[test]
    fn uninstall_footprint_wording() {
        let f = fx();
        let a = json!({"friendlyName":"Alto"});
        assert_eq!(
            detail(&f, "uninstall_modpack", a.clone()).unwrap(),
            "This permanently deletes the modpack's files."
        );
        *f.view.footprint.lock() = Some(PackFootprint {
            size_bytes: 4_509_715_660,
            world_count: 2,
            approximate: false,
        });
        assert_eq!(
            detail(&f, "uninstall_modpack", a.clone()).unwrap(),
            "This permanently deletes 4.2 GB including 2 saved worlds. This cannot be undone."
        );
        *f.view.footprint.lock() = Some(PackFootprint {
            size_bytes: 512,
            world_count: 1,
            approximate: true,
        });
        assert_eq!(
            detail(&f, "uninstall_modpack", a.clone()).unwrap(),
            "This permanently deletes at least 512 bytes including 1 saved world. This cannot be undone."
        );
        *f.view.footprint.lock() = Some(PackFootprint {
            size_bytes: 1024,
            world_count: 0,
            approximate: false,
        });
        assert_eq!(
            detail(&f, "uninstall_modpack", a).unwrap(),
            "This permanently deletes 1.0 KB. This cannot be undone."
        );
    }

    #[test]
    fn describe_size_vectors() {
        assert_eq!(describe_size(512), "512 bytes");
        assert_eq!(describe_size(0), "0 bytes");
        assert_eq!(describe_size(1024), "1.0 KB");
        assert_eq!(describe_size(1 << 20), "1.0 MB");
        assert_eq!(describe_size(4_509_715_660), "4.2 GB");
        assert_eq!(describe_size(512 << 20), "512 MB");
        assert_eq!(describe_size(1280), "1.3 KB", "half up like Java");
        assert_eq!(describe_size(10239), "10.0 KB", "Java quirk kept");
        assert_eq!(describe_size(3 << 40), "3.0 TB");
        assert_eq!(describe_size(5000u64 << 40), "5000 TB");
    }

    #[test]
    fn reject_unsafe_local_path_vectors() {
        assert_eq!(
            reject_unsafe_local_path("  ").unwrap(),
            "A localPath argument is required."
        );
        for abs in ["/etc/passwd", "\\x", "C:\\Windows", "c:/x"] {
            assert_eq!(
                reject_unsafe_local_path(abs).unwrap(),
                "localPath must be relative to the modpack folder, not an absolute path.",
                "{abs}"
            );
        }
        for esc in ["..", "mods/../../x", "mods\\..\\x"] {
            assert_eq!(
                reject_unsafe_local_path(esc).unwrap(),
                "localPath must stay inside the modpack folder."
            );
        }
        for ctl in ["mods/a\0.jar", "mods/\u{7f}", "mods/\u{85}x", "a\nb"] {
            assert_eq!(
                reject_unsafe_local_path(ctl).unwrap(),
                "localPath contains an illegal character."
            );
        }
        for ok in ["mods/jei.jar", "config/.hidden", "mods/..jar"] {
            assert!(reject_unsafe_local_path(ok).is_none(), "{ok}");
        }
    }

    #[test]
    fn launch_gates() {
        let f = fx();
        assert_eq!(
            gate(&f, "launch_modpack", json!({})).unwrap(),
            "A friendlyName argument is required."
        );
        assert_eq!(
            gate(&f, "launch_modpack", json!({"friendlyName":"X"})).unwrap(),
            unknown_pack("X")
        );
        assert_eq!(
            gate(&f, "launch_modpack", json!({"friendlyName":"Remote Pack"})).unwrap(),
            "\"Remote Pack\" is not installed. Install it before launching."
        );
        assert!(gate(&f, "launch_modpack", json!({"friendlyName":"Alto"})).is_none());
        *f.actions.blocked.lock() = Some("blocked!".into());
        assert_eq!(
            gate(&f, "launch_modpack", json!({"friendlyName":"Alto"})).unwrap(),
            "blocked!"
        );
        *f.view.status.lock() = LauncherStatus::default();
        assert_eq!(
            gate(&f, "launch_modpack", json!({"friendlyName":"Alto"})).unwrap(),
            "No account is signed in. Sign in through the launcher before launching a modpack."
        );
    }

    #[test]
    fn launch_passes_the_named_account() {
        let f = fx();
        run(
            &f,
            "launch_modpack",
            json!({"friendlyName":"Alto","account":"Alex"}),
        );
        run(
            &f,
            "launch_modpack",
            json!({"friendlyName":"Alto","account":"  "}),
        );
        let calls = f.actions.calls.lock();
        assert_eq!(calls[0], "launch Alto Some(\"Alex\")");
        assert_eq!(calls[1], "launch Alto None");
    }

    #[test]
    fn stop_targeting() {
        let none: Vec<RunningGame> = vec![];
        assert_eq!(
            stop_problem(&none, "").unwrap(),
            "No game is currently running."
        );
        let one = vec![running("Alto")];
        assert_eq!(target_of(&one, ""), Some("Alto".into()));
        assert_eq!(target_of(&one, "alto"), Some("Alto".into()));
        assert!(stop_problem(&one, "").is_none());
        assert_eq!(
            stop_problem(&one, "Beta").unwrap(),
            "\"Beta\" is not running. Running now: \"Alto\"."
        );
        let two = vec![running("Alto"), running("Beta")];
        assert_eq!(target_of(&two, ""), None);
        assert_eq!(
            stop_problem(&two, "").unwrap(),
            "Several games are running (\"Alto\", \"Beta\"). Name the one to stop with friendlyName."
        );
        assert_eq!(target_of(&two, "BETA"), Some("Beta".into()));
    }

    #[test]
    fn stop_tool_detail_and_run() {
        let f = fx();
        assert_eq!(
            gate(&f, "stop_game", json!({})).unwrap(),
            "No game is currently running."
        );
        f.view.running.lock().push(running("Alto"));
        assert!(gate(&f, "stop_game", json!({})).is_none());
        assert_eq!(
            detail(&f, "stop_game", json!({})).unwrap(),
            "This stops \"Alto\"."
        );
        run(&f, "stop_game", json!({"friendlyName":"alto"}));
        assert_eq!(f.actions.calls.lock()[0], "stop Alto");
        f.view.running.lock().clear();
        let r = run(&f, "stop_game", json!({}));
        assert!(r.is_error());
        assert_eq!(
            f.reg
                .find("stop_game")
                .unwrap()
                .input_schema()
                .get("required"),
            None
        );
    }

    #[test]
    fn outcome_maps_to_result() {
        let v = StubView::with_packs(vec![pack("Alto", true)]);
        let f = fx_with(
            v,
            StubActions {
                fail: true,
                ..StubActions::default()
            },
        );
        let r = run(&f, "uninstall_modpack", json!({"friendlyName":"Alto"}));
        assert!(r.is_error());
        assert_eq!(r.raw_text_blocks()[0], "failed uninstall Alto");
        assert!(result_of(None).is_error());
        assert_eq!(
            result_of(Some(Outcome::ok("yes"))).raw_text_blocks()[0],
            "yes"
        );
    }
}
