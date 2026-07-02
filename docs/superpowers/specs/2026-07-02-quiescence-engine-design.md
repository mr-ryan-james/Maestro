# Maestro Quiescence Engine — Design

## Purpose

Replace Maestro's guess-and-poll execution core (screenshot diffing, fixed sleeps, worst-case timeouts) with an event-driven quiescence engine so that AI agents running e2e flows or driving devices interactively experience minimal per-step latency. Target: per-step overhead in the tens of milliseconds on a settled screen; every avoidable wait eliminated.

The design ports the proven quiescence architecture from Celestial (`~/Dev/Celestial/celestial-deliverable/celestial-deliverable`, see `swift/Sources/CelestialHarness/Quiescence.swift`) into Maestro, adapted for React Native / Expo apps.

## Constraints

- **Fork is canonical.** Divergence from upstream mobile.dev Maestro is acceptable; upstream mergeability is not a design goal.
- **Nothing checked into app repos.** All app-side code is injected or provisioned into the ephemeral, Expo-generated `ios/` and `android/` directories (or injected at launch with no file changes at all). No RN/Expo packages, config plugins, or `app.json` entries.
- **Targets:** iOS simulator and Android physical devices/emulators. iOS physical devices are out of scope (they fall back to the black-box engine).
- **Graceful degradation.** Apps without the copilot, or platforms without injection, run on the rewritten black-box fallback. Absence of the copilot is a silent downgrade, never an error.
- Existing YAML flows may require edits (clean-slate compatibility bar); the default profile preserves upstream-like semantics for anything not migrated.

## Architecture

```
┌─────────────────────── Maestro fork ───────────────────────┐
│  Orchestra (sync-point planner)                             │
│    └── QuiescenceService (per driver)                       │
│         ├── Source 1: Copilot signals (event-driven push)   │
│         ├── Source 2: OS signals (windowUpdating, etc.)     │
│         └── Source 3: Black-box fallback (rewritten)        │
└─────────────────────────────────────────────────────────────┘
           ▲ socket (localhost / adb forward)
┌──────────┴──────────── inside the app ─────────────────────┐
│  Copilot (injected, never checked in)                       │
│   iOS: dylib via DYLD_INSERT_LIBRARIES at simctl launch     │
│   Android: AAR provisioned into generated android/ project  │
└─────────────────────────────────────────────────────────────┘
```

Three components:

1. **`maestro-copilot`** — new fork modules `copilot-ios` (Swift dylib) and `copilot-android` (Kotlin AAR). In-process idle tracking, quiescence protocol over a local socket, animation policy control.
2. **`QuiescenceService`** — driver-layer replacement for `waitForAppToSettle` / `waitUntilScreenIsStatic`. Consumes the best available signal source and degrades: copilot → OS signals → black-box fallback.
3. **Orchestra sync-point planner** — settling is requested only where the plan needs it, with per-command transition hints.

## Component: Copilot

### Signals

| Signal | iOS | Android |
|---|---|---|
| Main-thread idle | CFRunLoop observer | Looper idle + Choreographer frame callbacks |
| Layout/display epochs | `setNeedsLayout` / `setNeedsDisplay` swizzles (RN Fabric mounts land here) | `ViewTreeObserver` global-layout + Choreographer commit counts |
| Network in-flight | `URLSessionTask.resume` swizzle (RN iOS networking uses NSURLSession) | `OkHttpClientProvider.setOkHttpClientFactory` hook installed from the copilot ContentProvider, which initializes before RN builds its client |
| Screen transitions | `viewDidAppear` swizzle + root-structure fingerprint | Activity/Fragment lifecycle callbacks + window token changes |
| Stable frames | CADisplayLink counter | Choreographer-based counter |

Rules carried over from Celestial:

- Network idle excludes websockets and requests running longer than 10s, so long-poll/streaming connections never block quiescence.
- Stable-frame requirement scales with transition class: 1 frame for minor actions, more (default 2–3) after screen transitions; runtime promotes/demotes the classification when observed behavior contradicts the hint.
- Quiescence evaluation is a phase machine (`WAITING_FOR_RUNLOOP_DRAIN → EPOCHS_STABLE → ASYNC_IDLE → COMMIT → STABLE_FRAMES → QUIESCENT`); on timeout the unsatisfied phase and live signal values are reported.

JS-thread idle probing is deferred to v2: view epochs plus network tracking cover RN readiness because JS work manifests as mounts or requests.

### Animation policy

- iOS: copilot sets `UIView.setAnimationsEnabled(false)` and 1000× layer speed at launch (`ferrari` profile).
- Android: driver-side and copilot-free — adb sets the three system animation scales (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`) to 0 for the session and restores them after.

### Protocol

Tiny versioned request/response + push protocol over a local socket:

- iOS simulator: copilot listens on localhost; driver connects directly (simulator shares host network).
- Android: copilot listens on a localabstract socket; driver reaches it via adb forward (works identically on emulators and physical devices).
- On connect the copilot announces its protocol version. Version mismatch or missing copilot silently selects the fallback source.

Core messages: `awaitQuiescence(transitionClass, timeoutMs)` → `quiescent(epoch)` or `timeout(phase, signals)`; `setAnimationPolicy(off|fast|realistic)`; `getSignals()` for diagnostics.

## Component: QuiescenceService (driver layer)

Replaces `Driver.waitForAppToSettle`, `Driver.waitUntilScreenIsStatic`, and all fixed post-action sleeps. Selection order per session: copilot → OS signals → black-box fallback.

### Black-box fallback (rewritten, not tuned)

- Single new screenshot diffed against the cached last frame (halves capture cost versus the two-fresh-screenshots approach).
- Adaptive poll intervals: 16ms → 50ms → 100ms backoff.
- Settle budget is profile-scoped: 500ms under `fast`/`ferrari`, 3000ms under `default` (see Configuration).
- Android keeps the `windowUpdating` OS signal as a pre-check.
- Hierarchy-based settle (the 10 × 200ms fetch loop) survives only as a last resort inside the budget.
- All hardcoded `Thread.sleep` calls in drivers (300ms per Android keypress/backPress/hideKeyboard, etc.) are deleted; quiescence is the only wait primitive.

## Component: Orchestra sync-point planner

Assertions and element lookups are the sync points; actions just act.

- **No pre-tap settle when the hierarchy cache is fresh.** Hierarchies carry a quiescence epoch. If no action occurred since the last settled hierarchy, taps use cached coordinates immediately; a stale epoch triggers one re-fetch.
- **Post-action settling is hint-driven.** Commands carry a transition class:
  - `screen` — navigation expected; full quiescence + transition-grade stable frames.
  - `minor` — state toggle; 1 stable frame.
  - `none` — fire and forget (e.g. per-character typing).
  The compiler infers defaults (`tapOn` followed by `assertVisible` on new content → `screen`; `inputText` → `none`); YAML and MCP callers can override per command.
- **Lookup timeouts are profile-scoped**, replacing the single 17s default. MCP/agent calls support **fail-fast mode**: one settled hierarchy check, then an immediate structured miss with closest fuzzy matches and selector advice, because an iterating agent wants information in 300ms, not a 17s stall.
- **All polling loops use adaptive backoff** (16→50→100ms), replacing zero-sleep busy loops (`MaestroTimer.withTimeout`) and fixed 200ms sleeps.
- Tap retry-if-no-change uses the quiescence epoch instead of full hierarchy comparison per attempt.

## Provisioning

- **iOS simulator:** `maestro test` and MCP session launch inject `DYLD_INSERT_LIBRARIES=<fork-dist>/copilot.dylib` through the existing `simctl launch` environment path. The prebuilt dylib ships in the fork distribution alongside the XCTest runner. On by default for simulator targets; `--no-copilot` opts out. Zero app-repo footprint, no changes even to the generated `ios/` directory.
- **Android:** `maestro provision android` patches the Expo-generated `android/` project: appends a Gradle snippet adding `debugImplementation` of the copilot AAR (shipped in the fork dist). The copilot ContentProvider self-registers via manifest merge — no source edits. The command is idempotent; `expo prebuild` wipes the patch and re-running re-applies it. `maestro test` detects an unprovisioned target and prints the one command to run.

## Configuration

`speedProfile` is the single primary knob — per flow (`speedProfile: ferrari` in flow config), global (`MAESTRO_SPEED_PROFILE` env), or per MCP session. Profiles bundle settings that remain independently overridable (per-command `waitToSettleTimeoutMs` etc. still work):

| | `default` | `fast` | `ferrari` |
|---|---|---|---|
| Settle budget | 3000ms | 500ms | copilot event-driven, 5s ceiling |
| Lookup timeout | 17s | 3s | 3s (fail-fast in MCP) |
| Animations | untouched | Android scales off | off everywhere |
| Pre-tap settle | always | cached-epoch skip | cached-epoch skip |
| Hardcoded sleeps | removed | removed | removed |

`default` preserves upstream-like timing semantics for unmigrated flows.

## Diagnostics

- **Timeout failures name the blocking signal** in CLI output and MCP tool results, e.g. `not quiescent after 5000ms: network(2 active: POST /api/feed), stable_frames(1/3)`.
- **Per-step timing breakdown** in test output: `lookup: 45ms, action: 12ms, settle: 33ms`.
- Copilot `getSignals()` is exposed as an MCP diagnostic tool for live inspection.

## Testing

- Unit tests for `QuiescenceService` against fake signal sources (copilot present/absent/version-mismatched, timeout phases).
- Copilot unit tests per platform (signal trackers, phase machine) mirroring Celestial's `QuiescenceTest` coverage.
- Fixture-app e2e flows exercising each transition class on both platforms.
- **Benchmark lane:** a fixture flow measured per run asserting per-step overhead stays under budget, so the engine cannot silently regress.

## Build order

1. **Phase 1 — Orchestra rewrite + black-box fallback + Android animation scales.** Largest wins, no injection work.
2. **Phase 2 — iOS copilot dylib** (Celestial port) + simctl injection.
3. **Phase 3 — Android copilot AAR** + `maestro provision android`.
4. **Phase 4 — Profiles polish, MCP fail-fast, benchmark gate.**

## Risks

- **RN Fabric internals drift.** Mitigation: signals hook platform-level APIs (UIKit/ViewTreeObserver/Choreographer), not RN internals, except the OkHttp factory hook, which has a TrafficStats-delta fallback if RN changes `OkHttpClientProvider`.
- **DYLD injection blocked by hardened runtime.** Expo debug/dev-client simulator builds are not hardened; if a build is, the session degrades to fallback and reports why.
- **Quiescence false-positives** (app idle but content not ready). Assertions remain the correctness backstop: a lookup that misses re-awaits quiescence within its lookup budget.
- **Physical-device Android socket flakiness.** adb forward is the same transport the existing driver uses; copilot connect failures degrade to fallback.
