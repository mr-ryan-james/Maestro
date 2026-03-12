# Fast Path Troubleshooting

## Visible Button But Nothing Happens

Do not assume Maestro is intentionally waiting on that button.

Check the live status first:

- current command
- current wait reason
- driver health
- selector source

Common causes:

- driver connection dropped
- semantic bridge is not ready
- a guard file is still executing
- a lookup fell back to full hierarchy

## Driver Drops Or Port Refusals

Symptoms:

- `Failed to connect to /127.0.0.1:<port>`
- screen is static but command never advances

Actions:

1. confirm the session is still open
2. check the live trace for driver failures before the stall
3. use `hard_reset_session` only when the session is actually unhealthy
4. confirm the chosen `--driver-host-port` is unique on the host

## Slow Guard Files

If a “zero-time” guard is still slow:

- confirm the flow uses `assertVisibleNow` / `assertNotVisibleNow`
- confirm overlay handling uses `dismissKnownOverlays`
- confirm the trace shows minimal-query usage rather than full hierarchy fallback

## Full Hierarchy Fallback

Full hierarchy may still be required for unsupported selectors. That should be explicit in the trace. If it appears on the hot path for simple text/id checks, treat it as a regression.
