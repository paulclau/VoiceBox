# Android Keyboard — Feature Plan

A standalone Android IME: voice typing with a local model, a fully themeable keyboard, and
"flying letter" tap animations that travel from the key and land at the caret.

Pivot away from the Electron/OpenWhispr codebase. Nothing here reuses the renderer.

**Engine correction (was wrong in the first draft):** "Bark 3.0 from Underdog" is
`ConwayResearch/Underdog-Bark-3.0` — a 0.6B **English speech-recognition** model (a
compressed fine-tune of NVIDIA Parakeet-TDT-0.6B-v3, CC-BY-4.0), *not* the Suno Bark TTS
model and not an audio-feedback layer. It is therefore the default voice-typing engine, not
a sound-effect system. §5 has the details and the numbers that matter.

---

## 1. Shape of the project

An IME **cannot** be built in Expo/React Native (it is an `InputMethodService` with a
`View` it renders itself, not an Activity). So: a new native Kotlin/Gradle app.

```
android-keyboard/
  app/                        # IME + settings/theme editor
  asr/                        # :asr process — sherpa-onnx + model lifecycle (own process!)
  native/inference/           # sherpa-onnx JNI (prebuilt AAR) + VAD front end
  themes/                     # shipped theme JSON + schema
```

Four components:

| Component | Type | Job |
|---|---|---|
| `KeyboardService` | `InputMethodService` | Keys, animation, `InputConnection` commits |
| `AsrService` | bound Service in `:asr` process | Model load, decode, partials |
| `SettingsActivity` | Activity | Themes, engine choice, model download, voice layer |
| `GrantActivity` | transparent Activity | Runtime `RECORD_AUDIO` (an IME can't show the dialog itself) |

Running inference in a **separate `:asr` process** is deliberate: Bark 3.0 peaks around
1.15 GB RSS (§5), which must not be charged to the keyboard process or the keyboard dies
with it under memory pressure. It also lets the model stay warm across dictations and be
reclaimed by the system without taking typing down.

---

## 2. Key decisions

| Decision | Choice | Why |
|---|---|---|
| UI toolkit | Custom `View`/`ViewGroup` + Canvas, **not** Compose | Per-frame letter flight needs a predictable frame clock and zero recomposition; Compose in an IME also fights focus/lifecycle. |
| Landing target | `CursorAnchorInfo` → `getMatrix()` → screen coords | The only accurate way to know where the caret is in another app's editor. |
| Default ASR | **Bark 3.0 Clean int8** via sherpa-onnx (English) | Best accuracy per byte available today; Clean drops fillers, false starts and repeats in the model, so dictated text needs no post-filter. A sherpa-onnx ONNX build exists, pinned by SHA-256. |
| Second ASR engine | **None.** Bark 3.0 Clean is the only engine | Decided: one model, one accuracy target, one download to explain. No multilingual or low-memory profile ships. |
| Glide typing | In scope: trail → key sequence → dictionary/LM decode | Decided; design in §4. |
| Partials | Re-decode a growing buffer | Bark/Parakeet TDT is an **offline** model; sherpa-onnx confirms no true streaming for it. |
| Customisation | JSON layout + theme + gesture schema | Import/export/shareable themes, live editor, per-app overrides. |
| Model delivery | Play Asset Delivery / HF download at setup | The int8 model is ~685 MB on disk — too big for the APK. |

---

## 3. Feature: flying letter animation

**Mechanics**

1. On `ACTION_DOWN` the key is drawn in its "pressed" pose immediately (scale + shadow).
   The press state is committed in the touch handler; the animation starts on the next
   frame. Feedback must be instant.
2. The glyph is spawned as a particle: start = key centre, target = caret anchor (or the
   lane fallback), path = quadratic/cubic Bézier, `ValueAnimator`, ~180–240 ms, ease-out
   with a small settle overshoot.
3. Text is committed with `InputConnection.commitText()` **when the particle lands**, not
   on touch — committing early makes the animation a lie in slow apps. One char per tap so
   `getTextBeforeCursor` and undo stay sane.
4. On landing: a 90 ms ripple at the caret plus an alpha fade, so the eye reads "arrived".

**The hard part: the caret is in another process**

- `InputConnection.requestCursorUpdates(CURSOR_UPDATE_MONITOR)`; the system then delivers
  `CursorAnchorInfo` to `InputMethodService.onUpdateCursorAnchorInfo()`.
- Map `getInsertionMarkerHorizontal/Vertical()` through `CursorAnchorInfo.getMatrix()` into
  screen space, then subtract this view's `getLocationOnScreen()`.
- **Fallback A**: no anchor info (WebViews, some Compose/RV fields, first frame after
  focus) → fly to a fixed landing lane above the key rows.
- **Fallback B**: stale/offscreen anchor → clamp into view bounds, shorter trail.

**The window problem (resolve in M0)**

An IME window normally stops at the top of the keyboard. To fly *over* the text field the
input view must be taller than the keys: a transparent lane (~30–40% of keyboard height)
above the key area, with `onComputeInsets()` reporting the real keyboard top so the host app
still lays out correctly.

Those lane pixels belong to the IME window, so taps there hit the IME instead of the app:

- **Conservative (ship first):** short lane; the particle flies to the lane's top edge and
  continues as a shrinking ghost aimed at the caret point. Cheap, no touch loss.
- **Aggressive:** extend the lane to the caret when in range and punch a touch-transparent
  region. Needs per-app testing; eating an editor tap is worse than a clipped animation.

Never stretch the window mid-tap. Only animate when the target is inside the window.

**Nearly free once the particle exists:** long-press alternate-character strips springing
out along the same curve family; language glyph flying between spacebar and globe key;
per-theme easing so a theme feels snappy or floaty.

**Budget:** one particle pool (no per-tap allocation), glyphs drawn with `drawText`/
`StaticLayout` (no per-frame bitmap capture), and the animation never blocks `commitText`.
Fall back to a 120 ms fade in power-save mode or after two missed frames.

---

## 4. Feature: voice typing (mic key)

**Pipeline**

`AudioRecord` 16 kHz mono → VAD gate → ring buffer → `AsrService` (`:asr` process) →
text stabiliser → `InputConnection` streaming commit.

- **Partials:** every ~700 ms, re-decode the buffer from the start (Bark handles 15–30 s in
  one pass). Keep only the prefix that matches the previous partial (longest-common-prefix
  stability rule) and commit that, so the field grows left-to-right like a human typing.
- **Final:** on release, decode the whole utterance once more and `setComposingText` the
  tail before `finishComposingText()`.
- **Default: hold-to-talk.** Release = transcribe. Always-on listening is a later option,
  not the default (battery, thermals, privacy review).
- **Utterance cap** ~60 s; longer audio is chunked at VAD pauses to bound memory.

**IME-specific concerns**

- `RECORD_AUDIO` needs an Activity → `GrantActivity` from the mic key on first use. Until
  granted, the mic key opens that Activity.
- Audio focus: duck, don't take exclusive; stop on `onFinishInput`, screen-off, or a call.
- Never leave the mic open after release or when the field loses focus; show the mic
  indicator honestly.
- On OEMs that block mic for a non-foreground component, escalate to a short-lived
  foreground service (`microphone` type) — measured in M1, not a default.

---

## 5. Feature: the Bark 3.0 engine (and why the numbers matter)

**What it is** (`ConwayResearch/Underdog-Bark-3.0`, CC-BY-4.0): 163 MB packed download, a
0.6B Parakeet-TDT (FastConformer encoder + TDT decoder + LSTM predictor) fine-tune,
English-only, punctuation/casing/numerals preserved, writes fillers as spoken. Family:
3.0 (163 MB, 4.94 % avg WER), Plus (180 MB, 4.76 %), Pro (1.25 GB, 4.29 %), Clean (drops
"um"/"uh" — the right default for typing, see below). AMI meetings 7.82 % vs 9.42 % for
Parakeet and 13.63 % for Whisper large-v3.

**The Android path already exists:** `velddev/Underdog-Bark-3.0-sherpa-onnx` — unofficial
ONNX export in the exact layout of k2-fsa's Parakeet-TDT exports (`encoder/decoder/joiner/
tokens.txt`, `model_type=nemo_transducer`), tested with sherpa-onnx 1.13.8.

| Build | Disk | Notes |
|---|---|---|
| int8 (use this) | encoder 630.7 MB + decoder 47.7 MB + joiner 6.1 MB ≈ **685 MB** | weight-only int8; LSTM, embedding, depthwise convs stay fp32 |
| fp32 | encoder keeps weights in a separate >2 GB `encoder.weights` | not viable on a phone |

Measured on a desktop 8-thread CPU: **64 ms** to decode a 3 s utterance (int8), 138 ms for
8 s, RSS 1.15 GB after load. On a phone expect a multiple of that — still far inside a
"release the key and it appears" interaction.

**Consequences that shape the plan:**

1. **Memory is the constraint, not speed.** ~1.15 GB RSS is too much for 4 GB devices and
   hostile to the keyboard process → hence `:asr`. Set a stated floor (≥ 6 GB RAM) and
   refuse to install below it, rather than shipping a second engine to cover the gap.
2. **No streaming.** TDT here is an offline transducer (sherpa-onnx issue #2918 confirms),
   so partials are re-decodes. If live word-by-word feels bad in testing, swap in a
   streaming engine (sherpa-onnx streaming Zipformer, or Nemotron streaming — already used
   in the desktop app) behind the same interface; that is a model swap, not a rewrite.
3. **685 MB is a download, not an APK.** Use Play Asset Delivery (install-time asset pack)
   for the model, or a pinned-URL download from HF with the published SHA-256 on sideload
   builds. Never ship it in the base APK.
4. **Clean is what ships** (`velddev/Underdog-Bark-3.0-Clean-sherpa-onnx`). It removes
   fillers, false starts and repeats in the model, so there is no app-side filler filter.
   Measured on audible fillers; see the README for what it leaves behind.
5. **No leading silence.** Clean was evaluated on unpadded audio, so the 0.25 s pad the
   verbatim build wanted is removed — it shifted the first word rather than protecting it.
6. **Attribution is mandatory** (CC-BY-4.0, NVIDIA + Moondream upstream): a NOTICE screen in
   Settings and the NOTICE text in-app.
7. **English only.** There is no second engine to switch to, so the language picker states
   this up front; a visible product decision, not a hidden limitation.

**Licence/safety note:** the ONNX conversion is unofficial and untrusted-by-default — pin
its revision, verify every SHA-256 from the model card, and prefer converting the official
`model.bark` ourselves in CI over fetching a third-party export.

---

## 6. Feature: extreme customisability

Three declarative documents plus a JSON Schema each; the theme editor is a thin front-end
over them, and every shipped theme is a file with no privileged code path.

```jsonc
// layout.json — geometry in key units
{ "rows": [
    { "keys": [
        { "output": "q", "label": "q", "width": 1, "longPress": ["1", "@"] },
        { "kind": "shift", "width": 1.5 },
        { "kind": "mic",   "width": 1, "gesture": "swipe-up:voice" }
    ]},
    { "key": "space", "width": 6, "gesture": "swipe-left:language" }
]}

// theme.json — visual only, no behaviour
{ "colors": { "bg": "#0b0d10", "key": "#151a21", "keyPressed": "#232b36", "accent": "#5ee0c0" },
  "key": { "radius": 12, "gap": 6, "font": "SpaceGrotesk-SemiBold", "labelSize": 22 },
  "flight": { "duration": 200, "easing": "easeOutBack", "trail": true, "arc": 0.35 },
  "haptics": { "press": "light", "land": "tick" } }

// gestures.json — per-region action bindings
{ "space.swipe-down": "dismissKeyboard", "backspace.swipe": "deleteWord" }
```

Engine must support: variable row heights and key units; split/one-handed/floating modes;
extra rows; key kinds (char, shift, backspace with repeat + swipe-word, enter per
`imeOptions`, mic, language, emoji, macro); long-press popup sets; per-app profiles
(package + optional `inputType`); themes beyond colour (shapes, fonts, haptics, animation
curve, light/dark/AMOLED variants); live-preview editor with undo, import/export `.kbtheme`
(zip of JSON + fonts), share sheet, and safe mode.

Per-app profiles shipped as package → theme + layout only. The `inputType` half is the part
worth revisiting rather than dropping: keying on the field type as well as the app is what makes
"a number row in a password field, and nothing else" expressible, and it needs no new storage —
the `EditorInfo` that supplies the package supplies the input type in the same call.

Validation matters more than flexibility: parse → validate → fall back to defaults. A
malformed theme must never produce an unusable keyboard.

---

## 7. Milestones

| # | Milestone | Exit criteria |
|---|---|---|
| M0 | IME skeleton + flight spike | Real system keyboard (QWERTY, shift, backspace, enter, space) typing into real apps; one letter flies along a curve and commits on landing in ≥ 3 apps (Chrome field, Messages, a Compose app). Window-lane decision made with measurements. |
| M1 | Voice typing, engine spike first | Bark 3.0 int8 running in `:asr` via sherpa-onnx; 5 s utterance → text in field in < 1.5 s on a mid-range device, RSS and thermals measured; permission flow clean; mic never stuck open. |
| M2 | Customisation engine | JSON layout/theme/gesture + schema validation + live editor + import/export; 3 shipped themes; per-app profile; invalid theme falls back safely. |
| M3 | Model delivery + licence + polish | PAD/asset-pack delivery with checksum verification, attribution screen, onboarding (enable IME + grant mic + download model), accessibility (`ExploreByTouchHelper`, TalkBack), landscape/tablet. |
| M4 | Ship | Play listing (privacy disclosure: audio never leaves the device), crash-free target, battery/thermal soak test. |

Progress on M1 (2026-10-27): the native engine exists. `libbark_asr.so` binds the sherpa-onnx C
API (v2.0.1) to the Kotlin facade, is packaged for `arm64-v8a`, and the same translation unit
was run on the host against the pinned model: both test clips transcribed word-for-word as the
Python reference did, ~0.5 s per clip at 4 threads, 1451 MB peak RSS. Still open from M1: the
device run itself (latency, thermals, RSS on a phone), which no emulator here can stand in for.

Progress on M2 (2026-10-27): the customisation engine is in — validated JSON for layout, theme,
gesture and profile; a live-preview editor with `.kbtheme` import/export; two shipped themes; and
per-app profiles as package → theme + layout, resolved from `EditorInfo.packageName` when an input
view starts. Still open from M2: a third shipped theme, `inputType` in a profile, floating/split/
one-handed modes, fonts, light/dark/AMOLED variants, and the share sheet.

Sequencing notes: M0 is make-or-break for the animation — if the lane can't be made clean,
the fallback design is what ships and nothing downstream changes. M1's first task is the
engine spike *before* UI work, because the 1.15 GB RSS figure decides the device floor and
possibly the model choice.

---

## 8. Risks

- **IME window bounds vs. animation reach** — biggest visual-design risk; M0.
- **Caret anchor reliability** across WebView/Flutter/Compose/RV → always degrade, never break typing.
- **`commitText` timing** — per-char commits upset some autocorrecting fields; needs a per-`inputType` strategy.
- **1.15 GB RSS on phones** — OOM kills, slow first load (685 MB from storage), possible thermal throttling. Mitigate with `:asr`, mmap'd weights, `num_threads` 2–4, idle unload, and a stated device floor since there is no lighter engine.
- **Unofficial ONNX conversion** — pin and verify, or re-export ourselves; a silently wrong export is indistinguishable from a bad model.
- **English-only default** — will read as a bug unless the language picker explains it.
- **CC-BY-4.0 attribution** and Play Store IME-with-mic scrutiny — be explicit and verifiable that audio never leaves the device and no keystroke log exists.

---

## 9. Open questions

1. ~~**Default engine** — Bark with Whisper kept for multilingual + low-memory?~~
   **Decided:** Bark 3.0 Clean only. No Whisper, no second engine, no multilingual profile.
2. ~~**Filler words** — base model plus an on-device filler filter, or an export of
   **Bark 3.0 Clean**?~~ **Decided:** Bark 3.0 Clean (`velddev/Underdog-Bark-3.0-Clean-sherpa-onnx`),
   and the app-side filler filter is gone. Clean wants no leading silence either, so the
   0.25 s pad the verbatim build needed is removed too.
3. **Device floor** — "≥ 6 GB RAM, below that the app says so and stops" is now the only
   option, since there is no lighter engine to fall back to.
4. ~~Glide typing (swipe-to-type) — eventually in scope, or explicitly out?~~ **Decided:** in
   scope. Trail capture in the view, key-sequence extraction and dictionary/language-model
   decode in `core` (pure Kotlin, testable), one composing-text commit per word.
5. Separate app with its own Play listing (recommended), or branded as part of OpenWhispr?
