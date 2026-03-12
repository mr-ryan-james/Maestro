# Fast Path Architecture

This fork’s local-first performance model is built around one rule: avoid repeating expensive boundaries.

The fast path is:

1. one long-lived control plane
2. one hot session per device/app/project
3. semantic or minimal-query reads before full hierarchy fallback
4. typed batch actions and macros before ad hoc raw YAML generation
5. live trace visibility during every run

## What Changed

- `session_id` is the preferred runtime handle for repeated device work
- `maestro daemon` is the maximal-performance control plane, but the recent non-daemon fast path remains supported for apps that do not use the semantic bridge
- hot MCP/device tools reuse an existing session instead of reopening the driver
- `execute_batch`, `await_event`, `query_elements`, and `snapshot` are first-class
- `visibleNow` / `notVisibleNow` are intended for zero-wait guard logic
- `dismissKnownOverlays`, `assertVisibleNow`, and `assertNotVisibleNow` are the preferred hot-path primitives for startup guards
- live status and live trace files are part of the runtime contract, not optional diagnostics

## Source Order

Use this read order whenever possible:

1. app semantic bridge
2. minimal automation query / minimal snapshot
3. full accessibility hierarchy

Full hierarchy is still available, but it should be fallback behavior on the hot path.

## What Stays Legacy

- raw `maestro test` loops that repeatedly cold-start work
- guard-heavy YAML that fans out into many per-screen lookups
- UI-auth and UI dev-launcher handoff in local fast mode

Those paths still exist for compatibility or diagnostic coverage, but they are not the recommended local iteration path.

Compatibility note:

- wrappers and local scripts may set `MAESTRO_USE_DAEMON_HTTP=1` to force the daemon path
- set `MAESTRO_USE_DAEMON_HTTP=0` to stay on the recent fast non-daemon path
- the default wrapper mode is `MAESTRO_USE_DAEMON_HTTP=auto`, which prefers the daemon when healthy and falls back to the fast non-daemon path otherwise

## Related Guides

- [Migration](./MIGRATION.md)
- [Operations](./OPERATIONS.md)
- [Troubleshooting](./TROUBLESHOOTING.md)
