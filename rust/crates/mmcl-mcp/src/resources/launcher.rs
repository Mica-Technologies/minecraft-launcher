// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The three launcher resources (Java `LauncherResources`).

use std::sync::Arc;

use indexmap::IndexMap;
use serde_json::{Value, json};

use super::{Resource, ResourceRegistry, uri};
use crate::tools::{LauncherView, pack_entry};
use crate::{BoxError, McpError, seam_error};

/// The pack index.
pub const PACKS_URI: &str = "mica://packs";
/// One pack's manifest.
pub const MANIFEST_TEMPLATE: &str = "mica://modpack/{friendlyName}/manifest";
/// One pack's newest crash report.
pub const CRASH_TEMPLATE: &str = "mica://modpack/{friendlyName}/crash-report";

/// Registers the three resources.
pub fn register_all(
    reg: &mut ResourceRegistry,
    view: Arc<dyn LauncherView>,
) -> Result<(), McpError> {
    reg.register(Arc::new(Packs { view: view.clone() }))?;
    reg.register(Arc::new(Manifest { view: view.clone() }))?;
    reg.register(Arc::new(Crash { view }))?;
    Ok(())
}

fn friendly_name(params: &IndexMap<String, String>) -> Result<&str, BoxError> {
    params
        .get("friendlyName")
        .map(String::as_str)
        .ok_or_else(|| seam_error("missing friendlyName"))
}

struct Packs {
    view: Arc<dyn LauncherView>,
}

impl Resource for Packs {
    fn uri_template(&self) -> &str {
        PACKS_URI
    }
    fn name(&self) -> &str {
        "Modpack index"
    }
    fn description(&self) -> &str {
        "Every modpack the launcher knows about, installed and available."
    }
    fn mime_type(&self) -> &str {
        "application/json"
    }
    fn governing_tool_name(&self) -> &str {
        "list_modpacks"
    }
    fn read(&self, _: &IndexMap<String, String>) -> Result<String, BoxError> {
        let packs: Vec<Value> = self.view.packs().iter().map(pack_entry).collect();
        Ok(serde_json::to_string_pretty(&json!({ "modpacks": packs }))?)
    }
}

struct Manifest {
    view: Arc<dyn LauncherView>,
}

impl Resource for Manifest {
    fn uri_template(&self) -> &str {
        MANIFEST_TEMPLATE
    }
    fn name(&self) -> &str {
        "Modpack manifest"
    }
    fn description(&self) -> &str {
        "The raw manifest JSON for one modpack."
    }
    fn mime_type(&self) -> &str {
        "application/json"
    }
    fn governing_tool_name(&self) -> &str {
        "get_modpack_manifest"
    }
    fn read(&self, params: &IndexMap<String, String>) -> Result<String, BoxError> {
        let n = friendly_name(params)?;
        self.view
            .manifest_of(n)
            .ok_or_else(|| seam_error(format!("No manifest for modpack: {n}")))
    }
    fn concrete_uris(&self) -> Vec<String> {
        self.view
            .packs()
            .iter()
            .filter(|p| uri::is_safe_segment(&p.friendly_name))
            .filter_map(|p| uri::build(MANIFEST_TEMPLATE, &p.friendly_name).ok())
            .collect()
    }
}

struct Crash {
    view: Arc<dyn LauncherView>,
}

impl Resource for Crash {
    fn uri_template(&self) -> &str {
        CRASH_TEMPLATE
    }
    fn name(&self) -> &str {
        "Modpack crash report"
    }
    fn description(&self) -> &str {
        "The most recent crash report for one modpack, with credentials stripped."
    }
    fn mime_type(&self) -> &str {
        "text/plain"
    }
    fn governing_tool_name(&self) -> &str {
        "get_crash_report"
    }
    fn read(&self, params: &IndexMap<String, String>) -> Result<String, BoxError> {
        let n = friendly_name(params)?;
        self.view
            .latest_crash_of(n)
            .map(|c| c.report_text)
            .filter(|t| !t.trim().is_empty())
            .ok_or_else(|| seam_error(format!("No crash report for modpack: {n}")))
    }
    fn concrete_uris(&self) -> Vec<String> {
        self.view
            .packs()
            .iter()
            .filter(|p| p.installed && uri::is_safe_segment(&p.friendly_name))
            .filter_map(|p| uri::build(CRASH_TEMPLATE, &p.friendly_name).ok())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    //! Port of `LauncherResourcesTest`.
    use super::*;
    use crate::tools::ToolRegistry;
    use crate::tools::test_support::*;
    use crate::tools::{CrashInfo, read_only};

    fn reg(view: StubView) -> ResourceRegistry {
        let mut r = ResourceRegistry::new();
        register_all(&mut r, Arc::new(view)).unwrap();
        r
    }

    fn listed(r: &ResourceRegistry) -> Vec<String> {
        r.list_result()["resources"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["uri"].as_str().unwrap().to_string())
            .collect()
    }

    #[test]
    fn every_resource_is_governed_by_a_registered_read_only_tool() {
        let view = Arc::new(StubView::default());
        let mut tools = ToolRegistry::new();
        read_only::register_all(&mut tools, view.clone()).unwrap();
        let mut r = ResourceRegistry::new();
        register_all(&mut r, view).unwrap();
        for res in r.all() {
            let t = tools
                .find(res.governing_tool_name())
                .expect("governing tool");
            assert_eq!(t.risk_class(), crate::approval::RiskClass::ReadOnly);
        }
    }

    #[test]
    fn an_empty_library_advertises_only_the_index() {
        let r = reg(StubView::default());
        assert_eq!(listed(&r), [PACKS_URI]);
        assert_eq!(
            r.templates_list_result()["resourceTemplates"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn crash_reports_are_enumerated_for_installed_packs_only() {
        let r = reg(StubView::with_packs(vec![
            pack("Alto", true),
            pack("Remote", false),
        ]));
        let l = listed(&r);
        assert!(l.contains(&"mica://modpack/Alto/crash-report".to_string()));
        assert!(!l.contains(&"mica://modpack/Remote/crash-report".to_string()));
        assert!(l.contains(&"mica://modpack/Remote/manifest".to_string()));
    }

    #[test]
    fn names_with_spaces_are_encoded_and_resolve_back() {
        let r = reg(StubView::with_packs(vec![pack("Alto: 26.9.3", true)]));
        let l = listed(&r);
        assert!(l.contains(&"mica://modpack/Alto%3A%2026.9.3/manifest".to_string()));
        let m = r
            .resolve("mica://modpack/Alto%3A%2026.9.3/manifest")
            .unwrap();
        assert_eq!(m.params["friendlyName"], "Alto: 26.9.3");
    }

    #[test]
    fn unsafe_names_are_skipped() {
        let r = reg(StubView::with_packs(vec![
            pack("a/b", true),
            pack("..", true),
        ]));
        assert_eq!(listed(&r), [PACKS_URI]);
    }

    #[test]
    fn every_advertised_uri_resolves() {
        let r = reg(StubView::with_packs(vec![
            pack("Alto", true),
            pack("Ünïcode Pack", true),
            pack("x+y", false),
            pack("100%", true),
        ]));
        for u in listed(&r) {
            assert!(r.resolve(&u).is_some(), "{u}");
        }
    }

    #[test]
    fn reads() {
        let v = StubView::with_packs(vec![pack("Alto", true)]);
        v.manifests.lock().push(("Alto".into(), "{\"m\":1}".into()));
        v.crashes.lock().push((
            "Alto".into(),
            CrashInfo {
                report_text: "boom".into(),
                ..CrashInfo::default()
            },
        ));
        let r = reg(v);
        let read = |u: &str| {
            let m = r.resolve(u).unwrap();
            m.resource.read(&m.params)
        };
        let idx: Value = serde_json::from_str(&read(PACKS_URI).unwrap()).unwrap();
        assert_eq!(idx["modpacks"][0]["friendlyName"], json!("Alto"));
        assert!(idx.get("count").is_none());
        assert_eq!(read("mica://modpack/Alto/manifest").unwrap(), "{\"m\":1}");
        assert_eq!(read("mica://modpack/Alto/crash-report").unwrap(), "boom");
        assert!(read("mica://modpack/Nope/manifest").is_err());
        assert!(read("mica://modpack/Nope/crash-report").is_err());
    }
}
