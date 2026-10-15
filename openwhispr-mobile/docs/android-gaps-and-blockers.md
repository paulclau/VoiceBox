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
