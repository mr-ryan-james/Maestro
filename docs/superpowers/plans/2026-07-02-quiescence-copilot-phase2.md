# Quiescence Copilot — Phase 2 (iOS simulator) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or executing-plans. Steps use `- [ ]` checkboxes.

**Goal:** Add event-driven quiescence to the Maestro fork via an injected in-app copilot, so the `ferrari` profile settles on real app-idle signals instead of screenshot-diffing — cutting per-step settle from hundreds of ms to tens, and (as a side benefit) removing the per-screen lazy-bundle and AX-timeout guesswork that makes black-box settling flaky.

**Architecture:** A Swift dylib (`copilot-ios`) injected into the iOS simulator app at launch via `SIMCTL_CHILD_DYLD_INSERT_LIBRARIES`. It tracks run-loop drain, layout/display epochs, in-flight network (excluding websockets/SSE and >10s requests), view-controller transitions, and stable frames, then answers `awaitQuiescence` over a localhost socket. A `QuiescenceService` in the driver selects copilot → OS signals → the existing black-box fallback. Ships in `installDist` output so both machines get it via the wrapper symlink. Spec: `docs/superpowers/specs/2026-07-02-quiescence-engine-design.md`.

**Tech stack:** Swift 6 / xcodebuild (simulator dylib, arm64+x86_64), Kotlin driver layer, existing `LocalSimulatorUtils.launch` params map.

**Scope:** iOS simulator only (Ryan's setup: iOS sim + physical Android; iOS devices out of scope). Android copilot (AAR + `maestro provision`) is Phase 3, deferred.

---

### Task 1: copilot-ios Swift module skeleton + build
- Create `copilot-ios/` Swift package: `QuiescenceEngine`, `SignalTrackers`, `SocketServer`, `main` dylib entrypoint (`__attribute__((constructor))` bootstrap).
- Port Celestial's `Quiescence.swift` trackers (`~/Dev/Celestial/.../swift/Sources/CelestialHarness/Quiescence.swift`): `RunLoopTracker` (CFRunLoop observer), `EpochTracker` (`setNeedsLayout`/`setNeedsDisplay` swizzles), `NetworkTracker` (`URLSessionTask.resume` swizzle, exclude `URLSessionWebSocketTask` + >10s tasks), `TransitionTracker` (`viewDidAppear` swizzle), CADisplayLink stable-frame counter.
- Gradle task `:copilot-ios:assembleDylib` shelling to `xcodebuild`/`swiftc` → fat simulator dylib at `maestro-cli/build/install/maestro/copilot/libmaestro-copilot.dylib` (wire into `installDist`).
- **Verify:** `./gradlew :copilot-ios:assembleDylib` produces the dylib; `file` shows arm64+x86_64.

### Task 2: socket protocol
- Versioned length-prefixed JSON over localhost (`MAESTRO_COPILOT_PORT`): `hello{version}`, `awaitQuiescence{transitionClass,timeoutMs}→quiescent{epoch}|timeout{phase,signals}`, `setAnimationPolicy{off|fast|realistic}`, `getSignals()`.
- Copilot binds the port from `getenv("MAESTRO_COPILOT_PORT")` in its bootstrap.
- **Verify:** unit test the phase machine + a socket round-trip against a fake app loop.

### Task 3: DYLD injection at simulator launch
- `maestro-ios-driver/.../util/LocalSimulatorUtils.kt` `launch()` (~line 327): add to the existing `runCommand` params `SIMCTL_CHILD_DYLD_INSERT_LIBRARIES=<dylibPath>` and `SIMCTL_CHILD_MAESTRO_COPILOT_PORT=<port>`, gated on: profile==ferrari OR `MAESTRO_COPILOT=1`, dylib exists, simulator target. `MAESTRO_COPILOT=0` opts out. Missing/hardened → skip silently.
- **Verify:** launch a sim app with injection; copilot logs `hello`; `getSignals()` returns live data.

### Task 4: QuiescenceService source selection
- New `QuiescenceService` in the driver layer: try copilot socket first; else OS signals (`windowUpdating` etc.); else the shipped black-box fallback (Phase 1). `awaitQuiescence` replaces the settle call in `waitForAppToSettle` when a copilot session is live.
- Timeout diagnostics name the blocking signal (`network(2 active)`, `stable_frames(1/3)`).
- **Verify:** rename the dylib → silent fallback to black-box; with dylib → settle times drop to tens of ms on a fixture flow.

### Task 5: All Gravy integration (RN/Expo specifics)
- RN 0.83 / Fabric: layout/display swizzles catch Fabric mounts (UIKit-level); `URLSessionTask.resume` covers Apollo + fetch; websockets (partysocket/Stream) + SSE excluded by construction; Reanimated 4 runs on the UI thread — stable-frame counting catches it without touching Reanimated.
- Extend `all_gravy_cli/src/expo-plugins/e2eIdentifierProxyHost` to post `NotificationCenter` events (`ag.e2e.proxySyncBegan/Ended` + epoch); copilot gates quiescence on proxy population — retires the AX-proxy timing race permanently.
- `e2e-ios-prepare.sh`: when ferrari, verify dylib present in fork dist.
- **Solves observed Phase-1 flakiness:** copilot idle signal replaces the per-screen lazy-bundle guess (HubScreen 82s) and the screenshot-diff settle, and gates on real readiness instead of AX polling.

### Task 6: docs + benchmark
- Update `docs/speed-profiles.md`: ferrari now = copilot-gated event-driven quiescence.
- Fixture-flow benchmark asserting per-step settle under budget with copilot active.

### Deferred (Phase 3)
- Android copilot AAR + `maestro provision android` (gradle `debugImplementation` + ContentProvider), adb-forward transport for the physical device.
