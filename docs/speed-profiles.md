# Speed Profiles

Speed profiles control how aggressively Maestro waits between actions. One knob bundles settle budgets, lookup timeouts, animation policy, and the pre-tap settle skip; every bundled setting remains independently overridable per command.

## Selecting a profile

Precedence: flow config > environment > default.

```yaml
appId: com.example.app
speedProfile: fast
---
- launchApp
```

```bash
MAESTRO_SPEED_PROFILE=fast maestro test flow.yaml
```

Profile names are case-insensitive. Unknown values fall back to `default`.

## Profiles

| | `default` | `fast` | `ferrari` |
|---|---|---|---|
| Minor settle budget (taps, swipes) | driver default | 500ms | 500ms |
| Screen settle budget (launch, back, links) | driver default | 1500ms | 1500ms |
| Lookup timeout | 17s | 3s | 3s |
| Optional lookup timeout | 7s | 2s | 2s |
| Pre-tap settle | always | skipped when no action since last settle | skipped when no action since last settle |
| OS animations | untouched | disabled (Android) | disabled (Android) |

`default` preserves upstream Maestro timing semantics exactly — budgets and skips only engage for non-default profiles.

`ferrari` currently equals `fast`. It gains event-driven quiescence (sub-frame settle detection via the in-app copilot) when the copilot ships; use it now to opt into that upgrade automatically.

## Transition classes

Non-default profiles settle by command type instead of a single worst-case wait:

- **`none`** — text entry (`inputText`, `eraseText`, `pasteText`): no post-action settle; the next assertion or lookup is the sync point.
- **`minor`** — taps, swipes, scrolls: settle within the minor budget.
- **`screen`** — `launchApp`, `stopApp`, `killApp`, `openLink`, `back`: settle within the screen budget.

## Per-command override

`waitToSettleTimeoutMs` on any supporting command always wins over the profile:

```yaml
- tapOn:
    text: "Submit"
    waitToSettleTimeoutMs: 2000   # this tap waits up to 2s
- tapOn:
    text: "Next"
    waitToSettleTimeoutMs: 0      # this tap does not settle at all
```

## Android animation scales

With `disableAnimations` active (the `fast`/`ferrari` profiles), the session sets the three Android system animation scales (`window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`) to 0 at flow start and restores the prior values at flow end. Driver close restores them as a safety net if a flow aborts. iOS animation disabling arrives with the copilot.

## Timing visibility

Each executed command records its wall-clock duration in `CommandMetadata.durationMs` for output integrations and debugging.

## iOS robustness: surviving a blocked app main thread

On iOS the accessibility snapshot runs on the app's main thread. When that thread is
blocked for tens of seconds — most commonly Metro dev-server lazily bundling a heavy screen
on first navigation, or any long synchronous work — the snapshot RPC times out. The fork
treats a busy main thread as a transient "keep waiting" condition instead of a hard failure,
so a flow rides through the stall rather than dying on the first blocked snapshot.

How it works:

- The XCTest runner maps both server-side stalls to an HTTP 408 timeout: the 30s testmanagerd
  cap ("process main thread busy…") and the 60s XCTest watchdog ("XCTPerformOnMainRunLoop…").
- The driver classifies a timeout as **app-busy** (distinct from a transient AX glitch) and
  retries it on its own budget with a seconds-scale backoff, so the retry window spans a
  multi-second bundle. The settle loop tolerates a snapshot timeout as "not settled yet".

This is on by default; a normal warm flow is unaffected (the busy path only engages on the
busy timeout signatures). Tune or extend it with these env knobs:

| Env var | Default | Effect |
|---|---|---|
| `MAESTRO_IOS_BUSY_RETRY_COUNT` | `6` | Max snapshot attempts while the app main thread is busy. Each attempt burns ~30–60s server-side, so 6 comfortably spans an ~80s bundle. |
| `MAESTRO_IOS_BUSY_RETRY_BASE_MS` | `1000` | Base backoff between busy retries (doubles per attempt: 1s, 2s, 4s, …). |
| `MAESTRO_IOS_AX_TIMEOUT_SECONDS` | unset | **Advanced/opt-in.** Raises the accessibility snapshot timeout itself via a private XCTest API, so a single snapshot can wait longer (e.g. `120`) through a bundle instead of failing at ~30s — reducing how many busy retries are needed. Unset ⇒ framework default unchanged. Private-API failure is non-fatal (logged, then the default applies). |

Ceiling: the busy path can wait at most roughly `MAESTRO_IOS_BUSY_RETRY_COUNT` × (per-attempt
server timeout + backoff) ≈ a few minutes per snapshot at defaults — bounded, never infinite.

The durable fix for lazy-bundle stalls is still to run against a production/`expo export`
bundle (no per-screen bundling) or to pre-warm routes; this robustness layer keeps a
dev-client run alive through the stalls when you can't.
