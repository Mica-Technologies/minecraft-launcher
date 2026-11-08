// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Nekodetector port: signatures, the SIG3 quirk, malformed classes, folder runs and stage 2.

mod common;

use std::path::Path;
use std::sync::Mutex;

use common::build_jar;
use common::classwriter::{ClassWriter, Code};
use mmcl_scan::CancelToken;
use mmcl_scan::classfile::op;
use mmcl_scan::neko::{
    NekoError, SIG1, SIG2, SIG3, Sig, Stage2Env, check_stage2, run_with_env, scan_class, scan_jar,
};

/// Emits one signature instruction into `code`, adding its constants to `cw`.
fn emit(cw: &mut ClassWriter, code: Code, sig: &Sig) -> Code {
    match *sig {
        Sig::Type(o, desc) => {
            let c = cw.class(desc);
            code.raw(&[o]).u2(c)
        }
        Sig::Method(o, owner, name, desc) => {
            let m = cw.methodref(owner, name, desc);
            let code = code.raw(&[o]).u2(m);
            if o == op::INVOKEINTERFACE {
                code.raw(&[1, 0])
            } else {
                code
            }
        }
        Sig::Plain(o) => code.op(o),
        Sig::Int(o, v) => {
            assert_eq!(o, op::BIPUSH);
            code.bipush(v as i8)
        }
    }
}

fn class_with(name: &str, build: impl FnOnce(&mut ClassWriter) -> Code) -> Vec<u8> {
    let mut cw = ClassWriter::new(name);
    let code = build(&mut cw);
    cw.method("run", "()V", code);
    cw.finish()
}

fn sig_class(sig: &[Sig], tail: &[u8]) -> Vec<u8> {
    class_with("com/evil/Loader", |cw| {
        let mut code = Code::new();
        for s in sig {
            code = emit(cw, code, s);
        }
        code.raw(tail)
    })
}

#[test]
fn signature_tables_have_java_lengths() {
    assert_eq!(SIG1.len(), 15);
    assert_eq!(SIG2.len(), 7);
    assert_eq!(SIG3.len(), 53);
    // The bytes SIG3 pushes spell the C2 address.
    let text: String = SIG3
        .iter()
        .enumerate()
        .filter_map(|(i, s)| match s {
            // Value pushes are the BIPUSH right before a BASTORE, or the very last one.
            Sig::Int(_, v)
                if SIG3
                    .get(i + 1)
                    .is_none_or(|n| *n == Sig::Plain(op::BASTORE)) =>
            {
                Some(char::from(*v as u8))
            }
            _ => None,
        })
        .collect();
    assert_eq!(text, "85.217.144.130");
}

#[test]
fn sig1_positive() {
    assert!(scan_class(&sig_class(&SIG1, &[op::RETURN])));
}

#[test]
fn sig1_with_unrelated_instructions_interleaved() {
    let bytes = class_with("a/B", |cw| {
        let mut code = Code::new();
        for s in SIG1.iter() {
            code = code.op(op::ICONST_0).op(op::POP).op(op::DUP);
            code = emit(cw, code, s);
        }
        code.ret()
    });
    assert!(scan_class(&bytes));
}

#[test]
fn sig1_fails_when_the_first_same_opcode_instruction_differs() {
    let bytes = class_with("a/B", |cw| {
        let mut code = Code::new();
        for (i, s) in SIG1.iter().enumerate() {
            if i == 2 {
                // A NEW of another class where `NEW java/lang/String` is expected next.
                let c = cw.class("java/lang/StringBuilder");
                code = code.raw(&[op::NEW]).u2(c);
            }
            code = emit(cw, code, s);
        }
        code.ret()
    });
    assert!(!scan_class(&bytes));
}

#[test]
fn sig1_incomplete_is_clean() {
    assert!(!scan_class(&sig_class(&SIG1[..14], &[op::RETURN])));
}

#[test]
fn sig2_positive_after_unrelated_static_call() {
    let bytes = class_with("a/B", |cw| {
        let other = cw.methodref("java/lang/System", "nanoTime", "()J");
        let mut code = Code::new().invokestatic(other).op(0x58); // pop2
        for s in SIG2.iter() {
            code = emit(cw, code, s);
        }
        code.ret()
    });
    assert!(scan_class(&bytes));
}

#[test]
fn sig2_needs_each_next_same_opcode_call_to_match() {
    let bytes = class_with("a/B", |cw| {
        let mut code = Code::new();
        for (i, s) in SIG2.iter().enumerate() {
            if i == 2 {
                // An INVOKEVIRTUAL where String.concat is expected.
                let m = cw.methodref("java/lang/Object", "toString", "()Ljava/lang/String;");
                code = code.invokevirtual(m);
            }
            code = emit(cw, code, s);
        }
        code.ret()
    });
    assert!(!scan_class(&bytes));
}

#[test]
fn sig2_second_attempt_later_in_the_method_matches() {
    let bytes = class_with("a/B", |cw| {
        let mut code = Code::new();
        // A failed attempt (wrong concat) followed by a full sequence.
        for (i, s) in SIG2.iter().enumerate() {
            if i == 2 {
                let m = cw.methodref("java/lang/Object", "toString", "()Ljava/lang/String;");
                code = code.invokevirtual(m);
            }
            code = emit(cw, code, s);
        }
        for s in SIG2.iter() {
            code = emit(cw, code, s);
        }
        code.ret()
    });
    assert!(scan_class(&bytes));
}

#[test]
fn sig3_positive_with_javac_style_prefix() {
    let bytes = class_with("a/B", |cw| {
        let mut code = Code::new()
            .bipush(14)
            .raw(&[op::NEWARRAY, 8])
            .op(op::DUP)
            .op(op::ICONST_0);
        for s in SIG3.iter() {
            code = emit(cw, code, s);
        }
        code.op(op::BASTORE).op(op::POP).ret()
    });
    assert!(scan_class(&bytes));
}

#[test]
fn sig3_broken_sequence_is_clean() {
    let mut sig = SIG3.to_vec();
    sig[10] = Sig::Plain(op::POP); // DUP replaced by POP: resets the match
    assert!(!scan_class(&sig_class(&sig, &[op::RETURN])));
}

#[test]
fn sig3_last_instruction_quirk_is_ported() {
    // Java reports a hit when the method's last real instruction has the opcode the signature
    // expects next, however short the match is. Ported exactly, for parity.
    assert!(scan_class(&sig_class(&[Sig::Int(op::BIPUSH, 56)], &[])));
    assert!(scan_class(&sig_class(
        &[Sig::Int(op::BIPUSH, 56), Sig::Plain(op::BASTORE)],
        &[]
    )));
    // Ending in a return (as every real method does) resets it.
    assert!(!scan_class(&sig_class(
        &[Sig::Int(op::BIPUSH, 56)],
        &[op::RETURN]
    )));
    // A BIPUSH with the right opcode but the wrong value neither advances nor resets.
    // Here the BIPUSH 99 sits where BIPUSH 53 is expected; a reset would make the following
    // BIPUSH 53 fail (position 0 expects 56), so the hit proves there is none.
    let mut sig = SIG3[..4].to_vec();
    sig.push(Sig::Int(op::BIPUSH, 99));
    sig.push(SIG3[4]);
    sig.extend_from_slice(&SIG3[5..]);
    assert!(scan_class(&sig_class(&sig, &[op::RETURN])));
    assert!(!scan_class(&sig_class(&[Sig::Int(op::BIPUSH, 99)], &[])));
}

#[test]
fn clean_class_is_clean() {
    let bytes = class_with("a/B", |cw| {
        let s = cw.string("hello");
        let m = cw.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V");
        Code::new().ldc(s).invokevirtual(m).ret()
    });
    assert!(!scan_class(&bytes));
}

#[test]
fn malformed_and_very_new_classes() {
    assert!(!scan_class(&[]));
    assert!(!scan_class(b"not a class at all"));
    let good = sig_class(&SIG1, &[op::RETURN]);
    for n in [4, 10, good.len() / 2, good.len() - 1] {
        assert!(!scan_class(&good[..n]));
    }
    // A class version far beyond any JDK the scanner knows is still scanned.
    let mut cw = ClassWriter::new("Future");
    cw.major = 250;
    let mut code = Code::new();
    for s in SIG1.iter() {
        code = emit(&mut cw, code, s);
    }
    cw.method("run", "()V", code.ret());
    assert!(scan_class(&cw.finish()));
}

#[test]
fn jar_scan_continues_past_a_truncated_class() {
    let tmp = tempfile::tempdir().unwrap();
    let infected = sig_class(&SIG2, &[op::RETURN]);
    let jar = build_jar(
        tmp.path(),
        "x.jar",
        &[
            ("a/Truncated.class", &infected[..20]),
            ("README.CLASS", &infected), // case-sensitive suffix: not scanned
            ("b/Infected.class", &infected),
        ],
    );
    assert!(scan_jar(&jar, &|_| {}).unwrap());

    let only_upper = build_jar(tmp.path(), "y.jar", &[("Infected.CLASS", &infected)]);
    assert!(!scan_jar(&only_upper, &|_| {}).unwrap());
}

fn empty_env(tmp: &Path) -> Stage2Env {
    Stage2Env {
        appdata: Some(tmp.join("appdata")),
        home: Some(tmp.join("home")),
        is_linux: true,
    }
}

#[test]
fn run_finds_infected_jars_and_honors_safe_exclusions_only() {
    let tmp = tempfile::tempdir().unwrap();
    let pack = tmp.path().join("pack");
    let infected = sig_class(&SIG1, &[op::RETURN]);
    let clean = sig_class(&SIG1[..3], &[op::RETURN]);
    let evil = build_jar(&pack.join("mods"), "evil.jar", &[("E.class", &infected)]);
    build_jar(&pack.join("mods"), "clean.jar", &[("C.class", &clean)]);
    build_jar(
        &pack.join("custom").join("sub"),
        "skipped.jar",
        &[("E.class", &infected)],
    );
    let lib = build_jar(
        &pack.join("libraries"),
        "lib.jar",
        &[("E.class", &infected)],
    );
    std::fs::write(pack.join("mods").join("notes.txt"), b"x").unwrap();

    let lines = Mutex::new(Vec::<String>::new());
    let progress = Mutex::new(Vec::<(f64, f64)>::new());
    let log = |s: &str| lines.lock().unwrap().push(s.to_owned());
    let prog = |a: f64, b: f64| progress.lock().unwrap().push((a, b));
    let env = empty_env(tmp.path());
    let results = run_with_env(
        2,
        &pack,
        true,
        &["mods", "custom\\sub"],
        &log,
        Some(&prog),
        &CancelToken::new(),
        &env,
    )
    .unwrap();

    let mut expected = vec![
        lib.to_string_lossy().into_owned(),
        evil.to_string_lossy().into_owned(),
    ];
    expected.sort();
    let mut got = results.stage1.clone();
    got.sort();
    assert_eq!(
        got, expected,
        "mods cannot be excluded; libraries is scanned by Neko"
    );
    assert!(results.stage2.is_empty());

    let progress = progress.into_inner().unwrap();
    assert_eq!(progress.len(), 4, "every non-excluded file counts");
    assert_eq!(progress.last(), Some(&(4.0, 4.0)));
    let lines = lines.into_inner().unwrap();
    assert!(lines.iter().any(|l| l.contains("evil.jar")));
    assert!(lines.iter().all(|l| !l.contains('\u{1b}')));
}

#[test]
fn run_rejects_bad_arguments_and_honors_cancel() {
    let tmp = tempfile::tempdir().unwrap();
    let env = empty_env(tmp.path());
    let c = CancelToken::new();
    let none: [&str; 0] = [];
    let r = run_with_env(
        1,
        &tmp.path().join("missing"),
        true,
        &none,
        &|_| {},
        None,
        &c,
        &env,
    );
    assert!(matches!(r, Err(NekoError::DirMissing(_))));
    let file = tmp.path().join("f.txt");
    std::fs::write(&file, b"x").unwrap();
    let r = run_with_env(1, &file, true, &none, &|_| {}, None, &c, &env);
    assert!(matches!(r, Err(NekoError::NotADirectory(_))));
    let r = run_with_env(0, tmp.path(), true, &none, &|_| {}, None, &c, &env);
    assert!(matches!(r, Err(NekoError::NoThreads)));
    c.cancel();
    let r = run_with_env(1, tmp.path(), true, &none, &|_| {}, None, &c, &env);
    assert!(matches!(r, Err(NekoError::Cancelled)));
}

#[test]
fn stage2_checks_the_real_startup_folder_and_home() {
    let tmp = tempfile::tempdir().unwrap();
    let env = empty_env(tmp.path());
    assert!(check_stage2(&env).is_empty());

    let appdata = env.appdata.clone().unwrap();
    let startup = appdata
        .join("Microsoft")
        .join("Windows")
        .join("Start Menu")
        .join("Programs")
        .join("Startup");
    std::fs::create_dir_all(&startup).unwrap();
    std::fs::write(startup.join("run.bat"), b"x").unwrap();
    std::fs::write(startup.join("libWebGL64.jar"), b"x").unwrap();
    std::fs::write(startup.join("harmless.lnk"), b"x").unwrap();
    std::fs::write(startup.join("RUN.BAT.txt"), b"x").unwrap();
    std::fs::create_dir_all(appdata.join("Microsoft Edge")).unwrap();
    let lib = env.home.clone().unwrap().join(".config").join(".data");
    std::fs::create_dir_all(&lib).unwrap();
    std::fs::write(lib.join("lib.jar"), b"x").unwrap();

    let found = check_stage2(&env);
    let names: Vec<String> = found
        .iter()
        .map(|p| {
            Path::new(p)
                .file_name()
                .unwrap()
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    assert_eq!(
        names,
        vec!["Microsoft Edge", "libWebGL64.jar", "run.bat", "lib.jar"]
    );
    assert!(found.iter().all(|p| Path::new(p).is_absolute()));

    // Without the Linux flag, the home check does not run.
    let not_linux = Stage2Env {
        is_linux: false,
        ..env.clone()
    };
    assert_eq!(check_stage2(&not_linux).len(), 3);

    // Without the Startup folder, the Edge folder alone is not flagged (as Java).
    std::fs::remove_dir_all(appdata.join("Microsoft")).unwrap();
    assert_eq!(check_stage2(&not_linux), Vec::<String>::new());
}

#[test]
fn stage2_falls_back_to_home_appdata_roaming() {
    // Java built "null\Microsoft Edge" when APPDATA was unset; the fix uses the fallback folder.
    let tmp = tempfile::tempdir().unwrap();
    let home = tmp.path().join("home");
    let roaming = home.join("AppData").join("Roaming");
    std::fs::create_dir_all(
        roaming
            .join("Microsoft")
            .join("Windows")
            .join("Start Menu")
            .join("Programs")
            .join("Startup"),
    )
    .unwrap();
    std::fs::create_dir_all(roaming.join("Microsoft Edge")).unwrap();
    let env = Stage2Env {
        appdata: None,
        home: Some(home),
        is_linux: false,
    };
    let found = check_stage2(&env);
    assert_eq!(found.len(), 1);
    assert!(found[0].ends_with("Microsoft Edge"));
}

#[test]
fn stage2_ignores_cwd_relative_paths() {
    // The Java bug looked at "Microsoft\Windows\Start Menu\Programs\Startup" and
    // "~/.config/.data/lib.jar" relative to the working directory. With an env pointing at empty
    // folders nothing is found whatever the working directory holds.
    let tmp = tempfile::tempdir().unwrap();
    let env = empty_env(tmp.path());
    assert!(check_stage2(&env).is_empty());
    let none = Stage2Env::default();
    assert!(check_stage2(&none).is_empty());
}
