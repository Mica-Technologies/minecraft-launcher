// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A tiny, deterministic JVM class-file writer: just enough to build the no-op
//! `text2speech` stub that LWJGL2-on-ARM64 needs (Java built it with ASM at runtime). Class
//! files are version 52 (Java 8); the methods have no branches, so no `StackMapTable` is
//! needed.

use std::collections::HashMap;

/// Class file major version 52 (Java 8).
const MAJOR: u16 = 52;

const ACC_PUBLIC: u16 = 0x0001;
const ACC_STATIC: u16 = 0x0008;
const ACC_SUPER: u16 = 0x0020;
const ACC_INTERFACE: u16 = 0x0200;
const ACC_ABSTRACT: u16 = 0x0400;

#[derive(Clone, PartialEq, Eq, Hash)]
enum Const {
    Utf8(String),
    Class(u16),
    NameAndType(u16, u16),
    Methodref(u16, u16),
}

#[derive(Default)]
struct Pool {
    items: Vec<Const>,
    index: HashMap<Const, u16>,
}

impl Pool {
    fn add(&mut self, c: Const) -> u16 {
        if let Some(i) = self.index.get(&c) {
            return *i;
        }
        self.items.push(c.clone());
        let i = self.items.len() as u16;
        self.index.insert(c, i);
        i
    }
    fn utf8(&mut self, s: &str) -> u16 {
        self.add(Const::Utf8(s.to_string()))
    }
    fn class(&mut self, name: &str) -> u16 {
        let n = self.utf8(name);
        self.add(Const::Class(n))
    }
    fn method_ref(&mut self, owner: &str, name: &str, desc: &str) -> u16 {
        let c = self.class(owner);
        let (n, d) = (self.utf8(name), self.utf8(desc));
        let nt = self.add(Const::NameAndType(n, d));
        self.add(Const::Methodref(c, nt))
    }
    fn write(&self, out: &mut Vec<u8>) {
        u2(out, self.items.len() as u16 + 1);
        for c in &self.items {
            match c {
                Const::Utf8(s) => {
                    out.push(1);
                    u2(out, s.len() as u16);
                    out.extend_from_slice(s.as_bytes());
                }
                Const::Class(n) => {
                    out.push(7);
                    u2(out, *n);
                }
                Const::NameAndType(n, d) => {
                    out.push(12);
                    u2(out, *n);
                    u2(out, *d);
                }
                Const::Methodref(c, nt) => {
                    out.push(10);
                    u2(out, *c);
                    u2(out, *nt);
                }
            }
        }
    }
}

fn u2(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_be_bytes());
}

fn u4(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_be_bytes());
}

/// `(max_stack, max_locals, bytecode)`.
type Code = (u16, u16, Vec<u8>);

/// One method: access flags, name, descriptor, and optional `(max_stack, max_locals, code)`.
struct Method {
    access: u16,
    name: &'static str,
    desc: &'static str,
    code: Option<Code>,
}

fn class_file(
    access: u16,
    this: &str,
    interfaces: &[&str],
    methods: impl FnOnce(&mut Pool) -> Vec<Method>,
) -> Vec<u8> {
    let mut pool = Pool::default();
    let this_i = pool.class(this);
    let super_i = pool.class("java/lang/Object");
    let iface: Vec<u16> = interfaces.iter().map(|i| pool.class(i)).collect();
    let methods = methods(&mut pool);
    let code_attr = pool.utf8("Code");
    let encoded: Vec<(u16, u16, u16, Option<Code>)> = methods
        .into_iter()
        .map(|m| (m.access, pool.utf8(m.name), pool.utf8(m.desc), m.code))
        .collect();

    let mut out = Vec::new();
    u4(&mut out, 0xCAFE_BABE);
    u2(&mut out, 0);
    u2(&mut out, MAJOR);
    pool.write(&mut out);
    u2(&mut out, access);
    u2(&mut out, this_i);
    u2(&mut out, super_i);
    u2(&mut out, iface.len() as u16);
    for i in iface {
        u2(&mut out, i);
    }
    u2(&mut out, 0); // fields
    u2(&mut out, encoded.len() as u16);
    for (access, name, desc, code) in encoded {
        u2(&mut out, access);
        u2(&mut out, name);
        u2(&mut out, desc);
        match code {
            None => u2(&mut out, 0),
            Some((stack, locals, bytes)) => {
                u2(&mut out, 1);
                u2(&mut out, code_attr);
                u4(&mut out, 12 + bytes.len() as u32);
                u2(&mut out, stack);
                u2(&mut out, locals);
                u4(&mut out, bytes.len() as u32);
                out.extend_from_slice(&bytes);
                u2(&mut out, 0); // exception table
                u2(&mut out, 0); // attributes
            }
        }
    }
    u2(&mut out, 0); // class attributes
    out
}

const NARRATOR: &str = "com/mojang/text2speech/Narrator";
const NARRATOR_NOOP: &str = "com/mojang/text2speech/NarratorNoop";

const OP_ALOAD_0: u8 = 0x2a;
const OP_ICONST_0: u8 = 0x03;
const OP_IRETURN: u8 = 0xac;
const OP_ARETURN: u8 = 0xb0;
const OP_RETURN: u8 = 0xb1;
const OP_NEW: u8 = 0xbb;
const OP_DUP: u8 = 0x59;
const OP_INVOKESPECIAL: u8 = 0xb7;

/// `com/mojang/text2speech/Narrator`: the interface with abstract `say`, `clear`, `destroy`,
/// `close`, `active` and a static `getNarrator()` returning a new `NarratorNoop`.
pub fn narrator_class() -> Vec<u8> {
    let abs = |name, desc| Method {
        access: ACC_PUBLIC | ACC_ABSTRACT,
        name,
        desc,
        code: None,
    };
    class_file(
        ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
        NARRATOR,
        &[],
        |pool| {
            let noop = pool.class(NARRATOR_NOOP);
            let init = pool.method_ref(NARRATOR_NOOP, "<init>", "()V");
            let [n1, n2] = noop.to_be_bytes();
            let [i1, i2] = init.to_be_bytes();
            vec![
                abs("say", "(Ljava/lang/String;Z)V"),
                abs("clear", "()V"),
                abs("destroy", "()V"),
                abs("close", "()V"),
                abs("active", "()Z"),
                Method {
                    access: ACC_PUBLIC | ACC_STATIC,
                    name: "getNarrator",
                    desc: "()Lcom/mojang/text2speech/Narrator;",
                    code: Some((
                        2,
                        0,
                        vec![OP_NEW, n1, n2, OP_DUP, OP_INVOKESPECIAL, i1, i2, OP_ARETURN],
                    )),
                },
            ]
        },
    )
}

/// `com/mojang/text2speech/NarratorNoop`: implements `Narrator`; every method does nothing
/// and `active()` is `false`.
pub fn narrator_noop_class() -> Vec<u8> {
    class_file(ACC_PUBLIC | ACC_SUPER, NARRATOR_NOOP, &[NARRATOR], |pool| {
        let obj_init = pool.method_ref("java/lang/Object", "<init>", "()V");
        let [o1, o2] = obj_init.to_be_bytes();
        let ret = |name, desc, locals| Method {
            access: ACC_PUBLIC,
            name,
            desc,
            code: Some((0, locals, vec![OP_RETURN])),
        };
        vec![
            Method {
                access: ACC_PUBLIC,
                name: "<init>",
                desc: "()V",
                code: Some((1, 1, vec![OP_ALOAD_0, OP_INVOKESPECIAL, o1, o2, OP_RETURN])),
            },
            ret("say", "(Ljava/lang/String;Z)V", 3),
            ret("clear", "()V", 1),
            ret("destroy", "()V", 1),
            ret("close", "()V", 1),
            Method {
                access: ACC_PUBLIC,
                name: "active",
                desc: "()Z",
                code: Some((1, 1, vec![OP_ICONST_0, OP_IRETURN])),
            },
        ]
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classes_are_well_formed_and_deterministic() {
        for (bytes, name) in [
            (narrator_class(), NARRATOR),
            (narrator_noop_class(), NARRATOR_NOOP),
        ] {
            assert_eq!(&bytes[..4], &[0xCA, 0xFE, 0xBA, 0xBE]);
            assert_eq!(u16::from_be_bytes([bytes[6], bytes[7]]), 52);
            let text = String::from_utf8_lossy(&bytes);
            assert!(text.contains(name));
            assert!(text.contains("active"));
            assert!(text.ends_with("\0\0"));
        }
        assert_eq!(narrator_class(), narrator_class());
        assert!(String::from_utf8_lossy(&narrator_class()).contains("getNarrator"));
    }
}
