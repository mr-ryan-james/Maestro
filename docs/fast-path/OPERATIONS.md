# Fast Path Operations

## Build And Install

```bash
./gradlew :maestro-cli:installDist -x test
```

Verify the active binary:

```bash
which maestro
maestro --version
```

## Shared-Host Flags

Use these for local iOS concurrency and ownership-safe cleanup:

```bash
maestro \
  --driver-host-port 7105 \
  --run-owner "$USER" \
  --run-label thrivify-local \
  test <flow.yaml> --device <device-id>
```

## Hot Session Work

Prefer the hot-session MCP/device path for repeated work:

- `open_session`
- `resume_session`
- `execute_batch`
- `query_elements`
- `snapshot`
- `await_event`
- `close_session`
- `hard_reset_session`

## Live Trace Contract

Every wrapper-backed fast-path run should surface:

- `maestro-live-status.txt`
- `maestro-live-trace.log`

Use them first when a run appears stuck:

```bash
tail -f <artifact-dir>/maestro-live-status.txt
tail -f <artifact-dir>/maestro-live-trace.log
```

## Proof And Closeout

Use the repo proof harnesses to lock the daemon path, the fast non-daemon compatibility path, and bridge-less compatibility targets before shipping wrapper/runtime changes:

```bash
pnpm run mobile:maestro:proof:thrivify
pnpm run mobile:maestro:proof:compat
pnpm run mobile:maestro:proof:soundlikeus
pnpm run mobile:maestro:proof:closeout
```

The checked-in `maestro-proof` GitHub workflow now defaults manual runs to requiring both All Gravy compatibility and SoundLikeUs proof coverage, not just the scheduled runs.

## Fast Authoring Rules

- use `dismissKnownOverlays` instead of a multi-step overlay sweep
- use `assertVisibleNow` / `assertNotVisibleNow` for guard files
- use semantic or minimal-query waits before `waitForAnimationToEnd`
- keep direct raw YAML execution out of the live agent loop
