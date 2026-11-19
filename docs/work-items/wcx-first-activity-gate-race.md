# WCX first-Activity gate race

Date: 2026-11-19
Baseline: `zygisk-module` at `72d1fad1`
Fix branch: `fix/first-activity-gate-race`

## Root cause

1. **Late gate installation race**: The stable main-process path installed its `ActivityThread` interception only after the `libaff_biz` 1-second stability wait and an additional main-thread `Handler.post`. `LauncherUI` could be created before that delayed `post` ran, leaving the one-shot startup action permanently untriggered (`startup_gate_not_triggered`).
2. **Android 15/16 `ClientTransaction` change**: On Android 16 (`SDK_INT = 36`), `ClientTransaction` unparcels items into blocked `mTransactionItems` (`getTransactionItems()`), leaving `getCallbacks()` null.
3. **WeChat Splash bootstrap ordering**: On cold start, WeChat's `com.tencent.mm.splash` first swaps the initial launch into `SplashHackActivity` and `WeChatSplashActivity`, and `WeChatSplashActivity` triggers kernel initialization (`libaff_biz.so`). Blocking before `SplashHackActivity` stalled WeChat on its ~3.5-second splash watchdog (`startup_gate_wait_ms ≈ 4,600ms–5,350ms`). Deferring the gate past `SplashHackActivity` / `WeChatSplashActivity` until the first real host Activity (`LauncherUI`) lets `WeChatSplashActivity` load `libaff_biz.so` immediately (`startup_gate_wait_ms = 0ms` across all 18 acceptance runs).
4. **Fail-open `hybridClassLoader` guard**: `ActivityProxy.unwrapIntent` and `tryRecoverIntent` now guard `if (!routingInitialized) return null` so fail-open paths cannot access uninitialized `ParcelableFixer.hybridClassLoader`.

## Preserved constraints

- `libaff_biz` must remain stable for 1 second before LSPlant initialization.
- Pure background process launch (`BootReceiver`) must not initialize LSPlant (`pre_lsplant=0`, `pre_started=0`).
- WCX startup finishes before the first real Activity (`LauncherUI`) creation continues.
- Active gate waiting is bounded to 8 seconds (slice-capped so cgroup freezer periods while the display is off do not falsely trip fail-open).
- The injector is unchanged (`93dcbeea7a5ea0075c36c315696be62f`); deployment replaced only `module.apk`.

## VM verification

| Check | Result |
| --- | --- |
| Python contracts | 11/11 passed |
| standard arm64 release | `BUILD SUCCESSFUL`, `wcx-v347-arm64-v8a.apk` (44,309,977 bytes) |
| minified ABI contracts | 71/71 passed (`contracts=71 missing=0`) |
| APK SHA-256 | `f56c5e4c8337c3b2a1c172d318e4f84fe284a987be85fe85e5a1cd63505df57e` |
| Injector MD5 | `93dcbeea7a5ea0075c36c315696be62f` |

## Device matrix (18/18 PASS)

| Scenario | Required | Passed | Notes |
| --- | ---: | ---: | --- |
| Direct cold start (`direct`) | 10 | 10 | `enabled_max=102`, `proxy=1`, `E=0`, `focused=1`, `startup_gate_wait_ms=0` |
| Screen-off cold start (`screenoff`) | 5 | 5 | `enabled_max=102`, `proxy=1`, `E=0`, `focused=1`, `startup_gate_wait_ms=0` |
| Background then foreground (`boot`) | 3 | 3 | `pre_lsplant=0`, `pre_started=0`, `same_main=1`, `enabled_max=102`, `proxy=1`, `E=0` |

## Crash & health verification

- `new_java_crash` (`reason=4`): `0` across all 18 runs
- `new_native_crash` (`reason=5`): `0` across all 18 runs
- `new_status6` (`SIGABRT`): `0` across all 18 runs
- `new_status11` (`SIGSEGV`): `0` across all 18 runs
- `/data/tombstones`: `total 0` (no new tombstones)
- Installed `/data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so` MD5: `93dcbeea7a5ea0075c36c315696be62f`

## Stable startup timing baseline (`direct` 10-run cohort)

- `WeLauncher` main-thread initialization (`主线程初始化完成`):
  `[1153, 1117, 1167, 1173, 1138, 1127, 1115, 1137, 1146, 1152]` ms → **P50 = 1,138 ms**, **P95 = 1,173 ms** (min 1,115 ms, max 1,173 ms).
- `activity_gate -> startup_gate_result=started` (`LSPlant + StartupAgent + WeLauncher`):
  `[1330, 1316, 1346, 1346, 1316, 1307, 1316, 1313, 1322, 1335]` ms → **P50 = 1,316 ms**, **P95 = 1,346 ms** (min 1,307 ms, max 1,346 ms).
