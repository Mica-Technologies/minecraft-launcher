// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! The metadata form as plain data, and the form <-> document mapping (populate / collect).

use mmcl_manifest::document::{FileList, KEY_SCAN_EXCLUSIONS, ModPackDocument};

use crate::row::EditorRow;

/// The pack's mod loader as the editor's picker shows it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Loader {
    #[default]
    Forge,
    NeoForge,
    Fabric,
}

impl Loader {
    /// Every loader, in picker order.
    pub const ALL: [Loader; 3] = [Loader::Forge, Loader::NeoForge, Loader::Fabric];

    /// The brand name (not localized).
    pub fn label(self) -> &'static str {
        match self {
            Loader::Forge => "Forge",
            Loader::NeoForge => "NeoForge",
            Loader::Fabric => "Fabric",
        }
    }

    /// The `packModLoader` value.
    pub fn config_type(self) -> &'static str {
        match self {
            Loader::Forge => "forge",
            Loader::NeoForge => "neoforge",
            Loader::Fabric => "fabric",
        }
    }

    /// From a `packModLoader` value: trimmed, lower-cased; anything unknown or blank is Forge.
    pub fn from_config(value: &str) -> Loader {
        match value.trim().to_lowercase().as_str() {
            "neoforge" => Loader::NeoForge,
            "fabric" => Loader::Fabric,
            _ => Loader::Forge,
        }
    }

    /// The hint shown under the picker.
    pub fn hint_key(self) -> &'static str {
        match self {
            Loader::Forge => "editor.loaderHint.forge",
            Loader::NeoForge => "editor.loaderHint.neoforge",
            Loader::Fabric => "editor.loaderHint.fabric",
        }
    }
}

/// Every form field, as text.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct EditorForm {
    pub name: String,
    pub version: String,
    pub url: String,
    pub min_ram: String,
    pub unstable: bool,
    pub custom_rpc: bool,
    pub loader: Loader,
    pub loader_url: String,
    pub loader_hash: String,
    pub mc_version: String,
    pub logo_urls: String,
    pub logo_sha1: String,
    pub bg_urls: String,
    pub bg_sha1: String,
    pub scan_exclusions: String,
}

fn or_legacy(doc: &ModPackDocument, key: &str, legacy: &str) -> String {
    let v = doc.get_string(key);
    if v.trim().is_empty() {
        doc.get_string(legacy)
    } else {
        v
    }
}

impl EditorForm {
    /// populate: document -> form.
    pub fn populate(doc: &ModPackDocument) -> EditorForm {
        EditorForm {
            name: doc.get_string("packName"),
            version: doc.get_string("packVersion"),
            url: doc.get_string("packURL"),
            min_ram: doc.get_string("packMinRAMGB"),
            unstable: doc.get_bool("packUnstable"),
            custom_rpc: doc.get_bool("packCustomDiscordRpc"),
            loader: Loader::from_config(&doc.get_string("packModLoader")),
            loader_url: or_legacy(doc, "packModLoaderURL", "packForgeURL"),
            loader_hash: or_legacy(doc, "packModLoaderHash", "packForgeHash"),
            mc_version: doc.get_string("packMinecraftVersion"),
            logo_urls: doc.get_string_or_array_lines("packLogoURL"),
            logo_sha1: doc.get_string("packLogoSha1"),
            bg_urls: doc.get_string_or_array_lines("packBackgroundURL"),
            bg_sha1: doc.get_string("packBackgroundSha1"),
            scan_exclusions: doc.get_array_lines(KEY_SCAN_EXCLUSIONS),
        }
    }

    /// The rows of each file list, for populating the tables.
    pub fn rows_of(doc: &ModPackDocument, list: FileList) -> Vec<EditorRow> {
        doc.read_file_list(list)
            .iter()
            .map(EditorRow::from_entry)
            .collect()
    }

    /// collect: form (+ every table, in model order) -> document, in the order that decides
    /// where new keys land.
    pub fn collect<'a>(
        &self,
        doc: &mut ModPackDocument,
        tables: impl IntoIterator<Item = (FileList, &'a [EditorRow])>,
    ) {
        doc.put_string("packName", &self.name);
        doc.put_string("packVersion", &self.version);
        doc.put_string("packURL", &self.url);
        doc.put_string("packMinRAMGB", &self.min_ram);
        doc.put_bool("packUnstable", self.unstable);
        doc.put_bool("packCustomDiscordRpc", self.custom_rpc);

        let loader = self.loader.config_type();
        doc.put_string("packModLoader", loader);
        doc.put_string("packModLoaderURL", &self.loader_url);
        doc.put_string("packModLoaderHash", &self.loader_hash);
        // Legacy mirror so older launcher builds still read Forge packs; blank for the others
        // because the values would mislead.
        let forge = self.loader == Loader::Forge;
        doc.put_string("packForgeURL", if forge { &self.loader_url } else { "" });
        doc.put_string("packForgeHash", if forge { &self.loader_hash } else { "" });

        doc.put_string("packMinecraftVersion", &self.mc_version);
        doc.put_string_or_array("packLogoURL", &self.logo_urls);
        doc.put_string("packLogoSha1", &self.logo_sha1);
        doc.put_string_or_array("packBackgroundURL", &self.bg_urls);
        doc.put_string("packBackgroundSha1", &self.bg_sha1);
        doc.put_array_lines(KEY_SCAN_EXCLUSIONS, &self.scan_exclusions);

        for (list, rows) in tables {
            let entries: Vec<_> = rows.iter().map(EditorRow::to_entry).collect();
            doc.write_file_list(list, &entries);
        }
    }
}

/// The text the Modrinth version filter is pre-filled with: for a Forge installer URL, the text
/// after `/forge/` up to the first `-` (`.../forge/1.20.1-47.3.0/...` -> `1.20.1`).
pub fn game_version_from_loader_url(url: &str) -> Option<String> {
    let at = url.find("/forge/")?;
    let rest = &url[at + "/forge/".len()..];
    let end = rest.find('-').unwrap_or(rest.len());
    let v = rest[..end].trim();
    (!v.is_empty() && !v.contains('/')).then(|| v.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect_all(form: &EditorForm, doc: &mut ModPackDocument) {
        let rows: Vec<(FileList, Vec<EditorRow>)> = FileList::ALL
            .iter()
            .map(|l| (*l, EditorForm::rows_of(doc, *l)))
            .collect();
        form.collect(doc, rows.iter().map(|(l, r)| (*l, r.as_slice())));
    }

    #[test]
    fn loader_labels_and_config_types() {
        assert_eq!(Loader::from_config(" NeoForge "), Loader::NeoForge);
        assert_eq!(Loader::from_config("FABRIC"), Loader::Fabric);
        assert_eq!(Loader::from_config(""), Loader::Forge);
        assert_eq!(Loader::from_config("quilt"), Loader::Forge);
        for l in Loader::ALL {
            assert_eq!(Loader::from_config(l.config_type()), l);
            assert!(mmcl_i18n::english(l.hint_key()).is_some());
        }
    }

    #[test]
    fn a_blank_document_round_trips_unchanged() {
        let mut doc = ModPackDocument::blank();
        let before = doc.to_pretty_json();
        let form = EditorForm::populate(&doc);
        collect_all(&form, &mut doc);
        assert_eq!(doc.to_pretty_json(), before);
    }

    #[test]
    fn loaded_documents_are_stable_after_one_collect() {
        let mut doc = ModPackDocument::from_json(
            r#"{"packName":"P","packForgeURL":"https://f/x.jar","packForgeHash":"aa",
               "packLogoURL":["a","b"],"custom":{"keep":true},
               "packMods":[{"name":"M","remote":"r","local":"l","sha256":"h","md5":"m"}]}"#,
        )
        .unwrap();
        let form = EditorForm::populate(&doc);
        assert_eq!(form.loader_url, "https://f/x.jar");
        assert_eq!(form.loader_hash, "aa");
        assert_eq!(form.logo_urls, "a\nb");
        collect_all(&form, &mut doc);
        let once = doc.to_pretty_json();
        assert!(once.contains("\"custom\""));
        let again = EditorForm::populate(&doc);
        collect_all(&again, &mut doc);
        assert_eq!(doc.to_pretty_json(), once);
        assert_eq!(doc.get_string("packModLoaderURL"), "https://f/x.jar");
    }

    #[test]
    fn legacy_forge_keys_mirror_only_for_forge() {
        let mut doc = ModPackDocument::blank();
        let mut form = EditorForm::populate(&doc);
        form.loader = Loader::Fabric;
        form.loader_url = "https://meta/x".into();
        collect_all(&form, &mut doc);
        assert_eq!(doc.get_string("packModLoader"), "fabric");
        assert_eq!(doc.get_string("packForgeURL"), "");
        form.loader = Loader::Forge;
        collect_all(&form, &mut doc);
        assert_eq!(doc.get_string("packForgeURL"), "https://meta/x");
    }

    #[test]
    fn version_prefill_from_forge_urls_only() {
        assert_eq!(
            game_version_from_loader_url(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar"
            )
            .as_deref(),
            Some("1.20.1")
        );
        assert_eq!(
            game_version_from_loader_url("https://maven.neoforged.net/releases/x.jar"),
            None
        );
        assert_eq!(game_version_from_loader_url("x/forge/-1"), None);
    }
}
