# CLAUDE.md — Development Notes

Engineering notes for the Riddle Diary app (BOOX / Onyx e-ink).

**Target device: BOOX Note X3 Pro**, Android 12 / API 32, 1860×2480 @ ~304 PPI, arm64,
firmware 2026-05-20.

This is a fork of `billtt/boox-riddle-diary`, which was written for a **Note X2**
(Android 11 / API 30, ~227 PPI). Several of the upstream design decisions were forced by X2
firmware limitations that **do not apply here** — read the pen section before "simplifying"
anything back.

Upstream in turn is a port of `MaximeRivest/riddle` (Rust, reMarkable Paper Pro). Upstream
dropped that project's memory layer; this fork restores it.

## Module layout

```
app/src/main/java/com/billtt/riddle/
├── MainActivity.kt      # Full-screen entry; long-press = settings; pen attach timing
├── DiaryController.kt   # State machine (write→absorb→await→reveal→linger→fade); TouchHelper; animations
├── DiaryView.kt         # Page rendering: banded absorb cache, reply reveal, page PNG capture
├── Memory.kt            # Archive: strokes + transcript + reply; recall catalog
├── Oracle.kt            # Oracle interface, StreamParser, persona + memory protocol
├── OpenAiOracle.kt      # Streaming Chat Completions + vision, any OpenAI-compatible endpoint
├── ReplyTypesetter.kt   # Reply layout: wrap, center, CJK-per-char / Western-per-word
├── Stroke.kt            # Stroke data model
├── EInk.kt              # EpdController wrapper (DU4 fast refresh / GC full refresh)
└── Prefs.kt             # Profiles (endpoint + persona), idle delay, memory toggle
```

## The pen-input story — REVERSED from upstream

Upstream's central finding was that on the **Note X2**, `RawInputCallback` only fires in
`FEATURE_APP_TOUCH_RENDER` mode, that the hardware-draw mode is silent, and therefore that
live ink must be drawn in software. Its notes end by saying hardware-latency ink "would need
a lower-level Onyx path … beyond `onyxsdk-pen`".

**That is not true on the Note X3 Pro.** Measured with a standalone probe (`../penprobe`):

```
create(view, FEATURE_ALL_TOUCH_RENDER, cb, false) + setRawDrawingRenderEnabled(false)
  -> hardware live ink AND full callbacks
  -> 491 Hz average sample rate, pressure 0..4095, tilt populated, hover delivered
```

So this fork lets **the pen chip paint the stroke** and only persists points, committing on
pen-up. The per-move software redraw (`addLivePoint`, throttled partial `invalidate`) is
gone — it was ~22 redraws/s over 1860×2480.

Things that will silently break this if you touch them:

- **`setRawDrawingRenderEnabled(false)` means the CHIP renders.** The naming is inverted.
  `true` hands rendering back to the app, i.e. back to the slow path.
- **`onResume()` must also pass `false`.** Upstream passed `true` there; leaving that would
  drop you back to software rendering after every screen-off, with no other symptom.
- **`openRawDrawing()` resets the chip**, so stroke width / style / colour are applied
  *after* it, not only before.
- **Configure off the main thread.** The probe validated the sequence on a dedicated thread.
- **Attach only after the window has focus** (`onWindowFocusChanged`), so the view's
  on-screen position is final. This one *is* inherited from upstream and still holds.

`onBeginRawDrawing` reports `success=false` on this firmware even when everything works.
Do not treat it as an error signal.

### The top-of-screen blind spot

For a while the top of the page took input correctly -- callbacks fired with the right
coordinates -- but showed no hardware ink, while the lower part worked. It went away once
`hideSystemUi()` gained `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` and `LAYOUT_HIDE_NAVIGATION`
and `bindPen()` started re-binding on layout changes.

The likely mechanism is that without those flags the content is laid out *inside* the
system bars and then resized when they hide, so the limit rect was bound from an
intermediate geometry. That is consistent with the symptom, but it was not isolated with a
controlled test before the behaviour changed, so treat the causal claim as probable rather
than established. If it reappears, suspect the bind-time geometry first.

Known unresolved, both measured by the probe:

- `onEndRawDrawing` → `onPenUpRefresh` averages **541 ms**. Not yet tuned.
- Light touches are frequently classified as *erasing* (`onBeginRawErasing` at pressure
  220–361). Expect accidental erases until this is filtered.
- `TouchPoint.getToolType` is **not exposed** here, so pen vs eraser can only be told from
  which callback family fires.

## Physical units, not X2 pixels

Upstream hardcoded pixel constants that were correct at 227 PPI and wrong here. These are
now derived from `displayMetrics.xdpi`:

- stroke width — 0.5 mm (was a flat `4.5f`)
- eraser radius — 2.7 mm (was a flat `24f`)
- pressure ceiling — `EpdController.getMaxTouchPressure()` (was a hardcoded `4096f`;
  the real value here is 4095)

`DiaryController.FRAME_MS = 130` is still an X2-era constant, chosen for DU4 at ~150 ms on a
smaller panel. **Not yet re-measured on this device.**

## Refresh / animation pipeline

E-ink refresh is slow, so naïve per-frame gradients stutter. The pipeline:

- **DU4 fast refresh** as the view's default update mode during animation. Refresh a frame
  with a plain `view.invalidate()`; do **not** use `EpdController.postInvalidate` — it
  refreshes the ink layer without triggering `onDraw`, so nothing you drew appears.
- **Ink levels quantized to 5 steps** to match DU4.
- **Change-gated frames:** `runStagedFade` samples the timeline finely but only refreshes
  when an element crosses a quantization step. Dead frames are skipped.
- **GC full refresh** once at the end of a cycle to clear ghosting.
- **Absorb** splits strokes into bands, renders each into a bounding-box sized bitmap, and
  fades them. Cost scales with ink, not page size — which is why the 1.75× pixel count
  matters less here than expected. `ABSORB_BAND_STAGGER_MS` is 0, so the page fades as one;
  upstream staggered it to drain head-to-tail, which reads as being eaten line by line.

This firmware exposes more modes than upstream used: `DU4`, `GU_FAST`, `GC4`, `REGAL`,
`REGAL_D`, `REGAL_PLUS`, `ANIMATION_X`, `DU_QUALITY`, `HAND_WRITING_REPAINT_MODE`.

## Oracle: one call, two products

A single streaming vision call returns **both** the reply and a transcription, using the
upstream Rust project's protocol:

- The reply is prose. Then a line beginning `⁂` carries a verbatim transcription of what was
  written on the page. A trailing marked line is far more reliable with vision models than
  asking for JSON, which they routinely malform.
- `⟦show:N⟧` as the *entire* reply means "conjure catalog page N" instead of answering.
- `StreamParser` consumes the **running** full text and emits `Ink` / `Show` / `Transcript`
  events exactly once each, so the diary can start writing before the model finishes.

`StreamParser.sentenceCut` diverges from upstream deliberately: upstream only breaks on
`. ! ? …` **and requires following whitespace**, which never fires on Chinese, where `。！？`
are not followed by a space. CJK enders therefore terminate a sentence on their own, while
Latin enders keep the whitespace guard that stops "Dr. Smith" splitting.

The persona instructs a **mixed Chinese/English** reply mirroring the writer's own mix,
rather than upstream's "reply in the same language".

Only one backend remains: any OpenAI-compatible endpoint. Settings hold several profiles,
each pairing an endpoint with its own persona (blank persona = OraclePrompts.PERSONA). The
Anthropic-specific backend and the `anthropic-java` dependency were removed — Claude is
still reachable through its own OpenAI-compatible endpoint.

## Memory

`Memory.kt`, ported from the upstream Rust `memory.rs` that billtt's port dropped.

```
<getExternalFilesDir>/memories/
  index.tsv      id \t transcript \t reply   (tabs/newlines/backslashes escaped)
  <id>.strokes   one line per stroke: "x,y,p;x,y,p;…"
```

Strokes rather than a PNG, so a recalled page is **redrawn in the writer's own hand**. Points
within 3 px of the last kept one are dropped (`MIN_POINT_DIST2`), which shrinks files
several-fold without visibly changing the handwriting. Keeps the newest 400 pages.

Each turn sends a **freshly numbered** catalog (newest first). The numbers are reassigned
every turn and the prompt forbids reusing a number from an earlier turn — that is what stops
the model citing a stale index. `catalogIds[i]` resolves catalog number `i+1`.

Search is conversational: there is no search UI. The writer asks the diary.

## Build

JDK 17–21 + Android SDK 34. **JDK 25 will not work** (Gradle 8.7 / AGP 8.5.2 cap at 21).

Point `local.properties` at your own SDK (`sdk.dir=...`); it is gitignored.

If `./gradlew` cannot fetch its distribution (some networks fail on the
services.gradle.org -> GitHub-assets redirect), install Gradle 8.7 yourself and run
`gradle assembleDebug` directly instead of the wrapper.

- **Onyx SDK** from `http://repo.boox.com/repository/maven-public/` (`allowInsecureProtocol`).
- **Jetifier is required** (`android.enableJetifier=true`): the Onyx artifacts still pull in
  the legacy support library, which collides with AndroidX.
- **jniLibs conflict:** `packagingOptions.jniLibs.pickFirsts` for `libc++_shared.so`.
- `com.onyx.android.sdk.base.data.TouchPoint` — the superclass of the `TouchPoint` you
  actually use — lives in **`onyxsdk-baselite`**, reached transitively via `onyxsdk-base`.
  If a field stops resolving, check that artifact is still on the classpath. Do **not**
  hand-write a replacement class: it would shadow the real 43-method one and break the SDK
  at runtime.

## Install & debug

- **BOOX auto-freeze (EAC):** newly installed apps are disabled by the launcher's
  optimization — `dumpsys package` shows `enabled=3`. After `adb install`, run
  `adb shell pm enable com.billtt.riddle` or the launch fails with "Activity … does not
  exist". Also, launching immediately after install races `killAppForConfigChange`; wait a
  beat and relaunch.
- **Logs:** `adb logcat -s RiddleDiary`.
- Screenshots: use `adb exec-out screencap -p > f.png`. Note the pen chip's hardware ink
  bypasses SurfaceFlinger, so **live ink does not appear in a screenshot** — only committed
  software-drawn strokes do.

## Interaction

- Long-press with a finger opens settings.
- **Two-finger tap sends the page immediately**, bypassing the idle wait. Not a double-tap:
  a resting hand produces stray single-finger events far too easily.
- Idle delay is a setting (`Prefs.idleMs`, default 1500 ms), not the upstream constant.

## Security decisions

Hardening applied after an external review of a sibling fork; most findings were inherited
from upstream and applied here too.

- **The Onyx Maven repo is https.** Upstream declared it `http://` with
  `allowInsecureProtocol = true`, which makes every artifact that ends up executing in the
  APK MITM-able. The host serves https with a valid certificate — upstream simply never
  checked. Verified: 200, valid chain.
- **The Gradle distribution is pinned** with `distributionSha256Sum`, checked against the
  official published checksum.
- **The API key is encrypted at rest** (`Secret.kt`) with an AES-256-GCM key held in the
  Android Keystore, non-exportable, destroyed on uninstall. Values are prefixed `enc:v1:`;
  a value without the prefix is legacy plaintext, returned as-is and rewritten encrypted on
  first load, so an already-working key survives the upgrade.
- **Backup keeps the diary, drops the key.** `allowBackup` stays true — losing the archive
  on a device migration would be worse than the backup risk — but backup rules exclude
  `shared_prefs/riddle.xml`, which Auto Backup would otherwise sweep into the cloud.
- **The settings dialog sets `FLAG_SECURE`**, keeping the key out of screenshots, screen
  recordings and the recents thumbnail.
- **In-flight requests are genuinely cancellable.** `ask()` blocks, so cancelling its
  coroutine does not stop the HTTP exchange — it still reaches the server and is still
  billed. `Oracle.cancel()` aborts the OkHttp `Call` itself; the timeout path and
  `onDestroy` both call it.
- **Response size is bounded** (`MAX_REPLY_CHARS`, and `peekBody` for error bodies), so a
  hostile or broken endpoint cannot stream until OOM.
- **Absorb bitmaps are bounded** (`ABSORB_BUDGET_BYTES`). Band bounding boxes overlap
  heavily on a full page; ten near-full-screen ARGB_8888 bitmaps at 1860x2480 is ~184 MB.
  Band count now drops when ink is spread out.

Note this app does **not** need `hidden_api_policy=1`. It uses `hiddenapibypass`, which is
per-process; do not weaken the device-wide setting for it.

Still open: no dependency verification metadata, no tests, no CI, `lint abortOnError false`,
and `minifyEnabled false` for release.

## Known gaps

- **The transcription postscript is unreliable.** Measured over 28 archived turns: 12 had
  no marker line at all, so those pages carry a reply but no searchable text and cannot
  anchor a recall. The instruction now also appears in the per-turn user message rather
  than only at the end of a long system prompt, and StreamParser logs every miss so the
  rate is measurable. Not yet re-measured. If it stays unreliable, the next step is a
  marker the model reproduces more readily than U+2042.
- Replies are longer than the persona's one-to-three-short-sentences rule -- around a
  hundred characters in practice. Restated per turn alongside the postscript rule;
  unverified.

- Reply reveal waits for the whole stream; the sentence-at-a-time `Ink` events are collected
  and revealed together. Incremental reveal needs `DiaryView` to append without reflowing
  already-revealed words.
- `ReplyTypesetter` still picks **one** typeface for the whole reply, and tokenizes the whole
  reply as either CJK or Western. For genuinely mixed text this splits English words into
  letters and drops the spaces. Not yet fixed.
- Erase is whole-stroke deletion, not pixel-level.
- UI strings are Chinese; code and docs are English.
