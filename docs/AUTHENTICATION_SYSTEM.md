# Authentication System

Technical documentation for Microsoft/Minecraft sign-in: several accounts signed in at once,
per-account token refresh, an encrypted per-account store, and how a game launch picks its
account.

## Overview

The launcher keeps **every saved account signed in at the same time**. One of them is the
*default*: the header shows it, and a game launches with it unless the modpack is set to launch as
another account. Switching the default is instant (no restart), and a new account is added from a
sign-in window over whatever screen is showing.

Code:
- `game/auth/AccountManager.java`: the accounts, their sessions and the default
- `game/auth/AccountStore.java`: per-account files on disk; `AccountCipher.java` its encryption seam
- `game/auth/MCLauncherAuthManager.java`: the Microsoft calls, rate limiting, and a static facade
  over the default account
- `game/auth/LaunchAccountResolver.java`: which account a launch uses
- `gui/MicrosoftSignIn.java`, `gui/SignInPanel.java`, `gui/MCLauncherLoginGui.java`,
  `gui/AddAccountDialog.java`: the sign-in UI (see `GUI_SYSTEM.md`)

## Architecture

```
  Login screen ─────┐                         ┌───────────────────────────────┐
  Add-account window┴─> MCLauncherAuthManager │ AccountManager                │
                        • signInWithMicrosoft ─┼─> addSignedIn(user, file,     │
                        • renewWithMicrosoft <─┼──   remember, makeDefault)    │
                          (rate limit, timeout)│   sessions: uuid → Session    │
  Header / Settings ───────────────────────────┼─> setDefault / remove         │
  LauncherCore.play ──> userForLaunch(override)┼─> refresh(uuid)  (per account │
                        └ LaunchAccountResolver│                  single-flight)│
                                               │         │ remembered accounts  │
                                               └─────────┼─────────────────────┘
                                                         v
                                               AccountStore: <config>/profiles/<uuid>/
```

## Storage Layout

Each **remembered** account has its own folder, `<config>/profiles/<uuid>/`:

| File | Contents |
|---|---|
| `player.mica` | the gzip'd `AuthenticationFile` (refresh, access and Xbox tokens), AES-256-GCM encrypted, raw bytes |
| `cached_user.json` | the `User` (uuid, name, access token, …) as JSON, encrypted, Base64 |
| `renewal.timestamp` | epoch millis of the last token renewal, encrypted, Base64 |
| `profile.json` | plaintext uuid, display name and last-used time, so the account list needs no decryption |

The default account's uuid is `defaultAccountUuid` in `configuration.json`. Writes go through a
temp file and an atomic move, files are owner-only, and a uuid that isn't hex-and-dashes is refused
outright, so no uuid can steer a write outside the store.

Accounts signed in **without** "Stay signed in" are held in memory only (their authentication file
too, so they can still refresh during the session) and are gone after a restart. Signing an
account in without "Stay signed in" also deletes any copy an earlier sign-in stored.

### Migration from the single-account layout

Before multi-account, the active account's files sat flat in `<config>/` and other accounts were
"archived" copies in `profiles/<uuid>/` (the old `ProfileArchive`). On first load,
`AccountManager` moves the flat files into `profiles/<uuid>/` and makes that account the default:
copy, verify the copy reads back, then delete, so a crash part-way just repeats the migration. A
flat login whose cached user is unreadable is kept in memory and identified by its first renewal
(`refreshDefault`). One from another machine can never decrypt and is dropped. Archived folders
need no migration; the layout is the same.

## Encrypted Storage

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

### Envelope Format

Every encrypted value is `salt[16] + iv[12] + ciphertext + GCM tag`. The authentication file
(`player.mica`) holds that envelope as raw bytes; the cached user and renewal timestamp hold it
Base64-encoded. See *Storage Layout* for where the files live.

### Machine Key Derivation

The encryption key is derived from a machine fingerprint to bind cached tokens to the
specific machine. The fingerprint is composed of:

1. Operating system username
2. OS name
3. Hardware UUID (platform-specific query), when available
4. A per-install random secret at `<config>/machine-key.bin` — **always** mixed in
   (lazily created on first use, owner-only permissions)

This prevents cached tokens from being usable if the file is copied to another machine.

> **Always-mixed install secret (2026.6).** The per-install secret used to be only
> a *fallback* for when the hardware-UUID query failed, so on a normal machine the
> key derived purely from `username | os.name | hardwareUUID` — all values any
> local process can read. A sibling user could therefore reconstruct the key
> whenever the best-effort owner-only ACL on the config files silently failed
> (FAT32/exFAT data partitions, network homes, some WSL mounts). The secret is now
> always part of the fingerprint, so an attacker also needs to read the owner-only
> `machine-key.bin`. **Migration is transparent:** `MachineSecretCipher.decryptBytes`
> tries the current fingerprint, and on AEAD-tag failure retries the legacy
> (UUID-only) fingerprint; a value that decrypts under the legacy key is re-encrypted
> under the new one the next time it's written (token renewal, settings re-save).

> **Accepted residual risk — argv token exposure.** The live Minecraft access token
> is passed to the game JVM as a command-line argument (`--accessToken`), which any
> same-machine process can read (`/proc/<pid>/cmdline`, `Win32_Process.CommandLine`).
> This is inherent to the Minecraft launch protocol (the vanilla launcher does the
> same) and isn't avoidable; it's mitigated for *logs* by `SensitiveDataRedactor`
> (launch command + game-console output are redacted), but the live argv exposure to
> a same-UID process is an accepted residual.

> **Note:** A primary network MAC address was previously part of the fingerprint
> but was removed. Modern macOS randomizes every interface's MAC (Private Wi-Fi
> Address / Apple-Silicon randomization), so no stable real-OUI MAC exists; a
> real MAC only appeared intermittently (docks, USB-Ethernet, tethering). That
> rotated the derived key between launches and caused cached tokens to fail
> decryption with `AEADBadTagException`, surfacing as "token renewal failed /
> unknown login error" on ~90% of macOS launches. The hardware UUID already
> provides machine binding. See `MachineSecretCipher.deriveMachineKey`.

### Refresh Intervals & Rate Limiting

| Constant | Value | Purpose |
|---|---|---|
| `TOKEN_REFRESH_INTERVAL_MS` | 4 hours | A token older than this is refreshed before use |
| `TOKEN_SOFT_REFRESH_INTERVAL_MS` | 3 hours | Past this, a background refresh starts at launcher startup |
| `MIN_AUTH_INTERVAL_MS` | 5 seconds | Minimum gap between Microsoft calls |
| `MAX_BACKOFF_MS` | 2 minutes | Cap on the exponential backoff after failures |
| `AUTH_TIMEOUT_SECONDS` | 60 | Timeout for one Microsoft call |

Rate limiting and backoff are **launcher-wide**: every account's refresh and every interactive
sign-in share one budget (`enforceRateLimit` is synchronized). Refreshes are **per account**:
`AccountManager.refresh(uuid, force)` runs at most one refresh per account, and a second request
joins the one in flight. They run on one daemon executor, spaced by the rate limit.

## Flows

### First sign-in (login screen)

1. The user signs in on Microsoft's page in the login screen's `SignInPanel`.
2. `MicrosoftSignIn.parseCallback` extracts the authorization code from the OAuth redirect.
3. `MCLauncherAuthManager.loginWithMicrosoftAccount(code, remember)` exchanges it and calls
   `AccountManager.addSignedIn(user, authFile, remember, makeDefault = true)`.

### Adding an account

`AddAccountDialog` runs the same sign-in in a window over the current screen and calls
`addAccountWithMicrosoft(code, remember)`, which adds the account **without** changing the default
(unless there is none). Signing an existing account in again (after Microsoft rejected its saved
sign-in) replaces its credentials.

### Startup

`LauncherCore.performClientLogin`: if there is a default account (`hasExistingLogin`), its cached
user paints the GUI immediately (`loadCachedUserNow`) and `renewExistingLoginAsync` refreshes it in
the background when due. Without a readable cached user it renews synchronously behind a progress
screen; if that fails the account is signed out and the login screen shows.
`tryPreemptiveBackgroundRenewal` refreshes any account whose token is past the soft interval, so a
launch on a non-default account rarely waits.

### Refresh outcomes

| Outcome | Account status |
|---|---|
| Renewed | `READY`, new token persisted (remembered) or kept in memory |
| Credentials rejected (invalid credentials, game not owned, no user returned) | `NEEDS_SIGN_IN`; the UI offers "Sign in again" |
| Timeout or other transient failure | stays `READY` on its current token, which outlives the 4-hour interval by hours |
| Renewal returned a *different* account | treated as rejected; the identity behind an entry never silently changes |

### Which account a launch uses

`MCLauncherAuthManager.userForLaunch(overrideUuid)` asks `LaunchAccountResolver`: the pack's
override (Advanced tab → *Launch as*, stored per pack as `accountOverrideByPack`) if it has one,
otherwise the default. It then waits for that account's refresh if one is due. The launch is
**blocked**, never silently redirected, when:

| Problem | What the user sees |
|---|---|
| `OVERRIDE_MISSING` (the pack's account was signed out) | "Play as <default> instead?"; yes clears the stale override |
| `NEEDS_SIGN_IN` | "Sign in again", which opens the add-account window |
| `NO_ACCOUNT` | an error; sign in first |

`NEEDS_SIGN_IN` also blocks a launch whose due refresh Microsoft refused (credentials rejected):
the cached token is just as dead, so launching on it would only fail inside Minecraft. A refresh
that times out or fails for a transient reason still launches on the cached token.

The chosen `User` is passed explicitly to `GameModPack.startGame(user, cancelled)`; the launcher
builds Minecraft's sign-in arguments from it (`GameModPackLauncher.AuthArguments`). One account can
play one game at a time (see `GAME_LAUNCH_SYSTEM.md`, *Concurrent games*).

## Key API

```java
// The accounts (instance obtained via MCLauncherAuthManager.accounts())
List< AccountInfo > accounts()                     // most recently used first
AccountInfo account( String uuid )
User user( String uuid ) / defaultUser() / defaultUuid()
boolean setDefault( String uuid )                  // instant; null = none
boolean addSignedIn( User u, byte[] authFile, boolean remember, boolean makeDefault )
boolean remove( String uuid )                      // sign out and forget
CompletableFuture< User > refresh( String uuid, boolean force )
void addListener( Runnable listener )              // list, status or default changed

// MCLauncherAuthManager (static)
MCLauncherAuthResult loginWithMicrosoftAccount( String code, boolean save )   // becomes default
MCLauncherAuthResult addAccountWithMicrosoft( String code, boolean save )     // keeps the default
User userForLaunch( String overrideUuid ) throws LaunchAccountResolver.BlockedException
User getLoggedInUser()                              // the default account
void setStatusCallback( AuthStatusCallback callback )
```

## Key Classes

| Class | Purpose |
|---|---|
| `AccountManager` | Every signed-in account, per-account refresh, the default, listeners, legacy migration |
| `AccountStore` | `profiles/<uuid>/` files: atomic, owner-only, uuid-validated |
| `AccountCipher` | Encryption seam; `machineBound()` delegates to `MachineSecretCipher` |
| `MCLauncherAuthManager` | Microsoft calls with timeout, shared rate limit/backoff, default-account facade |
| `LaunchAccountResolver` | Pure: pack override vs default vs blocked |
| `MCLauncherAuthResult` | Result wrapper (user, or an `ERROR_*` sentinel) |

## Security Considerations

- Tokens are never stored in plaintext; machine binding defeats copying the files elsewhere.
- GCM gives confidentiality and tamper detection; salt and IV are fresh per encryption.
- Per-account folders are owner-only and written atomically; uuids are validated before use as paths.
- MCP sees account **usernames** only (`get_launcher_status`, `list_running_games`), never uuids or tokens.
- Signing an account out deletes its whole folder; a session-only account never touches disk.

## Tests

`AccountStoreTest`, `AccountManagerTest` (fake cipher, scripted renewer, manual executor),
`LaunchAccountResolverTest`, `AccountOverrideConfigTest`, `MCLauncherAuthManager*Test`, and
`MicrosoftSignInTest` (OAuth redirect parsing).
