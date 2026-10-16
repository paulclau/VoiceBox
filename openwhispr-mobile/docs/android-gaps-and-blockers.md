# Gaps & Ship Blockers — OpenWhispr Mobile (Android)

> Companion to `android-architecture-plan.md`. Checked items get a date + commit
> when closed. Re-audit before every release cut.

## Ship blockers

- [ ] **End-to-end run on hardware.** Live mic → transcription, real model
  downloads, packaged window/background behavior. Mocked-IPC tests only so far.
- [ ] **Returning-user rollout decision.** Who gets forced into new onboarding
  shells vs. fresh installs only (cf. desktop `localSetupV1` cutover lesson).
- [ ] **Translation review.** New-flow copy (`localOnboarding`-equivalent
  strings) in 10 non-English locales is unreviewed; fallback is English.
- [ ] **Two-device sync convergence.** Offline queue → reconnect → merge for
  notes/folders/snippets/dictionary; conflict + identity semantics aligned
  with desktop. Release gate, not aspiration.
- [ ] **On-device inference verdict.** Parakeet latency/battery on mid-range
  devices vs. kill criteria; cloud default + explicit UX if it misses.
- [ ] **Brand fonts.** Falls back to bundled without repo access — needs a
  licensed drop before release.
- [ ] **Play release checklist.** Signing, staged rollout, crash reporting,
  battery/memory profiling of local models.

## Android build — first green local build (closed this session)

`:app:assembleDebug` (x86_64) → `app-debug.apk` (109 MB) installed on a
Pixel_9_API_35 emulator (API 35). Metro bundled `expo-router/entry` (4,912
modules), the app rendered its first onboarding screen, and the only native log
noise was React Native's own dev-mode soft exception (`onWindowFocusChange`
before the React context is ready). Screenshot: `docs/assets/android-onboarding.png`.

- [x] **Kotlin stdlib mismatch.** `react-native-appsflyer` declares
  `kotlin-stdlib:${safeExtGet('kotlin_stdlib_version', '2.4.10')}`, so the module
  compiled against a stdlib newer than Expo's Kotlin (2.1.20) and
  `:react-native-appsflyer:compileDebugKotlin` died with an “internal compiler
  error” (really a metadata mismatch reported from the type checker). Fixed by
  `plugins/android-kotlin-stdlib/withAndroidKotlinStdlib`, which forces
  `kotlin-stdlib*` to `rootProject.ext.kotlinVersion` in every subproject.
- [x] **Manifest merger failure.** `expo-secure-store` and
  `com.appsflyer:af-android-sdk` both declare `dataExtractionRules` /
  `fullBackupContent`. Fixed by
  `plugins/android-backup-attributes/withAndroidBackupAttributes`, which adds
  `tools:replace` for the app's own rules (AppsFlyer's option deletes ours
  instead, which would let Keystore-backed values into cloud backups).
- [x] **Missing `@drawable/splashscreen_logo`.** `withAndroidSplashStyles`
  always writes the Android 12+ splash icon, but the drawable is only generated
  when a splash *image* is configured — `splash` had a background colour only.
  Fixed by giving Android the repo's previously unused `assets/splash-icon.png`
  (`android.splash`), leaving the iOS splash untouched.
- [x] **No Android EAS profiles.** `eas.json` had iOS-only `build` entries, so
  no Android build could be started. Added `android` to
  `development`/`preview` (apk) and `production` (app-bundle).

Still open (build-related):

- [ ] `android: userInterfaceStyle` warning: `expo-system-ui` is not installed,
  so the declared `light` style does not lock the Android theme.
- [ ] Play submission: no `submit.production.android` service-account path, and
  no upload/signing key story beyond the debug keystore.
- [ ] No CI Android build has ever run (EAS or otherwise) — the green build is a
  local, x86_64-only one.
- [ ] A production build bundling the JS (`assembleRelease` / EAS) is unverified;
  only the dev-client debug variant has booted.

### Local build notes (Windows)

- **Checkout path length.** Native codegen object paths exceed Windows' 260-char
  limit under this checkout
  (`…\OneDrive\Documents\GitHub\VoiceBox\openwhispr-mobile`), so ninja fails
  with `Filename longer than 260 characters` for the autolinked
  `react-native-safe-area-context` sources. Build from a short path — a
  `subst` drive mapped to the repo's *parent* works (`subst Y: <VoiceBox>`, then
  build in `Y:\openwhispr-mobile\android`). Mapping the app folder itself to a
  drive root does **not** work: expo-autolinking's package.json walk skips the
  loop body when the working directory is a drive root and reports
  `Couldn't find "package.json" up from path "X:\"`.
- **Memory.** Reanimated's C++ build (`InterpolatorFactory.cpp`) exhausted memory
  and killed clang while the emulator was running. Free the emulator first and
  cap parallelism (`--max-workers=2`, `-PreactNativeArchitectures=x86_64`).
- First native build is multi-minute per ABI; a debug APK for one ABI is ~110 MB.

## Functional gaps — desktop shell (closed this session)

- [x] Assistant chord went to the dictation `updateHotkey` channel (clobbered
  dictation registration, never set `voiceAgentKey`). Now routed through
  `setVoiceAgentKey`; pinned by
  `test/components/localOnboardingActivation.test.js`.
- [x] Skip path bypassed assistant registration — shell `persistActivation`
  now persists the chord to the voiceAgent slot too.
- [x] Model picks overwrote saved hotkeys with stub values — stub
  persistence removed from stack select handlers.
- [x] Default language ignored i18n/browser locale when
  `preferredLanguage` was `"auto"` (the store default).
- [x] Mic AudioManager mounted on shell mount — lazy `MicTestPanel`.
- [x] Cutover ignored by window-mode/hotkey gating — `showLocalSetup`
  plumbed through `AppRouter` + `controlPanelWindowMode`.

## Functional gaps (mobile vs. desktop)

- [ ] Microsoft + Apple calendar (mobile has Google only).
- [ ] All four connectors on device (Gmail send, Slack, Linear, GitHub) with
  the approval-card + receipt model.
- [ ] Semantic search story (Qdrant sidecar has no mobile equivalent —
  cloud-only until an on-device alternative lands).
- [ ] Notes editor parity gaps (TBD in a screen-by-screen audit).
- [ ] No "retry all" for failed model-download lists (per-item retry only).
- [ ] No back navigation in setup shells (forward-only steps).
- [ ] Flaky 5s timeouts under full-suite load (2 known suites) — fix with
  `retryTimes`/per-test timeouts, never by weakening assertions.

## Hygiene / process

- [ ] Full desktop + mobile suites green in one run (only targeted suites run
  per-change in session).
- [ ] ~28 desktop files uncommitted — split into reviewable commits before
  further work lands on top.
- [ ] No test renders the new desktop `OnboardingShell` orchestration
  (step handoff, activation completion) — typechecked only.
