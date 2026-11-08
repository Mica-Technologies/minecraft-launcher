// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! A tiny test-only class-file writer (the Rust stand-in for the ASM `ClassWriter` the Java tests
//! use). It writes a valid class with a constant pool, no fields and methods with a `Code`
//! attribute; max_stack / max_locals are set generously since nothing verifies them.

/// Bytecode under construction.
#[derive(Default, Clone)]
pub struct Code {
    pub bytes: Vec<u8>,
}

impl Code {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn raw(mut self, b: &[u8]) -> Self {
        self.bytes.extend_from_slice(b);
        self
    }

    pub fn u2(mut self, v: u16) -> Self {
        self.bytes.extend_from_slice(&v.to_be_bytes());
        self
    }

    fn op_u2(self, op: u8, v: u16) -> Self {
        self.raw(&[op]).u2(v)
    }

    /// `LDC_W` when the index does not fit in a byte, `LDC` otherwise.
    pub fn ldc(self, idx: u16) -> Self {
        if idx <= 0xff {
            self.raw(&[0x12, idx as u8])
        } else {
            self.op_u2(0x13, idx)
        }
    }

    pub fn new_(self, class_idx: u16) -> Self {
        self.op_u2(0xbb, class_idx)
    }

    pub fn getstatic(self, idx: u16) -> Self {
        self.op_u2(0xb2, idx)
    }

    pub fn invokevirtual(self, idx: u16) -> Self {
        self.op_u2(0xb6, idx)
    }

    pub fn invokespecial(self, idx: u16) -> Self {
        self.op_u2(0xb7, idx)
    }

    pub fn invokestatic(self, idx: u16) -> Self {
        self.op_u2(0xb8, idx)
    }

    pub fn bipush(self, v: i8) -> Self {
        self.raw(&[0x10, v as u8])
    }

    pub fn op(self, op: u8) -> Self {
        self.raw(&[op])
    }

    /// `RETURN`.
    pub fn ret(self) -> Self {
        self.raw(&[0xb1])
    }
}

pub struct ClassWriter {
    pub major: u16,
    cp: Vec<u8>,
    cp_count: u16,
    this_class: u16,
    super_class: u16,
    code_name: u16,
    methods: Vec<u8>,
    method_count: u16,
}

impl ClassWriter {
    pub fn new(internal_name: &str) -> Self {
        let mut cw = ClassWriter {
            major: 61,
            cp: Vec::new(),
            cp_count: 1,
            this_class: 0,
            super_class: 0,
            code_name: 0,
            methods: Vec::new(),
            method_count: 0,
        };
        cw.this_class = cw.class(internal_name);
        cw.super_class = cw.class("java/lang/Object");
        cw.code_name = cw.utf8("Code");
        cw
    }

    fn push(&mut self, bytes: &[u8], slots: u16) -> u16 {
        let idx = self.cp_count;
        self.cp.extend_from_slice(bytes);
        self.cp_count += slots;
        idx
    }

    pub fn utf8(&mut self, s: &str) -> u16 {
        let mut b = vec![1u8];
        b.extend_from_slice(&(s.len() as u16).to_be_bytes());
        b.extend_from_slice(s.as_bytes());
        self.push(&b, 1)
    }

    pub fn class(&mut self, name: &str) -> u16 {
        let n = self.utf8(name);
        let mut b = vec![7u8];
        b.extend_from_slice(&n.to_be_bytes());
        self.push(&b, 1)
    }

    pub fn string(&mut self, s: &str) -> u16 {
        let n = self.utf8(s);
        let mut b = vec![8u8];
        b.extend_from_slice(&n.to_be_bytes());
        self.push(&b, 1)
    }

    pub fn long(&mut self, v: i64) -> u16 {
        let mut b = vec![5u8];
        b.extend_from_slice(&v.to_be_bytes());
        self.push(&b, 2)
    }

    fn name_and_type(&mut self, name: &str, desc: &str) -> u16 {
        let n = self.utf8(name);
        let d = self.utf8(desc);
        let mut b = vec![12u8];
        b.extend_from_slice(&n.to_be_bytes());
        b.extend_from_slice(&d.to_be_bytes());
        self.push(&b, 1)
    }

    fn member(&mut self, tag: u8, owner: &str, name: &str, desc: &str) -> u16 {
        let c = self.class(owner);
        let nt = self.name_and_type(name, desc);
        let mut b = vec![tag];
        b.extend_from_slice(&c.to_be_bytes());
        b.extend_from_slice(&nt.to_be_bytes());
        self.push(&b, 1)
    }

    pub fn fieldref(&mut self, owner: &str, name: &str, desc: &str) -> u16 {
        self.member(9, owner, name, desc)
    }

    pub fn methodref(&mut self, owner: &str, name: &str, desc: &str) -> u16 {
        self.member(10, owner, name, desc)
    }

    pub fn interface_methodref(&mut self, owner: &str, name: &str, desc: &str) -> u16 {
        self.member(11, owner, name, desc)
    }

    /// Adds a `public static` method with the given bytecode.
    pub fn method(&mut self, name: &str, desc: &str, code: Code) {
        let n = self.utf8(name);
        let d = self.utf8(desc);
        let m = &mut self.methods;
        m.extend_from_slice(&0x0009u16.to_be_bytes());
        m.extend_from_slice(&n.to_be_bytes());
        m.extend_from_slice(&d.to_be_bytes());
        m.extend_from_slice(&1u16.to_be_bytes()); // one attribute
        m.extend_from_slice(&self.code_name.to_be_bytes());
        let attr_len = 2 + 2 + 4 + code.bytes.len() + 2 + 2;
        m.extend_from_slice(&(attr_len as u32).to_be_bytes());
        m.extend_from_slice(&64u16.to_be_bytes()); // max_stack
        m.extend_from_slice(&64u16.to_be_bytes()); // max_locals
        m.extend_from_slice(&(code.bytes.len() as u32).to_be_bytes());
        m.extend_from_slice(&code.bytes);
        m.extend_from_slice(&0u16.to_be_bytes()); // exception table
        m.extend_from_slice(&0u16.to_be_bytes()); // code attributes
        self.method_count += 1;
    }

    pub fn finish(&self) -> Vec<u8> {
        let mut b = Vec::new();
        b.extend_from_slice(&0xCAFE_BABEu32.to_be_bytes());
        b.extend_from_slice(&0u16.to_be_bytes());
        b.extend_from_slice(&self.major.to_be_bytes());
        b.extend_from_slice(&self.cp_count.to_be_bytes());
        b.extend_from_slice(&self.cp);
        b.extend_from_slice(&0x0021u16.to_be_bytes());
        b.extend_from_slice(&self.this_class.to_be_bytes());
        b.extend_from_slice(&self.super_class.to_be_bytes());
        b.extend_from_slice(&0u16.to_be_bytes()); // interfaces
        b.extend_from_slice(&0u16.to_be_bytes()); // fields
        b.extend_from_slice(&self.method_count.to_be_bytes());
        b.extend_from_slice(&self.methods);
        b.extend_from_slice(&0u16.to_be_bytes()); // class attributes
        b
    }
}
