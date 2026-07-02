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

`LocalSimulatorUtils.launch()` injects the dylib two ways when enabled:

1. **Per-launch** — `SIMCTL_CHILD_DYLD_INSERT_LIBRARIES` + `SIMCTL_CHILD_MAESTRO_COPILOT_PORT` on
   the `simctl launch`.
2. **Sim-wide** — `simctl spawn <udid> launchctl setenv DYLD_INSERT_LIBRARIES <dylib>` (plus
   `MAESTRO_COPILOT_PORT` / `MAESTRO_COPILOT_APP_ID`), so apps launched by other means (an Expo
   dev-client `openurl` deep-link relaunch) inherit the injection too. The gate env is set before
   `DYLD_INSERT_LIBRARIES` so transient system/`launchctl` processes load the dylib with the gate
   already in place and no-op immediately. A non-copilot run clears the sim-wide env (self-healing).

The dylib self-gates on `MAESTRO_COPILOT_APP_ID`: it loads into every process but only activates
(installs trackers + binds the socket) in the target bundle, so springboard/system daemons never
clash for the port. The dylib is extracted to a stable path (`~/.maestro/copilot/`) so the
sim-wide env never dangles at a deleted file.

The driver's `QuiescenceService` consults the copilot in `waitForAppToSettle`; if the copilot is
absent or unreachable it returns null and the existing black-box settle runs unchanged. Missing
dylib or a hardened target degrades to a no-op.

## Residual: dev-client network noise

On a dev-client build, Metro keeps persistent connections open; short-lived poll requests can keep
`activeNetworkRequests > 0` and hold quiescence at `WAITING_FOR_ASYNC_IDLE`. Websockets and
requests older than 10s are already excluded. For the cleanest signal run against a production /
`expo export` bundle (no Metro, no lazy bundling); the copilot then settles purely on render
epochs + content + stable frames. A future refinement can gate on the app's own readiness signal
(e.g. the `e2eIdentifierProxyHost` proxy population) to ignore dev-server traffic entirely.
