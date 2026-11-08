// Copyright (c) Mica Technologies. Licensed under the GNU GPL v3.

//! macOS: `IOPlatformUUID` of the `IOPlatformExpertDevice` service, as oshi's
//! `MacComputerSystem.platformExpert()` reads it.

use std::ffi::{c_char, c_void};

type CFTypeRef = *const c_void;
type CFStringRef = *const c_void;
type CFAllocatorRef = *const c_void;
type CFDictionaryRef = *const c_void;
type CFIndex = isize;
type CFTypeID = usize;
type IoObject = u32;

const K_CF_STRING_ENCODING_UTF8: u32 = 0x0800_0100;
/// `kIOMainPortDefault` / `kIOMasterPortDefault`.
const K_IO_MAIN_PORT_DEFAULT: u32 = 0;

#[link(name = "IOKit", kind = "framework")]
unsafe extern "C" {
    fn IOServiceMatching(name: *const c_char) -> CFDictionaryRef;
    fn IOServiceGetMatchingService(main_port: u32, matching: CFDictionaryRef) -> IoObject;
    fn IORegistryEntryCreateCFProperty(
        entry: IoObject,
        key: CFStringRef,
        allocator: CFAllocatorRef,
        options: u32,
    ) -> CFTypeRef;
    fn IOObjectRelease(object: IoObject) -> i32;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFStringCreateWithCString(
        alloc: CFAllocatorRef,
        c_str: *const c_char,
        encoding: u32,
    ) -> CFStringRef;
    fn CFStringGetLength(string: CFStringRef) -> CFIndex;
    fn CFStringGetMaximumSizeForEncoding(length: CFIndex, encoding: u32) -> CFIndex;
    fn CFStringGetCString(
        string: CFStringRef,
        buffer: *mut c_char,
        buffer_size: CFIndex,
        encoding: u32,
    ) -> u8;
    fn CFGetTypeID(cf: CFTypeRef) -> CFTypeID;
    fn CFStringGetTypeID() -> CFTypeID;
    fn CFRelease(cf: CFTypeRef);
}

/// The raw property, or `None` when the service or the property is missing.
pub fn query_uuid() -> Option<String> {
    // SAFETY: plain IOKit/CoreFoundation calls; every object created here is released, and the
    // matching dictionary is consumed by IOServiceGetMatchingService.
    unsafe {
        let matching = IOServiceMatching(c"IOPlatformExpertDevice".as_ptr());
        if matching.is_null() {
            return None;
        }
        let service = IOServiceGetMatchingService(K_IO_MAIN_PORT_DEFAULT, matching);
        if service == 0 {
            return None;
        }
        let key = CFStringCreateWithCString(
            std::ptr::null(),
            c"IOPlatformUUID".as_ptr(),
            K_CF_STRING_ENCODING_UTF8,
        );
        let mut result = None;
        if !key.is_null() {
            let value = IORegistryEntryCreateCFProperty(service, key, std::ptr::null(), 0);
            if !value.is_null() {
                if CFGetTypeID(value) == CFStringGetTypeID() {
                    result = cf_string_to_string(value);
                }
                CFRelease(value);
            }
            CFRelease(key);
        }
        IOObjectRelease(service);
        result
    }
}

/// # Safety
/// `string` must be a valid CFString.
unsafe fn cf_string_to_string(string: CFStringRef) -> Option<String> {
    // SAFETY: guaranteed by the caller; the buffer is sized by CFStringGetMaximumSizeForEncoding
    // plus the terminating NUL.
    unsafe {
        let length = CFStringGetLength(string);
        let capacity = CFStringGetMaximumSizeForEncoding(length, K_CF_STRING_ENCODING_UTF8) + 1;
        let mut buffer = vec![0u8; usize::try_from(capacity).ok()?];
        if CFStringGetCString(
            string,
            buffer.as_mut_ptr() as *mut c_char,
            capacity,
            K_CF_STRING_ENCODING_UTF8,
        ) == 0
        {
            return None;
        }
        let end = buffer.iter().position(|&b| b == 0).unwrap_or(buffer.len());
        buffer.truncate(end);
        String::from_utf8(buffer).ok()
    }
}
