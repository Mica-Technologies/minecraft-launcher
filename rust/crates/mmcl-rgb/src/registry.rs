// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Which backends to run for the current settings (Java `RgbBackendRegistry`).

use mmcl_i18n::{t, tf};

use crate::backend::{BackendKind, RgbBackend, guarded, safe_name};
use crate::backends::noop::NoOpBackend;
use crate::settings::{BackendChoice, RgbSettings};

/// Auto-mode probe order. The first is the "primary" in the status chip and in dispatch.
pub const AUTO_ORDER: [BackendKind; 6] = [
    BackendKind::OpenRgb,
    BackendKind::ChromaNative,
    BackendKind::WindowsDynamicLighting,
    BackendKind::Corsair,
    BackendKind::AsusAura,
    BackendKind::ChromaRest,
];

/// Environment switch that runs the vendor-DLL backends inside the launcher process instead of
/// the `--rgb-helper` child (`1`/`true`). For debugging only: a native fault in a vendor SDK then
/// kills the launcher.
pub const IN_PROCESS_ENV: &str = "MMCL_RGB_IN_PROCESS";

/// A fresh, unstarted backend of `kind`. Vendor-DLL kinds are wrapped in the isolating helper
/// process unless [`IN_PROCESS_ENV`] is set.
pub fn make_backend(kind: BackendKind) -> Box<dyn RgbBackend> {
    if kind.is_vendor_dll() && !in_process_requested() {
        return Box::new(crate::helper::HelperBackend::new(kind));
    }
    crate::backends::make_in_process(kind)
}

fn in_process_requested() -> bool {
    std::env::var(IN_PROCESS_ENV)
        .map(|v| matches!(v.trim().to_ascii_lowercase().as_str(), "1" | "true" | "yes"))
        .unwrap_or(false)
}

/// The backends to start for `settings`:
///
/// - RGB off or `none`: nothing.
/// - `auto`: each enabled backend in [`AUTO_ORDER`] whose `is_available()` says yes (a
///   panicking probe counts as unavailable).
/// - a manual choice: exactly that backend, unprobed (the controller probes it), regardless of
///   the Auto toggles.
pub fn resolve_backends(settings: &RgbSettings) -> Vec<Box<dyn RgbBackend>> {
    resolve_backends_with(settings, &make_backend)
}

/// [`resolve_backends`] over a custom backend factory (tests).
pub fn resolve_backends_with(
    settings: &RgbSettings,
    make: &dyn Fn(BackendKind) -> Box<dyn RgbBackend>,
) -> Vec<Box<dyn RgbBackend>> {
    if !settings.enabled {
        return Vec::new();
    }
    match settings.backend {
        BackendChoice::None => Vec::new(),
        BackendChoice::Auto => {
            let candidates = AUTO_ORDER
                .into_iter()
                .map(|k| (make(k), settings.auto_enabled.allows(k)))
                .collect();
            let out = filter_candidates(candidates);
            if out.is_empty() {
                tracing::debug!("{}", t!("log.rgb.registry.probeNoneAvailable"));
            }
            out
        }
        manual => manual.kind().map(make).into_iter().collect(),
    }
}

/// Keeps, in order, the candidates that are enabled and available. Disabled ones are never
/// probed.
pub fn filter_candidates(candidates: Vec<(Box<dyn RgbBackend>, bool)>) -> Vec<Box<dyn RgbBackend>> {
    let mut out = Vec::new();
    for (mut backend, enabled) in candidates {
        let name = safe_name(&*backend);
        if !enabled {
            tracing::debug!(
                "{}",
                tf!("log.rgb.registry.probeSkippedDisabled", name.as_str())
            );
            continue;
        }
        match guarded(|| Ok(backend.is_available())) {
            Ok(true) => {
                tracing::debug!("{}", tf!("log.rgb.registry.probeIncluding", name.as_str()));
                out.push(backend);
            }
            Ok(false) => {
                tracing::debug!(
                    "{}",
                    tf!("log.rgb.controller.backendNotAvailable", name.as_str())
                );
            }
            Err(e) => {
                tracing::warn!(
                    "{}: {e}",
                    tf!("log.rgb.registry.probeIsAvailableThrew", name.as_str())
                );
            }
        }
    }
    out
}

/// Legacy single-backend lookup by config name (unknown -> the no-op backend). Kept for debug
/// tooling; the launcher uses [`resolve_backends`].
pub fn resolve(name: &str) -> Box<dyn RgbBackend> {
    match BackendChoice::parse(name).and_then(BackendChoice::kind) {
        Some(kind) => make_backend(kind),
        None => {
            if !matches!(
                BackendChoice::parse(name),
                Some(BackendChoice::None | BackendChoice::Auto)
            ) {
                tracing::warn!("{}", tf!("log.rgb.registry.unrecognizedChoiceNoOp", name));
            }
            Box::new(NoOpBackend)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::RgbResult;
    use crate::frame::RgbFrame;
    use crate::settings::AutoToggles;

    struct StubBackend {
        name: &'static str,
        kind: BackendKind,
        available: Option<bool>, // None = panic
    }

    impl RgbBackend for StubBackend {
        fn name(&self) -> &str {
            self.name
        }
        fn is_available(&mut self) -> bool {
            match self.available {
                Some(a) => a,
                None => panic!("probe exploded"),
            }
        }
        fn start(&mut self) -> RgbResult<()> {
            Ok(())
        }
        fn render_frame(&mut self, _: &RgbFrame) -> RgbResult<()> {
            Ok(())
        }
        fn shutdown(&mut self) {}
        fn kind(&self) -> BackendKind {
            self.kind
        }
    }

    fn stub(name: &'static str, available: Option<bool>) -> Box<dyn RgbBackend> {
        Box::new(StubBackend {
            name,
            kind: BackendKind::OpenRgb,
            available,
        })
    }

    fn names(v: &[Box<dyn RgbBackend>]) -> Vec<String> {
        v.iter().map(|b| b.name().to_string()).collect()
    }

    #[test]
    fn passes_through_in_order() {
        let out = filter_candidates(vec![
            (stub("a", Some(true)), true),
            (stub("b", Some(true)), true),
        ]);
        assert_eq!(names(&out), ["a", "b"]);
    }

    #[test]
    fn disabled_are_filtered() {
        let out = filter_candidates(vec![
            (stub("a", Some(true)), false),
            (stub("b", Some(true)), true),
        ]);
        assert_eq!(names(&out), ["b"]);
    }

    #[test]
    fn unavailable_are_filtered() {
        let out = filter_candidates(vec![
            (stub("a", Some(false)), true),
            (stub("b", Some(true)), true),
        ]);
        assert_eq!(names(&out), ["b"]);
    }

    #[test]
    fn panicking_probe_is_unavailable() {
        let out = filter_candidates(vec![(stub("a", None), true), (stub("b", Some(true)), true)]);
        assert_eq!(names(&out), ["b"]);
    }

    #[test]
    fn empty_gives_empty() {
        assert!(filter_candidates(vec![]).is_empty());
    }

    #[test]
    fn order_is_preserved() {
        let out = filter_candidates(vec![
            (stub("c", Some(true)), true),
            (stub("a", Some(true)), true),
            (stub("x", Some(false)), true),
            (stub("b", Some(true)), true),
        ]);
        assert_eq!(names(&out), ["c", "a", "b"]);
    }

    fn fake_factory(kind: BackendKind) -> Box<dyn RgbBackend> {
        Box::new(StubBackend {
            name: kind.label(),
            kind,
            available: Some(true),
        })
    }

    #[test]
    fn resolve_respects_master_switch_choice_and_auto_order() {
        let mut s = RgbSettings::default();
        assert!(resolve_backends_with(&s, &fake_factory).is_empty());
        s.enabled = true;
        s.auto_enabled = AutoToggles::default();
        let auto = resolve_backends_with(&s, &fake_factory);
        let kinds: Vec<_> = auto.iter().map(|b| b.kind()).collect();
        assert_eq!(
            kinds,
            AUTO_ORDER[..5].to_vec(),
            "Chroma REST is off by default"
        );

        // Manual Corsair / ASUS are reachable (Java bug fixed) and ignore the toggles.
        s.auto_enabled.corsair = false;
        for (choice, kind) in [
            (BackendChoice::Corsair, BackendKind::Corsair),
            (BackendChoice::AsusAura, BackendKind::AsusAura),
            (BackendChoice::ChromaRest, BackendKind::ChromaRest),
        ] {
            s.backend = choice;
            let v = resolve_backends_with(&s, &fake_factory);
            assert_eq!(v.iter().map(|b| b.kind()).collect::<Vec<_>>(), vec![kind]);
        }
        s.backend = BackendChoice::None;
        assert!(resolve_backends_with(&s, &fake_factory).is_empty());
    }
}
