# Competitor research: dictation apps and customisable keyboards

Notes behind the look-and-feel editor in `android-keyboard/`. Three questions: what do the
dictation-first products do, what do customisable Android keyboards do, and which of those
features are worth building here.

## 1. Wispr Flow ("fuflow")

Cross-platform dictation: Mac, Windows, iOS and an Android bubble. Push-to-talk by default
(hold the shortcut, release to insert), with a hands-free toggle, and fully remappable
shortcuts.

- **Cleanup is the product.** Auto Cleanup has None / Light / Medium. Light strips fillers and
  fixes grammar; Medium may reword for clarity. "Undo AI edit" restores the raw transcript.
- **Backtrack.** Saying "actually" or "scratch that" removes a false start.
- **Spoken formatting.** "comma", "new line", numbered lists from spoken numbers.
- **Per-app style categories** (Personal, Work, Email, Other) that change capitalisation,
  punctuation and spacing — formatting, not rewriting.
- **Dictionary and snippets**, synced across devices; snippets expand a spoken trigger phrase.
- **100+ languages** claimed, 23 auto-detected.
- Pricing: free tier capped at 2,000 words/week on desktop; Pro $15/month.
- **Appearance is thin**: Light/Dark/System for its own hub, and a draggable Android bubble.
  It is not a keyboard and does not theme one.

Sources: [wisprflow.ai](https://wisprflow.ai/), [auto cleanup](https://docs.wisprflow.ai/articles/4283510616-auto-cleanup-control-how-much-flow-edits-your-dictation-beta),
[smart formatting and backtrack](https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-and-backtrack),
[dictionary](https://docs.wisprflow.ai/articles/4052411709-teach-flow-your-words-with-the-dictionary),
[Android setup](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings),
[pricing](https://wisprflow.ai/pricing).

Note: no FUTO product called "FUTO Flow" exists. The FUTO products are **FUTO Voice Input** and
**FUTO Keyboard**, which is the offline-voice-typing comparison below.

## 2. OpenWhispr

The desktop app this repository's other half is. Relevant because it is the same team's idea of
what dictation should feel like, and because its gaps are this project's reason to exist.

- Local engines (Whisper, Parakeet, Orukeet, Cohere, Nemotron) or cloud (OpenAI, Groq, Deepgram,
  AssemblyAI, …), 100+ languages, optional AI cleanup.
- Hold-to-record and push-to-toggle; a separate voice-assistant hotkey; translation hotkey.
- Custom dictionary with correction learning; history with configurable retention.
- Notes, meetings with diarisation, an AI assistant with approval-gated connectors.
- **No documented theme system**, and no Android keyboard — it is desktop plus an iOS app.

Sources: [GitHub](https://github.com/OpenWhispr/openwhispr), [docs](https://docs.openwhispr.com/),
[FAQ](https://docs.openwhispr.com/faq).

## 3. Keyboard landscape

The comparison that actually shapes this app's customisation.

| Product | Local voice typing | Customisation | Mic UX |
|---|---|---|---|
| FUTO Keyboard | Yes, offline models | Preset themes, advanced themes imported as ZIPs (44 colours, borders), YAML layouts | Tap to start/stop, 30 s default cap |
| HeliBoard | Only via an external voice IME | Deep: colours, background image, editable layouts, saved/shared themes, settings backup | Hands off to another app (reported as awkward) |
| Gboard | Google's on-device model on Pixel only | Themes incl. photos, key borders; no import/export | Tap mic |
| SwiftKey | Own multimodal model | 100+ themes, photo themes (not in Backup & Sync) | Tap mic or long-press comma |
| Typewise | No built-in mic | Honeycomb layout, themes, fonts | None — top feature request |
| Voxscribe / Whisper IME / WhisperType / Dictate | Yes (whisper.cpp / sherpa-onnx) | Light: light/dark, Material You, accent colour | Hold or tap depending on app |

Sources: [FUTO keyboard](https://keyboard.futo.tech/), [FUTO themes](https://github.com/futo-org/android-keyboard/releases),
[FUTO custom layouts](https://docs.keyboard.futo.tech/settings/customlayouts),
[HeliBoard](https://github.com/HeliBorg/HeliBoard), [HeliBoard mic issue](https://github.com/HeliBorg/HeliBoard/issues/764),
[Gboard advanced voice typing](https://support.google.com/gboard/answer/11197787?hl=en),
[SwiftKey photo themes](https://support.microsoft.com/en-us/swiftkey-keyboard/how-to-use-photo-themes-in-microsoft-swiftkey-keyboard),
[Typewise voice request](https://suggestions.typewise.app/suggestions/65517/voice-to-text-dictation),
[Voxscribe](https://f-droid.org/en/packages/org.y20k.voxscribe/), [Dictate](https://play.google.com/store/apps/details?id=net.devemperor.dictate).

## 4. What that changed here

Built into the editor (§ "The editor" in the README):

1. **A live preview, not a form.** FUTO's advanced theming is a desktop tool and HeliBoard's is a
   settings maze; judging a 44-colour theme without seeing keys is why people give up. The
   preview runs the real `LayoutResolver` and the real `Flight` curve.
2. **Import/export as a first-class feature.** HeliBoard and FUTO both treat shareable themes as
   a feature users ask for; `.kbtheme` is the plain theme document with no wrapper.
3. **Contrast help.** Every keyboard theme editor eventually produces unreadable labels; the
   editor warns when label and key brightness are too close and offers a fix.
4. **No 44-field surface.** The palette is the seven colours the renderer actually reads. Fields
   that do nothing are worse than absent.

Deliberately **not** built yet, in the order they look worth doing:

- **Per-app profiles** (Wispr's app categories, but for appearance): the editor and file format
  already support it; only the per-package lookup and UI are missing. No Android keyboard in the
  matrix documents doing this.
- **Voice formatting commands** ("comma", "new line", "scratch that"): the model is an ASR
  transcriber, not an instruction follower, so this needs either post-processing rules in `core`
  or a second pass — a real decision, not a tweak.
- **Mic hold-vs-tap setting**: FUTO, Voxscribe and Wispr differ, and users have opinions. Cheap
  to add once dictation runs on hardware.
