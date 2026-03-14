# Fast Path Migration

Use this guide when moving a project from legacy Maestro usage to the faster forked runtime.

## Prerequisites

- build and install the forked CLI from this repo
- use a unique `--driver-host-port` per concurrent local iOS run
- set `--run-owner` and `--run-label` on shared hosts
- verify the target Metro/dev server is the correct app before launching automation
- use wrapper-backed artifact directories so the live trace files are preserved

Compatibility modes:

- `MAESTRO_USE_DAEMON_HTTP=auto` is the recommended default in daemon-capable repos and prefers the daemon when available
- `MAESTRO_USE_DAEMON_HTTP=1` forces the daemon-backed maximal path
- `MAESTRO_USE_DAEMON_HTTP=0` preserves the supported fast non-daemon compatibility path for apps that do not expose a semantic bridge yet
- apps without custom TSX or bridge code remain supported on the recent fast non-daemon path; the daemon/semantic bridge path is additive, not a breaking requirement
- shell wrappers may use daemon-native helpers such as `run_macro`, `execute_batch`, `query_elements`, `snapshot`, `await_event`, and `list_sessions`, but those are optional enhancements on top of the supported non-daemon path
- daemon-backed wrappers and proof harnesses may also use `run_compiled_flow` and `hard_reset_session`; those stay additive and must not break the supported non-daemon path
- daemon-backed session reuse now validates session health before reuse; a resumed session must be healthy or explicitly repaired, not just unexpired
- active Maestro YAML guardrails are enforced via the shared runner in `~/Dev/ryans-technology/tooling/maestro-fast-lint`, with repo-local wrappers/config in each product repo
- the shared fast-lint guardrails now also ban scalar `visibleNow` / `notVisibleNow` in active flows; use explicit selector objects so zero-time guards stay unambiguous and fast
- the checked-in MCP configs now rely on the built-in `maestro` server only; the external `Maestro-Mcp` repo is no longer required for normal Codex or wrapper workflows

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
| bridge-specific route or readiness polling | semantic bridge commands like `awaitMarker`, `dismissDebugUi`, or `setFeatureFlags` when the app supports them |
| repeated parsed-YAML execution | `run_compiled_flow` with optimized compiled artifacts |

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

## Proof Commands

Use these after migrating a repo or wrapper set:

```bash
pnpm run mobile:maestro:proof:thrivify
pnpm run mobile:maestro:proof:compat
pnpm run mobile:maestro:proof:soundlikeus
pnpm run mobile:maestro:proof:closeout
```

- `mobile:maestro:proof:thrivify` exercises the daemon/session proof bar, including warm-loop and soak coverage
- `mobile:maestro:proof:compat` protects the fast non-daemon path and bridge-less app compatibility
- `mobile:maestro:proof:soundlikeus` applies the same daemon/non-daemon contract to SoundLikeUs
- `mobile:maestro:proof:closeout` runs the current closeout gate across Thrivify, compatibility coverage, and SoundLikeUs when available
- set `MAESTRO_PROOF_REUSE_LATEST=1` to have `mobile:maestro:proof:closeout` gate the latest retained summary roots instead of rerunning every proof
- closeout runs retain their own gate artifact under `artifacts/e2e-mobile/maestro-closeout-*/summary.json`
- the checked-in `maestro-proof` GitHub workflow now defaults manual runs to requiring both All Gravy compatibility and SoundLikeUs proof coverage instead of leaving those checks to scheduled runs only

Representative bridge-less compatibility target:

- All Gravy at `~/Dev/All-Gravy/all_gravy_cli` + `~/Dev/All-Gravy/native`
- the closeout bar keeps direct `maestro test` and the recent fast non-daemon path working there; daemon mode stays additive
