// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A minimal JVM class-file reader: the part of ASM the two scanners need.
//!
//! [`parse`] reads the constant pool, the class name, and every method's `Code` bytecode, and
//! decodes the bytecode into [`Insn`]s with the same real-instruction sequence ASM's tree API
//! produces (ASM's label, line-number and frame pseudo-nodes are simply never created):
//!
//! * `LDC_W` becomes `LDC`, `GOTO_W` becomes `GOTO`, `JSR_W` becomes `JSR`;
//! * a `wide` prefix is folded into the instruction it widens;
//! * `ILOAD_0` .. `ASTORE_3` become `ILOAD` .. `ASTORE` with the slot as operand.
//!
//! Unlike ASM the major version is **not** checked, so a class compiled for a JDK newer than the
//! scanner is still scanned (ASM rejecting it was a blind spot). Field bodies and attributes other
//! than `Code` are skipped. Every read is bounds-checked: a malformed class gives a
//! [`ClassError`], never a panic.

use thiserror::Error;

/// Why a class could not be read. The scanners treat every error as "skip this class".
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum ClassError {
    /// The file does not start with `0xCAFEBABE`.
    #[error("not a class file (bad magic)")]
    BadMagic,
    /// The data ended in the middle of a structure.
    #[error("truncated class file")]
    Truncated,
    /// A constant-pool entry has an unknown tag.
    #[error("unknown constant-pool tag {0}")]
    BadConstantTag(u8),
    /// A constant-pool index is out of range or names an entry of the wrong kind.
    #[error("bad constant-pool reference {0}")]
    BadConstantIndex(u16),
    /// The bytecode contains an opcode the JVM does not define.
    #[error("unknown opcode {op:#04x} at {pc}")]
    BadOpcode { op: u8, pc: usize },
    /// A `tableswitch` / `lookupswitch` has an impossible shape.
    #[error("malformed switch at {0}")]
    BadSwitch(usize),
}

/// JVM opcodes the scanners and the decoder refer to by name.
pub mod op {
    pub const ICONST_M1: u8 = 0x02;
    pub const ICONST_0: u8 = 0x03;
    pub const ICONST_1: u8 = 0x04;
    pub const ICONST_2: u8 = 0x05;
    pub const ICONST_3: u8 = 0x06;
    pub const ICONST_4: u8 = 0x07;
    pub const ICONST_5: u8 = 0x08;
    pub const BIPUSH: u8 = 0x10;
    pub const SIPUSH: u8 = 0x11;
    pub const LDC: u8 = 0x12;
    pub const LDC_W: u8 = 0x13;
    pub const LDC2_W: u8 = 0x14;
    pub const ILOAD: u8 = 0x15;
    pub const ALOAD: u8 = 0x19;
    pub const ISTORE: u8 = 0x36;
    pub const ASTORE: u8 = 0x3a;
    pub const BASTORE: u8 = 0x54;
    pub const POP: u8 = 0x57;
    pub const DUP: u8 = 0x59;
    pub const IINC: u8 = 0x84;
    pub const GOTO: u8 = 0xa7;
    pub const JSR: u8 = 0xa8;
    pub const RET: u8 = 0xa9;
    pub const TABLESWITCH: u8 = 0xaa;
    pub const LOOKUPSWITCH: u8 = 0xab;
    pub const RETURN: u8 = 0xb1;
    pub const GETSTATIC: u8 = 0xb2;
    pub const PUTFIELD: u8 = 0xb5;
    pub const INVOKEVIRTUAL: u8 = 0xb6;
    pub const INVOKESPECIAL: u8 = 0xb7;
    pub const INVOKESTATIC: u8 = 0xb8;
    pub const INVOKEINTERFACE: u8 = 0xb9;
    pub const INVOKEDYNAMIC: u8 = 0xba;
    pub const NEW: u8 = 0xbb;
    pub const NEWARRAY: u8 = 0xbc;
    pub const ANEWARRAY: u8 = 0xbd;
    pub const ATHROW: u8 = 0xbf;
    pub const CHECKCAST: u8 = 0xc0;
    pub const INSTANCEOF: u8 = 0xc1;
    pub const WIDE: u8 = 0xc4;
    pub const MULTIANEWARRAY: u8 = 0xc5;
    pub const IFNULL: u8 = 0xc6;
    pub const IFNONNULL: u8 = 0xc7;
    pub const GOTO_W: u8 = 0xc8;
    pub const JSR_W: u8 = 0xc9;
}

/// One constant-pool entry. Indices are raw `u16` pool indices.
#[derive(Debug, Clone, PartialEq)]
pub enum Constant {
    /// Slot 0, and the second slot of a `Long` / `Double`.
    Unusable,
    Utf8(Box<str>),
    Integer(i32),
    Float(f32),
    Long(i64),
    Double(f64),
    Class(u16),
    String(u16),
    Fieldref(u16, u16),
    Methodref(u16, u16),
    InterfaceMethodref(u16, u16),
    NameAndType(u16, u16),
    MethodHandle(u8, u16),
    MethodType(u16),
    Dynamic(u16, u16),
    InvokeDynamic(u16, u16),
    Module(u16),
    Package(u16),
}

/// A decoded instruction operand. Constant-pool operands were validated while decoding, so the
/// `ClassFile` resolvers succeed for them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Operand {
    /// No operand.
    None,
    /// `BIPUSH` / `SIPUSH` value, or `NEWARRAY` element type.
    Int(i32),
    /// A local-variable slot (`xLOAD`, `xSTORE`, `RET`).
    Var(u16),
    /// `IINC` slot and delta.
    Iinc { var: u16, delta: i16 },
    /// A branch offset relative to the instruction.
    Jump(i32),
    /// A constant-pool index: `LDC*`, `NEW`, `ANEWARRAY`, `CHECKCAST`, `INSTANCEOF`, field and
    /// method instructions, `INVOKEDYNAMIC`.
    Cp(u16),
    /// `MULTIANEWARRAY` class and dimension count.
    MultiANewArray { cp: u16, dims: u8 },
    /// `TABLESWITCH` / `LOOKUPSWITCH` (targets are not needed by the scanners).
    Switch,
}

/// One real instruction, normalised as described in the module docs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Insn {
    /// Normalised opcode.
    pub op: u8,
    /// Decoded operand.
    pub operand: Operand,
}

/// A resolved field or method reference.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MemberRef<'a> {
    /// Internal name of the owner class (`java/lang/String`).
    pub owner: &'a str,
    /// Member name.
    pub name: &'a str,
    /// Member descriptor.
    pub desc: &'a str,
}

/// A method and its decoded instructions (empty for `abstract` / `native` methods).
#[derive(Debug, Clone, PartialEq)]
pub struct Method {
    pub access: u16,
    pub name: String,
    pub descriptor: String,
    pub code: Vec<Insn>,
}

/// A parsed class file.
#[derive(Debug, Clone, PartialEq)]
pub struct ClassFile {
    pub minor_version: u16,
    pub major_version: u16,
    pub access: u16,
    /// Internal name of this class (`com/example/Foo`).
    pub name: String,
    pub constants: Vec<Constant>,
    pub methods: Vec<Method>,
}

impl ClassFile {
    fn constant(&self, idx: u16) -> Option<&Constant> {
        self.constants.get(idx as usize)
    }

    /// The text of a `Utf8` entry.
    pub fn utf8(&self, idx: u16) -> Option<&str> {
        utf8_in(&self.constants, idx)
    }

    /// The internal name a `Class` entry names.
    pub fn class_name(&self, idx: u16) -> Option<&str> {
        class_name_in(&self.constants, idx)
    }

    /// Resolves a `Fieldref`, `Methodref` or `InterfaceMethodref`.
    pub fn member_ref(&self, idx: u16) -> Option<MemberRef<'_>> {
        member_ref_in(&self.constants, idx)
    }

    /// The string an `LDC` of a `CONSTANT_String` loads; `None` for any other constant.
    pub fn ldc_string(&self, idx: u16) -> Option<&str> {
        match self.constant(idx)? {
            Constant::String(s) => self.utf8(*s),
            _ => None,
        }
    }
}

fn utf8_in(cp: &[Constant], idx: u16) -> Option<&str> {
    match cp.get(idx as usize)? {
        Constant::Utf8(s) => Some(s),
        _ => None,
    }
}

fn class_name_in(cp: &[Constant], idx: u16) -> Option<&str> {
    match cp.get(idx as usize)? {
        Constant::Class(n) => utf8_in(cp, *n),
        _ => None,
    }
}

fn name_and_type_in(cp: &[Constant], idx: u16) -> Option<(&str, &str)> {
    match cp.get(idx as usize)? {
        Constant::NameAndType(n, d) => Some((utf8_in(cp, *n)?, utf8_in(cp, *d)?)),
        _ => None,
    }
}

fn member_ref_in(cp: &[Constant], idx: u16) -> Option<MemberRef<'_>> {
    let (class, nat) = match cp.get(idx as usize)? {
        Constant::Fieldref(c, n)
        | Constant::Methodref(c, n)
        | Constant::InterfaceMethodref(c, n) => (*c, *n),
        _ => return None,
    };
    let owner = class_name_in(cp, class)?;
    let (name, desc) = name_and_type_in(cp, nat)?;
    Some(MemberRef { owner, name, desc })
}

/// A bounds-checked big-endian reader.
struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(b: &'a [u8]) -> Self {
        Reader { b, pos: 0 }
    }

    fn bytes(&mut self, n: usize) -> Result<&'a [u8], ClassError> {
        let end = self.pos.checked_add(n).ok_or(ClassError::Truncated)?;
        let s = self.b.get(self.pos..end).ok_or(ClassError::Truncated)?;
        self.pos = end;
        Ok(s)
    }

    fn u1(&mut self) -> Result<u8, ClassError> {
        Ok(self.bytes(1)?[0])
    }

    fn u2(&mut self) -> Result<u16, ClassError> {
        let s = self.bytes(2)?;
        Ok(u16::from_be_bytes([s[0], s[1]]))
    }

    fn u4(&mut self) -> Result<u32, ClassError> {
        let s = self.bytes(4)?;
        Ok(u32::from_be_bytes([s[0], s[1], s[2], s[3]]))
    }

    fn u8(&mut self) -> Result<u64, ClassError> {
        let s = self.bytes(8)?;
        let mut a = [0u8; 8];
        a.copy_from_slice(s);
        Ok(u64::from_be_bytes(a))
    }

    fn skip_attributes(&mut self) -> Result<(), ClassError> {
        let count = self.u2()?;
        for _ in 0..count {
            self.u2()?;
            let len = self.u4()? as usize;
            self.bytes(len)?;
        }
        Ok(())
    }
}

/// Decodes Java's "modified UTF-8" leniently, as ASM's `readUtf` does (malformed sequences
/// become U+FFFD instead of failing).
pub fn decode_modified_utf8(b: &[u8]) -> String {
    if b.iter().all(|&c| c != 0 && c < 0x80) {
        // Pure ASCII without NUL: identical in both encodings.
        return String::from_utf8_lossy(b).into_owned();
    }
    let mut units: Vec<u16> = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        i += 1;
        if c & 0x80 == 0 {
            units.push(u16::from(c));
        } else if c & 0xE0 == 0xC0 {
            match b.get(i) {
                Some(&c2) => {
                    i += 1;
                    units.push((u16::from(c & 0x1F) << 6) | u16::from(c2 & 0x3F));
                }
                None => units.push(0xFFFD),
            }
        } else {
            match (b.get(i), b.get(i + 1)) {
                (Some(&c2), Some(&c3)) => {
                    i += 2;
                    units.push(
                        (u16::from(c & 0x0F) << 12)
                            | (u16::from(c2 & 0x3F) << 6)
                            | u16::from(c3 & 0x3F),
                    );
                }
                _ => {
                    i = b.len();
                    units.push(0xFFFD);
                }
            }
        }
    }
    String::from_utf16_lossy(&units)
}

fn read_constant_pool(r: &mut Reader<'_>) -> Result<Vec<Constant>, ClassError> {
    let count = r.u2()? as usize;
    let mut cp = Vec::with_capacity(count.max(1));
    cp.push(Constant::Unusable);
    while cp.len() < count {
        let tag = r.u1()?;
        let c = match tag {
            1 => {
                let len = r.u2()? as usize;
                Constant::Utf8(decode_modified_utf8(r.bytes(len)?).into_boxed_str())
            }
            3 => Constant::Integer(r.u4()? as i32),
            4 => Constant::Float(f32::from_bits(r.u4()?)),
            5 => Constant::Long(r.u8()? as i64),
            6 => Constant::Double(f64::from_bits(r.u8()?)),
            7 => Constant::Class(r.u2()?),
            8 => Constant::String(r.u2()?),
            9 => Constant::Fieldref(r.u2()?, r.u2()?),
            10 => Constant::Methodref(r.u2()?, r.u2()?),
            11 => Constant::InterfaceMethodref(r.u2()?, r.u2()?),
            12 => Constant::NameAndType(r.u2()?, r.u2()?),
            15 => Constant::MethodHandle(r.u1()?, r.u2()?),
            16 => Constant::MethodType(r.u2()?),
            17 => Constant::Dynamic(r.u2()?, r.u2()?),
            18 => Constant::InvokeDynamic(r.u2()?, r.u2()?),
            19 => Constant::Module(r.u2()?),
            20 => Constant::Package(r.u2()?),
            other => return Err(ClassError::BadConstantTag(other)),
        };
        let wide = matches!(c, Constant::Long(_) | Constant::Double(_));
        cp.push(c);
        if wide {
            // Long and Double take two slots; the second is unusable.
            cp.push(Constant::Unusable);
        }
    }
    cp.truncate(count.max(1));
    Ok(cp)
}

/// Parses a class file. See the module docs for what is read.
pub fn parse(bytes: &[u8]) -> Result<ClassFile, ClassError> {
    let mut r = Reader::new(bytes);
    if r.u4()? != 0xCAFE_BABE {
        return Err(ClassError::BadMagic);
    }
    let minor_version = r.u2()?;
    let major_version = r.u2()?;
    let constants = read_constant_pool(&mut r)?;
    let access = r.u2()?;
    let this_class = r.u2()?;
    let name = class_name_in(&constants, this_class)
        .ok_or(ClassError::BadConstantIndex(this_class))?
        .to_owned();
    let _super = r.u2()?;
    let interfaces = r.u2()? as usize;
    r.bytes(interfaces.checked_mul(2).ok_or(ClassError::Truncated)?)?;

    // Fields: skipped.
    let fields = r.u2()?;
    for _ in 0..fields {
        r.bytes(6)?;
        r.skip_attributes()?;
    }

    let method_count = r.u2()?;
    let mut methods = Vec::with_capacity(method_count as usize);
    for _ in 0..method_count {
        let m_access = r.u2()?;
        let name_idx = r.u2()?;
        let desc_idx = r.u2()?;
        let m_name = utf8_in(&constants, name_idx)
            .ok_or(ClassError::BadConstantIndex(name_idx))?
            .to_owned();
        let m_desc = utf8_in(&constants, desc_idx)
            .ok_or(ClassError::BadConstantIndex(desc_idx))?
            .to_owned();
        let mut code = Vec::new();
        let attr_count = r.u2()?;
        for _ in 0..attr_count {
            let attr_name = r.u2()?;
            let len = r.u4()? as usize;
            let body = r.bytes(len)?;
            if utf8_in(&constants, attr_name) == Some("Code") {
                code = read_code_attribute(body, &constants)?;
            }
        }
        methods.push(Method {
            access: m_access,
            name: m_name,
            descriptor: m_desc,
            code,
        });
    }
    // Class attributes: bounds-checked like ASM does, otherwise unused.
    r.skip_attributes()?;

    Ok(ClassFile {
        minor_version,
        major_version,
        access,
        name,
        constants,
        methods,
    })
}

fn read_code_attribute(body: &[u8], cp: &[Constant]) -> Result<Vec<Insn>, ClassError> {
    let mut r = Reader::new(body);
    r.u2()?; // max_stack
    r.u2()?; // max_locals
    let len = r.u4()? as usize;
    let code = r.bytes(len)?;
    let handlers = r.u2()? as usize;
    r.bytes(handlers.checked_mul(8).ok_or(ClassError::Truncated)?)?;
    r.skip_attributes()?;
    decode_bytecode(code, cp)
}

fn check_cp(cp: &[Constant], idx: u16, ok: impl Fn(&Constant) -> bool) -> Result<u16, ClassError> {
    match cp.get(idx as usize) {
        Some(c) if ok(c) => Ok(idx),
        _ => Err(ClassError::BadConstantIndex(idx)),
    }
}

fn class_operand(cp: &[Constant], idx: u16) -> Result<u16, ClassError> {
    class_name_in(cp, idx)
        .map(|_| idx)
        .ok_or(ClassError::BadConstantIndex(idx))
}

fn member_operand(cp: &[Constant], idx: u16) -> Result<u16, ClassError> {
    member_ref_in(cp, idx)
        .map(|_| idx)
        .ok_or(ClassError::BadConstantIndex(idx))
}

fn loadable(c: &Constant) -> bool {
    matches!(
        c,
        Constant::Integer(_)
            | Constant::Float(_)
            | Constant::String(_)
            | Constant::Class(_)
            | Constant::MethodType(_)
            | Constant::MethodHandle(..)
            | Constant::Dynamic(..)
    )
}

fn ldc_operand(cp: &[Constant], idx: u16) -> Result<u16, ClassError> {
    let idx = check_cp(cp, idx, loadable)?;
    if let Some(Constant::String(s)) = cp.get(idx as usize) {
        utf8_in(cp, *s).ok_or(ClassError::BadConstantIndex(*s))?;
    }
    Ok(idx)
}

/// Decodes a method's bytecode into normalised real instructions.
pub fn decode_bytecode(code: &[u8], cp: &[Constant]) -> Result<Vec<Insn>, ClassError> {
    let mut out = Vec::with_capacity(code.len() / 2);
    let mut r = Reader::new(code);
    while r.pos < code.len() {
        let pc = r.pos;
        let raw = r.u1()?;
        let insn = match raw {
            0x00..=0x0f => plain(raw),
            op::BIPUSH => Insn {
                op: raw,
                operand: Operand::Int(i32::from(r.u1()? as i8)),
            },
            op::SIPUSH => Insn {
                op: raw,
                operand: Operand::Int(i32::from(r.u2()? as i16)),
            },
            op::LDC => {
                let idx = u16::from(r.u1()?);
                Insn {
                    op: op::LDC,
                    operand: Operand::Cp(ldc_operand(cp, idx)?),
                }
            }
            op::LDC_W => {
                let idx = r.u2()?;
                Insn {
                    op: op::LDC,
                    operand: Operand::Cp(ldc_operand(cp, idx)?),
                }
            }
            op::LDC2_W => {
                let idx = r.u2()?;
                let idx = check_cp(cp, idx, |c| {
                    matches!(
                        c,
                        Constant::Long(_) | Constant::Double(_) | Constant::Dynamic(..)
                    )
                })?;
                Insn {
                    op: raw,
                    operand: Operand::Cp(idx),
                }
            }
            0x15..=0x19 | 0x36..=0x3a | op::RET => Insn {
                op: raw,
                operand: Operand::Var(u16::from(r.u1()?)),
            },
            0x1a..=0x2d => Insn {
                op: op::ILOAD + (raw - 0x1a) / 4,
                operand: Operand::Var(u16::from((raw - 0x1a) % 4)),
            },
            0x3b..=0x4e => Insn {
                op: op::ISTORE + (raw - 0x3b) / 4,
                operand: Operand::Var(u16::from((raw - 0x3b) % 4)),
            },
            0x2e..=0x35 | 0x4f..=0x83 | 0x85..=0x98 | 0xac..=0xb1 | 0xbe | 0xbf | 0xc2 | 0xc3 => {
                plain(raw)
            }
            op::IINC => Insn {
                op: raw,
                operand: Operand::Iinc {
                    var: u16::from(r.u1()?),
                    delta: i16::from(r.u1()? as i8),
                },
            },
            0x99..=0xa8 | op::IFNULL | op::IFNONNULL => Insn {
                op: raw,
                operand: Operand::Jump(i32::from(r.u2()? as i16)),
            },
            op::GOTO_W | op::JSR_W => Insn {
                op: if raw == op::GOTO_W { op::GOTO } else { op::JSR },
                operand: Operand::Jump(r.u4()? as i32),
            },
            op::TABLESWITCH | op::LOOKUPSWITCH => {
                // Padding to a 4-byte boundary relative to the start of the code.
                let pad = (4 - (r.pos % 4)) % 4;
                r.bytes(pad)?;
                let _default = r.u4()?;
                if raw == op::TABLESWITCH {
                    let low = r.u4()? as i32;
                    let high = r.u4()? as i32;
                    if high < low {
                        return Err(ClassError::BadSwitch(pc));
                    }
                    let n = (i64::from(high) - i64::from(low) + 1) as usize;
                    r.bytes(n.checked_mul(4).ok_or(ClassError::BadSwitch(pc))?)?;
                } else {
                    let pairs = r.u4()? as i32;
                    if pairs < 0 {
                        return Err(ClassError::BadSwitch(pc));
                    }
                    r.bytes(
                        (pairs as usize)
                            .checked_mul(8)
                            .ok_or(ClassError::BadSwitch(pc))?,
                    )?;
                }
                Insn {
                    op: raw,
                    operand: Operand::Switch,
                }
            }
            0xb2..=0xb5 | op::INVOKEVIRTUAL | op::INVOKESPECIAL | op::INVOKESTATIC => {
                let idx = r.u2()?;
                Insn {
                    op: raw,
                    operand: Operand::Cp(member_operand(cp, idx)?),
                }
            }
            op::INVOKEINTERFACE => {
                let idx = r.u2()?;
                r.u1()?; // count
                r.u1()?; // 0
                Insn {
                    op: raw,
                    operand: Operand::Cp(member_operand(cp, idx)?),
                }
            }
            op::INVOKEDYNAMIC => {
                let idx = r.u2()?;
                r.u2()?; // 0, 0
                Insn {
                    op: raw,
                    operand: Operand::Cp(check_cp(cp, idx, |c| {
                        matches!(c, Constant::InvokeDynamic(..))
                    })?),
                }
            }
            op::NEW | op::ANEWARRAY | op::CHECKCAST | op::INSTANCEOF => {
                let idx = r.u2()?;
                Insn {
                    op: raw,
                    operand: Operand::Cp(class_operand(cp, idx)?),
                }
            }
            op::NEWARRAY => Insn {
                op: raw,
                operand: Operand::Int(i32::from(r.u1()?)),
            },
            op::MULTIANEWARRAY => {
                let idx = r.u2()?;
                let dims = r.u1()?;
                Insn {
                    op: raw,
                    operand: Operand::MultiANewArray {
                        cp: class_operand(cp, idx)?,
                        dims,
                    },
                }
            }
            op::WIDE => {
                let inner = r.u1()?;
                match inner {
                    0x15..=0x19 | 0x36..=0x3a | op::RET => Insn {
                        op: inner,
                        operand: Operand::Var(r.u2()?),
                    },
                    op::IINC => Insn {
                        op: inner,
                        operand: Operand::Iinc {
                            var: r.u2()?,
                            delta: r.u2()? as i16,
                        },
                    },
                    other => return Err(ClassError::BadOpcode { op: other, pc }),
                }
            }
            other => return Err(ClassError::BadOpcode { op: other, pc }),
        };
        out.push(insn);
    }
    Ok(out)
}

fn plain(op: u8) -> Insn {
    Insn {
        op,
        operand: Operand::None,
    }
}

#[cfg(test)]
#[path = "../tests/common/classwriter.rs"]
#[allow(dead_code)]
mod classwriter;

#[cfg(test)]
mod tests {
    use super::classwriter::{ClassWriter, Code};
    use super::*;

    #[test]
    fn parses_name_methods_and_resolves_operands() {
        let mut cw = ClassWriter::new("com/example/Foo");
        let s = cw.string("hello");
        let m = cw.methodref("java/io/PrintStream", "println", "(Ljava/lang/String;)V");
        let f = cw.fieldref("java/lang/System", "out", "Ljava/io/PrintStream;");
        let code = Code::new().getstatic(f).ldc(s).invokevirtual(m).ret();
        cw.method("main", "([Ljava/lang/String;)V", code);
        let bytes = cw.finish();

        let class = parse(&bytes).expect("parse");
        assert_eq!(class.name, "com/example/Foo");
        assert_eq!(class.methods.len(), 1);
        let main = &class.methods[0];
        assert_eq!(main.name, "main");
        let ops: Vec<u8> = main.code.iter().map(|i| i.op).collect();
        assert_eq!(
            ops,
            vec![op::GETSTATIC, op::LDC, op::INVOKEVIRTUAL, op::RETURN]
        );
        let Operand::Cp(ldc) = main.code[1].operand else {
            panic!("ldc operand")
        };
        assert_eq!(class.ldc_string(ldc), Some("hello"));
        let Operand::Cp(inv) = main.code[2].operand else {
            panic!("invoke operand")
        };
        let mr = class.member_ref(inv).expect("member");
        assert_eq!(mr.owner, "java/io/PrintStream");
        assert_eq!(mr.name, "println");
        assert_eq!(mr.desc, "(Ljava/lang/String;)V");
    }

    #[test]
    fn normalises_wide_ldc_w_load_n_and_goto_w() {
        let mut cw = ClassWriter::new("A");
        let s = cw.string("x");
        let code = Code::new()
            .raw(&[op::LDC_W])
            .u2(s)
            .raw(&[0x2a]) // aload_0
            .raw(&[0x3c]) // istore_1
            .raw(&[op::WIDE, op::ILOAD])
            .u2(300)
            .raw(&[op::WIDE, op::IINC])
            .u2(5)
            .u2(0xFFFF)
            .raw(&[op::GOTO_W, 0, 0, 0, 0])
            .ret();
        cw.method("m", "()V", code);
        let class = parse(&cw.finish()).unwrap();
        let code = &class.methods[0].code;
        assert_eq!(code[0].op, op::LDC);
        assert_eq!(
            code[1],
            Insn {
                op: op::ALOAD,
                operand: Operand::Var(0)
            }
        );
        assert_eq!(
            code[2],
            Insn {
                op: op::ISTORE,
                operand: Operand::Var(1)
            }
        );
        assert_eq!(
            code[3],
            Insn {
                op: op::ILOAD,
                operand: Operand::Var(300)
            }
        );
        assert_eq!(
            code[4],
            Insn {
                op: op::IINC,
                operand: Operand::Iinc { var: 5, delta: -1 }
            }
        );
        assert_eq!(code[5].op, op::GOTO);
        assert_eq!(code[6].op, op::RETURN);
        assert_eq!(code.len(), 7);
    }

    #[test]
    fn decodes_switch_padding() {
        let mut cw = ClassWriter::new("A");
        // pc 0: iconst_0; pc 1: tableswitch, pad to 4 (2 bytes), default, low 0, high 1, 2 offsets
        let mut code = Code::new().raw(&[op::ICONST_0, op::TABLESWITCH, 0, 0]);
        for v in [0i32, 0, 1, 0, 0] {
            code = code.raw(&v.to_be_bytes());
        }
        // iconst_0 at pc 24, lookupswitch at pc 25: pad 2 bytes, default, npairs 1, one pair
        code = code.raw(&[op::ICONST_0, op::LOOKUPSWITCH, 0, 0]);
        for v in [0i32, 1, 7, 0] {
            code = code.raw(&v.to_be_bytes());
        }
        cw.method("m", "()V", code.ret());
        let class = parse(&cw.finish()).unwrap();
        let ops: Vec<u8> = class.methods[0].code.iter().map(|i| i.op).collect();
        assert_eq!(
            ops,
            vec![
                op::ICONST_0,
                op::TABLESWITCH,
                op::ICONST_0,
                op::LOOKUPSWITCH,
                op::RETURN
            ]
        );
    }

    #[test]
    fn long_and_double_take_two_slots() {
        let mut cw = ClassWriter::new("A");
        let l = cw.long(42);
        let after = cw.string("after");
        assert_eq!(after, l + 3, "Long uses two slots, then Utf8 + String");
        let code = Code::new()
            .raw(&[op::LDC2_W])
            .u2(l)
            .raw(&[op::POP])
            .ldc(after)
            .ret();
        cw.method("m", "()V", code);
        let class = parse(&cw.finish()).unwrap();
        let Operand::Cp(i) = class.methods[0].code[2].operand else {
            panic!()
        };
        assert_eq!(class.ldc_string(i), Some("after"));
    }

    #[test]
    fn very_new_major_version_is_still_parsed() {
        let mut cw = ClassWriter::new("Future");
        cw.major = 200;
        cw.method("m", "()V", Code::new().ret());
        assert_eq!(parse(&cw.finish()).unwrap().major_version, 200);
    }

    #[test]
    fn malformed_inputs_are_errors_not_panics() {
        assert_eq!(parse(&[]), Err(ClassError::Truncated));
        assert_eq!(parse(&[0, 1, 2, 3, 4, 5, 6, 7]), Err(ClassError::BadMagic));
        let mut cw = ClassWriter::new("A");
        cw.method("m", "()V", Code::new().raw(&[0xfe]));
        assert!(matches!(
            parse(&cw.finish()),
            Err(ClassError::BadOpcode { op: 0xfe, .. })
        ));
        // Bad constant reference from an instruction.
        let mut cw = ClassWriter::new("A");
        cw.method("m", "()V", Code::new().raw(&[op::LDC, 200]).ret());
        assert_eq!(parse(&cw.finish()), Err(ClassError::BadConstantIndex(200)));
        // Every truncation of a valid class is an error.
        let mut cw = ClassWriter::new("A");
        let s = cw.string("abc");
        cw.method("m", "()V", Code::new().ldc(s).ret());
        let full = cw.finish();
        for n in 0..full.len() {
            assert!(parse(&full[..n]).is_err(), "truncated at {n}");
        }
    }

    #[test]
    fn random_and_mutated_bytes_never_panic() {
        // A deterministic stand-in for the cargo-fuzz target.
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let mut cw = ClassWriter::new("com/x/Y");
        let s = cw.string("https://example.com/a.b.c");
        let m = cw.methodref("java/awt/Robot", "<init>", "()V");
        let c = cw.class("java/awt/Robot");
        cw.method(
            "m",
            "()V",
            Code::new()
                .new_(c)
                .raw(&[op::DUP])
                .invokespecial(m)
                .ldc(s)
                .raw(&[op::POP, op::POP])
                .ret(),
        );
        let base = cw.finish();
        for _ in 0..20_000 {
            let mut b = base.clone();
            let flips = (next() % 6) as usize + 1;
            for _ in 0..flips {
                let i = (next() as usize) % b.len();
                b[i] = next() as u8;
            }
            let _ = parse(&b);
        }
        for _ in 0..5_000 {
            let len = (next() % 128) as usize;
            let mut b: Vec<u8> = (0..len).map(|_| next() as u8).collect();
            if b.len() >= 4 {
                b[..4].copy_from_slice(&[0xCA, 0xFE, 0xBA, 0xBE]);
            }
            let _ = parse(&b);
        }
    }

    #[test]
    fn modified_utf8_decodes_nul_and_supplementary_chars() {
        assert_eq!(decode_modified_utf8(b"abc"), "abc");
        assert_eq!(decode_modified_utf8(&[0xC0, 0x80]), "\0");
        // U+1F600 as a surrogate pair, each half encoded in 3 bytes.
        let bytes = [0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80];
        assert_eq!(decode_modified_utf8(&bytes), "\u{1F600}");
        assert_eq!(decode_modified_utf8(&[0xE4]), "\u{FFFD}");
    }
}
