# Fast Path Migration

Use this guide when moving a project from legacy Maestro usage to the faster forked runtime.

## Prerequisites

- build and install the forked CLI from this repo
- use a unique `--driver-host-port` per concurrent local iOS run
- set `--run-owner` and `--run-label` on shared hosts
- verify the target Metro/dev server is the correct app before launching automation
- use wrapper-backed artifact directories so the live trace files are preserved

Compatibility modes:

- `MAESTRO_USE_DAEMON_HTTP=auto` is the default wrapper mode and prefers the daemon when available
- `MAESTRO_USE_DAEMON_HTTP=1` forces the daemon-backed maximal path
- `MAESTRO_USE_DAEMON_HTTP=0` preserves the recent fast non-daemon path for apps that do not expose a semantic bridge yet
- apps without custom TSX or bridge code remain supported on the recent fast non-daemon path; the daemon/semantic bridge path is additive, not a breaking requirement

## Old To New Mapping

| Old path | New path |
| --- | --- |
| repeated direct `maestro test` hot-path execution | one hot `session_id` plus typed batch calls |
| `when: visible:` for cheap branching | `visibleNow` / `notVisibleNow` or semantic wait |
| repeated overlay subflows | `dismissKnownOverlays` |
| `assertNotVisible` in startup guard files | `assertNotVisibleNow` |
| UI OTP/login in fast mode | app bridge or direct bootstrap |
| repeated full hierarchy inspection | `query_elements` / minimal snapshot |
| silent waiting | `maestro-live-status.txt` and `maestro-live-trace.log` |

## Required Migration Steps

1. Replace startup overlay sweeps with `dismissKnownOverlays`.
2. Replace fatal/auth blocker guards with `assertVisibleNow` / `assertNotVisibleNow` where waiting is not intended.
3. Move agent-driven control to `execute_batch`, `await_event`, and macros instead of generating raw YAML each turn.
4. Prefer `query_elements` and `snapshot(mode=minimal)` for inspection.
5. Keep legacy flows only for CI/diagnostic coverage.

## Acceptance Bar

A migration is not complete until:

- the hot path reuses a session
- the run exposes live trace files
- startup guards avoid repeated hierarchy churn
- local fast mode avoids UI-auth and launcher-handling work
- full hierarchy is not the common path for simple checks
