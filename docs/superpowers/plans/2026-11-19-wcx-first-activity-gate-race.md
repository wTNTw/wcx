# WCX First Activity Gate Race Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install the main-process first-Activity gate before an Activity transaction can pass it, while keeping LSPlant initialization behind the stable `libaff_biz` condition and proving the fixed stable baseline reliable on-device.

**Architecture:** Split `ActivityProxy` into an early context-free Activity interception layer and the existing full routing layer. A one-shot coordinator lets the early Activity callback wait at most five seconds for the deferred worker to publish `libaff_biz` and `Application` readiness; timeout and failure paths fail open and reject late startup. Implement and validate the fix on an isolated branch from `zygisk-module`, then cherry-pick it into the performance branch only after the strict stable-device matrix passes.

**Tech Stack:** Kotlin/JVM, Android ActivityThread internals, LSPlant Zygisk bridge, Python `unittest` source contracts, POSIX shell/ADB probes, Rust/Gradle build orchestration in Debian VM.

---

## File map

### New files

- `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/MainProcessStartupGate.kt` — generic one-shot publication/consumption coordinator with bounded waiting and late-publication rejection.
- `scripts/tests/test_first_activity_gate_race.py` — source contracts for coordinator, early `ActivityProxy` interception, and `ZygiskEntry` ordering.
- `scripts/tests/test_first_activity_gate_probe.py` — probe contracts for structured gate markers and direct/screen-off/background modes.

### Modified files

- `app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt` — separate early Handler/Instrumentation interception from full Activity routing initialization.
- `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt` — install the gate synchronously, publish deferred readiness, consume it before first Activity creation, and emit structured result logs.
- `scripts/wcx-injection-probe.sh` — recognize new gate states, verify no pre-open LSPlant initialization, and add a screen-off mode.
- `docs/work-items/wcx-first-activity-gate-race.md` — record root cause, artifact identities, VM evidence, and the strict device matrix.

## Task 1: Create the isolated stable-fix worktree

**Files:**
- Bring in: `docs/superpowers/specs/2026-11-19-wcx-first-activity-gate-race-design.md`

- [ ] **Step 1: Verify the performance worktree is clean and record both branch heads**

Run:

```bash
git -C .worktrees/perf-module-size-startup status --short
git -C .worktrees/perf-module-size-startup rev-parse perf/module-size-startup
git -C .worktrees/perf-module-size-startup rev-parse zygisk-module
```

Expected: no status output; performance branch includes `621200bd`; stable branch resolves to `72d1fad1`.

- [ ] **Step 2: Create a dedicated worktree from the stable branch**

Run:

```bash
git worktree add .worktrees/fix-first-activity-gate-race \
  -b fix/first-activity-gate-race zygisk-module
```

Expected: new worktree checked out at `72d1fad1`.

- [ ] **Step 3: Bring only the approved design document into the fix branch**

Run:

```bash
git -C .worktrees/fix-first-activity-gate-race cherry-pick 621200bd
git -C .worktrees/fix-first-activity-gate-race status --short
git -C .worktrees/fix-first-activity-gate-race log -2 --oneline
```

Expected: clean worktree; the latest commit contains only the design document.

- [ ] **Step 4: Confirm no performance implementation is present**

Run:

```bash
git -C .worktrees/fix-first-activity-gate-race merge-base --is-ancestor 795b4781 HEAD; test $? -eq 1
git -C .worktrees/fix-first-activity-gate-race diff --stat zygisk-module..HEAD
```

Expected: the lazy feature startup commit is not an ancestor; diff contains only the design document.

## Task 2: Add the one-shot startup coordinator test-first

**Files:**
- Create: `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/MainProcessStartupGate.kt`
- Create: `scripts/tests/test_first_activity_gate_race.py`

- [ ] **Step 1: Write the failing coordinator contract**

Create `scripts/tests/test_first_activity_gate_race.py` with:

```python
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ZYGISK = ROOT / "app/src/main/java/com/Johnny/wcx/loader/entry/zygisk"
UTILS = ROOT / "app/src/main/java/com/Johnny/wcx/loader/utils"


class FirstActivityGateRaceContractTest(unittest.TestCase):
    def test_one_shot_gate_has_bounded_wait_and_rejects_late_publish(self):
        source = (ZYGISK / "MainProcessStartupGate.kt").read_text(encoding="utf-8")
        self.assertIn("internal class MainProcessStartupGate<T>", source)
        self.assertIn("completion.await(timeoutMs, TimeUnit.MILLISECONDS)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Consumed)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Published(value))", source)
        self.assertIn("completion.countDown()", source)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the contract and verify it fails**

Run:

```bash
cd .worktrees/fix-first-activity-gate-race
python -m unittest scripts.tests.test_first_activity_gate_race -v
```

Expected: ERROR because `MainProcessStartupGate.kt` does not exist.

- [ ] **Step 3: Implement the minimal one-shot coordinator**

Create `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/MainProcessStartupGate.kt` with:

```kotlin
package com.Johnny.wcx.loader.entry.zygisk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class MainProcessStartupGate<T> {
    private sealed interface State<out T> {
        data object Pending : State<Nothing>
        data class Published<T>(val value: T) : State<T>
        data object Consumed : State<Nothing>
    }

    private val state = AtomicReference<State<T>>(State.Pending)
    private val completion = CountDownLatch(1)

    fun publish(value: T): Boolean {
        if (!state.compareAndSet(State.Pending, State.Published(value))) return false
        completion.countDown()
        return true
    }

    fun awaitAndConsume(timeoutMs: Long): T? {
        if (!completion.await(timeoutMs, TimeUnit.MILLISECONDS) &&
            state.compareAndSet(State.Pending, State.Consumed)
        ) {
            return null
        }
        while (true) {
            when (val current = state.get()) {
                is State.Published -> {
                    if (state.compareAndSet(current, State.Consumed)) return current.value
                }
                State.Pending -> {
                    if (state.compareAndSet(State.Pending, State.Consumed)) return null
                }
                State.Consumed -> return null
            }
        }
    }
}
```

- [ ] **Step 4: Run the contract and all existing Python tests**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_race -v
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

Expected: coordinator contract passes; all pre-existing tests pass.

- [ ] **Step 5: Commit the coordinator**

Run:

```bash
git add \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/MainProcessStartupGate.kt \
  scripts/tests/test_first_activity_gate_race.py
git commit -m "fix(startup): add one-shot main process gate"
```

## Task 3: Split early Activity interception from full routing

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt`
- Modify: `scripts/tests/test_first_activity_gate_race.py`

- [ ] **Step 1: Add failing `ActivityProxy` contracts**

Add these methods to `FirstActivityGateRaceContractTest`:

```python
    def test_activity_proxy_exposes_context_free_startup_gate(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        signature = "fun installStartupGate(startupGate: () -> Unit): Boolean"
        self.assertIn(signature, source)
        start = source.index(signature)
        end = source.index("fun installInstrumentationFallback", start)
        gate_body = source[start:end]
        self.assertNotIn("Context", gate_body)
        self.assertIn("beforeFirstActivity.compareAndSet", gate_body)
        self.assertIn("ensureHandlerInterceptor()", gate_body)

    def test_activity_proxy_splits_interception_and_routing_state(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        self.assertIn("private var handlerInterceptorInstalled = false", source)
        self.assertIn("private var instrumentationInterceptorInstalled = false", source)
        self.assertIn("private var routingInitialized = false", source)
        self.assertNotIn("private var initialized = false", source)
        self.assertIn("if (!ensureHandlerInterceptor()) return false", source)
        self.assertIn("installInstrumentationFallback()", source)
```

- [ ] **Step 2: Run the tests and verify the new contracts fail**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_race -v
```

Expected: FAIL because `ActivityProxy` still has a single `initialized` state and no early gate API.

- [ ] **Step 3: Refactor `ActivityProxy` initialization state**

Replace the single initialization flag with:

```kotlin
    private val initLock = Any()
    private var handlerInterceptorInstalled = false
    private var instrumentationInterceptorInstalled = false
    private var routingInitialized = false
    private val beforeFirstActivity = AtomicReference<(() -> Unit)?>(null)
```

Add the context-free entry points:

```kotlin
    fun installStartupGate(startupGate: () -> Unit): Boolean {
        beforeFirstActivity.compareAndSet(null, startupGate)
        if (ensureHandlerInterceptor()) return true
        beforeFirstActivity.compareAndSet(startupGate, null)
        return false
    }

    fun installInstrumentationFallback(): Boolean = synchronized(initLock) {
        if (instrumentationInterceptorInstalled) return true
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = ActivityThread.currentActivityThread()
            val field = activityThreadClass.getDeclaredField("mInstrumentation")
                .makeAccessible()
            val instrumentation = field.get(activityThread) as? Instrumentation
                ?: return@synchronized false
            if (instrumentation !is ProxyInstrumentation) {
                field.set(activityThread, ProxyInstrumentation(instrumentation))
            }
            instrumentationInterceptorInstalled = true
            true
        }.onFailure {
            WeLogger.e(TAG, "failed to install Instrumentation fallback", it)
        }.getOrDefault(false)
    }

    private fun ensureHandlerInterceptor(): Boolean = synchronized(initLock) {
        if (handlerInterceptorInstalled) return true
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = ActivityThread.currentActivityThread()
            val handler = activityThreadClass.getDeclaredField("mH")
                .makeAccessible()
                .get(activityThread) as Handler
            val callbackField = Handler::class.java.getDeclaredField("mCallback")
                .makeAccessible()
            val current = callbackField.get(handler) as? Handler.Callback
            if (current == null || current.javaClass.name != ProxyHandlerCallback::class.java.name) {
                callbackField.set(handler, ProxyHandlerCallback(current))
            }
            handlerInterceptorInstalled = true
            true
        }.onFailure {
            WeLogger.e(TAG, "failed to install ActivityThread handler interceptor", it)
        }.getOrDefault(false)
    }
```

Refactor `init(ctx, startupGate)` so it performs operations in this order:

```kotlin
    fun init(ctx: Context, startupGate: (() -> Unit)? = null): Boolean {
        if (startupGate != null && !installStartupGate(startupGate)) return false
        if (!ensureHandlerInterceptor()) return false
        if (!installInstrumentationFallback()) return false

        synchronized(initLock) {
            if (routingInitialized) return true
            return runCatching {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = ActivityThread.currentActivityThread()
                hookIActivityManager()
                hookPackageManager(ctx, activityThread, activityThreadClass)
                routingInitialized = true
                true
            }.onFailure {
                WeLogger.e(TAG, "failed to init stub activity hooks", it)
            }.getOrDefault(false)
        }
    }
```

Keep `ProxyHandlerCallback`, `ProxyInstrumentation`, Intent token routing, and `runBeforeFirstActivity()` behavior unchanged.

- [ ] **Step 4: Run the focused contracts**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_race -v
```

Expected: PASS. The performance-branch port will separately rerun its Parcelable deferral contract.

- [ ] **Step 5: Commit the `ActivityProxy` split**

Run:

```bash
git add \
  app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt \
  scripts/tests/test_first_activity_gate_race.py
git commit -m "fix(startup): install activity gate before app routing"
```

## Task 4: Reorder main-process deferred startup around the early gate

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Modify: `scripts/tests/test_first_activity_gate_race.py`

- [ ] **Step 1: Add failing ordering and failure-path contracts**

Add these methods:

```python
    def test_zygisk_installs_gate_before_starting_deferred_worker(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        method = source[source.index("private fun startDeferredStartup"):]
        install = method.index("ActivityProxy.installStartupGate")
        worker = method.index('Thread({')
        self.assertLess(install, worker)
        self.assertIn("ACTIVITY_GATE_WAIT_MS = 5_000L", source)

    def test_gate_has_structured_outcomes_and_drops_late_readiness(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        for marker in (
            "startup_gate_installed",
            "startup_gate_activity_arrived",
            "startup_gate_ready",
            "startup_gate_wait_ms=",
            "startup_gate_result=started",
            "startup_gate_result=prepare_failed",
            "startup_gate_result=lsplant_failed",
            "startup_gate_result=startup_failed",
            "startup_gate_late_result_dropped",
        ):
            self.assertIn(marker, source)
        self.assertIn("gate.publish", source)
        self.assertIn("gate.awaitAndConsume(ACTIVITY_GATE_WAIT_MS)", source)

    def test_early_lsplant_ab_still_bypasses_activity_gate(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        early = source.index("if (isEarlyLsplantEnabled())")
        gate = source.index("ActivityProxy.installStartupGate", early)
        direct = source.index("runEarlyDeferredStartup", early)
        self.assertLess(direct, gate)
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_race -v
```

Expected: FAIL because gate installation still occurs after `waitForLibaffBiz()` and `Application` acquisition.

- [ ] **Step 3: Add preparation outcome types and constants**

In `ZygiskEntry`, add:

```kotlin
    private const val ACTIVITY_GATE_WAIT_MS = 5_000L

    private sealed interface DeferredStartupPreparation {
        data class Ready(
            val application: Application,
            val libaffLoaded: Boolean,
        ) : DeferredStartupPreparation

        data class Failed(val reason: String) : DeferredStartupPreparation
    }
```

Import `java.util.concurrent.TimeUnit` only in `MainProcessStartupGate.kt`; do not add a second timeout implementation in `ZygiskEntry`.

- [ ] **Step 4: Replace `startDeferredStartup` with gate-first orchestration**

Use this structure:

```kotlin
    private fun startDeferredStartup(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
    ) {
        if (isEarlyLsplantEnabled()) {
            Thread({
                runEarlyDeferredStartup(service, bridge, apkPath)
            }, "WCX-Defer-${android.os.Process.myPid()}").apply { isDaemon = true }.start()
            return
        }

        val gate = MainProcessStartupGate<DeferredStartupPreparation>()
        if (!ActivityProxy.installStartupGate {
                runMainStartupGate(service, bridge, apkPath, gate)
            }
        ) {
            WeLogger.e(TAG, "startup_gate_result=prepare_failed reason=install_failed")
            return
        }
        WeLogger.i(TAG, "startup_gate_installed")

        Thread({
            prepareDeferredStartup(gate)
        }, "WCX-Defer-${android.os.Process.myPid()}").apply { isDaemon = true }.start()
    }
```

Keep `debug.wcx.early_lsplant` on its existing direct path by extracting the old early branch into `runEarlyDeferredStartup(...)`.

- [ ] **Step 5: Implement background preparation without main-thread dependencies**

Add:

```kotlin
    private fun prepareDeferredStartup(
        gate: MainProcessStartupGate<DeferredStartupPreparation>,
    ) {
        val result = try {
            val loaded = waitForLibaffBiz()
            WeLogger.i(
                TAG,
                "deferred startup: libaff_biz ${if (loaded) "loaded" else "timeout"} after polling",
            )
            val app = acquireApplication(if (loaded) APP_WAIT_MS else FALLBACK_APP_WAIT_MS)
            if (app == null) {
                DeferredStartupPreparation.Failed("application_unavailable")
            } else if (!ActivityProxy.installInstrumentationFallback()) {
                DeferredStartupPreparation.Failed("instrumentation_unavailable")
            } else {
                DeferredStartupPreparation.Ready(app, loaded)
            }
        } catch (t: Throwable) {
            WeLogger.e(TAG, "deferred startup preparation failed", t)
            DeferredStartupPreparation.Failed("exception:${t.javaClass.simpleName}")
        }
        publishPreparation(gate, result)
    }

    private fun publishPreparation(
        gate: MainProcessStartupGate<DeferredStartupPreparation>,
        result: DeferredStartupPreparation,
    ) {
        if (gate.publish(result)) {
            when (result) {
                is DeferredStartupPreparation.Ready -> WeLogger.i(TAG, "startup_gate_ready")
                is DeferredStartupPreparation.Failed -> WeLogger.e(
                    TAG,
                    "startup_gate_result=prepare_failed reason=${result.reason}",
                )
            }
        } else {
            WeLogger.w(TAG, "startup_gate_late_result_dropped")
        }
    }
```

Do not post any preparation work to the main Handler.

- [ ] **Step 6: Implement bounded gate consumption before Activity creation**

Add:

```kotlin
    private fun runMainStartupGate(
        service: ZygiskLoaderService,
        bridge: ArtHookBridge,
        apkPath: String,
        gate: MainProcessStartupGate<DeferredStartupPreparation>,
    ) {
        WeLogger.i(TAG, "startup_gate_activity_arrived")
        val waitStarted = SystemClock.elapsedRealtime()
        val preparation = gate.awaitAndConsume(ACTIVITY_GATE_WAIT_MS)
        val waited = SystemClock.elapsedRealtime() - waitStarted
        WeLogger.i(TAG, "startup_gate_wait_ms=$waited")

        when (preparation) {
            null -> WeLogger.e(TAG, "startup_gate_result=prepare_failed reason=timeout")
            is DeferredStartupPreparation.Failed -> WeLogger.e(
                TAG,
                "startup_gate_result=prepare_failed reason=${preparation.reason}",
            )
            is DeferredStartupPreparation.Ready -> {
                WeLogger.i(TAG, "deferred startup: 首个 Activity 即将创建，开始初始化 LSPlant")
                if (!initializeLsPlantOffMainThread()) {
                    reportLsPlantFailure(preparation.libaffLoaded)
                    WeLogger.e(TAG, "startup_gate_result=lsplant_failed")
                    return
                }
                try {
                    UnifiedEntryPoint.prepareClassLoaderBridge()
                    StartupAgent.startup(
                        service,
                        bridge,
                        apkPath,
                        preparation.application,
                    )
                    WeLogger.i(TAG, "startup_gate_result=started")
                } catch (t: Throwable) {
                    WeLogger.e(TAG, "startup_gate_result=startup_failed", t)
                }
            }
        }
    }
```

Remove `installMainStartupGate(...)` and the default-path call to `postMainStartup(...)`. Keep `postMainStartup(...)` only if the early A/B path still uses it.

- [ ] **Step 7: Run source contracts and inspect ordering**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_race -v
grep -nE 'startup_gate_|startDeferredStartup|waitForLibaffBiz|Thread\(\{' \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt
```

Expected: tests pass; `startup_gate_installed` appears before the default deferred worker starts.

- [ ] **Step 8: Commit gate-first orchestration**

Run:

```bash
git add \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt \
  scripts/tests/test_first_activity_gate_race.py
git commit -m "fix(startup): arm first activity gate before libaff wait"
```

## Task 5: Extend the device probe for the new state machine

**Files:**
- Modify: `scripts/wcx-injection-probe.sh`
- Create: `scripts/tests/test_first_activity_gate_probe.py`

- [ ] **Step 1: Write failing probe contracts**

Create `scripts/tests/test_first_activity_gate_probe.py` with:

```python
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "scripts/wcx-injection-probe.sh"


class FirstActivityGateProbeContractTest(unittest.TestCase):
    def test_probe_recognizes_structured_gate_markers(self):
        source = PROBE.read_text(encoding="utf-8")
        for marker in (
            "startup_gate_installed",
            "startup_gate_activity_arrived",
            "startup_gate_ready",
            "startup_gate_result=started",
            "startup_gate_result=prepare_failed",
            "startup_gate_result=lsplant_failed",
            "startup_gate_result=startup_failed",
        ):
            self.assertIn(marker, source)

    def test_probe_has_direct_screenoff_and_boot_modes(self):
        source = PROBE.read_text(encoding="utf-8")
        self.assertIn("direct)", source)
        self.assertIn("screenoff)", source)
        self.assertIn("boot)", source)
        self.assertIn("PRE_LSPLANT", source)
        self.assertIn("background_initialized_early", source)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
python -m unittest scripts.tests.test_first_activity_gate_probe -v
```

Expected: FAIL because the probe still uses only the old free-form gate messages and has no screen-off mode.

- [ ] **Step 3: Add structured counters and stage selection**

In `scripts/wcx-injection-probe.sh`, replace the old gate counters with:

```sh
GATE=$(grep -ac 'startup_gate_installed' "$OUT")
TRIGGER=$(grep -ac 'startup_gate_activity_arrived' "$OUT")
READY=$(grep -ac 'startup_gate_ready' "$OUT")
STARTED=$(grep -ac 'startup_gate_result=started' "$OUT")
PREP_FAILED=$(grep -ac 'startup_gate_result=prepare_failed' "$OUT")
LSPLANT_FAILED=$(grep -ac 'startup_gate_result=lsplant_failed' "$OUT")
STARTUP_FAILED=$(grep -ac 'startup_gate_result=startup_failed' "$OUT")
```

Use this ordered stage logic:

```sh
[ "$ENTRY" -lt 1 ] && STAGE=no_java_entry
[ "$ENTRY" -ge 1 ] && [ "$GATE" -lt 1 ] && STAGE=startup_gate_missing
[ "$GATE" -ge 1 ] && [ "$TRIGGER" -lt 1 ] && STAGE=startup_gate_not_triggered
[ "$PREP_FAILED" -gt 0 ] && STAGE=startup_prepare_failed
[ "$LSPLANT_FAILED" -gt 0 ] && STAGE=lsplant_failed
[ "$STARTUP_FAILED" -gt 0 ] && STAGE=module_start_failed
[ "$TRIGGER" -ge 1 ] && [ "$READY" -lt 1 ] && [ "$PREP_FAILED" -eq 0 ] && STAGE=startup_not_ready
[ "$READY" -ge 1 ] && [ "$STARTED" -lt 1 ] && STAGE=startup_not_completed
[ "$STARTED" -ge 1 ] && [ "$LSPLANT" -lt 1 ] && STAGE=lsplant_log_missing
[ "$LSPLANT" -ge 1 ] && [ "$ENABLED" -lt 10 ] && STAGE=features_missing
[ "$ENABLED" -ge 10 ] && [ "$PROXY" -lt 1 ] && STAGE=first_activity_missed
```

Print all counters in the summary line.

- [ ] **Step 4: Add the `screenoff` mode**

Add this case before `boot`:

```sh
  screenoff)
    input keyevent KEYCODE_SLEEP
    sleep 2
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    sleep 8
    input keyevent KEYCODE_WAKEUP
    wm dismiss-keyguard >/dev/null 2>&1
    am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1
    ;;
```

This starts the cold process while the display is off, then wakes and re-delivers the launcher Intent without killing the process.

- [ ] **Step 5: Assert background mode does not initialize before Activity open**

After writing `$PRE`, add:

```sh
    PRE_LSPLANT=$(grep -ac 'deferred startup: LSPlant initialized' "$PRE")
    PRE_STARTED=$(grep -ac 'startup_gate_result=started' "$PRE")
```

Initialize both variables to zero before the mode switch. Add:

```sh
[ "$MODE" = boot ] && [ "$PRE_LSPLANT" -gt 0 ] && STAGE=background_initialized_early
[ "$MODE" = boot ] && [ "$PRE_STARTED" -gt 0 ] && STAGE=background_initialized_early
```

Print `pre_lsplant` and `pre_started` in the result.

- [ ] **Step 6: Run shell syntax and probe contracts**

Run:

```bash
sh -n scripts/wcx-injection-probe.sh
python -m unittest scripts.tests.test_first_activity_gate_probe -v
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

Expected: shell syntax succeeds and all Python tests pass.

- [ ] **Step 7: Commit the probe update**

Run:

```bash
git add scripts/wcx-injection-probe.sh scripts/tests/test_first_activity_gate_probe.py
git commit -m "test(startup): cover first activity gate race"
```

## Task 6: Compile and validate the stable fix in the Debian VM

**Files:**
- Create: `docs/work-items/wcx-first-activity-gate-race.md`

- [ ] **Step 1: Create the work-item evidence template**

Create `docs/work-items/wcx-first-activity-gate-race.md` with these completed facts and empty result tables, not placeholder words:

```markdown
# WCX first-Activity gate race

Date: 2026-11-19
Baseline: `zygisk-module` at `72d1fad1`
Fix branch: `fix/first-activity-gate-race`

## Root cause

The stable main-process path installed its ActivityThread interception only after the `libaff_biz` wait and an additional main-thread post. LauncherUI could be created in that interval, leaving the one-shot startup action permanently untriggered.

## Preserved constraints

- `libaff_biz` must remain stable for 1 second before LSPlant initialization.
- Pure background process launch must not initialize LSPlant.
- WCX startup must finish before the first Activity creation continues.
- The injector is unchanged; deployment replaces only `module.apk`.

## VM verification

| Check | Result |
| --- | --- |
| Python contracts | Not run in this document revision |
| standard arm64 release | Not run in this document revision |
| minified ABI contracts | Not run in this document revision |
| APK SHA-256 | Not recorded in this document revision |
| Injector MD5 | Not recorded in this document revision |

## Device matrix

| Scenario | Required | Passed |
| --- | ---: | ---: |
| Direct cold start | 10 | 0 |
| Screen-off cold start | 5 | 0 |
| Background then foreground | 3 | 0 |

No optimized APK may be deployed until this table is 18/18 and the crash checks are clean.
```

- [ ] **Step 2: Import the existing minified ABI verification tooling without runtime changes**

Run from the repository root:

```bash
git -C .worktrees/perf-module-size-startup show \
  perf/module-size-startup:scripts/wcx-minified-contract.sh \
  > .worktrees/fix-first-activity-gate-race/scripts/wcx-minified-contract.sh
git -C .worktrees/perf-module-size-startup show \
  perf/module-size-startup:scripts/wcx-minified-contracts.txt \
  > .worktrees/fix-first-activity-gate-race/scripts/wcx-minified-contracts.txt
git -C .worktrees/perf-module-size-startup show \
  perf/module-size-startup:scripts/tests/test_wcx_minified_contract.py \
  > .worktrees/fix-first-activity-gate-race/scripts/tests/test_wcx_minified_contract.py
chmod +x .worktrees/fix-first-activity-gate-race/scripts/wcx-minified-contract.sh
```

Expected: only verification files are copied; no app source or ProGuard rule changes.

- [ ] **Step 3: Run local static verification before syncing**

Run:

```bash
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
sh -n scripts/wcx-injection-probe.sh
git diff --check
git status --short
```

Expected: all tests pass; the work-item and imported ABI verification files are uncommitted.

- [ ] **Step 4: Commit the verification support and initial evidence document**

Run:

```bash
git add \
  scripts/wcx-minified-contract.sh \
  scripts/wcx-minified-contracts.txt \
  scripts/tests/test_wcx_minified_contract.py \
  docs/work-items/wcx-first-activity-gate-race.md
git commit -m "test(startup): add stable fix verification support"
```

- [ ] **Step 5: Sync the clean fix worktree and build only standard arm64 release**

Run from the repository root:

```bash
WCX_LOCAL_REPO=/mnt/d/Github/wcx/.worktrees/fix-first-activity-gate-race \
  /tmp/wcx-worktree-remote.sh release --flavor standard --abi arm64-v8a
```

Expected: `/root/wcx` builds successfully; `packageZygiskApk` produces one arm64 Zygisk APK.

- [ ] **Step 6: Run VM contracts against the final Zygisk APK**

Run:

```bash
ssh debian13 'cd /root/wcx && . /root/wcx-env.sh && \
  python -m unittest discover -s scripts/tests -p "test_*.py" -v && \
  APK=$(find app/build/outputs/zygisk -type f -name "*arm64-v8a*.apk" -print -quit) && \
  test -n "$APK" && \
  sh scripts/wcx-minified-contract.sh \
    --apk "$APK" \
    --mapping app/build/outputs/mapping/standardRelease \
    --contracts scripts/wcx-minified-contracts.txt'
```

Expected: all Python tests pass and every minified ABI contract passes.

- [ ] **Step 7: Pull artifacts and record identities**

Run:

```bash
rm -rf .worktrees/fix-first-activity-gate-race/dist-first-activity-fix
WCX_LOCAL_REPO=/mnt/d/Github/wcx/.worktrees/fix-first-activity-gate-race \
  /tmp/wcx-worktree-remote.sh pull \
  /mnt/d/Github/wcx/.worktrees/fix-first-activity-gate-race/dist-first-activity-fix
find .worktrees/fix-first-activity-gate-race/dist-first-activity-fix -name '*.apk' -print
sha256sum $(find .worktrees/fix-first-activity-gate-race/dist-first-activity-fix \
  -path '*zygisk*' -name '*arm64-v8a*.apk' -print -quit)
```

Also run:

```bash
ssh debian13 'md5sum /root/wcx/zygisk/magisk/zygisk/arm64-v8a.so 2>/dev/null || \
  find /root/wcx -path "*zygisk*arm64-v8a*.so" -type f -exec md5sum {} \;'
```

Expected injector MD5: `93dcbeea7a5ea0075c36c315696be62f`.

- [ ] **Step 8: Update the work item with exact command results and commit**

Replace each “Not run in this document revision” cell with the observed pass count, build result, APK SHA-256, and injector MD5. Then run:

```bash
git add docs/work-items/wcx-first-activity-gate-race.md
git commit -m "docs(startup): record stable fix VM verification"
```

## Task 7: Deploy the fixed stable APK without touching the injector

**Files:**
- Device: `/data/adb/modules/zygisk-wcx/module.apk`
- Device: `/data/local/tmp/wcx-injection-probe.sh`
- Update: `docs/work-items/wcx-first-activity-gate-race.md`

- [ ] **Step 1: Recheck the device safety gate and back up the installed stable APK**

Run:

```bash
adb devices
adb shell su -c 'sha256sum /data/adb/modules/zygisk-wcx/module.apk'
adb shell su -c 'md5sum /data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so'
adb shell getprop debug.wcx.wekite_m2b
adb shell getprop debug.wcx.wekite_selftest
adb shell getprop debug.wcx.early_lsplant
adb shell su -c 'cp -p /data/adb/modules/zygisk-wcx/module.apk \
  /data/local/tmp/wcx-module-before-first-activity-fix.apk'
```

Expected: device `92d7336f` is online; all three properties are empty; injector MD5 is `93dcbeea7a5ea0075c36c315696be62f`.

- [ ] **Step 2: Push the probe and fixed stable candidate to temporary paths**

Run:

```bash
FIX_APK=$(find .worktrees/fix-first-activity-gate-race/dist-first-activity-fix \
  -path '*zygisk*' -name '*arm64-v8a*.apk' -print -quit)
test -n "$FIX_APK"
adb push .worktrees/fix-first-activity-gate-race/scripts/wcx-injection-probe.sh \
  /data/local/tmp/wcx-injection-probe.sh
adb push "$FIX_APK" /data/local/tmp/wcx-fixed-stable.apk
adb shell su -c 'chmod 755 /data/local/tmp/wcx-injection-probe.sh'
```

- [ ] **Step 3: Replace only `module.apk` and restore its security metadata**

Run:

```bash
adb shell su -c 'cp /data/local/tmp/wcx-fixed-stable.apk \
  /data/adb/modules/zygisk-wcx/module.apk && \
  chmod 644 /data/adb/modules/zygisk-wcx/module.apk && \
  chcon u:object_r:system_file:s0 /data/adb/modules/zygisk-wcx/module.apk && \
  ls -lZ /data/adb/modules/zygisk-wcx/module.apk && \
  sha256sum /data/adb/modules/zygisk-wcx/module.apk && \
  md5sum /data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so'
```

Expected: device APK hash matches the VM artifact; injector MD5 remains unchanged. Do not reboot and do not restart the framework/zygote.

- [ ] **Step 4: Run one direct smoke probe before the full matrix**

Run:

```bash
adb shell su -c 'sh /data/local/tmp/wcx-injection-probe.sh direct 25'
```

Expected: `RESULT=PASS`, `startup_gate_result=started`, enabled feature maximum at least 10, and no new crash status.

If this fails, stop immediately and restore the backed-up APK with:

```bash
adb shell su -c 'cp /data/local/tmp/wcx-module-before-first-activity-fix.apk \
  /data/adb/modules/zygisk-wcx/module.apk && \
  chmod 644 /data/adb/modules/zygisk-wcx/module.apk && \
  chcon u:object_r:system_file:s0 /data/adb/modules/zygisk-wcx/module.apk'
```

Preserve the artifact path printed by the probe and diagnose before any additional run.

## Task 8: Run the strict 18-run stable matrix

**Files:**
- Update: `docs/work-items/wcx-first-activity-gate-race.md`
- Create evidence directory outside git or under an ignored `dist-first-activity-fix/evidence/` path.

- [ ] **Step 1: Run 10 direct cold starts**

Run:

```bash
mkdir -p .worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence
for i in $(seq 1 10); do
  adb shell su -c 'sh /data/local/tmp/wcx-injection-probe.sh direct 25' \
    | tee ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/direct-$i.txt"
  grep -q 'RESULT=PASS' \
    ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/direct-$i.txt" || exit 1
done
```

Expected: 10/10 PASS.

- [ ] **Step 2: Run 5 screen-off cold starts**

Run:

```bash
for i in $(seq 1 5); do
  adb shell su -c 'sh /data/local/tmp/wcx-injection-probe.sh screenoff 25' \
    | tee ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/screenoff-$i.txt"
  grep -q 'RESULT=PASS' \
    ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/screenoff-$i.txt" || exit 1
done
```

Expected: 5/5 PASS.

- [ ] **Step 3: Run 3 background-then-foreground starts**

Run:

```bash
for i in $(seq 1 3); do
  adb shell su -c 'sh /data/local/tmp/wcx-injection-probe.sh boot 25' \
    | tee ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/boot-$i.txt"
  grep -q 'RESULT=PASS' \
    ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/boot-$i.txt" || exit 1
  grep -q 'pre_lsplant=0' \
    ".worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/boot-$i.txt" || exit 1
done
```

Expected: 3/3 PASS and no LSPlant initialization before Activity open.

- [ ] **Step 4: Capture final crash and injector checks**

Run:

```bash
adb shell su -c 'md5sum /data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so'
adb shell dumpsys activity exit-info com.tencent.mm \
  > .worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/exit-info-final.txt
adb shell su -c 'ls -lt /data/tombstones 2>/dev/null | head -20' \
  > .worktrees/fix-first-activity-gate-race/dist-first-activity-fix/evidence/tombstones-final.txt
```

Expected: injector unchanged; no matrix-window Java/native crash, SIGABRT, SIGSEGV, reason 5, or status 6/11.

- [ ] **Step 5: Update and commit the completed matrix**

Record 10/10, 5/5, 3/3, artifact hash, injector hash, and crash checks in `docs/work-items/wcx-first-activity-gate-race.md`. Run:

```bash
git add docs/work-items/wcx-first-activity-gate-race.md
git commit -m "docs(startup): record reliable stable device baseline"
```

## Task 9: Port the proven fix to the performance branch

**Files:**
- Cherry-pick fix commits into: `.worktrees/perf-module-size-startup`
- Update: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: List fix-only commits after the design commit**

Run:

```bash
git -C .worktrees/fix-first-activity-gate-race log \
  --oneline --reverse zygisk-module..fix/first-activity-gate-race
```

Expected: design commit followed by coordinator, ActivityProxy, Zygisk orchestration, probe, and evidence commits.

- [ ] **Step 2: Cherry-pick implementation commits into the performance branch**

Do not cherry-pick the design commit because `621200bd` is already present. Cherry-pick only the four implementation/probe subjects; the stable branch's imported ABI tooling already exists on the performance branch, and its evidence document will be copied separately:

```bash
for subject in \
  'fix(startup): add one-shot main process gate' \
  'fix(startup): install activity gate before app routing' \
  'fix(startup): arm first activity gate before libaff wait' \
  'test(startup): cover first activity gate race'; do
  commit=$(git -C .worktrees/fix-first-activity-gate-race log \
    --format='%H %s' zygisk-module..fix/first-activity-gate-race \
    | awk -v wanted="$subject" 'index($0, wanted) == 42 { print substr($0, 1, 40) }')
  test -n "$commit" || exit 1
  git -C .worktrees/perf-module-size-startup cherry-pick "$commit" || exit 1
done

git -C .worktrees/fix-first-activity-gate-race show \
  fix/first-activity-gate-race:docs/work-items/wcx-first-activity-gate-race.md \
  > .worktrees/perf-module-size-startup/docs/work-items/wcx-first-activity-gate-race.md
```

Resolve only expected conflicts caused by performance instrumentation marks or deferred Parcelable changes. The stable fix branch does not contain `StartupPerformance`; in the performance worktree explicitly retain or restore both:

- `StartupPerformance.mark("libaff_ready")` after `waitForLibaffBiz()`;
- `StartupPerformance.mark("activity_gate")` when the first Activity gate is consumed;
- the performance summary;
- `ParcelableFixer.ensureIntentHooksInstalled()` only at module Activity boundaries.

- [ ] **Step 3: Run performance-branch static verification**

Run:

```bash
cd .worktrees/perf-module-size-startup
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
sh -n scripts/wcx-injection-probe.sh
grep -q 'StartupPerformance.mark("libaff_ready")' \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt
grep -q 'StartupPerformance.mark("activity_gate")' \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt
APK=$(find app/build/outputs/zygisk -type f -name '*arm64-v8a*.apk' -print -quit)
if [ -n "$APK" ]; then
  sh scripts/wcx-minified-contract.sh \
    --apk "$APK" \
    --mapping app/build/outputs/mapping/standardRelease \
    --contracts scripts/wcx-minified-contracts.txt
fi
git diff --check
```

Expected: all Python contracts pass; ABI contracts pass when a current local APK is available. The VM rebuild in the next step is authoritative.

- [ ] **Step 4: Rebuild the optimized arm64 APK in the VM**

Run:

```bash
WCX_LOCAL_REPO=/mnt/d/Github/wcx/.worktrees/perf-module-size-startup \
  /tmp/wcx-worktree-remote.sh release --flavor standard --abi arm64-v8a
```

Expected: build succeeds; size budgets remain within APK ≤36,000,000 B, DEX ≤25,500,000 B, native ≤5,500,000 B.

- [ ] **Step 5: Record the stable-gate prerequisite as satisfied**

Update `docs/work-items/wcx-performance-package-size.md` with:

```markdown
- Stable first-Activity gate fix validated on the non-optimized baseline: direct 10/10, screen-off 5/5, background-to-foreground 3/3.
- The same fix is present in the optimized candidate and passed static/VM verification.
- Optimized device acceptance may resume while preserving the injector.
```

Commit:

```bash
git add \
  docs/work-items/wcx-first-activity-gate-race.md \
  docs/work-items/wcx-performance-package-size.md
git commit -m "docs(perf): unblock optimized device acceptance"
```

## Task 10: Resume the existing optimized-device acceptance

**Files:**
- Follow: `docs/superpowers/plans/2026-11-19-wcx-performance-package-size.md`
- Update: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Recheck the safety gate before replacing the stable fixed APK**

Verify optimized artifact hash, current fixed-stable device hash, injector MD5, empty debug properties, and a fresh direct fixed-stable probe. Expected: all checks pass.

- [ ] **Step 2: Continue Task 16 from the existing performance plan**

Replace only `/data/adb/modules/zygisk-wcx/module.apk`, preserve the injector, run controlled direct/background matrices, set `MAIN_PID=$(adb shell pidof com.tencent.mm | tr -d '\r')` and inspect `/proc/$MAIN_PID/maps`, test Markdown/audio/Hchat voice-forward/module Activity first use, and compare startup P50/P95.

- [ ] **Step 3: Restore the fixed stable APK unless the user explicitly approves retaining the optimized candidate**

Verify the restored APK SHA-256 and injector MD5, then run one final direct health probe.
