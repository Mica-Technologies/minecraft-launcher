# Authentication System

Technical documentation for the Microsoft/Minecraft authentication system with encrypted
token caching.

## Overview

The authentication system handles the full Microsoft OAuth flow to obtain Minecraft access
tokens, with an encrypted local cache to avoid redundant auth round-trips. Tokens are
AES-256-GCM encrypted with a machine-derived key and cached for 4 hours.

All authentication code lives in:
- `src/main/java/com/micatechnologies/minecraft/launcher/game/auth/MCLauncherAuthManager.java`
- `src/main/java/com/micatechnologies/minecraft/launcher/gui/MCLauncherLoginGui.java`

## Architecture

```
                    ┌──────────────────────────────────────────┐
                    │           MCLauncherAuthManager           │
                    │                                          │
  Login GUI ───────>│  loginWithMicrosoftAccount(authCode)     │
                    │         │                                │
  LauncherCore ────>│  renewExistingLogin()                    │
                    │         │                                │
                    │         v                                │
                    │  ┌──────────────────────┐                │
                    │  │ Token Cache Check     │                │
                    │  │ (< 4 hours old?)      │                │
                    │  └──────┬───────┬────────┘                │
                    │     fresh│      │stale                    │
                    │         │      v                         │
                    │         │  ┌──────────────────┐          │
                    │         │  │ minecraft_        │          │
                    │         │  │ authenticator lib │          │
                    │         │  │ (Microsoft OAuth) │          │
                    │         │  └──────────────────┘          │
                    │         │      │                         │
                    │         v      v                         │
                    │  ┌──────────────────────┐                │
                    │  │ Encrypt & persist     │                │
                    │  │ cached_user.json      │                │
                    │  │ (AES-256-GCM)        │                │
                    │  └──────────────────────┘                │
                    └──────────────────────────────────────────┘
```

## Encrypted Token Cache

### Encryption Details

| Parameter | Value |
|---|---|
| Algorithm | AES/GCM/NoPadding |
| Key size | 256 bits |
| Authentication tag | 128 bits |
| Key derivation | PBKDF2WithHmacSHA256 |
| KDF iterations | 65,536 |
| Salt | 16 bytes (random per encryption) |
| IV | 12 bytes (random per encryption) |

### Storage Format

Cached tokens are stored as Base64-encoded binary:

```
Base64( salt[16 bytes] + iv[12 bytes] + ciphertext + GCM_tag )
```

Written to `cached_user.json` in the launcher data directory.

### Machine Key Derivation

The encryption key is derived from a machine fingerprint to bind cached tokens to the
specific machine. The fingerprint is composed of:

1. Operating system username
2. OS name
3. Hardware UUID (platform-specific query)
4. Primary network MAC address

This prevents cached tokens from being usable if the file is copied to another machine.

### Cache TTL & Rate Limiting

| Constant | Value | Purpose |
|---|---|---|
| `TOKEN_REFRESH_INTERVAL_MS` | 14,400,000 (4 hours) | Maximum cache age before renewal |
| `MIN_AUTH_INTERVAL_MS` | 5,000 (5 seconds) | Rate limit between API calls |
| `MAX_BACKOFF_MS` | 120,000 (2 minutes) | Maximum exponential backoff delay |
| `AUTH_TIMEOUT_SECONDS` | 30 | Timeout for individual API calls |

The renewal timestamp is persisted separately in a `renewal.timestamp` file so the cache
TTL survives application restarts.

## Authentication Flow

### Fresh Login

1. User completes Microsoft OAuth in the login webview (`MCLauncherLoginGui`)
2. Auth code passed to `loginWithMicrosoftAccount(authCode, save)`
3. `minecraft_authenticator` library exchanges code for Minecraft access token
4. Token and user profile encrypted and persisted to `cached_user.json`
5. Renewal timestamp written to `renewal.timestamp`

### Token Renewal (Startup)

1. `LauncherCore` calls `renewExistingLogin()` on startup
2. Load encrypted `cached_user.json` and decrypt with machine key
3. Check renewal timestamp against `TOKEN_REFRESH_INTERVAL_MS`
4. If fresh: return cached user immediately (no network call)
5. If stale: use `minecraft_authenticator` to refresh tokens via Microsoft OAuth
6. Re-encrypt and persist updated tokens
7. If renewal fails: fall back to login screen

### Exponential Backoff

Failed auth attempts use exponential backoff with jitter:
- Base delay doubles on each failure
- Capped at `MAX_BACKOFF_MS` (2 minutes)
- Rate limited to one attempt per `MIN_AUTH_INTERVAL_MS` (5 seconds)

## Key Methods

```java
// Check for existing cached credentials
public static boolean hasExistingLogin()

// Get the currently authenticated user (from cache)
public static User getLoggedInUser()

// Renew cached credentials (refresh if stale)
public static MCLauncherAuthResult renewExistingLogin()

// Fresh login with Microsoft OAuth auth code
public static MCLauncherAuthResult loginWithMicrosoftAccount( String authCode, boolean save )

// Clear all cached credentials
public static void logout()

// Register a callback for auth progress updates
public static void setStatusCallback( AuthStatusCallback callback )
```

## Status Callback

The `AuthStatusCallback` functional interface allows UI components to receive progress
updates during auth operations:

```java
@FunctionalInterface
public interface AuthStatusCallback
{
    void onStatusUpdate( String sectionText, String detailText );
}
```

Used by the progress GUI to show "Authenticating..." status during startup renewal.

## Key Classes

| Class | Purpose |
|---|---|
| `MCLauncherAuthManager` | Token cache, encryption, OAuth flow orchestration |
| `MCLauncherLoginGui` | Microsoft OAuth webview for fresh login |
| `MCLauncherAuthResult` | Result wrapper for auth operations (success/failure + user) |

## Security Considerations

- Tokens are never stored in plaintext on disk
- Machine binding prevents credential theft via file copy
- GCM mode provides both confidentiality and integrity (tamper detection)
- Fresh salt and IV per encryption operation (no nonce reuse)
- Cached tokens expire after 4 hours regardless of application state
- `logout()` deletes both `cached_user.json` and `renewal.timestamp`
