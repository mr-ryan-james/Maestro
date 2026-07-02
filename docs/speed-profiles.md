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
