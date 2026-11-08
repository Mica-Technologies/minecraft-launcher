// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! What drives the effects (Java `RgbIntegration`): the menu effect, the in-game effect, and
//! the session bootstrap / shutdown.
//!
//! Every entry point returns at once when RGB is disabled and never propagates a failure (it is
//! logged silently). The free functions act on the host registered with [`install`] and the
//! global controller; [`RgbIntegration`] is the same logic on an explicit host and controller.

use std::collections::BTreeSet;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::sync::Arc;

use parking_lot::RwLock;

use crate::color::RgbColor;
use crate::controller::RgbController;
use crate::effect::{
    CycleEffect, InGameEffect, PulseEffect, RainbowEffect, RgbEffect, SolidEffect,
};
use crate::palette::PaletteCache;
use crate::registry;
use crate::settings::{EffectStyle, RgbSettings};
use crate::theme::{accent_for_theme, derive_palette};

/// The in-game background when pack colours are off or unavailable ("Mica blue").
pub const DEFAULT_IN_GAME_PRIMARY: RgbColor = RgbColor::new(60, 90, 160);
/// The in-game key highlight when pack colours are off or unavailable (gold).
pub const DEFAULT_IN_GAME_HIGHLIGHT: RgbColor = RgbColor::new(240, 195, 60);
/// A sampled secondary colour must be at least this far from the primary to be used as the
/// highlight; otherwise the primary's complement is.
pub const HIGHLIGHT_MIN_DISTANCE: f64 = 0.25;
/// The literal context shown in menu effect names when no pack is in focus.
pub const THEME_ACCENT_CONTEXT: &str = "theme accent";

/// What RGB needs to know about a modpack.
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct PackRef {
    /// Palette cache key (the pack-logo key).
    pub key: String,
    /// Display name (the In-Game effect label and menu context).
    pub friendly_name: String,
    /// The logo file on disk, if any.
    pub logo_path: Option<PathBuf>,
    /// Vanilla packs have no logo colours.
    pub vanilla: bool,
}

/// The launcher side the integration reads from. Implemented by the engine.
pub trait RgbHost: Send + Sync + 'static {
    /// The live RGB settings (read each time; the config may have changed).
    fn settings(&self) -> RgbSettings;
    /// Whether the OS is in dark mode (default to `true` when unknown).
    fn os_is_dark(&self) -> bool;
    /// The pack whose game is newest among the running ones, if any.
    fn newest_running_pack(&self) -> Option<PackRef>;
    /// Up to `count` dominant logo colours; `None` for vanilla, no logo or an undecodable logo.
    /// The default samples the logo file through the global [`PaletteCache`].
    fn pack_palette(&self, pack: &PackRef, count: usize) -> Option<Vec<RgbColor>> {
        default_pack_palette(pack, count)
    }
}

/// The default [`RgbHost::pack_palette`].
pub fn default_pack_palette(pack: &PackRef, count: usize) -> Option<Vec<RgbColor>> {
    if pack.vanilla || count == 0 {
        return None;
    }
    let path = pack.logo_path.as_deref()?;
    PaletteCache::global().palette(&pack.key, path, count)
}

/// The integration logic over one host and one controller.
#[derive(Clone)]
pub struct RgbIntegration {
    host: Arc<dyn RgbHost>,
    controller: RgbController,
}

impl RgbIntegration {
    pub fn new(host: Arc<dyn RgbHost>, controller: RgbController) -> RgbIntegration {
        RgbIntegration { host, controller }
    }

    /// The controller this integration drives.
    pub fn controller(&self) -> &RgbController {
        &self.controller
    }

    fn enabled(&self) -> Option<RgbSettings> {
        let s = self.host.settings();
        s.enabled.then_some(s)
    }

    /// Starts the controller on the configured backends, then shows the running game (an
    /// in-process restart while a game runs) or the menu effect. Blocking; [`bootstrap`] runs
    /// it on a background thread.
    pub fn bootstrap_now(&self) {
        let Some(settings) = self.enabled() else {
            return;
        };
        guard("log.rgb.bootstrapThrew", || {
            self.controller.start(registry::resolve_backends(&settings));
            match self.host.newest_running_pack() {
                Some(pack) => self.play_started(&pack),
                None => self.apply_menu_effect(None),
            }
        });
    }

    /// The menu is showing, optionally with a pack in focus (its details modal). Ignored while
    /// a game runs: the devices show the game.
    pub fn on_menu(&self, context: Option<&PackRef>) {
        if self.enabled().is_none() {
            return;
        }
        guard("log.rgb.onMenuThrew", || {
            if self.host.newest_running_pack().is_some() {
                return;
            }
            self.apply_menu_effect(context);
        });
    }

    /// A game started (the follower's "show game").
    pub fn on_play_started(&self, pack: &PackRef) {
        if self.enabled().is_none() {
            return;
        }
        guard("log.rgb.onPlayStartedThrew", || self.play_started(pack));
    }

    /// The last game ended: back to the menu effect (the controller stays running).
    pub fn on_play_ended(&self) {
        if self.enabled().is_none() {
            return;
        }
        guard("log.rgb.onPlayEndedThrew", || self.apply_menu_effect(None));
    }

    /// Stops everything. Blocking (the caller bounds it).
    pub fn shutdown(&self) {
        guard("log.rgb.shutdownThrew", || self.controller.stop());
    }

    /// Settings "Test". Returns `false` (and does nothing) when RGB is disabled, so the caller
    /// can show the `notification.settings.rgbDisabled.*` toast. Blocks about 2 s.
    pub fn run_connection_test(&self) -> bool {
        if self.enabled().is_none() {
            return false;
        }
        guard("log.rgb.onMenuThrew", || {
            self.controller.run_connection_test()
        });
        true
    }

    /// Settings restart after a backend / master / Auto-toggle change.
    pub fn restart_from_settings(&self) {
        let settings = self.host.settings();
        guard("log.rgb.bootstrapThrew", || {
            self.controller.restart_from_settings(&settings);
            if settings.enabled {
                match self.host.newest_running_pack() {
                    Some(pack) => self.play_started(&pack),
                    None => self.apply_menu_effect(None),
                }
            }
        });
    }

    fn play_started(&self, pack: &PackRef) {
        let s = self.host.settings();
        let (primary, highlight) = self.in_game_colors(&s, pack);
        let keys: BTreeSet<_> = if s.highlight_keys {
            InGameEffect::DEFAULT_HIGHLIGHTED_KEYS.into_iter().collect()
        } else {
            BTreeSet::new()
        };
        self.controller.set_effect(Some(Arc::new(InGameEffect::new(
            &pack.friendly_name,
            primary,
            highlight,
            &keys,
        ))));
    }

    /// The in-game background and highlight colours.
    pub fn in_game_colors(&self, s: &RgbSettings, pack: &PackRef) -> (RgbColor, RgbColor) {
        if s.use_pack_colors
            && let Some(p) = self.host.pack_palette(pack, 2)
            && let Some(&primary) = p.first()
        {
            let highlight = match p.get(1) {
                Some(&h) if RgbColor::distance(primary, h) > HIGHLIGHT_MIN_DISTANCE => h,
                _ => primary.complement(),
            };
            return (primary, highlight);
        }
        (DEFAULT_IN_GAME_PRIMARY, DEFAULT_IN_GAME_HIGHLIGHT)
    }

    fn apply_menu_effect(&self, pack: Option<&PackRef>) {
        let s = self.host.settings();
        self.controller.set_effect(self.menu_effect(&s, pack));
    }

    /// The menu effect for `s` with `pack` in focus; `None` when the menu effect is off.
    pub fn menu_effect(
        &self,
        s: &RgbSettings,
        pack: Option<&PackRef>,
    ) -> Option<Arc<dyn RgbEffect>> {
        if !s.menu_effect_enabled {
            return None;
        }
        let ctx = pack.map_or(THEME_ACCENT_CONTEXT, |p| p.friendly_name.as_str());
        let effect: Arc<dyn RgbEffect> = match s.effect_style {
            EffectStyle::Rainbow => Arc::new(RainbowEffect::new("Menu (rainbow)", 8000).ok()?),
            style => {
                let mut palette = self.resolve_palette(s, pack, style);
                if palette.is_empty() {
                    palette.push(self.accent(s));
                }
                let primary = palette[0];
                match style {
                    EffectStyle::Solid => {
                        Arc::new(SolidEffect::new(format!("Menu (solid · {ctx})"), primary))
                    }
                    EffectStyle::Pulse => Arc::new(
                        PulseEffect::new(
                            format!("Menu (pulse · {ctx})"),
                            primary,
                            primary.complement(),
                            1500,
                        )
                        .ok()?,
                    ),
                    EffectStyle::Cycle => {
                        let period = palette.len().max(1) as i64 * 2500;
                        Arc::new(
                            CycleEffect::new(format!("Menu (cycle · {ctx})"), palette, period)
                                .ok()?,
                        )
                    }
                    _ => Arc::new(
                        PulseEffect::new(
                            format!("Menu (breathe · {ctx})"),
                            primary,
                            RgbColor::blend(primary, RgbColor::BLACK, 0.65),
                            4000,
                        )
                        .ok()?,
                    ),
                }
            }
        };
        Some(effect)
    }

    fn accent(&self, s: &RgbSettings) -> RgbColor {
        let theme = (!s.theme.is_empty()).then_some(s.theme.as_str());
        accent_for_theme(theme, self.host.os_is_dark())
    }

    fn resolve_palette(
        &self,
        s: &RgbSettings,
        pack: Option<&PackRef>,
        style: EffectStyle,
    ) -> Vec<RgbColor> {
        let cycle = style == EffectStyle::Cycle;
        if let Some(pack) = pack {
            let wanted = if cycle { 4 } else { 2 };
            if let Some(out) = self.host.pack_palette(pack, wanted)
                && !out.is_empty()
            {
                if cycle && out.len() < 3 {
                    return derive_palette(out[0], 3);
                }
                return out;
            }
        }
        let accent = self.accent(s);
        if cycle {
            derive_palette(accent, 3)
        } else {
            vec![accent]
        }
    }
}

fn guard(key: &str, f: impl FnOnce()) {
    if let Err(p) = catch_unwind(AssertUnwindSafe(f)) {
        tracing::warn!(
            "{}: {}",
            mmcl_i18n::t!(key),
            crate::backend::panic_message(&*p)
        );
    }
}

// ---- process-wide entry points -----------------------------------------------------------

static INSTALLED: RwLock<Option<RgbIntegration>> = RwLock::new(None);

fn installed() -> Option<RgbIntegration> {
    INSTALLED.read().clone()
}

/// Registers the host (once per launcher session, after the config and game mode are known).
/// A later call replaces it (in-process restart).
pub fn install(host: Arc<dyn RgbHost>) {
    *INSTALLED.write() = Some(RgbIntegration::new(host, RgbController::global().clone()));
}

/// The installed integration, if any.
pub fn current() -> Option<RgbIntegration> {
    installed()
}

/// Starts RGB on a background thread and returns at once.
pub fn bootstrap() {
    let Some(i) = installed() else { return };
    let spawned = std::thread::Builder::new()
        .name("mica-rgb-bootstrap".into())
        .spawn(move || i.bootstrap_now());
    if let Err(e) = spawned {
        tracing::warn!("{}: {e}", mmcl_i18n::t!("log.rgb.bootstrapThrew"));
    }
}

/// See [`RgbIntegration::on_menu`]. Sampling a logo can take tens of milliseconds; call it off
/// the UI thread.
pub fn on_menu(context: Option<&PackRef>) {
    if let Some(i) = installed() {
        i.on_menu(context);
    }
}

/// See [`RgbIntegration::on_play_started`].
pub fn on_play_started(pack: &PackRef) {
    if let Some(i) = installed() {
        i.on_play_started(pack);
    }
}

/// See [`RgbIntegration::on_play_ended`].
pub fn on_play_ended() {
    if let Some(i) = installed() {
        i.on_play_ended();
    }
}

/// See [`RgbIntegration::run_connection_test`]. `false` when RGB is disabled or not installed.
pub fn run_connection_test() -> bool {
    installed().is_some_and(|i| i.run_connection_test())
}

/// See [`RgbIntegration::restart_from_settings`].
pub fn restart_from_settings() {
    if let Some(i) = installed() {
        i.restart_from_settings();
    }
}

/// Stops the global controller (blocking; the app's cleanup bounds it to 3 s).
pub fn shutdown() {
    match installed() {
        Some(i) => i.shutdown(),
        None => guard("log.rgb.shutdownThrew", || RgbController::global().stop()),
    }
}
