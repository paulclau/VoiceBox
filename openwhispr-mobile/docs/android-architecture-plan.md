# Android Architecture Plan — OpenWhispr Mobile

> Status: audit complete. Decision: **parity completion on the existing Expo app**, not a rewrite.
> Baseline (Oct 2026): `tsc --noEmit` clean; Jest **2977/2979** (2 failures are
> load-flaky 5s timeouts — both pass solo 80/80 and 83/83; do not "fix" by
> weakening assertions, use `retryTimes` or per-test timeouts).

## 1. Guiding principles

1. Business logic stays in TypeScript; every platform seam sits behind an
   interface (the `TranscriptionService` / `localEngine` pattern is the model).
2. Desktop Electron IPC (`window.electronAPI.*`) must never leak into shared
   code — each needs an Android adapter (Expo module / native module).
3. Sync is the source of truth across devices; two-device convergence is a
   release gate, not an aspiration.
4. On-device inference is opt-in until it proves itself on mid-range hardware
   (latency + battery kill criteria, §5).

## 2. Capability matrix (mobile vs. desktop)

| Area | Mobile state | Verdict |
|---|---|---|
| Transcription | `LocalParakeetService`, `LocalWhisperService`, model downloader, benchmark screen, realtime meeting WS + reconnect | Adopt — spike §5 only |
| Sync engine (`src/sync/`) | Push/pull notes, folders, snippets, dictionary, team spaces, cursors, backfill | Adopt — verify convergence (§4.1) |
| Notes | List, editor, folders, actions, diarization screens, export | Adapt (editor parity gaps TBD in audit) |
| Reasoning/agent | `ReasoningService`, local reasoning + fallback, agent composer + stream client | Adapt |
| Providers/auth | Credential scopes, policy, execution, billing reconciliation, Superwall/RevenueCat | Adapt |
| Calendar | Google Calendar service + screen only (no Microsoft/Apple) | Gap — Phase 3 |
| Connectors | No Gmail/Slack/Linear/GitHub actions on device | Gap — Phase 3 |
| Input | Custom keyboard, shortcuts, tone, PiP tutorial, voice enrollment/profiles | Android-native, keep |
| Onboarding | Dedicated flow + steps | Adapt — align with desktop's rebuilt local-first flow |
| Paywall/analytics | Superwall, AppsFlyer, Sentry, tracking transparency | Adopt |

## 3. Platform seams needing Android adapters

- **Audio capture/playback** — foreground service + partial wake lock; Bluetooth
  mics, call interruptions, OEM background limits.
- **Background work** — uploads, model downloads, sync retries via
  WorkManager (not Expo background tasks alone).
- **Storage** — SQLite (expo-sqlite/OP-SQLite) schema parity with desktop
  `better-sqlite3` where sync depends on it. Qdrant sidecar has no mobile
  story — semantic search is cloud-only until an on-device alternative lands.
- **Auth/session** — share Better Auth session with desktop; tokens in
  EncryptedSharedPreferences/Keystore, never plaintext AsyncStorage.
- **Triggers** — no global hotkeys on Android: quick-settings tile,
  accessibility shortcut, persistent notification actions.

## 4. Phased delivery

- **Phase 0 (done)** — dep install, typecheck, full Jest baseline, matrix.
- **Phase 1 — Sync convergence test.** Offline queue → reconnect → two-device
  merge for notes/folders/snippets/dictionary; conflict + identity semantics
  aligned with desktop (`syncIdentity`, push scopes). Release gate.
- **Phase 2 — On-device inference spike.** Parakeet latency/battery on
  mid-range devices; kill criteria → cloud default with explicit UX.
- **Phase 3 — Dictation parity.** Foreground-service recording, local/cloud
  transcription, auth + account sync.
- **Phase 4 — Notes parity.** Editor gaps, offline queue + backfill, search.
- **Phase 5 — Assistant/connectors/calendar.** Reasoning providers,
  approval-gated actions reusing desktop result-type contracts, meeting
  transcription over the existing realtime WS service.
- **Phase 6 — Hardening.** Profiling, paywalls, Play signing/staged rollout,
  crash reporting, brand-font licensing (currently falls back to bundled —
  needs repo access or a licensed drop before release).

## 5. Decisions to lock early (open)

- [ ] ONNX Runtime mobile vs. sherpa-onnx Android builds for Parakeet/Whisper.
- [ ] Mid-range latency/battery targets + cloud-fallback UX.
- [ ] Returning-user rollout for any mobile onboarding rebuild (fresh-install
  only vs. forced, cf. desktop `localSetupV1` cutover lesson).
- [ ] Microsoft/Apple calendar + connectors scope for v1 (recommend: defer).
- [ ] Flaky-timeout remedy: `jest.retryTimes` vs. raised per-test timeouts.
