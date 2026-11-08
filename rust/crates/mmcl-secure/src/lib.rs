// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! Security primitives of the Mica Minecraft Launcher (Rust port of `MachineSecretCipher`,
//! `AccountCipher` and `FilePermissions`).
//!
//! - [`cipher`]: the machine-bound AES-256-GCM cipher. Its envelope, key derivation and
//!   fingerprint are byte-compatible with the Java launcher, so either launcher can read the
//!   other's encrypted files on the same machine and account.
//! - [`fingerprint`]: the PBKDF2 passphrase (`user|os|hwuuid|install:secret`) and the host
//!   values that go into it.
//! - [`install_secret`]: the per-install random secret in `<config>/machine-key.bin`.
//! - [`os_name`]: Java's `System.getProperty("os.name")`, reproduced exactly.
//! - [`hwuuid`]: the hardware UUID exactly as oshi 6.6.5 reports it.
//! - [`perms`]: owner-only permissions for credential files and folders, logging failures.
//!
//! The cipher must not be used before the launcher data root is known (the "early `main()` path
//! trap"): [`cipher::MachineCipher::new`] takes the config folder explicitly, and the
//! process-wide [`cipher::encrypt`] / [`cipher::decrypt`] fail with
//! [`CipherError::NotInitialized`] until [`cipher::init_global`] ran.

pub mod cipher;
mod error;
pub mod fingerprint;
pub mod hwuuid;
pub mod install_secret;
mod javastr;
pub mod os_name;
pub mod perms;

pub use cipher::{Cipher, FixedKeySource, KeySource, MachineCipher};
pub use error::CipherError;
pub use fingerprint::{HostKeySource, assemble_fingerprint};
pub use hwuuid::hardware_uuid;
pub use os_name::java_os_name;
