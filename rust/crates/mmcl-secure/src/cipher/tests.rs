// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

use super::*;
use crate::fingerprint::assemble_fingerprint;
use crate::install_secret::to_hex;

fn seq<const N: usize>(start: u8) -> [u8; N] {
    std::array::from_fn(|i| start.wrapping_add(i as u8))
}

fn fixed(fp: &str) -> MachineCipher {
    MachineCipher::with_source(Arc::new(FixedKeySource(fp.to_string())))
}

fn hex_key(hex: &str) -> [u8; KEY_LEN] {
    std::array::from_fn(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).unwrap())
}

/// Vectors produced by a JDK 26 running the exact `MachineSecretCipher.encryptBytes` steps
/// (`PBEKeySpec(fp.toCharArray(), salt, 65536, 256)` + `PBKDF2WithHmacSHA256`,
/// `AES/GCM/NoPadding` with a 128-bit tag) with a fixed salt and IV.
struct JavaVector {
    fingerprint: String,
    salt: [u8; SALT_LEN],
    iv: [u8; IV_LEN],
    plaintext: &'static str,
    key_hex: &'static str,
    envelope_b64: &'static str,
}

fn java_vectors() -> Vec<JavaVector> {
    vec![
        // The spec's fixture: assembleFingerprint("u", "Linux", "unknown", hex(32 zero bytes)).
        JavaVector {
            fingerprint: assemble_fingerprint(
                Some("u"),
                Some("Linux"),
                Some("unknown"),
                &to_hex(&[0u8; 32]),
            ),
            salt: seq(0x00),
            iv: seq(0x10),
            plaintext: "hello",
            key_hex: "02504054385d35d8f8cf8f2392134085d8f1cf8e631878d2f7c38143e48f9b84",
            envelope_b64: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaG4haju6pcO0uPE5tF3L1SqsvKfOORg==",
        },
        // Non-ASCII user name and plaintext: the password and the data are UTF-8 on both sides.
        JavaVector {
            fingerprint: assemble_fingerprint(
                Some("J\u{f6}rg"),
                Some("Windows 11"),
                Some("4C4C4544-0042-1234-8000-B2C04F563432"),
                &to_hex(&seq::<32>(0xA0)),
            ),
            salt: seq(0x40),
            iv: seq(0x60),
            plaintext: "token \u{e9}\u{4e2d} \u{1F600}",
            key_hex: "b630142a21062d44b24c97c65eac29b9ac6971595b09d1a4e23fe0755c346078",
            envelope_b64: "QEFCQ0RFRkdISUpLTE1OT2BhYmNkZWZnaGlqa/a7Jp9p5/bmtsphF//ugN+0YJ9DIaqxXC8XIV7w8sce",
        },
        // Every component empty, empty plaintext: the envelope is salt + iv + tag only.
        JavaVector {
            fingerprint: assemble_fingerprint(None, None, None, ""),
            salt: seq(0x80),
            iv: seq(0x90),
            plaintext: "",
            key_hex: "db26ebd99bc8e3de4d0a50fa3c4bdc9a76fd71181a196622c47c18edf04dbf49",
            envelope_b64: "gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmamxbToNErvqMDrIexczgHGN8=",
        },
    ]
}

// ---- compatibility with the Java launcher --------------------------------------------------

#[test]
fn key_derivation_matches_java() {
    for v in java_vectors() {
        assert_eq!(derive_key(&v.fingerprint, &v.salt), hex_key(v.key_hex));
    }
}

#[test]
fn encryption_with_the_same_salt_and_iv_is_byte_identical_to_java() {
    for v in java_vectors() {
        let key = derive_key(&v.fingerprint, &v.salt);
        let envelope = seal_with(&key, &v.salt, &v.iv, v.plaintext.as_bytes()).unwrap();
        assert_eq!(encode_base64(&envelope), v.envelope_b64);
    }
}

#[test]
fn java_envelopes_decrypt() {
    for v in java_vectors() {
        let cipher = fixed(&v.fingerprint);
        assert_eq!(
            cipher.decrypt_str(v.envelope_b64).unwrap().as_deref(),
            Some(v.plaintext)
        );
        let raw = decode_base64(v.envelope_b64).unwrap();
        assert_eq!(
            cipher.decrypt(&raw).unwrap().as_deref(),
            Some(v.plaintext.as_bytes())
        );
    }
}

#[test]
fn the_empty_plaintext_envelope_is_exactly_salt_iv_and_tag() {
    let v = &java_vectors()[2];
    let raw = decode_base64(v.envelope_b64).unwrap();
    assert_eq!(raw.len(), SALT_LEN + IV_LEN + TAG_LEN);
    assert_eq!(&raw[..SALT_LEN], &v.salt);
    assert_eq!(&raw[SALT_LEN..SALT_LEN + IV_LEN], &v.iv);
}

#[test]
fn base64_accepts_what_the_java_decoder_accepts() {
    let v = &java_vectors()[0];
    let unpadded = v.envelope_b64.trim_end_matches('=');
    assert_eq!(
        decode_base64(unpadded).unwrap(),
        decode_base64(v.envelope_b64).unwrap()
    );
    assert_eq!(decode_base64("Zm9v").unwrap(), b"foo");
    assert_eq!(decode_base64("").unwrap(), b"");
    assert_eq!(decode_base64("Zm9v YmFy"), Err(CipherError::InvalidBase64));
    assert_eq!(decode_base64("Zm9v-_"), Err(CipherError::InvalidBase64));
}

// ---- MachineSecretCipherSecurityTest: malformed input --------------------------------------

#[test]
fn decrypt_of_empty_string_returns_none() {
    assert_eq!(fixed("fp").decrypt_str(""), Ok(None));
}

#[test]
fn decrypt_of_blank_string_returns_none() {
    assert_eq!(fixed("fp").decrypt_str("    "), Ok(None));
    assert_eq!(fixed("fp").decrypt_str("\t\r\n"), Ok(None));
}

#[test]
fn decrypt_bytes_of_empty_array_returns_none() {
    assert_eq!(fixed("fp").decrypt_bytes(&[]), Ok(None));
}

#[test]
fn decrypt_bytes_of_truncated_blob_returns_none() {
    assert_eq!(fixed("fp").decrypt_bytes(&[0u8; 27]), Ok(None));
}

#[test]
fn decrypt_of_non_base64_garbage_fails_cleanly() {
    assert_eq!(
        fixed("fp").decrypt_str("!!!not-valid-base64!!!"),
        Err(CipherError::InvalidBase64)
    );
}

#[test]
fn a_short_envelope_that_passes_the_size_gate_fails_the_tag() {
    // 28..43 bytes reach GCM (there is no room for a tag), exactly like the Java.
    for len in [28usize, 30, 43] {
        assert_eq!(
            fixed("fp").decrypt_bytes(&vec![7u8; len]),
            Err(CipherError::Authentication),
            "length {len}"
        );
    }
}

// ---- MachineSecretCipherSecurityTest: real cipher -------------------------------------------

#[test]
fn round_trip_succeeds_and_tamper_is_detected_at_every_position() {
    let cipher = fixed("alice|Linux|unknown|install:00");
    let plaintext = "synthetic-test-token-1234";
    let envelope = cipher.encrypt(plaintext.as_bytes()).unwrap();
    assert_eq!(
        envelope.len(),
        SALT_LEN + IV_LEN + plaintext.len() + TAG_LEN
    );
    assert_eq!(
        cipher.decrypt(&envelope).unwrap().as_deref(),
        Some(plaintext.as_bytes())
    );
    for index in 0..envelope.len() {
        let mut tampered = envelope.clone();
        tampered[index] ^= 0xFF;
        assert_eq!(
            cipher.decrypt(&tampered),
            Err(CipherError::Authentication),
            "flipping byte {index} must fail the tag"
        );
    }
}

#[test]
fn random_garbage_the_size_of_a_real_envelope_fails_with_an_error() {
    let cipher = fixed("fp");
    let real = cipher.encrypt_str("synthetic").unwrap();
    let len = decode_base64(&real).unwrap().len();
    let garbage: Vec<u8> = random_array::<64>().unwrap()[..len].to_vec();
    assert_eq!(
        cipher.decrypt_str(&encode_base64(&garbage)),
        Err(CipherError::Authentication)
    );
}

#[test]
fn wrong_machine_key_fails_cleanly_rather_than_returning_garbage() {
    let machine_a = fixed("alice|Windows 11|UUID-A|install:aa");
    let machine_b = fixed("alice|Windows 11|UUID-A|install:bb");
    let envelope = machine_a
        .encrypt_str("synthetic-cross-install-token")
        .unwrap();
    assert_eq!(
        machine_a.decrypt_str(&envelope).unwrap().as_deref(),
        Some("synthetic-cross-install-token")
    );
    assert_eq!(
        machine_b.decrypt_str(&envelope),
        Err(CipherError::Authentication)
    );
}

#[test]
fn each_encryption_uses_a_fresh_salt_and_iv() {
    let cipher = fixed("fp");
    let a = cipher.encrypt(b"same").unwrap();
    let b = cipher.encrypt(b"same").unwrap();
    assert_ne!(&a[..SALT_LEN], &b[..SALT_LEN]);
    assert_ne!(
        &a[SALT_LEN..SALT_LEN + IV_LEN],
        &b[SALT_LEN..SALT_LEN + IV_LEN]
    );
    assert_ne!(a, b);
}

#[test]
fn text_round_trip_keeps_unicode() {
    let cipher = fixed("fp");
    let text = "J\u{f6}rg \u{4e2d}\u{6587} \u{1F600}";
    let envelope = cipher.encrypt_str(text).unwrap();
    assert_eq!(
        cipher.decrypt_str(&envelope).unwrap().as_deref(),
        Some(text)
    );
}

#[test]
fn non_utf8_plaintext_is_reported_by_the_text_api() {
    let cipher = fixed("fp");
    let envelope = encode_base64(&cipher.encrypt(&[0xff, 0xfe]).unwrap());
    assert_eq!(cipher.decrypt_str(&envelope), Err(CipherError::InvalidUtf8));
}

#[test]
fn the_host_cipher_round_trips_and_is_bound_to_its_install() {
    let a = tempfile::tempdir().unwrap();
    let b = tempfile::tempdir().unwrap();
    let install_a = MachineCipher::new(a.path());
    let envelope = install_a.encrypt_str("synthetic-host-token").unwrap();
    assert!(a.path().join("machine-key.bin").exists());
    // A second cipher over the same folder (a restart) reads the same secret.
    assert_eq!(
        MachineCipher::new(a.path())
            .decrypt_str(&envelope)
            .unwrap()
            .as_deref(),
        Some("synthetic-host-token")
    );
    assert_eq!(
        MachineCipher::new(b.path()).decrypt_str(&envelope),
        Err(CipherError::Authentication)
    );
}

#[test]
fn errors_name_themselves_without_data() {
    assert_eq!(
        CipherError::Authentication.kind_name(),
        "AuthenticationFailed"
    );
    assert!(!CipherError::InvalidBase64.to_string().is_empty());
}

#[test]
fn the_process_wide_cipher_refuses_work_before_init_but_passes_empties() {
    // No test installs the global cipher, so it is uninitialised here.
    assert_eq!(decrypt(""), Ok(None));
    assert_eq!(decrypt_bytes(&[0u8; 10]), Ok(None));
    assert_eq!(encrypt("x"), Err(CipherError::NotInitialized));
    assert_eq!(encrypt_bytes(b"x"), Err(CipherError::NotInitialized));
    assert_eq!(decrypt("Zm9v"), Err(CipherError::NotInitialized));
    assert!(global().is_none());
}
