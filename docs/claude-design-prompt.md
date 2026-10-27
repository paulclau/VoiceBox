# Design brief for Claude — Bark Board keyboard UI

Copy everything below the line into a Claude session (use extended thinking; ask for
deliverable-by-deliverable output rather than one giant document).

---

## Your job

You are designing the interface for an Android keyboard app and its settings app. Produce a
complete UX/UI specification and visual design from the requirements below. You are starting
from a blank sheet: no existing screens are being handed to you, so do not assume, reference
or imitate any current layout, palette or styling. Design what the requirements imply.

Deliver, in this order:

1. A screen inventory (name, purpose, entry point, and what the user can do there).
2. Per-screen layout with hierarchy, regions and approximate proportions.
3. A component library: keys, key states, popups, banners, list rows, sliders, colour
   pickers, sheets, dialogs, progress and error surfaces.
4. A state matrix covering every state listed under "States that must be designed".
5. A design-token set (colour roles, type scale, radii, spacing, elevation, motion
   durations/easings, haptic vocabulary) expressed as roles, not values you cannot justify.
6. A motion spec for the one signature animation, including what happens when it cannot
   play.
7. Notes on accessibility behaviour per surface.
8. Anything you had to assume, listed explicitly at the end.

## What the product is

An Android input method (a real system keyboard, replacing Gboard et al.) with two jobs that
no mainstream keyboard does together:

- **Voice typing that never leaves the device.** A 685 MB speech model runs on the phone. No
  audio is uploaded, no account, no cloud, no network needed to type.
- **A keyboard the user can redesign themselves** — colours, key shape, spacing, animation
  feel, haptics per tap, different looks per app — without a code editor.

And one signature idea:

- **The letter you tap physically travels from the key to where it will appear**, then the
  text is committed as it lands. Typing is not a beep-and-paste; it is a small journey.

Constraints that shape everything:

- The keyboard is a `View` the IME renders itself, drawn into a window that sits at the bottom
  of the screen, over another app. It does not own the text field, the app bar, or the screen.
- Screen height is the scarcest resource. Every pixel of permanent chrome is a pixel the user
  cannot see of their conversation.
- One-handed thumb use is the primary posture, often while walking. Landscape and tablets are
  secondary but real.
- Text is committed per character as each glyph lands, so the field can visibly grow behind
  the animation.
- The engine is **English-only** and drops filler words ("um", "uh", false starts, repeated
  words) as it transcribes. This is a product limitation that must read as a deliberate
  choice, not a bug.
- The model download is large and can fail, resume, or be incomplete. Users will hit this on
  first run and after a reinstall.
- No keystroke log, no network, no analytics. The UI is the only place where that promise can
  be made visible and believable.

## Surface 1 — The keyboard

### Keys and layout
- A grid of keys described by rows; key widths are relative units, rows can differ in height,
  and each row fills the available width exactly.
- Key kinds the design must cover: character, shift (with a state that shows it is active on
  the next letter only), backspace (repeat on hold, plus a "delete a whole word" gesture), enter
  (its label and meaning follow what the host app asks for — send, search, go, done, next,
  newline), space, microphone, language, emoji, and multi-step macro keys.
- Long-press on a key can offer a strip of alternate characters; the finger slides along the
  strip to choose, and the choice is highlighted before release.
- Every key can carry swipe actions (up/down/left/right) with bindings. Some are global, some
  per key, and a key's own binding wins. Default behaviours include: swipe the microphone up
  to dictate, swipe the spacebar left to switch language, swipe down on space to hide the
  keyboard, swipe the backspace to delete a word.
- **Design problem to solve:** a user cannot see that a key has swipe bindings. Affordances
  for discoverability are part of the deliverable — but they cannot permanently clutter 30+
  keys. Consider first-run hints, a hint layer that appears only while a key is held, a
  one-time teaching overlay, or a settings-side "what this key does" surface. Recommend one
  and design it.
- At least two layouts ship (letters and numbers/symbols) and users can add their own, so the
  design cannot assume a fixed key count, fixed row count, or that any specific key exists.
  Design for keys that are absent, keys that are unusually narrow, and a layout with an extra
  row.

### The flying letter (signature interaction)
- On touch-down the key must already look pressed — the press pose is on the same frame as
  the touch, before the animation begins.
- The glyph then travels along a curve from the key centre toward the caret in the host app,
  scaling and fading, with an optional trail, over a user-configurable duration.
- The text is committed to the field when it lands, with a small arrival acknowledgement (an
  accent ripple/fade at the destination) and an optional haptic.
- Fast typing spawns many glyphs at once. Design the density behaviour: overlapping paths,
  visual noise, and what happens when the pool is full (the character must still be typed; its
  flight is skipped). The experience must not degrade into a smeared mess at 8 taps/second.
- **Hard constraint:** the IME window normally ends where the keyboard begins, so a glyph
  cannot always be seen reaching the caret. Two behaviours exist and both need designs:
  (a) a transparent band above the keys that the keyboard reserves so glyphs can fly over the
  text — it costs the host app that much screen height, so it is opt-in; (b) when the caret is
  unknown or unreachable (many web views, some chat apps, first frame after focus), the glyph
  flies to the top edge of whatever area the keyboard controls and fades. Design (b) so it
  still reads as a deliberate arrival rather than a truncated animation.
- Design the "no animation" case too: users can turn motion off, and battery-saver or slow
  frames must degrade to a short fade without the field feeling laggy.

### Voice typing (vocal states)
- Holding the mic (or tapping, per setting) records; releasing transcribes. Text appears in the
  field progressively as the engine re-decodes the growing utterance, then a final pass
  completes it. The user must be able to tell that text is provisional vs. final, without the
  field becoming a strobe of revision.
- States needing design: idle; permission not yet granted (the mic cannot show the system
  dialog itself, so it routes through a separate permission screen); recording (including
  level feedback, elapsed time, and an honest "the mic is live" indicator); decoding/finishing;
  nothing heard (too short or silent); model missing or still downloading; transcription
  failed; auto-stop because silence was detected for a while; the 60-second cap approaching and
  reached.
- Design where the recording indicator lives so it cannot be mistaken for the host app's own
  mic use, and so it is visible when the keyboard is compact.
- Design the failure surfaces as non-blocking: a failed dictation must never eat the text that
  was already typed, and must offer one obvious retry.

### Chrome that must exist somewhere on the keyboard
- Some way to dismiss/hide the keyboard.
- Language: the app is English-only today. A language control exists in the layout. Design how
  it communicates "only one language is installed and the model cannot do others" without
  looking broken or unfinished.
- Emoji: there is an emoji key in the shipped layout, but no picker yet. Design the placeholder
  or "not available yet" behaviour so the key is not a dead end. Do not design a full emoji
  picker.
- Nothing else is permanent chrome. No suggested-words strip, no autocorrect bar, no clipboard
  panel, no toolbar — these are explicitly out of scope. Do not design them in.

## Surface 2 — The settings app

A separate app the user opens from the launcher. Its primary job is to get the keyboard
working on first run, and then to make customisation feel safe.

### 2.1 Setup / home
Must express, at a glance, four independent prerequisites and let the user act on each:

1. The keyboard is enabled in system settings (deep-links to the system screen).
2. The keyboard is selected as the current input method (opens the system picker).
3. Microphone permission is granted (opens a permission screen; states granted/not granted).
4. The speech model is present and verified — 685 MB across several files, each checkable.
   States: missing (with "n of m files verified"), downloading (per-file progress and overall
   progress; must survive leaving the screen), verified/ready, download failed (with reason and
   resume), and "engine library missing in this build" (a developer-facing failure that still
   needs a human-readable form).

Design this as a checklist or equivalent that makes "what is left before I can type" obvious
in one glance, and that is honest when a step can only be completed outside the app. Include
the current selection (theme + layout) and one obvious way into customisation.

Also on this surface: the licence/attribution text for the speech model (CC-BY-4.0, derived
from NVIDIA and Moondream work), the English-only + filler-dropping note, and the privacy
statement that audio never leaves the device.

### 2.2 Theme and layout selection
- Choose the active theme and the active layout from flat lists of names.
- Themes may be built-in or user-made; a user's edited copy of a built-in theme must be
  distinguishable from the original, and choosing between them must be unambiguous.
- Changing either applies the next time the keyboard opens, not immediately. Design how that is
  communicated (a transient confirmation, a persistent "pending" marker, or both).
- Deleting a theme: confirm, explain that the file is removed from the device and any built-in
  of the same name reappears underneath, and recover from the case where the active theme was
  deleted.

### 2.3 Look and feel editor
A live preview sits inside this screen: tapping a key in the preview must show the real
flying-letter animation so the user can feel a change before saving. Everything below is
editable, grouped so a non-designer can succeed without understanding the file format:

- **Colours:** background, key, pressed key, accent, key label, label-on-accent, key border.
  Each is a full colour picker (hue, saturation, lightness, opacity), not a hex field.
- **Keys:** corner radius, gap between keys, label size, press-shrink amount.
- **Flying letter:** on/off; duration; arc (curvature, positive and negative); start size;
  landing size; trail on/off; and the flight-band height, which is off by default because it
  costs the host app screen height.
- **Haptics:** separate choices for press and for landing, from a fixed vocabulary
  (none, light, medium, heavy, tick, click).
- **Theme management:** save, revert to the state the screen opened with, duplicate (editing a
  copy, with both kept on save), import a theme file, export/share a theme file, delete.
- **Guardrail:** if the user picks a label colour too close in brightness to the key colour,
  warn and offer a one-tap readable fix. Never silently allow an unreadable keyboard; design the
  warning so it teaches rather than blocks.
- **Validation failure:** a malformed imported theme or an out-of-range value must fall back to
  a working keyboard and explain what was wrong in human terms. Design that message.

### 2.4 Per-app profiles
- A profile gives one app its own theme, its own layout, or both. Anything unset follows the
  global default, and that inheritance must be visible in the list.
- Add a profile by picking an installed app from a list (icon + name), with a manual package-name
  entry for apps that do not appear. Explain where a package name comes from.
- Edit: theme = same as default / a specific theme; layout = same as default / a specific
  layout. Show the current effective result in the row.
- Remove a profile with confirmation and a clear statement that the app returns to defaults and
  nothing else changes.
- Saved profiles can become unreadable or invalid: design a non-destructive warning explaining
  that saved profiles are being ignored and why.
- Profiles apply the next time the keyboard opens in that app.

### 2.5 Supporting screens
- **Microphone permission screen:** why the mic is needed, that audio stays on the device, and
  the system prompt — reachable from the keyboard's mic key and from setup.
- **Licence / about:** the model's attribution and licence text in readable, in-app form.
- **Errors and empty states:** no themes in build, no layouts in build, import failed, export
  failed, save failed, nothing granted yet.

## States that must be designed

Keyboard: idle; key pressed; shift active; long-press strip open; mid-swipe; recording;
decoding; transcription failed; nothing heard; model missing; permission missing; flight
running at low density; flight running at high density; flight with no caret (fallback);
motion disabled; band enabled vs. disabled.

Settings: not set up (all four prerequisites unmet); partially set up; fully set up; model
downloading; download failed; engine unavailable; theme edited but unsaved; theme invalid;
profile invalid; empty profile list; active theme deleted.

## Accessibility and platform requirements

- TalkBack has to be able to reach every key as a labelled control, announce its state (shift
  on, recording), and perform it — a canvas-drawn keyboard is invisible to it by default, so
  design the focus order, labels and announcements.
- Minimum touch target for any key must hold even in dense custom layouts; state what happens
  when a user's layout makes keys smaller than the minimum.
- Text and labels must meet contrast requirements in every colour combination the editor allows,
  which is why the contrast guardrail exists — design its copy.
- The keyboard must not make the host app unusable: the text field and its caret must never be
  covered by the keyboard or the flight band.
- Landscape, small phones, tablets and foldables: describe how regions reflow, and whether
  settings adopt a two-pane layout.
- One-handed use: specify any reachability measures (a compact mode, key height behaviour, or a
  settings-side option) rather than leaving it implicit.

## Voice and tone

Plain, warm, unfussy, second person, no exclamation marks, no jargon. Name things by what they
do ("Look and feel", "Per-app profiles", "Flying letter") rather than by their implementation
("theme.json", "lane"). Never claim something is cloud-processed, never imply data collection,
and never apologise for the English-only engine — state it once, clearly.

## Rules

- Design only what is described above. Do not add autocorrect, predictions, clipboard history,
  GIFs, stickers, theming marketplaces, accounts, sync, or cloud features.
- Do not describe or assume any existing visual design; treat every colour, shape and layout as
  yours to propose, and justify the choices against the constraints (one-handed reach, height
  cost, outdoor legibility, OLED battery).
- Where a requirement forces a trade-off (height vs. animation, discoverability vs. clutter,
  flexibility vs. safety), say which way you went and why.
- Mark anything you cannot satisfy within an Android IME window as a limitation with a fallback,
  not as a wish.
