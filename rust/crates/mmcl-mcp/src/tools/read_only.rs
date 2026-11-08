// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The seven read-only tools (Java `ReadOnlyTools`), always registered when the server runs.

use std::sync::Arc;

use serde_json::{Map, Value, json};

use super::{
    Args, CallContext, LauncherView, Tool, ToolRegistry, ToolResult, pack_entry, string_arg,
    unknown_pack,
};
use crate::approval::RiskClass;
use crate::{BoxError, McpError};

/// Registration order.
pub const TOOL_NAMES: [&str; 7] = [
    "list_modpacks",
    "get_modpack_info",
    "get_modpack_manifest",
    "get_crash_report",
    "diagnose_launch_failure",
    "get_launcher_status",
    "list_running_games",
];

const NAME_REQUIRED: &str = "A friendlyName argument is required.";

/// `{"type":"object","properties":{}}`.
pub fn no_args_schema() -> Value {
    json!({"type":"object","properties":{}})
}

/// The single required `friendlyName` string.
pub fn pack_name_schema() -> Value {
    json!({
        "type":"object",
        "properties":{"friendlyName":{"type":"string","description":"The modpack's friendly (display) name."}},
        "required":["friendlyName"]
    })
}

/// Registers the seven tools in [`TOOL_NAMES`] order.
pub fn register_all(reg: &mut ToolRegistry, view: Arc<dyn LauncherView>) -> Result<(), McpError> {
    for kind in [
        Kind::ListModpacks,
        Kind::GetModpackInfo,
        Kind::GetModpackManifest,
        Kind::GetCrashReport,
        Kind::DiagnoseLaunchFailure,
        Kind::GetLauncherStatus,
        Kind::ListRunningGames,
    ] {
        reg.register(Arc::new(ReadOnlyTool {
            kind,
            view: view.clone(),
        }))?;
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Kind {
    ListModpacks,
    GetModpackInfo,
    GetModpackManifest,
    GetCrashReport,
    DiagnoseLaunchFailure,
    GetLauncherStatus,
    ListRunningGames,
}

struct ReadOnlyTool {
    kind: Kind,
    view: Arc<dyn LauncherView>,
}

impl ReadOnlyTool {
    fn invoke_inner(&self, args: &Args) -> ToolResult {
        let v = &self.view;
        match self.kind {
            Kind::ListModpacks => {
                let packs: Vec<Value> = v.packs().iter().map(pack_entry).collect();
                let count = packs.len();
                let mut m = Map::new();
                m.insert("modpacks".into(), Value::Array(packs));
                m.insert("count".into(), Value::from(count));
                ToolResult::json(&Value::Object(m))
            }
            Kind::GetModpackInfo => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return ToolResult::error(NAME_REQUIRED);
                }
                match v.pack(&name) {
                    Some(p) => ToolResult::json(&pack_entry(&p)),
                    None => ToolResult::error(unknown_pack(&name)),
                }
            }
            Kind::GetModpackManifest => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return ToolResult::error(NAME_REQUIRED);
                }
                match v.manifest_of(&name) {
                    Some(m) => ToolResult::text(m),
                    None => ToolResult::error(format!(
                        "No manifest is available for modpack \"{name}\". It may not exist, or its manifest may be unreachable."
                    )),
                }
            }
            Kind::GetCrashReport => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return ToolResult::error(NAME_REQUIRED);
                }
                match v
                    .latest_crash_of(&name)
                    .filter(|c| !c.report_text.trim().is_empty())
                {
                    Some(c) => ToolResult::text(c.report_text),
                    None => ToolResult::error(format!(
                        "No crash report is available for modpack \"{name}\"."
                    )),
                }
            }
            Kind::DiagnoseLaunchFailure => {
                let name = string_arg(args, "friendlyName");
                if name.is_empty() {
                    return ToolResult::error(NAME_REQUIRED);
                }
                let Some(p) = v.pack(&name) else {
                    return ToolResult::error(unknown_pack(&name));
                };
                let mut m = Map::new();
                m.insert(
                    "friendlyName".into(),
                    Value::String(p.friendly_name.clone()),
                );
                m.insert("version".into(), Value::String(p.version.clone()));
                m.insert("modLoader".into(), Value::String(p.mod_loader.clone()));
                m.insert("unstable".into(), Value::Bool(p.unstable));
                match v
                    .latest_crash_of(&name)
                    .filter(|c| !c.report_text.trim().is_empty())
                {
                    None => {
                        // "Not crashed" is a success: an error would make the model retry.
                        m.insert("crashReportAvailable".into(), Value::Bool(false));
                        m.insert(
                            "note".into(),
                            Value::String("No crash report is on record for this modpack.".into()),
                        );
                    }
                    Some(c) => {
                        m.insert("crashReportAvailable".into(), Value::Bool(true));
                        m.insert("category".into(), Value::String(c.category));
                        m.insert("title".into(), Value::String(c.title));
                        m.insert("summary".into(), Value::String(c.summary));
                        m.insert(
                            "suggestions".into(),
                            Value::Array(c.suggestions.into_iter().map(Value::String).collect()),
                        );
                        m.insert("crashReport".into(), Value::String(c.report_text));
                    }
                }
                ToolResult::json(&Value::Object(m))
            }
            Kind::GetLauncherStatus => {
                let s = v.status();
                let mut m = Map::new();
                m.insert("launcherVersion".into(), Value::String(s.launcher_version));
                m.insert("signedIn".into(), Value::Bool(s.signed_in));
                m.insert("username".into(), Value::String(s.username));
                m.insert(
                    "installedModpackCount".into(),
                    Value::from(s.installed_pack_count),
                );
                ToolResult::json(&Value::Object(m))
            }
            Kind::ListRunningGames => {
                let games: Vec<Value> = v
                    .running_games()
                    .into_iter()
                    .map(|g| {
                        let mut m = Map::new();
                        m.insert("friendlyName".into(), Value::String(g.friendly_name));
                        m.insert("account".into(), Value::String(g.account));
                        m.insert("phase".into(), Value::String(g.phase));
                        m.insert("uptimeSeconds".into(), Value::from(g.uptime_seconds));
                        Value::Object(m)
                    })
                    .collect();
                ToolResult::json(&json!({ "games": games }))
            }
        }
    }
}

impl Tool for ReadOnlyTool {
    fn name(&self) -> &str {
        match self.kind {
            Kind::ListModpacks => "list_modpacks",
            Kind::GetModpackInfo => "get_modpack_info",
            Kind::GetModpackManifest => "get_modpack_manifest",
            Kind::GetCrashReport => "get_crash_report",
            Kind::DiagnoseLaunchFailure => "diagnose_launch_failure",
            Kind::GetLauncherStatus => "get_launcher_status",
            Kind::ListRunningGames => "list_running_games",
        }
    }

    fn title(&self) -> &str {
        match self.kind {
            Kind::ListModpacks => "List modpacks",
            Kind::GetModpackInfo => "Get modpack info",
            Kind::GetModpackManifest => "Get modpack manifest",
            Kind::GetCrashReport => "Get crash report",
            Kind::DiagnoseLaunchFailure => "Diagnose launch failure",
            Kind::GetLauncherStatus => "Get launcher status",
            Kind::ListRunningGames => "List running games",
        }
    }

    fn description(&self) -> &str {
        match self.kind {
            Kind::ListModpacks => {
                "Lists every modpack the launcher knows about, both installed and available to install. Returns each pack's friendly name, installed version, latest published version, whether an update is available, mod loader, install state, and whether its author flagged it unstable. Use the friendly name to address a pack in other tools."
            }
            Kind::GetModpackInfo => {
                "Reports the installed version, the latest published version, whether an update is available, the mod loader, install state and stability flag of one modpack, addressed by its friendly name."
            }
            Kind::GetModpackManifest => {
                "Returns the raw modpack manifest JSON for one pack: its mod, config, resource pack and shader lists, mod loader URLs, and metadata."
            }
            Kind::GetCrashReport => {
                "Returns the most recent crash report for one modpack, as raw text. Credentials are stripped from the output."
            }
            Kind::DiagnoseLaunchFailure => {
                "Explains why a modpack failed to launch or crashed. Returns the launcher's own diagnosis (category, summary, suggested fixes) together with the raw crash report and the pack's version and mod loader, so the report can be read in context. Credentials are stripped from the output. Start here for any 'why did my game crash' question."
            }
            Kind::GetLauncherStatus => {
                "Reports the launcher version, whether an account is signed in and its username, and how many modpacks are installed."
            }
            Kind::ListRunningGames => {
                "Lists the games that are launching or running: the modpack, the account playing it (username), whether it is still preparing or running, and how long it has run in seconds."
            }
        }
    }

    fn input_schema(&self) -> Value {
        match self.kind {
            Kind::ListModpacks | Kind::GetLauncherStatus | Kind::ListRunningGames => {
                no_args_schema()
            }
            _ => pack_name_schema(),
        }
    }

    fn risk_class(&self) -> RiskClass {
        RiskClass::ReadOnly
    }

    fn invoke(&self, _ctx: &CallContext, args: &Args) -> Result<ToolResult, BoxError> {
        Ok(self.invoke_inner(args))
    }
}

#[cfg(test)]
mod tests {
    //! Port of `ReadOnlyToolsTest`.
    use super::*;
    use crate::tools::test_support::*;
    use crate::tools::{CrashInfo, LauncherStatus};

    fn registry(view: StubView) -> (ToolRegistry, Arc<StubView>) {
        let view = Arc::new(view);
        let mut r = ToolRegistry::new();
        register_all(&mut r, view.clone()).unwrap();
        (r, view)
    }

    fn call(r: &ToolRegistry, name: &str, a: Value) -> ToolResult {
        r.find(name).unwrap().invoke(&ctx(), &args(a)).unwrap()
    }

    fn parsed(r: &ToolResult) -> Value {
        serde_json::from_str(&r.raw_text_blocks()[0]).unwrap()
    }

    #[test]
    fn registers_seven_read_only_tools_in_order() {
        let (r, _) = registry(StubView::default());
        let names: Vec<String> = r.all().iter().map(|t| t.name().to_string()).collect();
        assert_eq!(names, TOOL_NAMES);
        for t in r.all() {
            assert_eq!(t.risk_class(), RiskClass::ReadOnly);
        }
    }

    #[test]
    fn every_tool_has_localized_title_and_description() {
        let (r, _) = registry(StubView::default());
        for t in r.all() {
            for suffix in ["title", "description"] {
                let key = format!("mcp.tool.{}.{suffix}", t.name());
                assert!(mmcl_i18n::english(&key).is_some(), "missing {key}");
            }
        }
    }

    #[test]
    fn list_modpacks_shape_and_empty_library() {
        let (r, _) = registry(StubView::default());
        assert_eq!(
            parsed(&call(&r, "list_modpacks", json!({}))),
            json!({"modpacks":[],"count":0})
        );
        let (r, _) = registry(StubView::with_packs(vec![
            pack("Alto", true),
            pack("Beta", false),
        ]));
        let out = parsed(&call(&r, "list_modpacks", json!({})));
        assert_eq!(out["count"], json!(2));
        let keys: Vec<&String> = out["modpacks"][0].as_object().unwrap().keys().collect();
        assert_eq!(
            keys,
            [
                "friendlyName",
                "version",
                "latestVersion",
                "updateAvailable",
                "modLoader",
                "installed",
                "unstable"
            ]
        );
    }

    #[test]
    fn info_requires_a_name_and_a_known_pack() {
        let (r, _) = registry(StubView::with_packs(vec![pack("Alto", true)]));
        let e = call(&r, "get_modpack_info", json!({}));
        assert!(e.is_error());
        assert_eq!(e.raw_text_blocks()[0], NAME_REQUIRED);
        let e = call(&r, "get_modpack_info", json!({"friendlyName":"Nope"}));
        assert_eq!(e.raw_text_blocks()[0], unknown_pack("Nope"));
        let ok = call(&r, "get_modpack_info", json!({"friendlyName":"  Alto "}));
        assert_eq!(parsed(&ok)["friendlyName"], json!("Alto"));
    }

    #[test]
    fn a_non_string_name_reads_as_missing_or_its_text() {
        let (r, _) = registry(StubView::default());
        let e = call(&r, "get_modpack_info", json!({"friendlyName":{"x":1}}));
        assert_eq!(e.raw_text_blocks()[0], NAME_REQUIRED);
    }

    #[test]
    fn manifest_is_raw_text() {
        let v = StubView::default();
        v.manifests
            .lock()
            .push(("Alto".into(), "{ \"packName\" : \"Alto\" }".into()));
        let (r, _) = registry(v);
        let ok = call(&r, "get_modpack_manifest", json!({"friendlyName":"Alto"}));
        assert_eq!(ok.raw_text_blocks()[0], "{ \"packName\" : \"Alto\" }");
        let e = call(&r, "get_modpack_manifest", json!({"friendlyName":"X"}));
        assert!(e.raw_text_blocks()[0].starts_with("No manifest is available for modpack \"X\"."));
    }

    #[test]
    fn crash_report_is_raw_and_blank_counts_as_none() {
        let v = StubView::default();
        v.crashes.lock().push((
            "Alto".into(),
            CrashInfo {
                report_text: "boom".into(),
                ..CrashInfo::default()
            },
        ));
        v.crashes.lock().push((
            "Blank".into(),
            CrashInfo {
                report_text: "  ".into(),
                ..CrashInfo::default()
            },
        ));
        let (r, _) = registry(v);
        assert_eq!(
            call(&r, "get_crash_report", json!({"friendlyName":"Alto"})).raw_text_blocks()[0],
            "boom"
        );
        let e = call(&r, "get_crash_report", json!({"friendlyName":"Blank"}));
        assert_eq!(
            e.raw_text_blocks()[0],
            "No crash report is available for modpack \"Blank\"."
        );
    }

    #[test]
    fn diagnose_reports_no_crash_as_success() {
        let (r, _) = registry(StubView::with_packs(vec![pack("Alto", true)]));
        let out = call(
            &r,
            "diagnose_launch_failure",
            json!({"friendlyName":"Alto"}),
        );
        assert!(!out.is_error());
        assert_eq!(
            parsed(&out),
            json!({"friendlyName":"Alto","version":"1.0.0","modLoader":"forge","unstable":false,
                   "crashReportAvailable":false,"note":"No crash report is on record for this modpack."})
        );
        let e = call(&r, "diagnose_launch_failure", json!({"friendlyName":"X"}));
        assert_eq!(e.raw_text_blocks()[0], unknown_pack("X"));
    }

    #[test]
    fn diagnose_with_a_crash() {
        let v = StubView::with_packs(vec![pack("Alto", true)]);
        v.crashes.lock().push((
            "Alto".into(),
            CrashInfo {
                report_text: "java.lang.OutOfMemoryError".into(),
                title: "Out of memory".into(),
                summary: "More RAM".into(),
                category: "OutOfMemory".into(),
                suggestions: vec!["Raise RAM".into()],
            },
        ));
        let (r, _) = registry(v);
        let out = parsed(&call(
            &r,
            "diagnose_launch_failure",
            json!({"friendlyName":"Alto"}),
        ));
        assert_eq!(out["crashReportAvailable"], json!(true));
        assert_eq!(out["category"], json!("OutOfMemory"));
        assert_eq!(out["suggestions"], json!(["Raise RAM"]));
        assert_eq!(out["crashReport"], json!("java.lang.OutOfMemoryError"));
        let keys: Vec<&String> = out.as_object().unwrap().keys().collect();
        assert_eq!(
            keys,
            [
                "friendlyName",
                "version",
                "modLoader",
                "unstable",
                "crashReportAvailable",
                "category",
                "title",
                "summary",
                "suggestions",
                "crashReport"
            ]
        );
    }

    #[test]
    fn launcher_status_shape() {
        let v = StubView::default();
        *v.status.lock() = LauncherStatus {
            launcher_version: "2026.11".into(),
            signed_in: true,
            username: "Steve".into(),
            installed_pack_count: 3,
        };
        let (r, _) = registry(v);
        assert_eq!(
            parsed(&call(&r, "get_launcher_status", json!({}))),
            json!({"launcherVersion":"2026.11","signedIn":true,"username":"Steve","installedModpackCount":3})
        );
    }

    #[test]
    fn running_games_shape() {
        let v = StubView::default();
        v.running.lock().push(running("Alto"));
        let (r, _) = registry(v);
        assert_eq!(
            parsed(&call(&r, "list_running_games", json!({}))),
            json!({"games":[{"friendlyName":"Alto","account":"Steve","phase":"running","uptimeSeconds":12}]})
        );
    }

    #[test]
    fn schemas() {
        let (r, _) = registry(StubView::default());
        assert_eq!(
            r.find("list_modpacks").unwrap().input_schema(),
            no_args_schema()
        );
        assert_eq!(
            r.find("get_crash_report").unwrap().input_schema()["required"],
            json!(["friendlyName"])
        );
    }
}
