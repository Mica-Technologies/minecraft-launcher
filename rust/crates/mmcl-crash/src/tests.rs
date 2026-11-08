// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Ports `CrashReportAnalyzerTest` (rendered through the real English bundle) plus tests for
//! the Rust-only seams: suggestion lists, the disable-mod lookup, RAM math and panics.

use std::path::Path;
use std::sync::LazyLock;

use mmcl_i18n::{FmtArg, Locale, Localizer};

use crate::analyze::run_table;
use crate::detectors::{self, Detector, DetectorId, java_release_for_class_file_version};
use crate::*;

const FAKE_EXIT_CODE: i32 = -1;

static EN: LazyLock<Localizer> = LazyLock::new(|| Localizer::new(Locale::en_us()));

struct English;

impl TextResolver for English {
    fn get(&self, key: &str) -> String {
        assert!(EN.contains(key), "missing bundle key {key}");
        EN.get(key).into_owned()
    }
    fn format(&self, key: &str, args: &[ResolvedArg<'_>]) -> String {
        assert!(EN.contains(key), "missing bundle key {key}");
        let args: Vec<FmtArg<'_>> = args
            .iter()
            .map(|a| match a {
                ResolvedArg::Str(s) => FmtArg::Str(s.clone()),
                ResolvedArg::Int(n) => FmtArg::Int(*n),
            })
            .collect();
        EN.format(key, &args)
    }
}

const ENV: Env = Env {
    max_ram_mb: 4096,
    system_ram_gb: 0,
};

fn run(text: &str) -> CrashDiagnosis {
    analyze(Some(text), None, FAKE_EXIT_CODE, &ENV)
}

fn title(d: &CrashDiagnosis) -> String {
    d.title.resolve(&English)
}

fn summary(d: &CrashDiagnosis) -> String {
    d.summary.resolve(&English)
}

fn labels(d: &CrashDiagnosis) -> Vec<String> {
    d.suggestions
        .iter()
        .map(|s| s.label.resolve(&English))
        .collect()
}

fn actions(d: &CrashDiagnosis) -> Vec<Option<CrashAction>> {
    d.suggestions.iter().map(|s| s.action.clone()).collect()
}

// ===== Ported from CrashReportAnalyzerTest =====

#[test]
fn out_of_memory_detected() {
    let d = run("---- Minecraft Crash Report ----\n\
                 Description: Exception in server tick loop\n\
                 java.lang.OutOfMemoryError: Java heap space\n\
                 \tat net.minecraft.world.World.tick(World.java:1234)\n");
    assert_eq!(d.category, Category::OutOfMemory);
    assert!(title(&d).to_lowercase().contains("memory"), "{}", title(&d));
}

#[test]
fn java_version_mismatch_detected() {
    let d = run("Caused by: java.lang.UnsupportedClassVersionError:\n  \
                 com/example/Mod has been compiled by a more recent version of the Java Runtime\n  \
                 (class file version 61.0), this version of the Java Runtime only recognizes\n  \
                 class file versions up to 55.0\n");
    assert_eq!(d.category, Category::JavaVersion);
    assert!(summary(&d).contains("Java 17"), "{}", summary(&d));
}

#[test]
fn disk_full_detected() {
    assert_eq!(
        run("java.io.IOException: No space left on device").category,
        Category::DiskIo
    );
}

#[test]
fn native_library_failure_detected() {
    let d = run(
        "java.lang.UnsatisfiedLinkError: Could not load library lwjgl64.dll\n  \
                 at org.lwjgl.system.Library.loadNative(Library.java:1)\n",
    );
    assert_eq!(d.category, Category::NativeLibrary);
}

#[test]
fn mixin_conflict_detected() {
    let d = run(
        "org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError:\n  \
                 Mixin [mixins.examplemod.json:HudOverlay] from phase\n  \
                 could not apply to net.minecraft.client.gui.GuiIngame\n",
    );
    assert_eq!(d.category, Category::MixinConflict);
    assert!(summary(&d).contains("examplemod"), "{}", summary(&d));
}

#[test]
fn forge_dependency_mismatch_detected() {
    let d = run("Mod 'JEI' (jei) requires patchouli [1.20.1,) loaded");
    assert_eq!(d.category, Category::ModLoading);
    let t = title(&d).to_lowercase();
    assert!(t.contains("dependency") || t.contains("mismatched"), "{t}");
    let s = summary(&d);
    assert!(
        s.contains("\"JEI\"") && s.contains("patchouli [1.20.1,)"),
        "{s}"
    );
}

#[test]
fn forge_dependency_second_branch() {
    let d = run("Mod examplemod requires geckolib version [4.0,)");
    assert_eq!(d.title.primary_key(), Some("crash.forgeDep.title"));
    let s = summary(&d);
    assert!(
        s.contains("\"examplemod\"") && s.contains("geckolib [4.0,)"),
        "{s}"
    );
}

#[test]
fn mod_loading_generic_fallback_detected() {
    let d = run("java.lang.NoClassDefFoundError: com/example/SomeApi");
    assert_eq!(d.category, Category::ModLoading);
    assert!(
        summary(&d).contains("com.example.SomeApi"),
        "{}",
        summary(&d)
    );
}

#[test]
fn mod_loading_without_class_uses_generic_summary() {
    let d = run("net.minecraftforge.fml.ModLoadingException: boom");
    assert_eq!(d.summary, Text::key("crash.modLoading.summaryGeneric"));
}

#[test]
fn gpu_open_gl_failure_detected() {
    assert_eq!(
        run("Failed to create OpenGL context: GLFW error 65540").category,
        Category::Gpu
    );
}

#[test]
fn world_corruption_detected() {
    let d = run(
        "java.io.IOException: Could not load level \u{2014} RegionFileException at chunk 12,34",
    );
    assert_eq!(d.category, Category::WorldCorruption);
}

#[test]
fn auth_failure_detected() {
    let d = run("Could not authenticate against Mojang: Invalid session ID");
    assert_eq!(d.category, Category::Auth);
    assert_eq!(actions(&d), vec![Some(CrashAction::OpenSettings)]);
    assert!(d.suggestions[0].primary);
}

#[test]
fn unknown_fallback_on_empty_crash_text() {
    let d = analyze(Some(""), None, 42, &ENV);
    assert_eq!(d.category, Category::Unknown);
    assert!(summary(&d).contains("42"), "{}", summary(&d));
    assert!(d.suggestions.is_empty());
}

#[test]
fn unknown_fallback_on_unrelated_exception() {
    let d = run("java.lang.NullPointerException at com.example.Random.thing(Random.java:1)");
    assert_eq!(d.category, Category::Unknown);
}

#[test]
fn audio_init_failure_detected() {
    let d = run(
        "com.mojang.blaze3d.audio.AudioException: Could not initialize OpenAL\n  \
                 Cause: ALSA lib pcm_dmix.c:1052:(snd_pcm_dmix_open) unable to open slave\n",
    );
    assert_eq!(d.category, Category::Audio);
    assert_eq!(
        d.severity,
        Severity::Warning,
        "audio failures are non-fatal"
    );
}

#[test]
fn file_lock_detected() {
    let d = run(
        "java.nio.file.FileSystemException: C:/...: The process cannot access the file because it is being used by another process",
    );
    assert_eq!(d.category, Category::FileLock);
    assert!(
        summary(&d).to_lowercase().contains("antivirus"),
        "{}",
        summary(&d)
    );
    // No pack: nothing to open (the Java built a button that then failed).
    assert!(d.suggestions.is_empty());
}

#[test]
fn optifine_sodium_conflict_detected() {
    let d = run(
        "java.lang.RuntimeException: Cannot load mod 'sodium' alongside 'optifine'\n  \
                 at me.jellysquid.mods.sodium.client.SodiumClientMod.<init>\n  \
                 Caused by: org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError\n",
    );
    assert_eq!(d.category, Category::LoaderConflict);
    let t = title(&d).to_lowercase();
    assert!(t.contains("optifine") && t.contains("sodium"), "{t}");
    // The summary is a plain lookup: its literal {name} must survive.
    assert!(
        summary(&d).contains("{name}.jar.disabled"),
        "{}",
        summary(&d)
    );
}

#[test]
fn optifine_without_sodium_does_not_trigger_conflict() {
    let d = run("Mod 'optifine' threw: java.lang.NoClassDefFoundError: missing/Dep\n");
    assert_eq!(d.category, Category::ModLoading);
}

#[test]
fn mod_minecraft_version_mismatch_detected() {
    let d = run(
        "Mod ExampleMod was compiled for Minecraft 1.20.1 but is being loaded by Minecraft 1.20.4",
    );
    assert_eq!(d.category, Category::ModLoading);
    let t = title(&d);
    assert!(t.contains("1.20.1") && t.contains("1.20.4"), "{t}");
}

#[test]
fn jei_rei_dual_presence_detected() {
    let d = run(
        "Caused by: java.lang.RuntimeException: Failed to register recipes\n  \
                 at mezz.jei.library.runtime.JeiRecipeManager\n  \
                 ... then ...\n  \
                 at me.shedaniel.rei.impl.ClientHelperImpl\n",
    );
    assert_eq!(d.category, Category::ModLoading);
    let t = title(&d).to_lowercase();
    assert!(t.contains("jei") && t.contains("rei"), "{t}");
}

#[test]
fn duplicate_recipe_id_detected() {
    let d = run("java.lang.IllegalStateException: Duplicate recipe id minecraft:crafting/example");
    assert_eq!(d.category, Category::ModLoading);
    assert!(title(&d).to_lowercase().contains("recipe"), "{}", title(&d));
    assert_eq!(labels(&d), vec!["Open Mods Folder"]);
    assert!(!d.suggestions[0].primary);
}

#[test]
fn oom_beats_downstream_mod_loading_failure() {
    let d = run("java.lang.OutOfMemoryError: Java heap space\n  \
                 at net.minecraftforge.fml.loading.moddiscovery.ModFile.<init>\n  \
                 Caused by: java.lang.NoClassDefFoundError\n");
    assert_eq!(d.category, Category::OutOfMemory);
}

#[test]
fn mod_mc_version_beats_generic_mod_loading() {
    let d = run(
        "Mod ExampleMod was compiled for Minecraft 1.20.1 but is being loaded by Minecraft 1.20.4. Caused by: NoClassDefFoundError",
    );
    assert_eq!(d.category, Category::ModLoading);
    assert!(title(&d).contains("1.20.1"));
}

#[test]
fn optifine_sodium_beats_mixin_conflict() {
    let d = run(
        "org.spongepowered.asm.mixin.transformer.throwables.MixinApplyError\n  \
                 Caused by: java.lang.RuntimeException sodium and optifine cannot coexist\n",
    );
    assert_eq!(d.category, Category::LoaderConflict);
}

#[test]
fn analyze_null_crash_text_survives() {
    let d = analyze(None, None, FAKE_EXIT_CODE, &ENV);
    assert_eq!(d.category, Category::Unknown);
}

#[test]
fn search_phrase_captures_fully_qualified_exception_and_message() {
    let text = "Some preamble\n\
                com.example.cursed.WidgetException: Cannot bake widget for biome plains\n\
                \tat com.example.cursed.Widget.bake(Widget.java:42)\n";
    assert_eq!(
        extract_crash_search_phrase(text).as_deref(),
        Some("com.example.cursed.WidgetException: Cannot bake widget for biome plains")
    );
}

#[test]
fn search_phrase_falls_back_to_type_when_no_message() {
    assert_eq!(
        extract_crash_search_phrase("boom com.example.mod.LoaderError\n more").as_deref(),
        Some("com.example.mod.LoaderError")
    );
}

#[test]
fn search_phrase_truncates_long_messages() {
    let prefix = "com.example.FooException: ";
    let phrase = extract_crash_search_phrase(&format!("{prefix}{}", "x".repeat(300))).unwrap();
    assert_eq!(phrase.len(), prefix.len() + 120);
}

#[test]
fn search_phrase_returns_none_when_no_exception_present() {
    assert_eq!(
        extract_crash_search_phrase("just some plain log output, no stack trace"),
        None
    );
    assert_eq!(extract_crash_search_phrase(""), None);
}

#[test]
fn unmatched_crash_gets_web_search_suggestion() {
    let d = run("com.unknownmod.GremlinException: the gremlins won");
    assert_eq!(d.category, Category::Unknown);
    assert_eq!(d.suggestions.len(), 1);
    assert_eq!(labels(&d), vec!["Search the web"]);
    assert_eq!(
        d.suggestions[0].action,
        Some(CrashAction::OpenUrl(
            "https://www.google.com/search?q=com.unknownmod.GremlinException%3A+the+gremlins+won"
                .into()
        ))
    );
}

// ===== Rust-side behaviour =====

#[test]
fn every_pattern_compiles() {
    for p in detectors::all_patterns() {
        assert!(!p.as_str().is_empty());
    }
}

#[test]
fn detector_order_matches_spec() {
    use DetectorId::*;
    assert_eq!(
        detector_order().collect::<Vec<_>>(),
        vec![
            OutOfMemory,
            JavaVersion,
            DiskFull,
            FileLock,
            NativeLibrary,
            OptiFineSodium,
            Mixin,
            ModMinecraftVersion,
            ForgeDependency,
            JeiRei,
            ModLoading,
            Audio,
            Gpu,
            WorldCorruption,
            Auth,
        ]
    );
}

#[test]
fn every_key_used_exists_in_the_bundle() {
    // Run a corpus that hits every detector and every branch, rendering through the English
    // bundle (whose resolver asserts each key exists).
    let dir = pack_with_mods(&[
        "OptiFine_HD_U_I6.jar",
        "sodium-fabric-0.5.jar",
        "jei-1.20.1.jar",
        "rei-12.jar",
        "examplemod-1.0.jar",
    ]);
    let pack = PackInfo {
        name: Some("Pack"),
        root: Some(dir.path()),
        url: Some("https://example.invalid/pack.json"),
    };
    let env = Env {
        max_ram_mb: 4096,
        system_ram_gb: 32,
    };
    let corpus = [
        "java.lang.OutOfMemoryError: Java heap space",
        "java.lang.OutOfMemoryError",
        "class file version 65.0 java.lang.UnsupportedClassVersionError",
        "java.lang.UnsupportedClassVersionError",
        "Disk full",
        "Sharing violation",
        "java.lang.UnsatisfiedLinkError",
        "optifine sodium",
        "MixinApplyError mixins.examplemod.json",
        "MixinApplyError",
        "requires 1.20.1 but you have 1.19.2",
        "Mod 'A' (a) requires b 1.0",
        "mezz.jei me.shedaniel.rei",
        "Duplicate recipe id x",
        "java.lang.ClassNotFoundException: a/b/C",
        "missing dependencies",
        "ALSA lib",
        "GL_OUT_OF_MEMORY",
        "Invalid level format",
        "Bad login",
        "com.x.YException: z",
        "nothing here",
    ];
    for text in corpus {
        for pack in [None, Some(&pack)] {
            let d = analyze(Some(text), pack, 1, &env);
            title(&d);
            summary(&d);
            labels(&d);
        }
    }
}

#[test]
fn oom_suggestions_and_summary() {
    let env = Env {
        max_ram_mb: 4096,
        system_ram_gb: 16,
    };
    let d = analyze(
        Some("java.lang.OutOfMemoryError: Java heap space"),
        None,
        1,
        &env,
    );
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::SetMaxRamMb(6 * 1024)),
            Some(CrashAction::OpenSettings)
        ]
    );
    assert!(d.suggestions[0].primary && !d.suggestions[1].primary);
    assert_eq!(
        labels(&d),
        vec!["Increase max RAM to 6 GB", "Open RAM settings"]
    );
    assert_eq!(
        summary(&d),
        "Minecraft ran out of memory (Java heap space). Your launcher's max RAM is currently 4 GB; \
         this pack needs more. Your system has about 16 GB total."
    );
}

#[test]
fn oom_cap_and_unknown_system_ram() {
    // 12 GB of 16: cap = 12, so no bump is offered.
    let capped = Env {
        max_ram_mb: 12 * 1024,
        system_ram_gb: 16,
    };
    let d = analyze(Some("java.lang.OutOfMemoryError"), None, 1, &capped);
    assert_eq!(actions(&d), vec![Some(CrashAction::OpenSettings)]);
    assert!(
        !summary(&d).contains("()"),
        "no detail part: {}",
        summary(&d)
    );

    // Small machine: the cap never drops below 4 GB.
    let small = Env {
        max_ram_mb: 2048,
        system_ram_gb: 4,
    };
    let d = analyze(Some("java.lang.OutOfMemoryError"), None, 1, &small);
    assert_eq!(
        d.suggestions[0].action,
        Some(CrashAction::SetMaxRamMb(4 * 1024))
    );

    // Unknown system RAM: uncapped, no system-total sentence; a sub-GB setting counts as 1 GB.
    let unknown = Env {
        max_ram_mb: 512,
        system_ram_gb: 0,
    };
    let d = analyze(Some("java.lang.OutOfMemoryError"), None, 1, &unknown);
    assert_eq!(
        d.suggestions[0].action,
        Some(CrashAction::SetMaxRamMb(3 * 1024))
    );
    assert!(!summary(&d).contains("system has"));
    assert!(summary(&d).contains("currently 1 GB"));
}

#[test]
fn java_release_mapping() {
    assert_eq!(java_release_for_class_file_version("61"), "17");
    assert_eq!(java_release_for_class_file_version("52"), "8");
    assert_eq!(java_release_for_class_file_version("49"), "5");
    assert_eq!(java_release_for_class_file_version("48"), "48");
}

#[test]
fn java_version_with_pack_adds_manifest_part() {
    let pack = PackInfo::default();
    let d = analyze(
        Some("java.lang.UnsupportedClassVersionError"),
        Some(&pack),
        1,
        &ENV,
    );
    let s = summary(&d);
    assert!(s.contains("declared by the modpack manifest"), "{s}");
    assert!(!s.contains("targets Java"), "{s}");
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::OpenRuntime),
            Some(CrashAction::OpenSettings)
        ]
    );
}

#[test]
fn folder_suggestions_with_pack() {
    let pack = PackInfo::default();
    let d = analyze(Some("Disk full"), Some(&pack), 1, &ENV);
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
            Some(CrashAction::OpenPackFolder(PackFolder::Root)),
        ]
    );
    assert!(d.suggestions[0].primary && !d.suggestions[1].primary);

    let d = analyze(Some("Sharing violation"), Some(&pack), 1, &ENV);
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::OpenPackFolder(PackFolder::Root)),
            Some(CrashAction::OpenPackFolder(PackFolder::CrashReports)),
        ]
    );
    assert!(d.suggestions[0].primary);

    let d = analyze(Some("ALSA lib"), Some(&pack), 1, &ENV);
    assert_eq!(
        actions(&d),
        vec![Some(CrashAction::OpenPackFolder(PackFolder::CrashReports))]
    );

    let d = analyze(Some("GL_OUT_OF_MEMORY"), Some(&pack), 1, &ENV);
    assert!(d.suggestions.is_empty());
}

#[test]
fn native_library_reinstall_needs_a_url() {
    let with_url = PackInfo {
        url: Some("https://example.invalid/p.json"),
        ..PackInfo::default()
    };
    let d = analyze(
        Some("java.lang.UnsatisfiedLinkError"),
        Some(&with_url),
        1,
        &ENV,
    );
    assert_eq!(d.suggestions[0].action, Some(CrashAction::ReinstallPack));
    assert!(d.suggestions[0].primary);
    assert_eq!(d.suggestions.len(), 4);

    let blank_url = PackInfo {
        url: Some("  "),
        ..PackInfo::default()
    };
    let d = analyze(
        Some("java.lang.UnsatisfiedLinkError"),
        Some(&blank_url),
        1,
        &ENV,
    );
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
            Some(CrashAction::OpenPackFolder(PackFolder::Root)),
            Some(CrashAction::OpenPackFolder(PackFolder::CrashReports)),
        ]
    );
}

fn pack_with_mods(names: &[&str]) -> tempfile::TempDir {
    let dir = tempfile::tempdir().unwrap();
    let mods = dir.path().join("mods");
    std::fs::create_dir_all(&mods).unwrap();
    for n in names {
        std::fs::write(mods.join(n), b"").unwrap();
    }
    dir
}

fn pack_at(root: &Path) -> PackInfo<'_> {
    PackInfo {
        name: Some("My Pack"),
        root: Some(root),
        url: None,
    }
}

#[test]
fn optifine_sodium_offers_disable_buttons() {
    let dir = pack_with_mods(&[
        "sodium-fabric-0.5.jar",
        "OptiFine_HD_U_I6.jar",
        "optifine-old.jar.disabled",
        "readme.txt",
    ]);
    let pack = pack_at(dir.path());
    let d = analyze(Some("optifine and sodium"), Some(&pack), 1, &ENV);
    let mods = dir.path().join("mods");
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::DisableMod(mods.join("OptiFine_HD_U_I6.jar"))),
            Some(CrashAction::DisableMod(mods.join("sodium-fabric-0.5.jar"))),
            Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
        ]
    );
    assert_eq!(
        d.suggestions.iter().map(|s| s.primary).collect::<Vec<_>>(),
        vec![true, false, false]
    );
    assert_eq!(labels(&d)[0], "Disable OptiFine_HD_U_I6.jar");
}

#[test]
fn optifine_sodium_without_jars_falls_back_to_mods_folder() {
    let dir = pack_with_mods(&[]);
    let pack = pack_at(dir.path());
    let d = analyze(Some("optifine and sodium"), Some(&pack), 1, &ENV);
    assert_eq!(
        actions(&d),
        vec![Some(CrashAction::OpenPackFolder(PackFolder::Mods))]
    );
    assert!(d.suggestions[0].primary);
    // Without any pack the Java still offers the (primary) mods-folder button.
    let d = run("optifine and sodium");
    assert_eq!(d.suggestions.len(), 1);
}

#[test]
fn disable_mod_picks_first_by_name() {
    let dir = pack_with_mods(&["rei-b.jar", "rei-a.JAR"]);
    let pack = pack_at(dir.path());
    let s = detectors::maybe_disable_mod(Some(&pack), "rei").unwrap();
    assert_eq!(
        s.action,
        Some(CrashAction::DisableMod(
            dir.path().join("mods").join("rei-a.JAR")
        ))
    );
    assert!(detectors::maybe_disable_mod(Some(&pack), "jei").is_none());
    assert!(detectors::maybe_disable_mod(None, "rei").is_none());
    assert!(detectors::maybe_disable_mod(Some(&PackInfo::default()), "rei").is_none());
    let empty = tempfile::tempdir().unwrap();
    assert!(detectors::maybe_disable_mod(Some(&pack_at(empty.path())), "rei").is_none());
}

#[test]
fn mixin_culprit_becomes_primary_disable() {
    let dir = pack_with_mods(&["ExampleMod-1.2.jar"]);
    let pack = pack_at(dir.path());
    let d = analyze(
        Some("MixinApplyError in mixins.ExampleMod.json"),
        Some(&pack),
        1,
        &ENV,
    );
    // The owner regex is lower-case only, so `mixins.ExampleMod.json` names no culprit.
    assert!(!matches!(
        d.suggestions[0].action,
        Some(CrashAction::DisableMod(_))
    ));
    let d = analyze(
        Some("MixinApplyError in mixins.examplemod.json"),
        Some(&pack),
        1,
        &ENV,
    );
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::DisableMod(
                dir.path().join("mods").join("ExampleMod-1.2.jar")
            )),
            Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
            Some(CrashAction::OpenPackFolder(PackFolder::Root)),
            Some(CrashAction::OpenPackFolder(PackFolder::CrashReports)),
        ]
    );
    assert!(d.suggestions[0].primary);
    let d = run("MixinTransformerError @Mixin(SomeTarget)");
    assert!(summary(&d).contains("\"SomeTarget\""), "{}", summary(&d));
}

#[test]
fn jei_rei_dual_offers_disables() {
    let dir = pack_with_mods(&["jei-1.20.1-forge.jar", "RoughlyEnoughItems-12.jar"]);
    let pack = pack_at(dir.path());
    let d = analyze(Some("just enough items ... rei-12"), Some(&pack), 1, &ENV);
    assert_eq!(d.title, Text::key("crash.jeiRei.titleDual"));
    // Neither file name contains "rei" ("roughlyenoughitems" does not), so only the JEI
    // disable is offered.
    assert_eq!(
        actions(&d),
        vec![
            Some(CrashAction::DisableMod(
                dir.path().join("mods").join("jei-1.20.1-forge.jar")
            )),
            Some(CrashAction::OpenPackFolder(PackFolder::Mods)),
        ]
    );
    assert!(d.suggestions[0].primary && !d.suggestions[1].primary);
}

#[test]
fn search_url_includes_pack_name_and_form_encodes() {
    let pack = PackInfo {
        name: Some("Alto & Co"),
        ..PackInfo::default()
    };
    assert_eq!(
        search_url(Some(&pack), "a.BException: x/y"),
        "https://www.google.com/search?q=Alto+%26+Co+a.BException%3A+x%2Fy"
    );
    assert!(search_suggestion(None, "  ").is_none());
}

#[test]
fn panicking_detector_is_skipped() {
    fn boom(_: &detectors::Input<'_>) -> Option<CrashDiagnosis> {
        panic!("detector bug")
    }
    fn auth(i: &detectors::Input<'_>) -> Option<CrashDiagnosis> {
        i.text
            .contains("Bad login")
            .then(|| CrashDiagnosis::unknown(7))
    }
    let table = [
        Detector {
            id: DetectorId::OutOfMemory,
            detect: boom,
        },
        Detector {
            id: DetectorId::Auth,
            detect: auth,
        },
    ];
    let mut errors = Vec::new();
    let prev = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {}));
    let d = run_table(&table, Some("Bad login"), None, 1, &ENV, &mut |id, m| {
        errors.push((id, m))
    });
    std::panic::set_hook(prev);
    assert_eq!(d, CrashDiagnosis::unknown(7));
    assert_eq!(
        errors,
        vec![(DetectorId::OutOfMemory, "detector bug".to_owned())]
    );
}

#[test]
fn unicode_word_chars_do_not_extend_ascii_classes() {
    // Java's \w is ASCII-only; a non-ASCII letter must end the class name.
    assert_eq!(
        extract_crash_search_phrase("com.example.FooException\u{e9}").as_deref(),
        Some("com.example.FooException")
    );
}
