# BarkBoard — Android keyboard

A standalone Android IME: tap a letter and it flies along a curve into the text field; hold the
mic and speak, transcribed on-device by **Underdog Bark 3.0 Clean** (English, fillers and false
starts dropped by the model itself). Fully customisable layout, theme and gestures — globally, or
per app.

The design and its constraints are in [../docs/android-keyboard-plan.md](../docs/android-keyboard-plan.md).
This file covers what exists and how to run it.

## Status

| piece | state |
| --- | --- |
| Gradle project, core + app modules | builds, `gradle test assembleDebug` green |
| Layout engine (spec → rows/keys, hit testing) | done, unit-tested |
| Flying-glyph animation to the caret (`CursorAnchorInfo` → view coords) | done |
| Transparent flight lane above the keys (`onComputeInsets`: content top, touchable region, see-through window) | wired, off by default |
| Theme / layout / gesture specs, validated with fallback | done, incl. asset parse test |
| Keyboard UI: long-press popups, gesture swipes, haptics, recording state | done |
| Voice capture: 16 kHz `AudioRecord` → energy VAD → utterance | done |
| `:asr` process + `Messenger` protocol, model download w/ SHA-256 pins (Bark 3.0 Clean int8) | done |
| Native inference (`libbark_asr.so` over sherpa-onnx v2.0.1) | built and packaged for arm64-v8a; verified on the host, not yet on a phone — [docs/native-engine.md](docs/native-engine.md) |
| Settings screen (theme picker, model download, mic permission) | functional, plain views |
| Look-and-feel editor: live preview, palette, key shape, flight, haptics, `.kbtheme` import/export | built, not yet run on a device |
| Per-app profiles (package → theme + layout) | built, not yet run on a device |
| Play Asset Delivery, Play Store listing, attribution screen | not built |

Voice typing needs the native library (fetched, not committed — see Build) plus the ~685 MB int8
model, which is downloaded on the device from Settings. With both present the mic key transcribes:
the engine was verified on the host against the pinned model, word for word with the Python
reference. It has **not been run on a phone yet**, so latency, memory and thermals there are
unmeasured. Without the prebuilts the app still builds and the mic key reports the model as
unavailable — that is the honest state, not a bug in the keyboard.

When a model is on disk, the engine starts loading as soon as an input view appears
(`AsrProtocol.MSG_WARM`) instead of on the first mic press, so the first utterance is not paying
for a 630 MB read. The trade is memory for latency: `:asr` then holds ~1.2 GB for as long as the
keyboard is up, which is why the warm-up is skipped entirely when no model is present. Whether that
trade is right on a phone with little memory is still a measurement, not a decision.

Dictation is model-clean, not string-clean: no padding before the audio, no app-side filler
filter. The only text touched on the way in is sherpa-onnx's `<unk>` placeholder and whitespace.

## Build

Requires JDK 17, the Android SDK (platform 35, build-tools 35.0.0) and — for voice typing — NDK
29 plus CMake 3.22.1. If you do not have them:

```bash
./tools/setup-toolchain.sh     # installs JDK, Gradle, SDK, NDK and CMake under $HOME/.toolchain
source tools/env.sh            # puts them on PATH
```

Then fetch the sherpa-onnx prebuilts (once; ~26 MB of arm64 binaries, git-ignored), and build:

```bash
./tools/download-sherpa-onnx.sh
./gradlew test                 # core geometry/text/VAD specs + asset validation
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk (~28 MB with the engine)
```

`assembleDebug` and `test` are offline-capable after the first resolve (`--offline` works unless
dependencies changed).

## Verify the model without a phone

```bash
python3 -m venv /tmp/asr-venv && /tmp/asr-venv/bin/pip install sherpa-onnx soundfile pandas pyarrow
/tmp/asr-venv/bin/python tools/verify-model.py --model-dir /tmp/clean --download
```

It parses the SHA-256 pins straight out of `BarkModel.kt`, verifies the four downloaded files
against them, then loads them exactly as `libbark_asr.so` will (`nemo_transducer`, int8, **no
padding**) and scores 73 LibriSpeech dev-clean clips against their references. It exits non-zero
on a bad pin or a WER over `--max-wer`.

Last run on this checkout: all four pins matched; **4.61 % WER** (the model card reports 4.17 %
for this int8 build, on a different sherpa-onnx release); 1656 MB peak RSS including the Python
harness; ~0.5 s median per clip at 4 threads.

Filler removal was checked separately, on disfluent speech synthesised with Piper (the same
approach the model card used) and decoded by both pinned exports:

| said | Bark 3.0 Clean | Bark 3.0 (verbatim) |
| --- | --- | --- |
| "Um, so I think we should, uh, you know, meet at three." | `so I think we should, meet at three.` | `Um So I think we should, oh you know, meet at three.` |
| "I I I mean, the the deadline is Friday." | `I mean that the deadline is Friday.` | `I I I mean that the deadline is Friday.` |
| "We need to, uh, ship this by Monday, you know?" | `We need to, ship this by Monday,` | `we need to uh ship this by monday you know` |

So the filler, false-start and repeat removal is real, and the verbatim build is the wrong
default for a keyboard. Two artefacts come with it (see known gaps): a comma is left where the
filler was, and an utterance that began with a filler can start lowercase.

## Run on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable dev.barkboard.ime/.KeyboardService
adb shell ime set    dev.barkboard.ime/.KeyboardService
```

Or install, then open **BarkBoard** from the launcher and follow its setup rows (enable keyboard →
select it → grant microphone → download the model). The launcher entry *is* the settings screen;
there is no other app UI.

The APK carries `arm64-v8a` only, so it installs on phones but not on an x86_64 emulator. The model
download is ~685 MB and lands in the app's private storage; until it finishes the mic key reports
the model as missing rather than failing silently.

For the flying glyph to land in the text field, the target app must report cursor updates. Most
do; those that don't get the fallback (glyph flies to the top edge of the keyboard and fades), and
the character is still committed — only the landing animation is lost.

## Layout of the code

```
core/                        pure Kotlin, no Android imports → fast JVM tests
  layout/   LayoutSpec, LayoutResolver, KeyHitTester   spec JSON → positioned keys
  flight/   Flight, ParticlePool                       Bézier path + glyph pool, fixed dt
  text/     TranscriptStabilizer                      partials → committed text
  asr/      EnergyVad, PcmFile, TranscribeEngine       VAD, WAV writer, engine seam
  theme/    Theme, Gestures, SpecLoader                validated specs with fallback
  profile/  AppProfiles, ProfileResolver               per-app overrides and their resolution
  model/    BarkModel                                  Clean model pins + SHA-256 verification
  theme/    ColorKit, ThemeLimits                      HSL maths and the editor/validator ranges

app/
  KeyboardService.kt          InputMethodService: input connection, caret, dictation toggle
  ui/KeyboardView.kt          Canvas rendering, touch, animation, flight lane
  ui/ThemePreviewView.kt      the editor's live keyboard preview (same geometry, same curve)
  ui/CursorAnchorMapper.kt    CursorAnchorInfo → view coordinates
  voice/VoiceDictation.kt     AudioRecord + VAD + partial re-decodes + stabiliser
  asr/                        AsrService (:asr process), AsrClient, SherpaBarkEngine, JNI decls
  model/ModelRepository.kt    resumable download, checksum-verified, deletes bad files
  theme/                      built-in specs, ThemeRepository (assets, user themes and profiles)
  AppearanceActivity.kt       look-and-feel editor: preview, palette, shape, flight, haptics
  ProfilesActivity.kt         per-app theme and layout overrides
  SettingsActivity.kt, GrantActivity.kt
```

`core` deliberately has no Android dependency so the animation maths, layout resolution and text
stabilisation can be tested on a laptop.

## Customisation format

Three JSON documents, all validated on load. A malformed spec falls back to the built-in default
instead of producing an unusable keyboard, and reports the problem string (logged, and shown in
Settings).

Bundled in `app/src/main/assets/`:

- `layouts/qwerty.json`, `layouts/numbers.json` — rows of keys: `char` (with `output`, long-press
  `alternates`), `shift`, `backspace`, `enter`, `space`, `mic`, `language`, `macro`. Widths are
  fractions of the row. Layouts are assets, so the choice is between the ones shipped; profiles
  pick from the same list.
- `themes/midnight.theme.json`, `themes/paper.theme.json` — palette, key radius, elevation, font,
  haptic mode, flight duration/curve, `flightLane` height.
- `gestures/default.json` — swipe direction → action (`voice`, `language`, `deleteWord`,
  `space`, `dismissKeyboard`).

User themes live in `filesDir/themes` and shadow the asset of the same id; a file that fails
validation is ignored with a logged reason, so a bad import cannot brick the keyboard.

### The editor

Settings → **Customise look and feel** opens the editor. It previews a three-row keyboard drawn
with the real `LayoutResolver` and the real `Flight` curve, so radius, gap, label size, press
shrink, flight duration, arc and both scales are judged by looking rather than by imagining. Tap a
key in the preview to watch the glyph fly to a stand-in caret.

What is editable: all seven palette colours (HSL + opacity, with a contrast warning when a key
label would vanish into its key), corner radius, key gap, label size, press shrink, the whole
flight animation, and both haptic slots. Everything else is fixed.

Save writes `filesDir/themes/<id>.theme.json` atomically and bumps a revision the keyboard reads
while starting an input view, so an edit applies next time the keyboard opens — even though the
theme keeps its id. **Revert** returns to the version the screen opened with, **Duplicate** moves
the edit to a fresh id so the built-in survives, **Delete** removes a user theme (a built-in
reappears underneath), and **Import…/Export…** move a `.kbtheme` file through the system file
picker. Imports are validated before they are stored, and a colliding id is stored as
`<id>-2` rather than overwriting what was already there.

A `.kbtheme` file is exactly the theme document below — no wrapper, no extra fields — so a theme
can be written by hand or generated by a script and imported.

### Per-app profiles

Settings → **Per-app profiles** gives one app its own theme, its own layout, or both. Everything
else — every other app, and every part of an app's profile you leave alone — keeps following the
defaults on the Settings screen.

The keyboard resolves this in `onStartInput`/`onStartInputView`, where the `EditorInfo` names the
package the field belongs to, and rebuilds the input view when the resolved theme or layout differs
from the one it is showing. The same view is reused as the user moves between apps, so a profile
switch is exactly the "this app is different now" signal a rebuild needs; no extra bookkeeping is
involved.

Profiles live in `filesDir/profiles.json`:

```json
{
  "apps": [
    { "packageName": "com.android.chrome", "label": "Chrome", "themeId": "paper" },
    { "packageName": "com.termux", "label": "Termux", "layoutId": "numbers" }
  ]
}
```

`themeId` and `layoutId` are both optional and independent: Chrome above changes only its look, and
Termux only its keys. `label` is what Settings shows when the package cannot be resolved to an
installed app — it is not the key, `packageName` is.

Two deliberate behaviours:

- **A profile pointing at something that no longer exists degrades to the default, not to the
  built-in.** Delete a theme a profile used and that app falls back to your global theme rather
  than to the compiled-in one, and the reason is logged as a load problem.
- **The document is validated like the other three.** A broken `profiles.json` reads as "no
  profiles" with the reason kept for the Settings screen, so a hand-edit cannot leave the keyboard
  unloadable.

App names come from the launcher list (declared under `<queries>` in the manifest — without it
Android 11+ returns almost nothing), with a manual package-name entry for apps that are not there.

## Known gaps, in the order I'd close them

1. **The device run of voice typing.** The engine, the binding and the model are all verified on a
desktop CPU; what is left is first-load time from flash for a 630 MB encoder, decode latency on a
mid-range chip, RSS against Android's per-process limit, and the `:asr` reconnect path under real
memory pressure. That is measurement work, not construction work.
2. **Measure the flight lane.** `flight.laneDp` reserves transparent height above the keys and is
   reported to the window (`contentTopInsets`, `visibleTopInsets`, a touchable region that excludes
   it, plus a cleared window background) so the app keeps that space and its taps. It ships as `0`
   because whether a taller lane is worth the cost needs a real phone, and only the physics — not
   the plumbing — is unproven here.
3. **Tidy the filler debris.** Clean drops "um" but leaves the comma behind it
   (`We need to, ship this by Monday,`), and a transcript whose utterance opened with a filler
   starts lowercase. Both need the field context — capitalise only at a sentence start, drop a
   comma that has nothing to separate — which is why it is not a string filter bolted on here.
4. **A device check of the per-app profiles.** The lookup, the store and the screen are in
   (`filesDir/profiles.json`), but "does this app really get this theme" has only been verified by
   unit tests, not by typing in a real app with a profile set. Per-profile `inputType` overrides (e.g. a
   number row only in a password field) are not implemented.
5. **Play Asset Delivery** for the model, plus the CC-BY-4.0 attribution screen the licence
   requires before any release (Conway Research / Underdog, NVIDIA Parakeet TDT 0.6B v3,
   Moondream Parakeet Ultra).
