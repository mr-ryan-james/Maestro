# iOS Copilot — event-driven quiescence (ferrari)

The copilot is a small Swift dylib injected into the iOS-simulator app under test. Instead of
guessing when the app has settled by screenshot-diffing or polling the accessibility tree, it
observes the app from the inside and answers `awaitQuiescence` over a localhost socket — so the
`ferrari` profile settles on real render-readiness.

This is the fix for the RN/Expo **render race**: after navigation the tab bar renders and the
main thread stays responsive, but the screen's content stays blank while a lazy Metro bundle
mounts. A black-box settle sees an idle screen and reports "ready" too early; the copilot gates
on content actually being on screen.

## What it tracks

- **Run loop drain** — CFRunLoop observer (`.beforeWaiting`).
- **Layout/display epochs** — `setNeedsLayout` / `setNeedsDisplay` swizzles; stable epochs mean
  the UI has stopped invalidating.
- **In-flight network** — `URLSessionTask.resume` swizzle, excluding websockets and any request
  running longer than 10s (SSE / long-poll), so persistent connections don't wedge quiescence.
- **View-controller transitions** — `viewDidAppear` swizzle.
- **Content present** — a visible window whose root view tree has real rendered leaves (not an
  empty shell). This is the gate that keeps it from reporting ready on a blank mounting screen.
- **Stable frames** — a CADisplayLink counts consecutive identical frames; screen transitions
  demand more stability than minor updates.

## Socket protocol

Versioned, length-prefixed JSON over `127.0.0.1:$MAESTRO_COPILOT_PORT` (4-byte big-endian length
+ JSON body). The iOS simulator shares the host network, so the driver connects to the same
`127.0.0.1:port`.

- `{"cmd":"hello"}` → `{"version":1,"ok":true}`
- `{"cmd":"awaitQuiescence","transitionClass":"screen|minor|none","timeoutMs":15000}` →
  `{"quiescent":bool,"phase":..,"framesObserved":..,"signals":{..}}`
- `{"cmd":"getSignals"}` → current phase + signals (diagnostics)

## Build

The dylib is a fat iOS-simulator binary (arm64 + x86_64), built out-of-band like the XCTest
runner and staged into the driver resources so `installDist` ships it (no Xcode needed at
install time):

```bash
copilot-ios/build-copilot-dylib.sh
# → copilot-ios/build/libmaestro-copilot.dylib
# → maestro-ios-driver/src/main/resources/copilot/libmaestro-copilot.dylib (shipped in the jar)
```

Then rebuild/install the CLI (`installLocally.sh`).

## Enable

Off by default. Opt in per run:

- `MAESTRO_SPEED_PROFILE=ferrari` (implies copilot), or
- `MAESTRO_COPILOT=1` (any profile). `MAESTRO_COPILOT=0` force-disables.
- `MAESTRO_COPILOT_PORT` overrides the socket port (default `7113`).

At launch, `LocalSimulatorUtils.launch()` DYLD-injects the dylib via
`SIMCTL_CHILD_DYLD_INSERT_LIBRARIES` and passes `SIMCTL_CHILD_MAESTRO_COPILOT_PORT`. The driver's
`QuiescenceService` then consults the copilot in `waitForAppToSettle`; if the copilot is absent or
unreachable it returns null and the existing black-box settle runs unchanged. Missing dylib or a
hardened target degrades to a no-op.

## Known limitation: Expo dev-client deep-link relaunch

DYLD injection applies to `simctl launch`. Expo dev-client flows commonly launch the app, stop it,
then relaunch via a `simctl openurl` deep link (`exp+…://…?url=<metro>`) — and `openurl` does not
carry `SIMCTL_CHILD_*` env, so the copilot is not injected into the deep-link-launched instance.
For the copilot to help those flows, inject on the instance that actually runs the app (launch it
with the Metro URL as a launch argument instead of a post-launch `openurl`), or run against a
production / `expo export` bundle. This All Gravy integration is tracked as Phase 2 Task 5.
