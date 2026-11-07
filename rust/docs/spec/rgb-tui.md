# Porting spec: RGB lighting and the terminal UI (`rgb/`, `tui/`)

Source slice (Java, about 10,700 lines):

- `src/main/java/com/micatechnologies/minecraft/launcher/rgb/**`: the RGB subsystem (controller,
  circuit breaker, effect engine, effects, backend registry, six vendor backends, WinRT bridge).
- `src/main/java/com/micatechnologies/minecraft/launcher/tui/**`: the full-screen terminal UI
  started by `--cli` / `--tui` (Lanterna 3.1.2 today; ratatui in the port).

Related code outside the slice that this spec also describes, because the port must reproduce it:
`config/RgbConfig.java` + the `RGB_*` constants in `consts/ConfigConstants.java`, the RGB tab of
`gui/MCLauncherSettingsGui.java` (status chip, connection test, restart), the pack-logo colour
sampler in `gui/MCLauncherMainGui.java`, the RGB hooks in `LauncherCore` / `LauncherSession` /
`game/session/RunningGameFollower.java`, and the TUI hooks in `LauncherCore`, `LauncherSession`,
`LauncherArgs`, `files/Logger.java`, `game/modpack/GameModPackLauncher.java`.

Target: two library crates in the `rust/` workspace, `mmcl-rgb` and `mmcl-tui`, consumed by
`mmcl-app`. RGB has no files on disk of its own; its only persisted state is config keys in the
launcher config JSON (owned by the config subsystem). The TUI has no files either; it writes only
to the launcher log file (through the logging subsystem) and reads/writes config.

---

## Part A: RGB subsystem

### A.1 What it does, in one paragraph

When the user turns RGB on in Settings, the launcher lights their peripherals (keyboards, mice,
mousepads, headsets, case fans, motherboard/GPU/RAM zones) through whatever vendor SDKs are
present: OpenRGB (TCP), Razer Chroma (native DLL, plus a deprecated REST API), Corsair iCUE (DLL),
ASUS Aura (legacy DLL) and Windows 11 Dynamic Lighting (WinRT `LampArray`). In the menus it plays
an idle "menu effect" in the theme accent colour (or the colours of the modpack whose details
modal is open). While a game runs it shows a static "in-game" effect: the pack's dominant logo
colour as background with W/A/S/D/E/Q/F/Space/LShift/LCtrl highlighted in a contrast colour.
Every vendor failure is contained: the launcher must keep working even if every backend
explodes, and one vendor failing must not stop the others.

### A.2 Architecture and threads

```
  callers (GUI, launch flow, follower)          RgbIntegration (static glue, all null-safe,
            |                                    all wrapped in catch-everything)
            v                                              |
  RgbController (singleton) ---- setEffect --> RgbEffectEngine (thread "mica-rgb-effects")
     |   ^                                           | ticks every 33 ms, effect.frameAt(elapsed)
     |   +------------------- submitFrame <----------+
     |  bounded queue (cap 4, drop when full)
     v
  worker thread "mica-rgb": poll(500 ms) -> for each slot whose breaker allows: backend.renderFrame
     slots: [ (backend, RgbBackendHealth) ... ]  in dispatch order
```

Threads:

| Name | Owner | Lifetime | Purpose |
|---|---|---|---|
| `mica-rgb` | `RgbController` | started by `start()` when at least one backend came up; daemon | The only thread that calls `render_frame` on backends. |
| `mica-rgb-effects` | `RgbEffectEngine` | created lazily on first `set_effect(Some)`; torn down by `stop()` or a `start()` with zero backends | Fixed-rate 33 ms ticker; idle (no ticks scheduled) when no effect. |
| `mica-rgb-chroma-heartbeat` | `ChromaRestBackend` | `start()`..`shutdown()` | `PUT {session}/heartbeat` every 10 s. |
| Corsair SDK callback thread | iCUE DLL | SDK-owned | Calls the session-state callback. |
| arbitrary task thread | `RgbIntegration.bootstrap`, Settings restart/test | one-shot | Backend `is_available()` + `start()` run here, NOT on `mica-rgb`. |

Important consequence: `start()` of each backend runs on a background task thread, then
`render_frame()` runs on `mica-rgb`. Backends must tolerate that thread hop (Windows Dynamic
Lighting has to initialise WinRT on each thread; see A.9.6). `shutdown()` may run on any thread
(Settings restart thread, app cleanup thread).

The Java `RgbBackend` contract says "all calls are dispatched on mica-rgb; no internal sync
needed". That is not literally true (see above), but calls never overlap in practice because the
controller stops and joins the worker before calling `shutdown()`, and `start()` happens before the
worker exists. In Rust, make backends `Send` (not necessarily `Sync`) and move them into the
worker; keep `start()` before the worker spawn and `shutdown()` after the join, so `&mut self` is
enough everywhere.

### A.3 Value types

#### `RgbColor`

- 24-bit RGB, channels `u8` (Java validates 0..=255 at construction and throws otherwise; in Rust
  `u8` makes that unrepresentable. Keep a fallible `RgbColor::try_new(i32,i32,i32)` for the paths
  that do arithmetic in wider ints).
- Constants: `BLACK = (0,0,0)`, `WHITE = (255,255,255)`.
- `from_hex("#RRGGBB" | "RRGGBB")`: exactly 6 hex digits after an optional `#`, else error.
  Errors are loud (callers wrap).
- `blend(a, b, t)`: `t` clamped to `[0,1]`; per channel `round(a + (b - a) * t)` using
  Java `Math.round` semantics (round half up: `floor(x + 0.5)`). Rust `f64::round` rounds half
  away from zero; for non-negative values these agree, and all values here are non-negative, so
  `f64::round` is fine.
- `complement()`: `(255-r, 255-g, 255-b)` (the Java doc calls it an "HSL 180 degree rotation" but it
  is a plain channel inversion; port the inversion).
- `pack_rgb() -> u32`: `0x00RRGGBB`.
- Equality and hash are field-wise. `Display`: `#RRGGBB` uppercase.

#### `KeyboardKey`

Closed enum of the keys effects may address (deliberately not every key):
`W A S D E Q F SPACE LEFT_SHIFT LEFT_CTRL NUM_1..NUM_9 ESCAPE ENTER T TAB F1..F12`.
The enum's variant *name* matters: OpenRGB LED-name matching compares against it (A.9.1), so give
the Rust enum a `fn canonical_name(&self) -> &'static str` returning exactly those strings
(`"LEFT_SHIFT"`, `"NUM_1"`, ...).

#### `RgbFrame`

- `background: RgbColor` plus a sparse immutable map `KeyboardKey -> RgbColor` of overrides.
- `RgbFrame::solid(c)` = no overrides. `color_for(key)` = override or background.
- Equality: background equal and override maps equal.
- Rust: `struct RgbFrame { background: RgbColor, overrides: Arc<[(KeyboardKey, RgbColor)]> }` or an
  `enum_map::EnumMap<KeyboardKey, Option<RgbColor>>` (34 entries, `Copy`, cheap). The effect
  engine makes ~30 per second; `InGameEffect` and `SolidEffect` return a pre-built frame, so make
  `RgbFrame: Clone` cheap (EnumMap of `Option<RgbColor>` is 34*4 bytes, fine to copy).

### A.4 `RgbBackend` trait (the vendor contract)

```rust
pub trait RgbBackend: Send {
    /// Log / Settings label, e.g. "OpenRGB", "Razer Chroma (Native)".
    fn name(&self) -> &str;
    /// Must return in < 500 ms, must not fail (return false on any doubt), no side effects.
    fn is_available(&mut self) -> bool;
    /// One-time setup; may fail. Controller drops the backend on Err.
    fn start(&mut self) -> anyhow::Result<()>;
    /// Paint background everywhere, then per-key overrides where addressable. May fail.
    fn render_frame(&mut self, frame: &RgbFrame) -> anyhow::Result<()>;
    /// Release everything, best-effort restore; must never fail or panic out.
    fn shutdown(&mut self);
    /// Stable identity used by the controller's "same request" check (Java compares classes).
    fn kind(&self) -> BackendKind;
}
```

`BackendKind` = `OpenRgb | ChromaNative | ChromaRest | WindowsDynamicLighting | Corsair | AsusAura |
NoOp` (replaces Java's `getClass()` comparisons).

Every call into a backend is wrapped by the controller in "catch everything". Java catches
`Throwable`, which includes `UnsatisfiedLinkError` from a DLL that vanished after a driver
update. Rust equivalent: `std::panic::catch_unwind(AssertUnwindSafe(|| ...))` around every backend
call plus `Result`. Note that a native access violation inside a vendor DLL can NOT be caught in
either language (it kills the process; see A.10).

`NoOpBackend`: name `"None"`, always available, does nothing. Used only as a sentinel by the
single-backend `start(backend)` overload and by the legacy `resolve()`; the controller filters it
out and never gives it a slot.

### A.5 Circuit breaker `RgbBackendHealth` (port exactly; it has tests)

State machine per backend slot: `Healthy -> Degraded -> Dead` (Dead is one-way for the session).

Constants: `DEGRADED_THRESHOLD = 3`, `DEAD_THRESHOLD = 10`, `INITIAL_BACKOFF_MS = 30_000`,
`MAX_BACKOFF_MS = 30 * 60_000`.

Fields: `state = Healthy`, `consecutive_failures = 0`, `next_retry_at = 0`,
`current_backoff_ms = INITIAL_BACKOFF_MS`. All behind one mutex (the Settings chip reads from the
UI thread).

- `can_call(now_ms)`: Healthy -> true; Degraded -> `now_ms >= next_retry_at`; Dead -> false.
- `record_success()`: if Dead, do nothing. Else failures = 0, backoff = initial, state = Healthy.
- `record_failure(now_ms)`: `failures += 1`; if `failures >= 10` -> Dead, return (counter keeps
  incrementing past 10 on later calls; a test checks `20` after 20 failures). Else if
  `failures >= 3` -> Degraded, `next_retry_at = now + current_backoff`,
  `current_backoff = min(current_backoff * 2, MAX)`.
- Accessors `state()`, `consecutive_failures()`.

Note the counter is "consecutive across the session": a success resets it, so Dead needs 10 in a
row. Time source is wall-clock epoch ms (`System.currentTimeMillis()`); in Rust take `now_ms` as a
parameter (tests pass synthetic times) and use a monotonic clock in production.

### A.6 `RgbController` (singleton)

Constants: `FRAME_QUEUE_CAPACITY = 4`, `QUEUE_POLL_TIMEOUT_MS = 500`, worker join timeout 2000 ms.

State: bounded frame queue; `slots: Vec<BackendSlot{backend, health}>` (Java uses a
CopyOnWriteArrayList so the worker iterates lock-free); `last_requested_kinds: Vec<BackendKind>`;
`running: AtomicBool`; worker `JoinHandle`; `effect_engine: Option<RgbEffectEngine>`.

All lifecycle methods are serialised by one controller mutex (Java `synchronized`).

`start(backends: Vec<Box<dyn RgbBackend>>)`:

1. Filter out `NoOp` entries.
2. If `running` and `same_request_and_all_slots_healthy(real)` -> log debug "same backend set already
   requested" and return without touching anything. The comparison is against the PREVIOUS
   REQUEST (kinds, in order), not against the surviving slots, and fails if any current slot is
   Dead. Why: Windows Dynamic Lighting sometimes fails its start, so a request for
   {ChromaNative, WinDL} yields slots={ChromaNative}; Settings re-calls `start` with the same
   request after unrelated toggles, and comparing against slots would tear down the working
   Chroma session every time. Razer Synapse does not tolerate rapid session churn. Order counts:
   the same set in a different order is a change (the first entry is the "primary" in the UI).
3. If running: stop the worker and shut down every slot (A.6.1). Clear slots.
4. Record `last_requested_kinds` = kinds of `real` (even before knowing which start).
5. For each backend in order: `is_available()` (panic/err -> log warning, skip; false -> log debug,
   skip), then `start()` (err/panic -> log warning, skip), then push a slot with a fresh breaker.
6. If no slots: `running = false`; if an effect engine exists, shut it down and drop it (otherwise
   its ticker would run forever against a stopped controller); log debug; return.
7. `running = true`, spawn daemon worker `mica-rgb`, log "RGB subsystem started on backend(s): a, b".

`start(backend: Option<Box<dyn RgbBackend>>)` convenience: None or NoOp -> `start(vec![])`.

`stop()`: if not running, return (idempotent). Shut the effect engine down first (so it stops
feeding the queue), then stop worker + shut down slots, clear slots, clear
`last_requested_kinds`.

Bug guarded: `stop()` early-returns when not running, so an effect engine created while not
running would leak; that is why `set_effect` refuses to create an engine for `None` (below) and
why step 6 above tears down a stray engine.

`submit_frame(frame)`: if not running, drop. Else non-blocking `try_send`; if the queue is full,
drop silently. Rationale: a stuck backend means the device is already out of sync; queueing stale
frames amplifies the glitch on recovery.

`set_effect(effect: Option<Arc<dyn RgbEffect>>)`: if not running and `effect.is_some()`, return
(no-op when idle). Under the lock: if no engine and `effect.is_none()`, return (do not spin one up
just to idle it). Create the engine lazily. Then (outside the lock) `engine.set_effect(effect)`.
`stop_effect()` = `set_effect(None)`. `active_effect()` = engine's current effect or None.

`status() -> Status { backends: Vec<BackendStatus{name, health, consecutive_failures}>, running }`
in dispatch order. `Status::primary_name()` = first name or `"None"`. `Status::worst_health()` =
Dead if any Dead, else Degraded if any Degraded, else Healthy (Healthy when empty).

Worker loop:

```
while running:
    frame = queue.recv_timeout(500 ms)   // timeout -> continue; disconnected/interrupted -> break
    now = now_ms()
    for slot in slots: if slot.health.can_call(now): render_to(slot, &frame)
```

`render_to`: call `render_frame` inside catch-everything. Ok -> `record_success()`. Err/panic ->
`record_failure(now)`, log warning with state + count. If the new state is Dead: log error
"marked DEAD", call `shutdown()` on that backend (safely) BUT KEEP THE SLOT in the list marked
Dead. Bug this guards: an earlier version removed dead slots, which made `worst_health()`, the
status chip and the `start()` Dead check unreachable, so a dead backend silently vanished from the
UI. If every slot is Dead, log "every active backend has failed". Note the Dead slot's backend has
already been shut down, and `stop_worker_and_shutdown_slots` will call `shutdown()` on it again,
so every `shutdown()` must be idempotent.

#### A.6.1 `stop_worker_and_shutdown_slots`

`running = false`; signal + join the worker with a 2 s bound (Java interrupts the thread; in Rust
drop/close the channel sender or send a `Stop` message, then join with a timeout implemented by
a completion channel); `shutdown()` every slot inside catch-everything; drain the queue.

`safe_name(backend)`: if `name()` panics fall back to the kind's debug name.

### A.7 `RgbEffect` trait and `RgbEffectEngine`

```rust
pub trait RgbEffect: Send + Sync {
    fn name(&self) -> String;
    /// Pure function of elapsed time since activation. None = "no change this tick".
    fn frame_at(&self, elapsed_ms: u64) -> Option<RgbFrame>;
}
```

Effects must not block, must be cheap, and may hold immutable config only (no internal clock).

Engine (`TICK_INTERVAL_MS = 33`, i.e. ~30 fps, fixed rate):

- `set_effect(effect)`: under lock; if the new effect is the SAME INSTANCE as the active one
  (Java `==`; use `Arc::ptr_eq`), return without restarting its clock. Else store it, set
  `effect_start = now`. If `None`: cancel ticking and submit one final `RgbFrame::solid(BLACK)` so
  devices return to a known state. If `Some` and not ticking: start ticking immediately (initial
  delay 0) at fixed rate.
- `stop()` = `set_effect(None)`.
- `shutdown()`: clear the effect, cancel ticking, terminate the thread. Unusable afterwards. Does
  NOT submit a black frame (the backends paint their own on shutdown).
- `tick()`: snapshot (effect, start) under lock; if none, return; `elapsed = now - start`;
  `frame_at(elapsed)` inside catch-everything; Some -> `controller.submit_frame`. A panicking
  effect is logged and that tick is dropped; the effect is NOT removed.

Rust shape: one thread owning a `Receiver<EngineCmd>`; loop with `recv_timeout(until next tick)`
when active, blocking `recv()` when idle. Fixed-rate scheduling: compute `next += 33ms` rather than
sleeping 33 ms after work (Java `scheduleAtFixedRate`). The engine holds a handle to the controller's
`submit_frame` (pass a `Sender`/closure, not the whole controller, to avoid an `Arc` cycle).

### A.8 Effects (pure; port exactly; they have tests)

All periodic effects take `period_ms > 0` (constructor returns an error / panics on 0 or
negative; Java throws `IllegalArgumentException`). `phase = (elapsed % period) / period` in `[0,1)`.

| Effect | Constructor | `frame_at` |
|---|---|---|
| `SolidEffect` | `(name, color)` | always the same pre-built `solid(color)` |
| `PulseEffect` | `(name, a, b, period_ms)` | `t = (1 - cos(2*pi*phase)) / 2`; `solid(blend(a, b, t))`. 0 at start, 1 at half period. |
| `CycleEffect` | `(name, colors: non-empty, period_ms)` | 1 colour -> `solid(c0)`. Else `scaled = phase * n`; `seg = floor(scaled)` clamped to `n-1`; `seg_phase = scaled - seg`; `t = (1 - cos(pi * seg_phase)) / 2`; `solid(blend(colors[seg], colors[(seg+1) % n], t))`. Wraps last -> first. `color_count()`. |
| `RainbowEffect` | `(name, period_ms)` | `hue = phase * 360`; `solid(hsl_to_rgb(hue, 1.0, 0.5))` |
| `InGameEffect` | `(pack_display_name, background, highlight, keys: set)` | static frame: background + each key in `keys` -> highlight. Name `"In-Game"` if pack name empty else `"In-Game (<name>)"`. |

`InGameEffect::DEFAULT_HIGHLIGHTED_KEYS = {W, A, S, D, E, Q, F, SPACE, LEFT_SHIFT, LEFT_CTRL}`.

`hsl_to_rgb(hue_deg, s, l)` (shared by RainbowEffect and ThemeAccentColors, identical code; put it
on `RgbColor` in Rust):

```
h = ((hue % 360) + 360) % 360
c = (1 - |2l - 1|) * s
x = c * (1 - |(h / 60) % 2 - 1|)
m = l - c/2
(r1,g1,b1) = h<60:(c,x,0) h<120:(x,c,0) h<180:(0,c,x) h<240:(0,x,c) h<300:(x,0,c) else:(c,0,x)
channel = clamp(round((v + m) * 255), 0, 255)
```

Java `%` on doubles is `fmod` (sign of dividend) = Rust `%` on `f64`. Good.

`rgb_to_hsl(c)` (ThemeAccentColors): standard; `l = (max+min)/2`; if `max == min` then `h = s = 0`;
else `d = max-min`, `s = l > 0.5 ? d/(2-max-min) : d/(max+min)`; `h` = `(g-b)/d + (g<b ? 6 : 0)` if
max is r, `(b-r)/d + 2` if g, `(r-g)/d + 4` if b; `h *= 60`.

Effect names used by the menu effect (shown in logs / status): `"Menu (rainbow)"`,
`"Menu (solid · <ctx>)"`, `"Menu (pulse · <ctx>)"`, `"Menu (cycle · <ctx>)"`,
`"Menu (breathe · <ctx>)"`, where `<ctx>` is the pack friendly name or the literal
`"theme accent"`. The Settings test effect is `"Connection Test"`. These are internal labels
(not localised in Java).

### A.9 Backends

Common patterns used by several backends (implement once as helpers in Rust):

- **Partial-rig tolerance**: a frame is "delivered" if ANY family/device/endpoint accepted it; the
  backend returns Err only when every attempted one failed. Without this a user who owns only (say)
  Razer fans would trip the breaker on every frame because the keyboard family errors.
- **Never-succeeded drop**: a family/device that has failed `5` consecutive times AND has never
  succeeded this session is permanently dropped from rotation (logged once). A family that worked
  at least once is never dropped (its failures should reach the breaker). Success resets its
  counter.
- **Background-colour dedup** (Corsair, Aura, WinDL only): remember the last pushed packed colour
  (sentinel = "none"); skip the whole push if unchanged. Only update the memory after a successful
  frame. Reset on shutdown. These backends only paint the background, so overrides are ignored.
- **First-success log**: one log line per family/endpoint per session on first success.

#### A.9.1 OpenRGB (`OpenRgbBackend`, `OpenRgbProtocol`)

Pure TCP client, all platforms. Name `"OpenRGB"`. Server `localhost:6742`.

- `is_available`: TCP connect with 500 ms timeout, close; true if connected.
- `start`: connect (2000 ms timeout), read timeout 1000 ms. Then:
  1. `SET_CLIENT_NAME` (id 50), device 0, body = ASCII `"Mica Minecraft Launcher"` + NUL.
  2. `REQUEST_CONTROLLER_COUNT` (id 0), device 0, empty body; read reply (expect id 0); body
     `< 4` bytes -> error; count = u32 LE.
  3. For `i in 0..count`: send `REQUEST_CONTROLLER_DATA` (id 1), device index `i`, body = u32 LE
     protocol version `3`; read reply (expect id 1); parse (below). Parse error -> log, skip that
     device. `led_count == 0` -> skip. Send `RGBCONTROLLER_SETCUSTOMMODE` (id 1100), device `i`,
     empty body, ignore errors (no reply is read). Keyboards (`device_type == 5`) get an LED-name
     -> `KeyboardKey` map; others none. Register `Device{index, type, num_leds, key_map, name}` and
     log "registered <type> #i "<name>" (<n> LEDs, <k> mapped keys)".
  4. No devices -> log "connected but no usable devices" (NOT an error; start succeeds and frames
     are no-ops).
- `render_frame`: for each device build `num_leds` colours all = background packed `0x00RRGGBB`;
  for keyboards, overwrite `key_map[key]` indices with override colours (bounds checked); send
  `RGBCONTROLLER_UPDATELEDS` (id 1050) to that device index. No reply is read. Any I/O error
  propagates (breaker).
- `shutdown`: best-effort send all-zero colours to every device (black), close socket, clear.

Wire format (all little-endian):

- Header, 16 bytes: magic `b"ORGB"`, `u32 device_index`, `u32 packet_id`, `u32 body_len`. Header and
  body written then flushed together.
- Reading: read exactly 16 bytes (short read -> error), check magic (mismatch -> "stream desynced"
  error), if `packet_id != expected` read and discard `body_len` bytes THEN error (keeps the stream
  aligned), read exactly `body_len` bytes (short -> error).
- `UPDATELEDS` body: `u32 data_size` (= total body length = 4 + 2 + 4n), `u16 num_colors`, then per
  LED `R, G, B, 0x00`.
- Controller data body (protocol 3), parsed with a cursor; any underflow -> error "truncated or
  schema mismatch":
  ```
  u32 data_size (ignored); u32 device_type
  str name; str description; str version; str serial; str location
  u16 num_modes; u32 active_mode
  num_modes x { str name; u32 value, flags, speed_min, speed_max, brightness_min,
                brightness_max, colors_min, colors_max, speed, brightness, direction,
                color_mode; u16 n; n x u32 colour }
  u16 num_zones
  num_zones x { str name; u32 type, leds_min, leds_max, leds_count; u16 matrix_size;
                if matrix_size > 0 { u32 h; u32 w; h*w x u32 } }
  u16 num_leds; num_leds x { str name; u32 value }
  ```
  `str` = `u16 len` (INCLUDES trailing NUL), then `len` bytes; strip one trailing NUL; decode UTF-8
  (lossy in Rust). `len == 0` -> "". Only `device_type`, `name`, `description`, LED names are kept.
  Caveat for the port: Java asks for protocol 3 and the server can downshift; the parser
  hard-codes protocol-3 mode fields (brightness fields). It does not do `REQUEST_PROTOCOL_VERSION`
  (id 40 is defined but unused). Protocol 4 added a vendor string and protocol 5 added segments;
  servers answer in the version the client requested, so asking for 3 keeps this layout valid.
  Keep asking for 3.
- Device type labels for logs: 0 motherboard, 1 DRAM, 2 GPU, 3 cooler, 4 LED strip, 5 keyboard,
  6 mouse, 7 mousemat, 8 headset, 9 headset stand, 10 gamepad, 11 light, 12 speaker, 13 virtual,
  else `device (type=N)`.
- LED-name normalisation: uppercase (locale-neutral), trim; strip a leading `"KEY:"` or `"KEY "`
  (then trim); strip a trailing `" KEY"` (then trim). Lookup table: every `KeyboardKey` canonical
  name, plus synonyms `LSHIFT, LEFTSHIFT, LEFT SHIFT -> LEFT_SHIFT`; `LCTRL, LEFTCTRL, LEFT CTRL,
  LEFT CONTROL -> LEFT_CTRL`; `ESC -> ESCAPE`; `RETURN -> ENTER`; `"1".."9" -> NUM_1..NUM_9`. First
  LED index wins for a key (dual-zone keys). Unmatched LEDs keep the background.

Existing crate `openrgb` (async, tokio) exists, but a hand-rolled ~250-line sync client over
`std::net::TcpStream` matches the Java tolerance exactly and avoids pulling tokio into the RGB
thread. Recommend hand-rolled.

#### A.9.2 Razer Chroma native (`ChromaNativeBackend`, `RzChromaSdkLibrary`, `ChromaAppInfo`, `ChromaEffectTypes`)

Windows only. Name `"Razer Chroma (Native)"`. DLL `RzChromaSDK64.dll`, found through the normal
DLL search path (Synapse installs it into System32). Calling convention: `extern "system"`
(stdcall on x86, ignored on x64). Load lazily, once; `None` on non-Windows or load failure.

Exports used (all return `RZRESULT` = i32, 0 = success):

```
Init() ; InitSDK(APPINFOTYPE*) ; UnInit()
CreateKeyboardEffect(i32 effect, void* param, GUID* out_id)
CreateMouseEffect / CreateMousepadEffect / CreateHeadsetEffect / CreateKeypadEffect /
CreateChromaLinkEffect  (same signature)
SetEffect(GUID id)   ; DeleteEffect(GUID id)
```

ABI WARNING: Java declares `SetEffect(Pointer effectId)` / `DeleteEffect(Pointer)`, passing a
pointer to the 16-byte GUID buffer. In Razer's header `RZEFFECTID` is a `GUID` passed BY VALUE.
On x64 the MS ABI passes structs larger than 8 bytes by hidden reference, so passing a pointer to
the GUID is binary-identical and works (validated on hardware). In Rust declare
`extern "system" fn(id: GUID) -> i32` (by value, `#[repr(C)] struct GUID { u32, u16, u16, [u8;8] }`)
which is correct on every ABI, or keep `*const GUID` with a comment that it relies on the x64 ABI.

`APPINFOTYPE` layout (packed, UTF-16LE, zero-filled; total 3592 bytes):

| Offset | Field | Size |
|---|---|---|
| 0 | `wchar_t Title[256]` | 512 |
| 512 | `wchar_t Description[1024]` | 2048 |
| 2560 | `Author.Name[256]` | 512 |
| 3072 | `Author.Contact[256]` | 512 |
| 3584 | `DWORD SupportedDevice` | 4 |
| 3588 | `DWORD Category` | 4 |

(3592 bytes total; Java allocates `512+2048+512+512+4+4`.) Each string copied as UTF-16LE truncated
to `capacity - 2` bytes so the last wchar is always NUL. Values: Title `"Mica Minecraft Launcher"`,
Description `"Modpack-aware RGB lighting for Minecraft sessions."`, Author `"Mica Technologies"`,
Contact `"https://github.com/Mica-Technologies/minecraft-launcher"`, SupportedDevice =
`0x01|0x02|0x04|0x08|0x10|0x20` (keyboard, mouse, headset, mousepad, keypad, chromalink),
Category = 1 (application; 2 = game). Keep the struct buffer alive for the whole session (Java keeps
it in a field because Synapse may read it after `InitSDK` returns).

Effect type integers DIFFER PER FAMILY (mixing them up gives result 87 INVALID_PARAMETER):

| Family | Type used | Value | Param |
|---|---|---|---|
| Keyboard | CHROMA_CUSTOM | 2 | `COLORREF[6][22]` = 528 bytes, offset `(row*22+col)*4` |
| Mouse | CHROMA_STATIC | 6 | `{ u32 LEDId = 0 (all), COLORREF }` = 8 bytes |
| Mousepad | CHROMA_CUSTOM2 | 6 | `COLORREF[20]` = 80 bytes, every entry = colour |
| Headset | CHROMA_STATIC | 1 | `COLORREF` = 4 bytes |
| Keypad | CHROMA_STATIC | 4 | `COLORREF` |
| ChromaLink | CHROMA_STATIC | 2 | `COLORREF` |

(Defined but unused: KEYBOARD_STATIC = 4, CHROMALINK_CUSTOM = 1 with `COLORREF[5]`.)

CRASH PITFALL (the "launcher silently vanishes" bug, found 2026-10): the mousepad CUSTOM2 param was
once a 4-byte block; the SDK copies 80 bytes (`CUSTOM_EFFECT_TYPE2 { RZCOLOR Color[MAX_LEDS2=20] }`),
reading 76 bytes past the allocation every frame. When the block ended a mapped heap page this was
an access violation (`0xc0000005`) inside `RzChromaSDK64.dll` (3.8.0.234, offsets 0xcd93-0xcdab),
killing the process with no Java log line and no `hs_err` file. It shows up only in Windows Event
Viewer (Application log, Id 1000). Rule for the port: every param buffer must be at least the
size of the struct the SDK reads for that effect type; never smaller. Unit tests pin the sizes.

COLORREF packing for Chroma (both native and REST): `(b << 16) | (g << 8) | r` (R in the low byte).

Keyboard grid (`ChromaKeyboardLayout`, 6 rows x 22 cols, shared with REST): fill with background,
then set override keys at (row, col): ESC (0,1); F1..F12 (0,3)..(0,14); NUM_1..NUM_9 (1,2)..(1,10);
TAB (2,1); Q (2,2); W (2,3); E (2,4); T (2,6); A (3,2); S (3,3); D (3,4); F (3,5); ENTER (3,14);
LEFT_SHIFT (4,1); LEFT_CTRL (5,1); SPACE (5,7). Values mirror Razer `RZKEY_*` = `(row << 8) | col`.
Unmapped keys stay background. Non-existent keys on a model are silently ignored by the SDK.

Lifecycle:

- `is_available` = DLL loadable.
- `start`: error if not loadable; build APPINFOTYPE; `InitSDK`; non-zero -> drop the buffer, error
  `"InitSDK returned result=N <label>"`. Success -> `initialized = true`, log.
- `render_frame` (no-op unless initialized): build all four param buffers from this frame; for each
  family in order keyboard, mouse, mousepad, headset, keypad, chromalink that is not permanently
  dropped: `attempts += 1`, `try_family`. If `attempts == 0` return Ok (no Razer hardware; not a
  failure). If `successes == 0` return Err with the last result code and its label.
- `try_family(name, create)`: 16-byte GUID out buffer; `create` != 0 -> record failure ("Create"),
  false. `SetEffect`; ALWAYS `DeleteEffect` afterwards (even if SetEffect failed) to avoid leaking
  effect IDs; SetEffect != 0 -> record failure ("SetEffect"), false. Success: reset counter, first
  success log, true.
- `record_family_failure`: increment; if never succeeded and count >= 5 -> drop permanently, log
  "giving up on family" once, and suppress further logging; else log a silent warning with the
  result label.
- `shutdown` (no-op unless initialized): best-effort black frame, `UnInit`, reset all per-family
  sets, drop the APPINFOTYPE buffer. Note `UnInit` means the SDK "cannot be reinitialized without
  re-loading the DLL" per the Java comment; in practice the Settings restart path does call
  `InitSDK` again on the same loaded DLL and it works on hardware. Keep the same behaviour.

Result labels (`describe_result`): 0 SUCCESS; 5 ACCESS_DENIED; 50 NOT_SUPPORTED; 87
INVALID_PARAMETER; 126 MOD_NOT_FOUND; 1062 SERVICE_NOT_ACTIVE; 1152 SINGLE_INSTANCE_APP; 1167
DEVICE_NOT_CONNECTED; 1168 NOT_FOUND; 1247 ALREADY_INITIALIZED; 4309 RESOURCE_DISABLED; 4319
DEVICE_NOT_AVAILABLE; 5023 NOT_VALID_STATE; else unknown.

Hardware facts (user's rig, from project memory): works end-to-end with a Chroma keyboard
(Huntsman V3 Pro) connected; Chroma Connect case fans only follow when a Razer-native primary
device is in the chain.

#### A.9.3 Razer Chroma REST (`ChromaRestBackend`), deprecated

Name `"Razer Chroma"`. Default-off in Auto mode. Works only on older Synapse; current Synapse
returns result 126 on every push.

- `is_available`: TCP connect to `localhost:54235`, 500 ms timeout (no HTTP; the init POST has
  side effects).
- `start`: HTTP client, connect + request timeout 1000 ms. `POST http://localhost:54235/razer/chromasdk`
  `Content-Type: application/json`:
  ```json
  {"title":"Mica Minecraft Launcher",
   "description":"Modpack-aware RGB lighting for Minecraft sessions.",
   "author":{"name":"Mica Technologies","contact":"https://github.com/Mica-Technologies/minecraft-launcher"},
   "device_supported":["keyboard","mouse","mousepad","headset","keypad","chromalink"],
   "category":"application"}
  ```
  Non-200 -> error with body. Body JSON must contain `"uri"` (string) -> session URI (server picks
  another port), else error. Start heartbeat thread.
- Heartbeat: every 10 000 ms `PUT {uri}/heartbeat` (empty body, response discarded); failures are
  logged silently only (the next render will fail and reach the breaker). Session expires after
  ~15 s without traffic.
- `render_frame` (no-op without session): build the 6x22 grid as in A.9.2; then independently:
  `PUT {uri}/keyboard` body `{"effect":"CHROMA_CUSTOM","param":[[c,c,...22],...6 rows]}` (ints in
  decimal), and for each of `/mouse /mousepad /headset /keypad /chromalink`:
  `{"effect":"CHROMA_STATIC","param":{"color":C}}`. Each PUT: non-200 -> error; else parse
  `result` from the JSON body: blank body or missing field -> 0; unparseable JSON -> -1; non-zero ->
  error with label. All six fail -> Err "all 6 endpoints failed" (with last cause); otherwise Ok.
  Razer reports SDK errors as `result` inside a 200 reply, so checking only HTTP status misses them.
- On the first `result == 126` of the session, log a multi-line user hint once (keys
  `log.rgb.chroma.hint126.*`: explains the REST path is deprecated and recommends OpenRGB).
- REST result labels: 0 SUCCESS; 1 RZRESULT_FAILED; 5 ACCESS_DENIED (check Synapse SDK access);
  50 NOT_SUPPORTED; 87 INVALID_PARAMETER; 126 MOD_NOT_FOUND; 1168 NOT_FOUND; 1247
  ALREADY_INITIALIZED; 4309 RESOURCE_DISABLED; 4319 DEVICE_NOT_AVAILABLE. Also seen in the wild:
  -1 generic, -57 NOT_VALID_STATE (session dropped), -1073741275 invalid parameter.
- `shutdown`: best-effort black frame; stop + join heartbeat (1 s); `DELETE {uri}` (best-effort;
  without it the session lingers ~15 s and the next start may hit Synapse's per-app session cap);
  clear state, reset the 126-hint flag.

Note: this backend sends six HTTP PUTs per frame at 30 fps with no dedup.

#### A.9.4 Corsair iCUE (`CorsairBackend`, `CorsairSdkLibrary`)

Windows only. Name `"Corsair iCUE"`. iCUE SDK v4-style API (Java calls it "v3"; the exports are
the `CorsairConnect`/`CorsairGetDevices` generation). DLL candidates tried in order:
`iCUESDK.x64_2019`, `iCUESDK.x64_2017`, `iCUESDK_x64`, `iCUESDK` (search path). First that loads wins.

Constants: `DEVICE_ID_LEN = 128` (`CorsairDeviceId` = `char[128]`), `CDT_ALL = 0x7FFFFFFF`,
`MAX_DEVICES = 64`, `MAX_LEDS_PER_DEVICE = 512`, connect timeout 8000 ms, drop threshold 5.
Errors `CorsairError`: 0 Success, 1 NotConnected, 2 NoControl, 3 IncompatibleProtocol,
4 InvalidArguments, 5 InvalidOperation, 6 DeviceNotFound, 7 NotAllowed. Session states: 0 Invalid,
1 Closed, 2 Connecting, 3 Connected, 4 Subscribed, 5 ConnectionLost, 6 ConnectionRefused,
7 ConnectionTimeout.

Exports and structs (`#[repr(C)]`):

```
CorsairConnect(cb: extern "system" fn(ctx: *mut c_void, ev: *const CorsairSessionStateChanged), ctx) -> i32
CorsairDisconnect() -> i32
CorsairGetDevices(filter: *const CorsairDeviceFilter{ i32 deviceTypeMask }, sizeMax: i32,
                  devices: *mut CorsairDeviceInfo, size: *mut i32) -> i32
CorsairGetLedPositions(deviceId: *const c_char /*[128]*/, sizeMax: i32,
                       ledPositions: *mut CorsairLedPosition, size: *mut i32) -> i32
CorsairSetLedColors(deviceId: *const c_char, size: i32, ledColors: *const CorsairLedColor) -> i32
CorsairDeviceInfo   { i32 type; char id[128]; char serial[128]; char model[128]; i32 ledCount; i32 channelCount }
CorsairLedPosition  { i32 id; f64 cx; f64 cy }      // 24 bytes with padding after id
CorsairLedColor     { u32 id; u8 r, g, b, a }
CorsairSessionStateChanged { i32 state; ... details (ignored) }
```

`start`:
1. Error if DLL not loadable.
2. Register a state callback that reads the first i32 (`state`) of the event: Connected/Subscribed
   -> remember state, release latch; ConnectionRefused/Timeout/Lost/Closed -> remember, release;
   Connecting -> wait. The callback runs on an SDK thread. KEEP THE CALLBACK ALIVE for the whole
   session (Java holds a field so the JNA trampoline is not GC'd; in Rust use a plain
   `extern "system" fn` plus a `'static`/leaked or `Arc`-owned context, and only free the context
   after `CorsairDisconnect` returns).
3. `CorsairConnect(cb, ctx)` != 0 -> error. Wait on the latch up to 8 s; timeout -> `Disconnect`,
   error "iCUE service might not be running". Terminal state not Connected/Subscribed ->
   `Disconnect`, error naming the state.
4. `connected = true`; enumerate: `GetDevices(filter CDT_ALL, 64, buf, &n)`; error -> log and
   leave empty. For each `i < n`: `GetLedPositions(id, 512, buf, &m)` (error -> skip device), take
   the LED `id`s; skip devices with 0 LEDs; copy the 128-byte id; model = bytes up to first NUL
   (UTF-8). Log each device.
5. No devices -> `Disconnect`, `connected = false`, error ("connected but no devices found").

`render_frame` (no-op unless connected with devices): dedup on packed background; for each
non-dropped device build `CorsairLedColor[led_count]` with every id set to `(r,g,b,a=0xFF)` and call
`SetLedColors`. 0 -> success bookkeeping; else failure bookkeeping (drop rule); a panic/exception is
treated as InvalidOperation. All attempted devices failed -> Err (dedup memory not updated). Else
update dedup memory.

`shutdown`: `Disconnect` if connected (iCUE restores its own profile), clear devices, reset dedup,
drop callback context.

JNA pitfall that disappears in Rust: Java's `Structure.toArray()` shares one native block and needs
`read()`/`write()` per element. With `#[repr(C)]` `Vec`s this is automatic.

#### A.9.5 ASUS Aura (`AsusAuraBackend`, `AsusAuraSdkLibrary`)

Windows only. Name `"ASUS Aura"`. Legacy `AURA_SDK.dll`; candidates `AURA_SDK`, `AuraSDK`,
`AURA_SDK.x64`. Modes: `MODE_SOFTWARE = 1` (we drive), `MODE_DEFAULT = 0` (give back). Drop
threshold 5.

Per family, function names (all stdcall, i32 return):

| Family label | Create | LED count | Mode | Color | Release |
|---|---|---|---|---|---|
| `Motherboard` | `CreateMbController(void**)` | `GetMbLedCount(void*)` | `SetMbMode(void*, i32)` | `SetMbColor(void*, u8*, i32 size)` | `ReleaseMbController(void**)` |
| `GPU` | `CreateGPUController` | `GetGPULedCount` | `SetGPUMode` | `SetGPUColor` | `ReleaseGPUController` |
| `RAM` | `CreateRAMController` | `GetRAMLedCount` | `SetRAMMode` | `SetRAMColor` | `ReleaseRAMController` |
| `Claymore Keyboard` | `CreateClaymoreKeyboard` | `GetClaymoreKeyboardLightCount` | `SetClaymoreKeyboardMode` | `SetClaymoreKeyboardColor` | `ReleaseClaymoreKeyboard` |

Caveat for the port: the real AURA_SDK headers declare `CreateXController(MbLightControl* handles)`
returning a COUNT of controllers into a caller array, and `ReleaseXController` may not exist in
every SDK build. The Java binding treats Create as "out pointer, rc < 0 = failure" and has never
been validated on ASUS hardware. Resolve each symbol individually with `libloading` and treat a
missing symbol as "family unavailable" (Java would fail the JNA call and skip the family).

`start`: error if DLL not loadable. For each family in the order above (`try_init`): call Create
(panic -> skip, debug log); `rc < 0` or null handle -> skip; LED count (panic -> 0); `<= 0` ->
Release, skip; best-effort `SetMode(SOFTWARE)`; register the family with an apply closure
(`SetColor(handle, buf, buf.len())`) and a release closure (`SetMode(DEFAULT)` then `Release`,
both best-effort). No families -> error suggesting OpenRGB. Log "N family/families connected — Motherboard (x LEDs), ...".

`render_frame`: dedup on packed background; per non-dropped family build `3 * led_count` bytes of
`R,G,B` (RGB order per ASUS docs; if a user reports swapped colours, BGR is the first suspect);
`rc >= 0` is success (some calls return the LED count); `< 0` -> failure bookkeeping; panic -> rc -1.
All failed -> Err. `shutdown`: run each release closure (log failures), clear, reset dedup.

#### A.9.6 Windows Dynamic Lighting (`WindowsDynamicLightingBackend`, `winrt/Combase`, `winrt/WinRt`)

Windows 10/11. Name `"Windows Dynamic Lighting"`. Uses `Windows.Devices.Lights.LampArray`. Only
sees HID Lighting & Illumination devices (usage page 0x59) that the user enabled under Settings >
Personalization > Dynamic Lighting. Razer hardware does not participate (Synapse overrides).
Validated on a Logitech G502 X PLUS.

History: originally a PowerShell subprocess backend whose handshake always timed out; replaced by
a JNA vtable bridge to `combase.dll` (~600 lines). In Rust use the `windows` crate's projected
WinRT types instead, which removes all the vtable-index and struct-packing hazards. The Java
details are recorded here so the port can cross-check behaviour:

- Activation: `RoGetActivationFactory("Windows.Devices.Lights.LampArray", IID_ILampArrayStatics
  {7BB8C98D-5FC1-452D-BB1F-4AD410D398FF})` and `("Windows.Devices.Enumeration.DeviceInformation",
  IID_IDeviceInformationStatics {C17F100E-3A46-4A78-8013-769DC9B97390})`.
- Vtable slots used: ILampArrayStatics 6 `GetDeviceSelector`, 7 `FromIdAsync`;
  IDeviceInformationStatics 10 `FindAllAsyncAqsFilter`; IAsyncInfo (IID
  `{00000036-0000-0000-C000-000000000046}`) 7 `get_Status`; IAsyncOperation 8 `GetResults`;
  IVectorView 6 `GetAt`, 7 `get_Size`; IDeviceInformation 6 `get_Id`; ILampArray 23 `SetColor`.
- `Windows.UI.Color` is `{A,R,G,B}` bytes; passed by value in one register it packs as
  `(B<<24)|(G<<16)|(R<<8)|A`, not ARGB. (Irrelevant with windows-rs, which passes the struct.)

Behaviour to port:

- `is_available`: Windows and WinRT loadable (with windows-rs: `cfg(windows)` and true; optionally
  check the `Windows.Devices.Lights.LampArray` factory activates, but keep it cheap and side-effect
  free; Java does not activate anything here).
- `start`: initialise WinRT MTA on the calling thread (`RoInitialize(RO_INIT_MULTITHREADED)`;
  `S_OK` and `S_FALSE` are success; `RPC_E_CHANGED_MODE` 0x80010106 = apartment conflict -> error).
  `selector = LampArray::GetDeviceSelector()`; log it at debug. `op =
  DeviceInformation::FindAllAsyncAqsFilter(selector)`; wait up to 5000 ms polling `Status` every
  25 ms (Started = still running -> timeout error; Canceled/Error -> error "device enumeration
  was cancelled / reported an error / timed out"). For each `DeviceInformation` in the collection:
  `id = info.Id()`; `LampArray::FromIdAsync(id)` with the same 5 s poll; failure/timeout/null result
  -> log, skip that device. Cache each `LampArray`. If none opened -> error "enumeration reported N
  device(s) but none opened — check that 'Use dynamic lighting on my devices' is on". On any error
  release everything opened so far. Log "N LampArray device(s) connected".
- `render_frame` (no-op unless started): ensure WinRT is initialised on THIS thread (thread-local
  flag; the `mica-rgb` worker is a different thread from the one that ran `start`, and MTA objects
  are legal from any MTA thread); dedup on the colour; `lamp.SetColor(Color{A:255,R,G,B})` on every
  cached lamp; count successes; all failed -> Err with last HRESULT. Update dedup only on success.
- `shutdown`: `started = false`, drop all lamps (Release), reset dedup. Deliberately never call
  `RoUninitialize` (per-thread refcounted; tearing down the MTA here could give
  `CO_E_NOTINITIALIZED` later on a worker that still holds objects).

Polling instead of `.get()`: windows-rs offers blocking `IAsyncOperation::get()`, but it has no
timeout. Keep the poll loop (`op.Status()` every 25 ms until deadline; then `op.Cancel()` and
treat as timeout) so a wedged WinRT call can never block the bootstrap thread forever.

### A.10 Crash pitfalls and containment summary

1. Native faults in vendor DLLs are uncatchable and kill the whole launcher silently (A.9.2). Size
   every param buffer to the struct the SDK reads. Diagnose with Event Viewer Application Id 1000
   (`Get-WinEvent` filtered on the launcher exe), not the launcher log.
2. Callbacks handed to native code (Corsair) must outlive the session.
3. Buffers handed to native code (Razer APPINFOTYPE) must outlive the call (and the session, to be
   safe).
4. Per-family effect-type integers differ (Razer); wrong value = result 87 forever.
5. Missing-hardware families would otherwise spam logs at 30 fps and trip the breaker; use the
   never-succeeded drop + "any success = delivered" rules.
6. Rapid session churn upsets Synapse; `start()` is idempotent against the same request, and the
   Settings toggles only restart on a real value change.
7. WinRT needs per-thread init; never uninit.
8. Recommended (new) defence for the port: run the Windows vendor-DLL backends (Razer native,
   Corsair, Aura) in a small helper child process (`mmcl-app --rgb-host` speaking a line-based
   or bincode pipe protocol) so an SDK access violation kills only the helper; the controller sees
   pipe errors and the breaker handles it. This is an open question (see the end), not a Java
   behaviour.

### A.11 Backend registry and config

Config keys (JSON booleans/strings in the launcher config file, owned by the config subsystem):

| Key | Type | Default | Notes |
|---|---|---|---|
| `rgbEnable` | bool | `false` | master switch; off = nothing probes, no threads |
| `rgbBackend` | string | `"auto"` | `auto`, `openrgb`, `chroma` (REST), `chroma-native`, `windows-dl`, `corsair`, `asus-aura`, `none` |
| `rgbEnableOpenRGB` | bool | `true` | Auto-mode filter |
| `rgbEnableChromaNative` | bool | `true` | |
| `rgbEnableChromaRest` | bool | `false` | deprecated path |
| `rgbEnableWindowsDL` | bool | `true` | |
| `rgbEnableCorsair` | bool | `true` | |
| `rgbEnableAsusAura` | bool | `true` | |
| `rgbMenuEffectEnable` | bool | `true` | idle menu effect on/off |
| `rgbEffectStyle` | string | `"breathe"` | `solid`, `breathe`, `pulse`, `cycle`, `rainbow`; unknown read -> default; unknown write ignored |
| `rgbUsePackColors` | bool | `true` | in-game colours from pack logo |
| `rgbHighlightKeys` | bool | `true` | in-game WASD etc. highlight |

JAVA BUG to fix in the port: `RgbConfig.getRgbBackend()` / `setRgbBackend()` validate against
`auto, openrgb, chroma, chroma-native, windows-dl, none` only. `corsair` and `asus-aura` are NOT in
that list, so picking "Corsair iCUE" or "ASUS Aura" in the Settings dropdown stores `"auto"` and the
dropdown snaps back to Auto: manual mode for those two is unreachable. The Rust config must accept
all eight values. (Auto mode does reach both.)

`resolve_backends_from_config() -> Vec<Box<dyn RgbBackend>>`:
- `rgbEnable == false` -> empty.
- `auto` -> probe in this fixed order, each only if its enable flag is on, keeping those whose
  `is_available()` returns true (a panicking probe = unavailable, logged): OpenRGB, ChromaNative,
  WindowsDynamicLighting, Corsair, AsusAura, ChromaRest. Order matters: the first is the "primary"
  name in the status chip and the dispatch order. Log each inclusion/skip and "no enabled backend
  available" when empty. Worst case is the sum of ~500 ms probes.
- explicit name -> exactly that one backend (no probe here; the controller probes), ignoring the
  per-backend flags.
- `none` -> empty. Unknown -> warn and treat as `auto`.

Pure seam for tests: `filter_candidates(Vec<(Box<dyn RgbBackend>, bool)>) -> Vec<..>`.

Legacy single-backend helpers `resolveFromConfig()` (first or NoOp) and `resolve(name)` (unknown ->
NoOp) exist for back-compat/tests only; port `resolve(name)` only if a debug CLI wants it.

Note that Auto probing calls `is_available()` once in the registry and again in
`controller.start()`; both must be cheap and side-effect free.

### A.12 `RgbIntegration`: what drives effects (the public glue)

All functions: return immediately if `rgbEnable` is false; never propagate failures (log silently).

- `bootstrap()`: called once per launcher session after args/config are resolved (must be after
  game mode is known, because config paths depend on it: the "early main() path trap"). If enabled,
  on a background task: `controller.start(resolve_backends_from_config())`; then if
  `GameSessionRegistry::newest_running()` exists (an in-process restart while a game runs),
  `on_play_started(that.pack)` and return; else `apply_menu_effect(None)`.
- `on_menu(context_pack: Option<&Pack>)`: if a game is running (`newest_running().is_some()`),
  return (the devices show the game, and nothing would put the game effect back). Else
  `apply_menu_effect(context_pack)`. Callers: modpack details modal open (`on_menu(Some(pack))`,
  off the UI thread because SDK calls cost tens of ms), modal close (`on_menu(None)`), Settings menu
  effect toggle, effect style change, theme change, and Settings save.
- `apply_menu_effect(pack)`:
  - menu effect disabled -> `controller.stop_effect()` (final black frame) and return.
  - style `rainbow` -> `RainbowEffect("Menu (rainbow)", 8000)`.
  - palette = `resolve_palette(pack, style)`; empty -> `[accent_for_current_theme()]`;
    `primary = palette[0]`.
  - `solid` -> `SolidEffect(primary)`; `pulse` -> `PulseEffect(primary, primary.complement(), 1500)`;
    `cycle` -> `CycleEffect(palette, max(1, n) * 2500)`; default/`breathe` ->
    `PulseEffect(primary, blend(primary, BLACK, 0.65), 4000)`.
- `resolve_palette(pack, style)`: `wanted = 4` for cycle else 1. With a pack: sample the logo
  (`sample_dominant_pack_palette(pack, 4)` for cycle, else the 2-colour sampler); convert non-null
  entries; if non-empty: for cycle with fewer than 3 colours return
  `derive_palette(out[0], 3)`, else return `out`. Without a pack or when sampling gives nothing:
  theme accent; cycle -> `derive_palette(accent, 3)`; else `[accent]`.
- `on_play_started(pack)`: colours: if `rgbUsePackColors` and the 2-colour sample has a primary,
  `primary = s[0]`; `highlight = s[1]` if present and `colour_distance(s0, s1) > 0.25` (Euclidean
  in normalised 0..1 RGB) else `primary.complement()`. Otherwise defaults
  `primary = (60, 90, 160)` "Mica blue", `highlight = (240, 195, 60)` gold. Keys =
  `DEFAULT_HIGHLIGHTED_KEYS` if `rgbHighlightKeys` else empty. `set_effect(InGameEffect(pack
  friendly name or "", primary, highlight, keys))`.
- `on_play_ended()`: `apply_menu_effect(None)` (controller stays running).
- `shutdown()`: `controller.stop()` (sync; the app's cleanup runs it bounded to 3 s).

Who calls `on_play_started` / `on_play_ended`: the `RunningGameFollower` (game/session subsystem),
installed on the first launch through `LauncherCore.play`. It follows the newest RUNNING session
(`phase == Running`, latest `started_ms`, ties go to the later one in the list) and calls
`show_game(session)` only when the followed session changes, and `show_no_game()` when it goes from
some to none. Updates run on one ordered daemon thread (`LatestUpdateRunner`, which skips an update
that a newer one replaced) so a slow "show game" (logo sampling) cannot land after a quick "no game".
`show_game` -> `RgbIntegration::on_play_started(pack)` + Discord game presence; `show_no_game` ->
`on_play_ended()` + Discord menu presence. After an in-process restart, `resync()` re-shows.

GAP in Java: the TUI launches games without going through `LauncherCore.play`, so the follower is
never installed in `--cli` mode and the keyboard stays on the menu effect during TUI-launched
games. The Rust port should install the follower at session start (or on the first admitted
session from any front end).

#### Theme accent colours (`ThemeAccentColors`)

Mirrors `-color-primary` in `src/main/resources/ui/ui-tokens-*.css` (keep in sync with the GUI
theme spec):

| Theme value (config `theme`) | Accent |
|---|---|
| `Dark` | `#6FCF3D` |
| `Light` | `#3C8527` |
| `Blue+gray` | `#0668E1` |
| `Creeper` | `#43D22D` |
| `Orange+purple` | `#D257DB` |
| `Native (Mica)`, `Automatic` | OS dark ? `#6FCF3D` : `#3C8527` |
| anything else / null | `#6FCF3D` |

OS dark detection defaults to dark when unknown. Also used by the macOS toolbar tint
(`MacOsToolbarManager`), so expose it publicly.

`derive_palette(primary, count)`: `count < 1` -> 1; 1 -> `[primary]`; else convert to HSL, step 30
degrees, `start = -30 * (count - 1) / 2`, hue_i = `((h + start + 30 i) % 360 + 360) % 360`, same s
and l, back to RGB. The primary is the middle of the spread.

#### Pack logo colour sampling (needed from the GUI/imaging side)

RGB needs `sample_dominant_pack_palette(pack, count) -> Option<Vec<RgbColor>>` and the 2-colour
variant `sample_dominant_pack_colors(pack) -> Option<(RgbColor, Option<RgbColor>)>`. Java algorithm
(in `MCLauncherMainGui`, shared with the hero-card gradient, cached per pack-logo key):

- `None` if no pack, vanilla pack, `count < 1`, no logo file on disk, or decode error.
- Decode the logo scaled to fit 64x64 preserving ratio (smooth = true).
- `step = max(1, min(w, h) / 32)`; sample every `step` pixels in both axes; skip alpha < 0.5.
- `sat = max(r,g,b) - min(r,g,b)` (0..1); `weight = 1 + floor(sat * 20)`.
- Bucket 16 levels per channel: `idx = min(15, floor(c * 16))`, 4096 buckets; accumulate weight
  and weighted channel sums.
- Greedy: repeat `count` times: heaviest bucket (strictly greater than the current best) whose
  average colour is at Euclidean distance `> 0.25` from every already picked colour; stop when none.
  Empty -> None.
- The 2-colour variant returns `(p[0], p.get(1))`.
- Cache: palette per logo key; a cached palette with `>= count` entries is sliced.
- Java colour conversion: `round(channel * 255)` clamped.

Put this in a small shared crate or in the GUI crate behind a function the RGB crate receives as a
callback (`PaletteSource` trait) so `mmcl-rgb` does not depend on the GUI.

### A.13 Settings RGB tab behaviour (owned by GUI, but RGB-facing)

- Backend dropdown order: Auto, OpenRGB, Razer Chroma (Native), Razer Chroma (REST), Windows
  Dynamic Lighting, Corsair iCUE, ASUS Aura, None. Product names unlocalised; Auto/None localised
  (`settings.rgb.backend.auto/none`). Unknown stored value selects Auto.
- Changing backend or master toggle or any per-backend Auto toggle: only on a REAL value change,
  save config, refresh visibility, restart the controller on a background task: `stop()`, then if
  enabled `start(resolve_backends_from_config())`, then refresh the chip.
- Per-backend Auto toggles are visible only when backend = auto and RGB is enabled.
- Menu-effect toggle and effect-style change: save, then `RgbIntegration::on_menu(None)`. Style
  labels: Solid, Breathe, Pulse, Cycle, Rainbow.
- Pack-colours and highlight-keys toggles: save only (take effect at the next game start).
- Status chip text/style: RGB disabled -> `settings.rgb.status.disabled` (muted); not running or no
  slots -> `notDetected` (warn); else label = primary name plus `" +N"` for extra backends, worst
  health Healthy -> `connected` (success), Degraded -> `degraded` (warn), Dead -> `unavailable`
  (danger).
- Test button: on a background task; if RGB disabled -> warning toast
  (`notification.settings.rgbDisabled.*`); else remember `active_effect()`, set
  `SolidEffect("Connection Test", (255, 0, 255))` (fixed magenta so it reads the same in every
  theme), sleep 2000 ms, restore the prior effect (None = stop, final black), refresh chip.
  Note: when no backend is running, `set_effect` is a no-op, so the test silently does nothing.

### A.14 Java class -> Rust map (RGB)

| Java | Rust (crate `mmcl-rgb`) | Key items |
|---|---|---|
| `RgbColor` | `color::RgbColor` | `BLACK`, `WHITE`, `from_hex`, `blend`, `complement`, `pack_rgb`, `chroma_pack`, `hsl_to_rgb`, `to_hsl` |
| `KeyboardKey` | `key::KeyboardKey` | enum + `canonical_name()`, `ALL` |
| `RgbFrame` | `frame::RgbFrame` | `solid`, `new(bg, overrides)`, `color_for`, `overrides()` |
| `RgbEffect` | `effect::RgbEffect` trait | `name`, `frame_at` |
| `effects/*` | `effect::{SolidEffect, PulseEffect, CycleEffect, RainbowEffect, InGameEffect}` | constructors return `Result` for invalid period / empty palette |
| `RgbBackend` | `backend::RgbBackend` trait + `BackendKind` | see A.4 |
| `RgbBackendHealth` | `health::BackendHealth`, `health::HealthState` | `can_call`, `record_success`, `record_failure` |
| `RgbController` | `controller::RgbController` (global via `std::sync::OnceLock`) | `global()`, `start`, `stop`, `submit_frame`, `set_effect`, `stop_effect`, `active_effect`, `status` |
| `RgbController.Status/BackendStatus` | `controller::{Status, BackendStatus}` | `primary_name`, `worst_health` |
| `RgbEffectEngine` | `engine::EffectEngine` (private) | thread + command channel |
| `RgbBackendRegistry` | `registry` | `resolve_backends(&RgbSettings)`, `filter_candidates` |
| `RgbIntegration` | `integration` | `bootstrap`, `on_menu`, `on_play_started`, `on_play_ended`, `shutdown` |
| `ThemeAccentColors` | `theme::{accent_for_theme, derive_palette}` | takes theme string + `os_is_dark` |
| `NoOpBackend` | `backends::noop::NoOpBackend` | |
| `OpenRgbBackend`, `OpenRgbProtocol` | `backends::openrgb::{OpenRgbBackend, proto}` | pure-Rust; `proto::{write_packet, read_packet, parse_controller_data, update_leds_body}`, `led_name_map` |
| `ChromaKeyboardLayout` | `backends::chroma::layout` | `ROWS`, `COLS`, `coord_of`, `build_grid(bg, overrides) -> [[u32;22];6]` |
| `ChromaRestBackend` | `backends::chroma::rest::ChromaRestBackend` | blocking HTTP |
| `ChromaNativeBackend`, `RzChromaSdkLibrary`, `ChromaAppInfo`, `ChromaEffectTypes` | `backends::chroma::native::{ChromaNativeBackend, sdk, app_info, params}` | `#[cfg(windows)]`; param builders return `Vec<u8>` / `#[repr(C)]` arrays (testable on every OS) |
| `CorsairBackend`, `CorsairSdkLibrary` | `backends::corsair::{CorsairBackend, sdk}` | `#[cfg(windows)]` |
| `AsusAuraBackend`, `AsusAuraSdkLibrary` | `backends::asus_aura::{AsusAuraBackend, sdk}` | `#[cfg(windows)]`, per-symbol optional |
| `WindowsDynamicLightingBackend`, `winrt/Combase`, `winrt/WinRt` | `backends::dynamic_lighting::DynamicLightingBackend` | `#[cfg(windows)]`, windows-rs; the vtable toolkit is not ported |
| `RgbConfig` (config package) | config subsystem; RGB reads a `RgbSettings` snapshot | |

On non-Windows builds the Windows backends compile to stubs whose `is_available()` returns false
(so the registry code stays identical on every OS and the dropdown entries still exist).

### A.15 Public API sketch (`mmcl-rgb`)

```rust
// ---- inputs the RGB crate needs, injected at bootstrap ----
pub struct RgbSettings {
    pub enabled: bool,
    pub backend: BackendChoice,           // Auto | OpenRgb | ChromaRest | ChromaNative | WindowsDl | Corsair | AsusAura | None
    pub auto_enabled: AutoToggles,        // openrgb, chroma_native, chroma_rest, windows_dl, corsair, asus_aura
    pub menu_effect_enabled: bool,
    pub effect_style: EffectStyle,        // Solid | Breathe | Pulse | Cycle | Rainbow
    pub use_pack_colors: bool,
    pub highlight_keys: bool,
    pub theme: String,                    // config "theme" value
}

pub trait RgbHost: Send + Sync + 'static {
    fn settings(&self) -> RgbSettings;                       // read live config each time
    fn os_is_dark(&self) -> bool;
    /// None if vanilla / no logo / undecodable. `count >= 1`.
    fn pack_palette(&self, pack: &PackRef, count: usize) -> Option<Vec<RgbColor>>;
    /// Newest running game, if any (GameSessionRegistry::newest_running).
    fn newest_running_pack(&self) -> Option<PackRef>;
}

#[derive(Clone)]
pub struct PackRef { pub key: String, pub friendly_name: String /* + whatever pack_palette needs */ }

pub mod integration {
    pub fn install(host: Arc<dyn RgbHost>);                 // once, at session start
    pub fn bootstrap();                                     // spawns a task; returns at once
    pub fn on_menu(context: Option<&PackRef>);
    pub fn on_play_started(pack: &PackRef);
    pub fn on_play_ended();
    pub fn shutdown();                                      // blocking, bounded by caller
}

pub struct RgbController { /* private */ }
impl RgbController {
    pub fn global() -> &'static RgbController;
    pub fn start(&self, backends: Vec<Box<dyn RgbBackend>>);
    pub fn stop(&self);
    pub fn restart_from_settings(&self, s: &RgbSettings);    // stop + start(resolve) ; used by Settings
    pub fn submit_frame(&self, frame: RgbFrame);
    pub fn set_effect(&self, effect: Option<Arc<dyn RgbEffect>>);
    pub fn stop_effect(&self);
    pub fn active_effect(&self) -> Option<Arc<dyn RgbEffect>>;
    pub fn status(&self) -> Status;
    /// Settings "Test": flash magenta 2 s then restore. Blocks; call off the UI thread.
    pub fn run_connection_test(&self);
}

pub fn resolve_backends(settings: &RgbSettings) -> Vec<Box<dyn RgbBackend>>;
pub fn accent_for_theme(theme: Option<&str>, os_is_dark: bool) -> RgbColor;
pub fn derive_palette(primary: RgbColor, count: usize) -> Vec<RgbColor>;
```

The controller emits a "status changed" notification (a `Fn()` listener or a
`watch`-style channel) so the GPUI Settings view can refresh its chip instead of polling; Java only
refreshes after its own actions, which misses a backend going Dead mid-session.

### A.16 Rust crates for RGB

- `libloading` (0.8): runtime DLL loading for RzChromaSDK64, iCUESDK, AURA_SDK; resolve symbols
  individually, keep the `Library` alive for the process (store in a `OnceLock`; never unload while a
  callback may run).
- `windows` (0.61 or newer; features `Devices_Lights`, `Devices_Enumeration`, `Foundation`,
  `Foundation_Collections`, `UI`, `Win32_System_WinRT`): LampArray. The `windows-core`/`windows-future`
  split in newer versions supplies `IAsyncOperation`. Also `GUID` type for Razer.
- `crossbeam-channel` (0.5): bounded(4) frame queue with `try_send` + `recv_timeout`, engine command
  channel. (std `sync_channel(4)` also works: `try_send`, `recv_timeout`.)
- `ureq` (2.x/3.x, no TLS needed) or the workspace's chosen HTTP client in blocking mode, for
  Chroma REST; `serde_json` for the init/result bodies.
- `enum-map` (optional) for `RgbFrame` overrides.
- `parking_lot` (optional) for mutexes.
- `anyhow`/`thiserror` for errors; logging through the workspace logging facade (`tracing` or
  `log`) with localised message keys.
- Nothing missing: everything has a Rust equivalent. The only thing without a direct equivalent is
  Java's ability to catch `UnsatisfiedLinkError`; in Rust a missing symbol is a `libloading` error at
  resolve time (better), and a native fault is fatal in both.

### A.17 Tests to port (RGB)

From `src/test/java/.../rgb/`:

- `RgbBackendHealthTest` (7 tests): initial healthy; 2 failures still healthy; 3rd failure ->
  degraded with `can_call(300)=false`, `(30000)=false`, `(30301)=true`; success from degraded ->
  healthy; backoff doubles (4th failure at 30001 -> blocked until +60000); 10 failures -> dead and
  `record_success` does not revive; 20 failures -> dead with counter 20.
- `RgbBackendRegistryTest` (6 tests) on `filter_candidates` with a `StubBackend`: pass-through
  order, disabled filtered, unavailable filtered, panicking `is_available` treated as unavailable,
  empty -> empty, order preserved.
- `CycleEffectTest` (7): empty palette rejected; period 0/-1 rejected; single colour is solid at
  any time; 3 colours / 3000 ms -> RED at 0, GREEN at 1000, BLUE at 2000; midpoints equal
  `blend(a, b, 0.5)` within +/-1 per channel (cos(pi/2) is ~6e-17, so exact equality can be off by
  one); wraps (0 == 3000, 500 == 3500, 2999 == 5999); `color_count`.
- `RainbowEffectTest` (5): period rejected; phase 0 = (255,0,0); every 60 degrees gives
  R, Y, G, C, B, M within +/-1; wraps; `hsl_to_rgb(0, 0.5, 0.75)` has R in 220..=224 and G == B.
- `ChromaEffectTypesTest` (4): mousepad CUSTOM2 type is 6 and the param is 80 bytes all equal to the
  colour; keyboard param 528 bytes with `[5][21]` at offset `(5*22+21)*4`; mouse param 8 bytes
  `{0, colour}`; static param 4 bytes. Port these as pure tests on the byte builders (they must
  run on Linux/macOS CI too, so keep the builders outside `cfg(windows)`).

New tests worth adding in Rust (cheap, pure):

- `RgbColor`: `from_hex` accept/reject cases, `blend` clamp, `complement`, `pack_rgb`,
  `chroma_pack` byte order, Display.
- `ThemeAccentColors`: every theme value, Native/Automatic with dark/light, unknown -> fallback;
  `derive_palette(c, 1)`, `(c, 3)` centred on c, hue wrap.
- `PulseEffect`: t=0 at 0, colour B at half period.
- OpenRGB protocol: header round trip; `read_packet` with wrong id drains the body (next read is
  aligned); magic mismatch error; `update_leds_body` layout; `parse_controller_data` on a captured
  real protocol-3 blob (add a fixture) and on truncated input (error, no panic); LED-name
  normaliser table (`"Key: W"`, `"Key W"`, `"W"`, `"W Key"`, `"Left Shift"`, `"LCTRL"`, `"Esc"`,
  `"1"`).
- Chroma keyboard grid placement and REST JSON body text (byte-exact).
- Razer APPINFOTYPE builder: total size, field offsets, UTF-16LE + NUL truncation.
- Controller with stub backends and a manual clock: same-request no-op (no `shutdown` calls on the
  stubs), DEAD slot kept and reported by `status()`, `start` with zero survivors tears down an
  existing engine, `submit_frame` drops when full, `set_effect(None)` with no engine creates none.
- Integration with a fake `RgbHost`: menu effect choice per style; `on_menu` ignored while a game
  runs; in-game colours from palette with distance threshold 0.25; default Mica blue/gold.
- Palette sampler (wherever it lives): synthetic images (two-colour split, transparent, monochrome).

---

## Part B: Terminal UI (`--cli` / `--tui`)

### B.1 What it does

A full-screen keyboard-driven terminal UI that runs the launcher as a normal CLIENT (same config
and data folder as the GUI) without any GUI toolkit. It reuses the account the user signed into
via the GUI (no interactive sign-in in v1), and offers four views: Library (installed packs:
launch, uninstall, view logs, stop), Browse (available packs + install from URL), Logs (tail of a
running game's output), Settings (a subset of config). A persistent status bar shows running count,
live total playtime and the signed-in user. Quitting (`q`) leaves games running as detached child
processes; then the launcher shuts down.

### B.2 Entry wiring (must be reproduced in `mmcl-app`)

1. Detection: `TuiMode.requestedIn(args)` is true when ANY argument equals exactly `--cli` or
   `--tui` (case-sensitive; `--CLI`, `-cli`, `"--cli "` are not). Checked at the very top of
   `main`, before anything else touches stdout.
2. `TuiMode.enable(realOut, realIn)`: stores the real stdout/stdin BEFORE the logger reassigns
   `System.out`/`System.err` to the log file, and sets a process-wide `enabled` flag.
   `real_out()`/`real_in()` fall back to the current stdout/stdin when not enabled. In Rust there is
   no global stdout reassignment, so this becomes: a global `TUI_MODE: AtomicBool` (or a field on
   the app context), and the logger must be configured to write file-only (no console sink) when it
   is set. Anything that would print to stdout in TUI mode must use the terminal backend instead.
3. JavaFX prestart is skipped in TUI mode (Rust: never create the GPUI `App`).
4. Argument parsing (`LauncherArgs.parse(args, tuiEnabled=true)`): an `mmcl://` deep link anywhere
   still wins (client mode with that URI). Otherwise mode = CLIENT; strip `--cli`, `--tui` and the
   client-mode flag (`-c`, case-insensitive), and the LAST remaining bare token becomes the modpack
   selection (currently unused by the TUI but parsed). TUI mode never enters server mode even if
   `-s` is present.
5. Single-instance lock: if another launcher instance holds the port and there is no URI to hand
   over, print `tui.alreadyRunning` to the real terminal and exit 0 (never show a GUI popup).
6. Session start (`LauncherSession.run`): RGB bootstrap runs as usual (so the menu effect plays),
   locale is resolved and applied, logger configured (file-only). Then the TUI branch:
   - If there is no saved account (`MCLauncherAuthManager.hasExistingLogin()`), print
     `tui.auth.noAccount` and close the app.
   - `loadCachedUserNow()` (instant restore from the encrypted account store) and
     `renewExistingLoginAsync()` (background token refresh).
   - `GameModPackManager.fetchInstalledModPacks(None)`; `startAvailableModPacksFetchAsync()`.
   - Run the TUI (blocks until quit). Any error: log it and print `tui.error.generic` with the
     error to the real terminal.
   - `closeApp()` (normal cleanup: RGB shutdown, etc.).
7. Game process I/O mode: in TUI mode the game's stdout/stderr are PIPED (never inherited, which
   would draw the game log over the TUI; never discarded, which would lose it). The pipe must be
   drained continuously or the game stalls once the pipe buffer fills (Forge logs a lot early).
8. GUI-only utilities must know TUI mode is on (Java `GUIUtilities.fxGuiIntendedThisSession`
   refuses to bootstrap JavaFX on demand during teardown, which would hang on headless GTK). Rust:
   any code that would lazily open a GPUI window must check the TUI flag.

### B.3 Terminal setup and the Windows limitation

Java (Lanterna): `DefaultTerminalFactory(realOut, realIn, UTF-8)`, `setForceTextTerminal(true)` so it
renders inline. On failure (always on Windows: Lanterna's native console support throws on Win11 in
both Windows Terminal and the IntelliJ console; also under IDE run consoles on macOS/Linux with no
controlling tty), it logs `log.tui.nativeTerminalUnavailable`, prints `tui.fallback.windowed` to
the real terminal, and opens Lanterna's Swing terminal emulator window instead. macOS/Linux render
inline.

Rust (ratatui + crossterm): crossterm works natively on the Windows console (VT sequences / Win32
console API), so the Swing fallback is not needed on Windows. Behaviour to implement:

- If stdin/stdout is not a terminal (`std::io::IsTerminal`), or entering raw mode / the alternate
  screen fails, print a localised message to stderr/stdout and exit with a non-zero code. (Opening
  a new console window is possible on Windows via `AllocConsole`/spawning `conhost`, and on macOS
  by launching Terminal.app, but that is an open question; the Java message text `tui.fallback.windowed`
  would need rewording.)
- Enter alternate screen + raw mode; enable mouse capture only if needed (Java has none); install a
  panic hook that restores the terminal before printing the panic (otherwise a panic leaves the
  user's shell in raw mode).
- On exit restore the terminal in all paths (RAII guard).

### B.4 Event loop

Java drives Lanterna manually: loop while the main window is open: `processEventsAndUpdate()`; on
`EOFException` (terminal closed) break; on `IOException` log `log.tui.terminalIoError` and break;
on any other exception log it and CONTINUE (a broken component must not tear down the UI); sleep
20 ms (50 Hz; a 5 ms sleep spun the CPU at ~200 Hz idle and hurt laptop battery).

Rust: single UI thread owning the `Terminal` and app state. Use `crossterm::event::poll(timeout)` +
`read()` with a timeout of ~250 ms, plus an `mpsc` channel for messages from worker threads
(launch progress, task completion, game exit, 1 Hz tick). Redraw after any input or message. Catch
panics in per-view handlers with `catch_unwind` if you want the same "keep the UI alive" property
(log and continue). Terminal I/O errors end the loop.

All background threads communicate with the UI thread only through that channel (Java's
`gui.getGUIThread().invokeLater`). Never mutate UI state from a worker.

### B.5 Layout and theme

Full-screen, no decorations, three regions:

- Top: header label, bold.
- Centre: swappable content panel (one bordered list or text box with a title).
- Bottom: status bar label.

Header text adapts to width so hints are never cut mid-word (re-render on resize):

```
views = "[L] <Library>  [B] <Browse>  [G] <Logs>  [S] <Settings>"
back  = "[Esc] <Back>" ; quit = "[q] <Quit>"
full    = "  <AppName>   " + views + "    [Enter] <actions>  " + back + "  " + quit
medium  = "  " + views + "    " + back + "  " + quit
compact = "  [L] [B] [G] [S]    " + back + "  " + quit
minimal = "  [L][B][G][S]  [Esc][q]"
```

Pick the first whose length is `<= columns` (Java uses `String.length()`, i.e. UTF-16 units; in Rust
use display width via `unicode-width`, which is more correct for translations). Columns default to
120 when the size is unknown.

Status line: `"  " + format("tui.status.line", running_count, format_duration(total_ms), user)`
where `user` = signed-in account name or `tui.status.signedOut`. English:
`"{0} running   ·   total playtime {1}   ·   {2}"`.

Theme (`TuiTheme`): light if config theme is `Light`, or (`Native (Mica)` or `Automatic`) and the OS
is light; else dark (Dark and all colourful themes). Lanterna theme:

| | Light | Dark |
|---|---|---|
| base fg/bg | black / white | white / black |
| editable fg/bg | black / white | white / black |
| selected fg/bg | white / blue | black / white |
| GUI background | white | black |

Use ANSI named colours (not RGB) so the terminal's palette applies. Re-apply live when the Theme
setting changes.

### B.6 Global keys

Handled BEFORE the focused widget sees the key (Java uses a window `onInput` listener and sets
`deliverEvent=false`). Reason: Lanterna's `ActionListBox` does first-letter navigation, so pressing
`L` in Settings jumped to the "Language" row instead of going to the Library. In Rust, run the
global key handler first in the main view, but NOT while a modal dialog (text input, confirm,
action menu, progress) is open: dialogs capture all input (in Lanterna they are separate windows
and the main window's listener does not see their keys).

| Key | Action |
|---|---|
| `Esc` | if not on Library: go to Library (consumed). On Library: not consumed (does nothing; never quits). |
| `q` / `Q` | quit (close main window -> event loop ends) |
| `l` / `L` | Library |
| `b` / `B` | Browse |
| `g` / `G` | Logs |
| `s` / `S` | Settings |
| `n` / `N` | only in Logs: cycle to the next running game; elsewhere passes through |

Only plain character keys are matched (case-insensitive via lowercase).

### B.7 Views

#### Library (`showLibrary`)

Bordered list titled `tui.library.title` ("Installed Packs"). Empty -> one inert row
`tui.library.empty`. Else one row per installed pack (in manager order):

```
format!("{marker}{name:<28}  v{version:<12}  {last_label} {last_played:<12}  {playtime}")
marker = "● " if running else "  "
name = truncate(friendly_name or pack_name, 28); version = truncate(version or "?", 12)
last_label = tui.row.lastPlayed ("last:"); last_played = truncate(pack.last_played_formatted or "", 12)
playtime = format_duration(total_play_time_ms)
```

Every accessor is wrapped so a failing one falls back (`safe(getter, fallback)`): a single broken
pack must not break the list. Focus goes to the list. Enter on a row opens the action menu.

Action menu (`packActions`): dialog titled with the friendly name; description `tui.actions.running`
if running else `tui.actions.choose`. Not running: Launch, Uninstall. Running: View logs (sets the
Logs target to this pack and opens Logs), Stop game (`process.destroy()`, i.e. graceful terminate;
the exit watcher updates the UI). Always: Close.

Uninstall: Yes/No confirm (`tui.uninstall.title`, `tui.uninstall.confirm` with name; body contains
a `\n`), then `run_with_spinner(tui.uninstall.working, uninstall_mod_pack(pack), on_done=show_library)`.

#### Launch flow (`launch(pack)`)

1. If already running (TUI runtime map), return.
2. Show a centred bordered progress window titled `" " + tui.launch.title(name) + " "` with three
   lines: section (initially `tui.launch.preparing`), detail (""), percent ("0%").
3. Install a progress provider on the pack whose callback (called on the launch thread) posts to
   the UI thread: section = `sec or ""`; detail = `det or ""`, and if `status` is non-blank append
   it (`detail.is_empty() ? status : detail + "   " + status`); percent = `clamp(percent, 0, 100)`
   truncated to int + "%".
4. On a daemon thread `tui-launch-<sanitized name>`:
   - Resolve the account: `userForLaunch(config.account_override_for_pack(pack.settings_key))`.
     If blocked, fail with a localised reason (the TUI cannot run Microsoft sign-in):
     `OVERRIDE_MISSING -> tui.launch.overrideAccountMissing`, `NEEDS_SIGN_IN ->
     tui.launch.accountNeedsSignIn(account name)`, `NO_ACCOUNT -> tui.auth.noAccount`.
   - Create a `GameSession(pack, key_for(pack), friendly name, user.uuid, user.name, clock)`, bind the
     current thread as its worker, `GameSessionRegistry.tryRegister(session)`. Refused ->
     `session = None` and fail with `launch.refused.packRunning(name)` /
     `launch.refused.accountBusy(other account, other pack)` /
     `launch.refused.anotherGame(other pack)` (empty strings when the conflict is unknown). Same
     admission rules as the GUI: one game per pack, one per account (and optionally one at a time).
   - `process = pack.start_game(user, || session.is_cancelled())`; None -> fail with
     `tui.launch.noProcess`.
   - `session.attach_process(process)`; wrap it in a `RunningGame` and register it in the TUI runtime
     with an on-exit callback that posts "games changed" to the UI thread; post "close progress +
     games changed".
   - Any failure: log; post "close progress + message dialog `tui.launch.failed` with the error
     message" (OK button).
   - Finally: if a session exists but never got a game (`phase == Preparing`), `end_without_game()`
     so the pack/account are released; and swap the pack's progress provider to none TRANSIENTLY
     (Java `swapProgressProviderTransiently(null)`, which releases the TUI labels without clearing the
     launcher's record of the last launched process).

#### Browse (`showBrowse`)

Bordered list titled `tui.browse.title`. First row always `"＋  " + tui.browse.installFromUrl`
(full-width plus sign U+FF0B). If the available-packs fetch has not finished: an inert
`tui.browse.loading` row, and when the fetch completes, rebuild Browse IF Browse is still the current
view. Else one row per available pack whose manifest URL is not already installed:
`format!("{:<34}  v{}", truncate(name, 34), truncate(version or "?", 14))`; Enter -> confirm
install (`tui.install.title`, `tui.install.confirm(name)`) -> `run_with_spinner(tui.install.working,
install_mod_pack(pack), show_browse)`. Nothing to show -> inert `tui.browse.allInstalled`.

Install from URL: text input dialog (`tui.install.url.title`, `tui.install.url.prompt`); cancel or
blank -> nothing; else trim and `run_with_spinner(tui.install.url.working,
install_mod_pack_by_url(url), show_browse)`.

#### Settings (`showSettings`)

Bordered list titled `tui.settings.title` ("Settings — Enter to change"). Rebuilding keeps the
selected index (clamped) when Settings was already the current view. Rows, in order (label padded
`{:<30}` then value):

| Row label key | Kind | Config getter/setter |
|---|---|---|
| `tui.setting.discord` | bool | Discord RPC enable |
| `tui.setting.console` | bool | in-game console enable (GUI) |
| `tui.setting.updateCheck` | bool | launcher update check |
| `tui.setting.autoBackup` | bool | auto backup before update |
| `tui.setting.proxyEnable` | bool | proxy enable |
| `tui.setting.minRam` | i64 | min RAM (MB) |
| `tui.setting.maxRam` | i64 | max RAM (MB) |
| `tui.setting.maxBackups` | i32 | max backups per pack |
| `tui.setting.proxyPort` | i32 | proxy port |
| `tui.setting.jvmArgs` | string | custom JVM args |
| `tui.setting.proxyHost` | string | proxy host |
| `tui.setting.theme` | choice | theme |
| `tui.setting.language` | choice | locale override |

- bool: value `tui.settings.on` ("[on]") / `off`; Enter flips and rebuilds.
- number: Enter opens a text input prefilled with the current value; on OK parse the trimmed text;
  parse error -> error dialog `tui.error.number`; setter error (validation) -> error dialog with its
  message; then rebuild. Cancel -> rebuild.
- string: value shown as `tui.settings.empty` when empty else `truncate(v, 40)`; Enter prompts
  prefilled; setter errors (e.g. JVM-args validation) -> error dialog.
- Error dialog title `tui.error.invalid.title`, body = message or `tui.error.invalid.body`.
- Theme: value = `SettingsOptionLabels.theme(current)` (localised label from the GUI/settings
  subsystem); Enter opens an action list of Dark, Light, Automatic, Native (Mica), Blue+gray,
  Orange+purple, Creeper (labels localised); pick -> save, re-apply TUI theme live, rebuild.
- Language: value = `tui.locale.osDefault` when the override is blank, else the matching
  `SupportedLocales` display name, else the raw tag (e.g. `en-US`). Enter opens: "Use OS Language"
  (tag ""), "English" (tag `en-US`, literal unlocalised), then every `SupportedLocales::ENTRIES`
  display name. Pick -> save override, `LocaleBootstrap::apply(tag)`,
  `LocalizationManager::set_locale(resolved)`, re-render header and status bar, rebuild Settings.

Settings does not expose RGB, account or per-pack options.

#### Logs (`showLogs`)

- No running games: a bordered label `"  " + tui.logs.none` titled `tui.logs.title`; no focusable
  widget.
- Else if the target is unset or not running, target = the first running game (map iteration
  order; Rust should use launch order for determinism). Title:
  `tui.logs.header(truncate(name, 32))` + (if more than one running: `"   " +
  tui.logs.next(count)`) + `"   [↑↓←→] " + tui.logs.scrollHint`.
- Body: read-only scrollable multi-line text, focused so arrow keys scroll immediately. Java box
  size hint 100x24 (fills the centre in practice).
- Key handling in the log box: Up/Down/Left/Right/PageUp/PageDown/Home/End move the caret; at the
  edges it stays put instead of moving focus; every other key is NOT handled by the box (editing is
  blocked) so global keys still work.
- Refresh (`refreshLogsBox`, called on build and every second while in Logs):
  - target no longer running -> show `tui.logs.exited` once (sentinel -2), return.
  - snapshot lines; if count == last rendered count -> return (do not fight the user's scroll).
  - FOLLOW RULE: only reload if the caret is on the last line (`caret_row >= line_count - 1`). If
    the user scrolled up, freeze and do NOT update `last count`, so returning to the bottom
    re-engages following on the next tick.
  - Show only the last 500 lines; put the caret on the last line, column 0. BUG GUARDED: the caret
    row must be `line_count - 1` (a valid index); using `line_count` put it one past the end and
    arrowing into it threw out-of-bounds and tore down the UI.
  - Note: once the 4000-line ring is full the snapshot size stops changing even though new lines
    arrive, so the Java view stops updating. In Rust track a monotonically increasing "lines
    appended" counter instead of the snapshot length.
- `n` cycles: if fewer than 2 running, nothing; find the current target index by manifest URL (0 if
  not found), target = next modulo count, rebuild.

In ratatui: a `Paragraph` with `scroll((offset, 0))` plus explicit `offset`/`follow` state is
simpler than a caret; `follow = offset == max_offset`. Keep the same semantics: follow at bottom,
freeze when scrolled up, arrow/page/home/end navigation, no editing.

#### Shared behaviour

- `on_games_changed` (game started or exited): rebuild Library or Logs if current (they show running
  state); never rebuild Browse or Settings (it would lose the user's place); always update the
  status bar.
- `run_with_spinner(message, work, on_done)`: centred bordered modal `"  " + message + "  "`; run
  `work` on daemon thread `tui-task`; then on the UI thread close the modal and either show an error
  dialog (`tui.generic.failed`, error message) or run `on_done`; update the status bar.
- 1 Hz refresher (daemon `tui-refresh`, first run after 1 s): post to UI: update status bar; if in
  Logs, refresh the log box.
- `format_duration(ms)`: `ms <= 0` -> `tui.duration.minutes("0")` ("0m"); `minutes = ms / 60000`,
  `hours = minutes / 60`, `minutes %= 60`; hours > 0 -> `tui.duration.hoursMinutes(h, m)` ("2h 30m");
  minutes > 0 -> `tui.duration.minutes(m)`; else `tui.duration.underMinute` ("<1m"). Integer
  truncation, not rounding. Arguments are passed as strings (so MessageFormat does not insert
  thousands separators); in Rust format the numbers yourself before substitution.
- `truncate(s, max)`: None -> ""; `len <= max` -> s; else first `max - 1` chars + `"…"` (U+2026);
  `max == 0` -> `"…"`. Java counts UTF-16 units; port with `chars()` (Unicode scalar values) or
  display width; keep the test expectations (all ASCII).

### B.8 Running-game bookkeeping (`RunningGame`, `TuiRuntime`)

`TuiRuntime`: concurrent map key -> `RunningGame`, key = pack manifest URL if non-blank else pack
name. `register(game, on_change)`: insert, then `game.start(on_exit = remove + on_change)`.
`running_count`, `is_running(pack)`, `running_for(pack)`, `running()` snapshot,
`live_total_playtime_ms()` = sum of every installed pack's `total_play_time_ms` + sum of
`elapsed_ms` of running games.

`RunningGame(pack, process)`: `start_ms = now` at construction. `start(on_exit)`:
`pack.record_launch_start()` (best-effort), spawn reader threads for stdout and stderr
(`tui-game-log-<sanitized>`; read UTF-8 lines; append to a ring buffer capped at `4000` lines,
evicting the oldest; stop on EOF/error), spawn exit watcher (`tui-game-watch-<sanitized>`): wait for
the process, `pack.record_session_end(now - start_ms)` (best-effort), call `on_exit`.
`is_alive`, `stop()` (terminate request), `elapsed_ms`, `log_snapshot()`.

Note the duplication: the `GameSession` (session subsystem) also tracks the process (its
`attach_process` registers an exit hook that sets the phase to Exited/Crashed) but in TUI mode it
does NOT drain the pipes; the TUI's `RunningGame` is the only reader. In GUI mode `GameLog` is the
reader. Recommendation for Rust: have the TUI use the session subsystem's `GameSession` +
`GameLog` (one drainer, one exit watcher, one playtime record) and keep only a thin view adapter in
the TUI. That removes the risk of two code paths recording playtime differently. If kept separate,
make sure exactly one component drains each pipe (two readers on one pipe split the lines between
them).

### B.9 Progress provider (`TuiProgressProvider`)

Subclass of `GameModPackProgressProvider` whose single callback
`(percent: f64, section: Option<String>, detail: Option<String>, download_status: Option<String>)`
is forwarded unchanged to a listener (null listener tolerated; nulls passed through as nulls; the
TUI converts nulls to ""). In Rust: whatever trait the modpack subsystem defines for progress
(`trait ProgressSink: Send + Sync { fn update(&self, percent: f64, section: Option<&str>, detail:
Option<&str>, status: Option<&str>); }`) plus a closure adapter.

### B.10 Java class -> Rust map (TUI)

| Java | Rust (crate `mmcl-tui`) | Notes |
|---|---|---|
| `TuiMode` | `mode::{requested_in(&[String]) -> bool, enable(), is_enabled()}` | real stdout capture becomes "logger file-only" config; keep `requested_in` pure |
| `TuiApp` | `app::TuiApp` + `views::{library, browse, logs, settings}` + `widgets::{action_menu, confirm, text_input, message, spinner}` | ratatui immediate-mode; state struct + `Msg` enum |
| `TuiApp.LogBox` | `widgets::log_view::LogView` | scroll offset + follow flag |
| `TuiApp.formatDuration/truncate/statusLine/headerText/formatPackRow/formatAvailableRow` | `format` module (pure fns) | testable without a terminal |
| `TuiRuntime` | `runtime::TuiRuntime` (or a view over `GameSessionRegistry`) | |
| `RunningGame` | `runtime::RunningGame` (or session `GameLog`) | ring buffer 4000 |
| `TuiProgressProvider` | `progress::TuiProgress` (closure adapter) | |
| `TuiTheme` | `theme::TuiTheme { is_light, styles() }` | ANSI colours |

### B.11 Public API sketch (`mmcl-tui`)

```rust
/// True when argv contains exactly "--cli" or "--tui".
pub fn requested_in(args: &[String]) -> bool;

/// Everything the TUI needs from the rest of the launcher, injected by mmcl-app.
pub trait TuiHost: Send + Sync + 'static {
    // packs
    fn installed_packs(&self) -> Vec<PackSummary>;              // name, version, last played (localised), playtime, manifest url, key
    fn available_packs(&self) -> Option<Vec<PackSummary>>;      // None while the fetch is in flight
    fn on_available_ready(&self, f: Box<dyn FnOnce() + Send>);  // fire when the fetch completes
    fn install(&self, pack_key: &str) -> anyhow::Result<()>;
    fn install_by_url(&self, url: &str) -> anyhow::Result<()>;
    fn uninstall(&self, pack_key: &str) -> anyhow::Result<()>;
    // launching (performs account resolution + admission + start_game; maps refusals to localised messages)
    fn launch(&self, pack_key: &str, progress: Box<dyn ProgressSink>) -> anyhow::Result<GameHandle>;
    // sessions
    fn running_games(&self) -> Vec<RunningGameView>;            // pack key, name, elapsed, alive
    fn game_log_tail(&self, pack_key: &str, max_lines: usize) -> Option<(u64 /*appended*/, Vec<String>)>;
    fn stop_game(&self, pack_key: &str);
    fn on_games_changed(&self, f: Box<dyn Fn() + Send + Sync>);
    fn total_playtime_ms(&self) -> u64;                         // installed packs + live sessions
    // account
    fn signed_in_name(&self) -> Option<String>;
    // config (subset) + locale + theme
    fn settings(&self) -> &dyn TuiSettings;
    fn os_is_dark(&self) -> bool;
    fn t(&self, key: &str) -> String;                           // LocalizationManager::get
    fn tf(&self, key: &str, args: &[&str]) -> String;           // LocalizationManager::format
}

/// Blocks until the user quits. Restores the terminal on every exit path.
pub fn run(host: Arc<dyn TuiHost>) -> anyhow::Result<()>;

pub mod format {
    pub fn format_duration(ms: i64, t: &dyn Fn(&str, &[&str]) -> String) -> String;
    pub fn truncate(s: Option<&str>, max: usize) -> String;
    pub fn header_text(cols: u16, t: ...) -> String;
}
```

`mmcl-app` owns the startup sequence in B.2 and calls `mmcl_tui::run` after headless auth restore.

### B.12 Rust crates for the TUI

- `ratatui` (0.29 or newer) + `crossterm` (0.28 or newer; the version ratatui re-exports): rendering
  and input on Windows, macOS and Linux. This removes the Windows Swing fallback.
- `tui-textarea` or `tui-input` (optional) for the single-line text input dialog; or hand-roll
  (single line, cursor, backspace, Enter/Esc) to stay dependency-light.
- `unicode-width` (and `unicode-segmentation` if truncating by grapheme) for header fitting and
  `truncate`.
- `crossbeam-channel` or `std::sync::mpsc` for the UI message channel.
- No equivalent needed for Lanterna's Swing terminal emulator (deliberately dropped).

### B.13 Tests to port (TUI)

From `src/test/java/.../tui/`:

- `TuiModeTest` (9): None/empty args false; `--cli` alone, `--tui` alone true; flag anywhere among
  other args true (`-s MyPack --cli`, `--tui -c`); unrelated args false (`-c MyPack`, `--help`);
  near misses false (`--CLI`, `"--cli "`, `-cli`). The two `realOut/realIn` fallback tests do not
  apply to Rust (no stream capture); replace with "logger is file-only when TUI mode is on" if the
  logging subsystem exposes that.
- `TuiAppFormattingTest` (10): `format_duration`: 0 and -1000 -> "0m"; 1 and 59999 -> "<1m"; 60000
  -> "1m", 45 min -> "45m", 59 min -> "59m"; 60 min -> "1h 0m", 150 min -> "2h 30m"; 90500 -> "1m"
  (truncation). `truncate`: None -> ""; at/under max unchanged ("hello",5; "hi",10);
  ("abcdefghij",5) -> "abcd…"; result length == max (12); max 0 -> "…". (These depend on English
  strings; feed the formatter an English lookup in tests.)
- `TuiProgressProviderTest` (5): forwards all four args unchanged; forwards every call in order;
  nulls pass through as None; no listener does not panic; type relationship (drop in Rust or assert
  it implements the progress trait).

New tests worth adding: `header_text` picks full/medium/compact/minimal at boundary widths (use
`unicode-width`); `format_pack_row` padding/truncation; log-view follow/freeze state machine
(follow at bottom, freeze when scrolled up, re-engage at bottom, caret/offset never past last line,
"exited" sentinel shown once); global-key routing (Esc on Library not consumed; `n` only in Logs;
letters ignored while a dialog is open); Settings selection kept across rebuild; Browse skips
installed URLs; ratatui `TestBackend` snapshot of each view in light and dark.

---

## Part C: cross-subsystem summary

Consumes:

- Config: all `rgb*` keys (A.11); `theme`; TUI rows (Discord RPC, in-game console, update check,
  auto-backup, proxy enable/host/port, min/max RAM, max backups, custom JVM args with validation
  errors, locale override); per-pack account override.
- Localization: `get`/`format`, `SupportedLocales::ENTRIES` (tag, display name),
  `LocaleBootstrap::{apply, resolve}`, live `set_locale`; ~65 `tui.*` keys, ~60 `log.rgb.*` keys,
  `settings.rgb.*`, `launch.refused.*`, `notification.settings.rgbDisabled.*`.
- OS theme detection (`is_os_dark`, default dark).
- Modpacks: installed/available lists, available-fetch completion, install/uninstall/install-by-URL,
  pack fields (friendly name, pack name, version, manifest URL, settings key, sanitised name,
  vanilla flag, logo file path, last-played formatted, total playtime), `record_launch_start`,
  `record_session_end`, progress provider set + transient swap, `start_game(user, cancelled) ->
  Process`.
- Auth: `has_existing_login`, `load_cached_user_now`, `renew_existing_login_async`,
  `user_for_launch(override) -> Result<User, Blocked{problem, account_name}>`, signed-in user.
- Sessions: `GameSession` (+ `key_for`, `bind_worker`, `attach_process`, `end_without_game`,
  `is_cancelled`), `GameSessionRegistry::{try_register -> Decision{ok, outcome, conflicting},
  newest_running, sessions, add_listener}`, `RunningGameFollower` + `LatestUpdateRunner`.
- Process I/O: child pipe mode PIPE in TUI mode.
- Logging: file-only mode in TUI; silent-warning vs. toast-warning distinction (RGB logs are all
  "silent"); throwable logging.
- Imaging: pack logo dominant-palette sampler (A.12), probably shared with the GUI hero card.
- App lifecycle: single-instance lock message in TUI mode, `close_app` cleanup with a 3 s bound per
  step (RGB shutdown is one step), in-process restart (RGB bootstrap re-shows a running game).
- Platform: `ThemeAccentColors` is consumed by the macOS toolbar tint.

Provides:

- `mmcl-rgb`: `integration::{install, bootstrap, on_menu, on_play_started, on_play_ended, shutdown}`;
  `RgbController::{global, restart_from_settings, status, run_connection_test, active_effect,
  set_effect}`; `Status/BackendStatus/HealthState` for the Settings chip; `accent_for_theme`,
  `derive_palette`, `RgbColor` for platform/GUI accent use; backend labels for the dropdown.
- `mmcl-tui`: `requested_in(args)`, `run(host)`; pure `format` helpers.

---

## Open questions

1. Should the Windows vendor-DLL backends (Razer native, Corsair, ASUS Aura) run in an isolated
   helper process so an SDK access violation cannot kill the launcher (recommended), or in-process
   as in Java?
2. Fix the Java config bug that makes manual `corsair` / `asus-aura` unselectable (recommended),
   or keep byte-for-byte behaviour?
3. Install the `RunningGameFollower` in TUI mode so RGB and Discord follow TUI-launched games
   (recommended; Java misses this)?
4. TUI on a non-interactive stdout (IDE console, piped): exit with a message (recommended), or
   open a separate terminal window like Lanterna's Swing fallback did?
5. Should the TUI reuse the session subsystem's `GameLog` instead of its own `RunningGame` reader
   (recommended), which also fixes the "log view stops updating once the 4000-line ring is full"
   quirk?
6. Add a controller-level "skip identical frame, but re-send at least once per second" dedup for
   OpenRGB and Chroma (Java re-sends static frames 30 times per second, six HTTP PUTs per frame for
   Chroma REST), or keep per-backend behaviour exactly?
7. ASUS Aura binding was never hardware-validated and its signatures do not match the published
   header. Port as-is with per-symbol tolerance, re-derive from the ASUS header, or drop the backend
   in favour of OpenRGB?
8. Where does the pack-logo palette sampler live (GUI crate, an imaging crate, or the modpack crate)?
   RGB needs it without depending on GPUI.
