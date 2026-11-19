// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A pack's optional mods and the player's choices (Java `OptionalMods`): the one place the
//! detail dialog and the MCP tools read and switch them, so both get the same answer. The rules
//! are in [`mmcl_manifest::optional`]; the choices are stored in `optionalModsByPack`.

use mmcl_i18n::tf;
use mmcl_manifest::PackManifest;
use mmcl_manifest::optional::{self, Choices, OptionalMod, ToggleResult};
use mmcl_modpack::GameModPack;

use super::Engine;
use crate::error::EngineResult;
use crate::event::EngineEvent;
use crate::model::{OptionalModsView, PackId};

impl Engine {
    /// The optional mods of an installed pack, the player's stored choices and which mods are
    /// on. Reads the manifest from the cache when the pack is only a stub (an install-index
    /// entry whose manifest was never loaded), so call it off the UI thread. `None` when the
    /// pack is not installed.
    pub fn optional_mods(&self, id: &PackId) -> Option<OptionalModsView> {
        let pack = self.installed_pack(id)?;
        let mods = self.optional_mods_of(id, &pack);
        let choices = self.optional_choices_of(&pack);
        let enabled = optional::resolve_enabled(&mods, &choices);
        Some(OptionalModsView {
            mods,
            choices,
            enabled,
        })
    }

    /// Turns one optional mod on or off for the player, with its `requires` and
    /// `conflictsWith` applied, and stores the result in the player's config (never the
    /// manifest, so this works for a pack installed from a remote manifest too). Takes effect
    /// on the next launch or sync. A refused toggle stores nothing.
    pub fn set_optional_mod(
        &self,
        id: &PackId,
        local: &str,
        enable: bool,
    ) -> EngineResult<ToggleResult> {
        let pack = self.require_installed(id)?;
        let mods = self.optional_mods_of(id, &pack);
        let result = optional::toggle(&mods, &self.optional_choices_of(&pack), local, enable);
        if result.applied
            && let Some(key) = pack.settings_key()
        {
            self.inner.config.set_optional_mod_choices(
                &key,
                &optional::to_stored_choices(&mods, &result.choices),
            );
            self.emit(EngineEvent::PackChanged(id.clone()));
        }
        Ok(result)
    }

    /// The player's stored choices for `pack` (empty without a settings key).
    fn optional_choices_of(&self, pack: &GameModPack) -> Choices {
        pack.settings_key()
            .map(|k| self.inner.config.optional_mod_choices(&k))
            .unwrap_or_default()
    }

    /// `pack`'s optional mods in manifest order, reading the cached manifest for a stub.
    fn optional_mods_of(&self, id: &PackId, pack: &GameModPack) -> Vec<OptionalMod> {
        if !pack.is_stub() {
            return pack.manifest().optional_mods().to_vec();
        }
        let Some(text) = self.pack_manifest_text(id) else {
            return Vec::new();
        };
        match PackManifest::parse(&text) {
            Ok(m) => m.optional_mods().to_vec(),
            Err(e) => {
                mmcl_log::warn_silent(&tf!(
                    "log.optionalMods.manifestReadFailed",
                    pack.name(),
                    e.key()
                ));
                Vec::new()
            }
        }
    }
}
