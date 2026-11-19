# WCX first-Activity gate race

Date: 2026-11-19
Baseline: `zygisk-module` at `72d1fad1`
Fix branch: `fix/first-activity-gate-race`

## Root cause

1. The stable main-process path installed its ActivityThread interception only after the `libaff_biz` 1-second stability wait and an additional main-thread `Handler.post`. `LauncherUI` could be created inside `handleBindApplication` -> `EXECUTE_TRANSACTION` before that delayed `post` ran, leaving the one-shot startup action permanently untriggered (`startup_gate_not_triggered`).
2. On Android 15/16 (`SDK_INT >= 35`), `ClientTransaction` unparcels items into blocked `mTransactionItems` (`getTransactionItems()`), leaving `getCallbacks()` null. Relying on `getCallbacks()` alone prevented `ProxyHandlerCallback` from seeing `LaunchActivityItem` before hidden-API exemption.

## Preserved constraints

- `libaff_biz` must remain stable for 1 second before LSPlant initialization.
- Pure background process launch must not initialize LSPlant.
- WCX startup must finish before the first Activity creation continues.
- Active gate waiting is bounded to 5 seconds (slice-capped so cgroup freezer periods while the display is off do not falsely trip fail-open).
- The injector is unchanged; deployment replaces only `module.apk`.

## VM verification

| Check | Result |
| --- | --- |
| Python contracts | 11/11 passed |
| standard arm64 release | `BUILD SUCCESSFUL`, `wcx-v347-arm64-v8a.apk` (44,309,977 bytes) |
| minified ABI contracts | 71/71 passed (`contracts=71 missing=0`) |
| APK SHA-256 | `7ff621e8f34ed894d9cf66c71cc8fffaa9c4be40a395479a16eecd1f6421a23f` |
| Injector MD5 | `93dcbeea7a5ea0075c36c315696be62f` |

## Device matrix

| Scenario | Required | Passed |
| --- | ---: | ---: |
| Direct cold start | 10 | 0 |
| Screen-off cold start | 5 | 0 |
| Background then foreground | 3 | 0 |

No optimized APK may be deployed until this table is 18/18 and the crash checks are clean.
