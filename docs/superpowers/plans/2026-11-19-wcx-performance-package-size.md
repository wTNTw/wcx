# WCX Performance and Package Size Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep one full-featured WCX APK while reducing the arm64 Zygisk package to at most 36 MB and lowering main-process WCX initialization P50 by at least 25% without changing injection or hook semantics.

**Architecture:** Execute four independently revertible tracks: measurement/budgets, R8/dependency slimming, lazy feature startup, and lazy native loading. Preserve the current `libaff_biz` gate, first-Activity gate, LSPlant/Dobby split, `IHookBridge` contract, standard/legacy entry points, and all user-visible features.

**Tech Stack:** Kotlin/KSP, Android Gradle Plugin/R8, Java buildSrc tasks, Python 3 standard library, Rust/Cargo JNI libraries, Zygisk, LSPlant, DexKit, GitHub Actions.

**Design:** `docs/superpowers/specs/2026-11-19-wcx-performance-package-size-design.md`

---

## Execution boundaries

- Run implementation in a dedicated worktree based on `zygisk-module`.
- Build only in Debian VM `/root/wcx` through `C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh`.
- Do not touch the phone until the stable baseline build and static contracts pass.
- Keep every numbered task in its own commit unless the task explicitly lists several dependency-removal commits.
- After each task, record APK, DEX and native size deltas in `docs/work-items/wcx-performance-package-size.md`.

## Planned file structure

### Measurement and budgets

- Create `scripts/wcx_apk_report.py` — deterministic APK/DEX/native size report and budget check.
- Create `scripts/tests/test_wcx_apk_report.py` — host-side parser tests using synthetic ZIPs and package reports.
- Create `config/wcx-performance-budget.json` — checked-in arm64 full-package budgets.
- Create `app/src/main/java/com/Johnny/wcx/utils/perf/StartupPerformance.kt` — low-overhead startup marks and one-line summary.
- Modify `.github/workflows/ci.yml` — generate/upload reports and enforce budgets in the optimization workflow.
- Modify `scripts/wcx-injection-probe.sh` — collect startup summaries without changing device state beyond the existing probe contract.

### R8 and dependency slimming

- Create `scripts/wcx-minified-contracts.txt` — fixed JNI, entry-point and script ABI contracts.
- Create `scripts/wcx-minified-contract.sh` — inspect a minified APK with `apkanalyzer` and mapping files.
- Modify `app/proguard-rules.pro` — remove broad keep rules in isolated steps.
- Modify `app/build.gradle.kts` — remove proven-unused or duplicate dependencies one at a time.
- Delete `app/src/main/java/com/Johnny/wcx/dev/ujhhgtg/reflekt/utils/FastJavaMethod.kt` if the release build confirms it remains unused.

### Lazy feature startup

- Modify `libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/core/Feature.kt` — add `defaultEnabled` metadata.
- Create `app/src/main/java/com/Johnny/wcx/features/core/FeatureDescriptor.kt` — immutable generated metadata.
- Create `app/src/main/java/com/Johnny/wcx/features/core/FeatureRegistry.kt` — thread-safe instance cache.
- Modify `libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/FeaturesScanner.kt` — generate descriptors and factories.
- Modify `app/src/main/java/com/Johnny/wcx/features/core/BaseFeature.kt` and `SwitchFeature.kt` — accept generated metadata.
- Modify default-enabled feature annotations listed in Task 10.
- Modify `app/src/main/java/com/Johnny/wcx/features/core/FeaturesLoader.kt` — instantiate early and deferred sets separately.
- Modify `app/src/main/java/com/Johnny/wcx/loader/utils/ParcelableFixer.kt` and `ActivityProxy.kt` — defer Intent hooks until the first module Activity.

### Lazy native loading

- Create `app/src/main/rust/wekit-media/Cargo.toml` and `src/lib.rs` — media-only JNI library.
- Move media modules from `app/src/main/rust/wekit-native/src/` into `wekit-media/src/`.
- Modify `Cargo.toml`, `xtask/src/main.rs`, `NativeLoader.kt`, `ZygiskNativePayload.kt`, and media Java/Kotlin callers.
- Keep `libwekit_native.so` as the core library name for compatibility; add `libwekit_media.so` for deferred capabilities.

---

### Task 1: Add deterministic APK size reporting

**Files:**
- Create: `scripts/wcx_apk_report.py`
- Create: `scripts/tests/test_wcx_apk_report.py`
- Create: `config/wcx-performance-budget.json`

- [ ] **Step 1: Write synthetic APK report tests**

Create tests that build a temporary ZIP with stored DEX/native entries and compressed assets:

```python
# scripts/tests/test_wcx_apk_report.py
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.wcx_apk_report import Budget, collect_zip_groups, check_budget


class WcxApkReportTest(unittest.TestCase):
    def test_groups_dex_native_assets_and_resources(self):
        with tempfile.TemporaryDirectory() as td:
            apk = Path(td) / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100, compress_type=zipfile.ZIP_STORED)
                zf.writestr("classes2.dex", b"e" * 50, compress_type=zipfile.ZIP_STORED)
                zf.writestr("lib/arm64-v8a/libx.so", b"n" * 40, compress_type=zipfile.ZIP_STORED)
                zf.writestr("assets/a.txt", b"a" * 30, compress_type=zipfile.ZIP_DEFLATED)
                zf.writestr("resources.arsc", b"r" * 20, compress_type=zipfile.ZIP_STORED)
            report = collect_zip_groups(apk)
            self.assertEqual(report["groups"]["dex"]["raw"], 150)
            self.assertEqual(report["groups"]["native"]["raw"], 40)
            self.assertEqual(report["groups"]["assets"]["raw"], 30)
            self.assertEqual(report["groups"]["resources"]["raw"], 20)

    def test_budget_failure_lists_each_exceeded_metric(self):
        budget = Budget(apk_bytes=100, dex_bytes=60, native_bytes=30)
        failures = check_budget(
            {"apk_bytes": 101, "groups": {"dex": {"raw": 61}, "native": {"raw": 31}}},
            budget,
        )
        self.assertEqual(len(failures), 3)
        self.assertIn("apk_bytes", failures[0])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run in Debian VM:

```bash
cd /root/wcx
python3 -m unittest scripts.tests.test_wcx_apk_report -v
```

Expected: `ModuleNotFoundError: No module named 'scripts.wcx_apk_report'`.

- [ ] **Step 3: Implement ZIP grouping and budgets**

Implement these public interfaces:

```python
# scripts/wcx_apk_report.py
from __future__ import annotations

import argparse
import json
import subprocess
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class Budget:
    apk_bytes: int
    dex_bytes: int
    native_bytes: int


def _group(name: str) -> str:
    if name.startswith("classes") and name.endswith(".dex"):
        return "dex"
    if name.startswith("lib/") and name.endswith(".so"):
        return "native"
    if name.startswith("assets/"):
        return "assets"
    if name == "resources.arsc" or name.startswith("res/"):
        return "resources"
    if name.startswith("META-INF/"):
        return "meta_inf"
    return "other"


def collect_zip_groups(apk: Path) -> dict:
    groups: dict[str, dict[str, int]] = {}
    entries: list[dict[str, int | str]] = []
    with zipfile.ZipFile(apk) as zf:
        for info in zf.infolist():
            group = _group(info.filename)
            totals = groups.setdefault(group, {"raw": 0, "compressed": 0, "count": 0})
            totals["raw"] += info.file_size
            totals["compressed"] += info.compress_size
            totals["count"] += 1
            entries.append({
                "name": info.filename,
                "raw": info.file_size,
                "compressed": info.compress_size,
                "stored": int(info.compress_type == zipfile.ZIP_STORED),
            })
    entries.sort(key=lambda item: int(item["raw"]), reverse=True)
    return {"apk_bytes": apk.stat().st_size, "groups": groups, "largest_entries": entries[:100]}


def check_budget(report: dict, budget: Budget) -> list[str]:
    actual = {
        "apk_bytes": int(report["apk_bytes"]),
        "dex_bytes": int(report["groups"].get("dex", {}).get("raw", 0)),
        "native_bytes": int(report["groups"].get("native", {}).get("raw", 0)),
    }
    limits = asdict(budget)
    return [f"{name}: actual={actual[name]} limit={limit}" for name, limit in limits.items() if actual[name] > limit]
```

The CLI must accept `--apk`, `--budget`, `--output`, optional `--mapping`, optional `--apkanalyzer`, and optional `--llvm-size`. When mapping and `apkanalyzer` are available, run:

```bash
apkanalyzer dex packages --defined-only --proguard-folder <mapping-dir> <apk>
```

Parse package lines into `dex_packages`, preserving package name and bytecode size. When `llvm-size` is available, run `llvm-size -A` for each extracted `.so` and store section sizes under `native_sections`.

Use this budget file:

```json
{
  "apk_bytes": 36000000,
  "dex_bytes": 25500000,
  "native_bytes": 5500000
}
```

The CLI exits `2` on budget violation and `0` otherwise.

- [ ] **Step 4: Run parser tests and the real baseline report**

```bash
cd /root/wcx
python3 -m unittest scripts.tests.test_wcx_apk_report -v
python3 scripts/wcx_apk_report.py \
  --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk \
  --budget config/wcx-performance-budget.json \
  --mapping app/build/outputs/mapping/standardRelease \
  --output /tmp/wcx-apk-report.json || test $? = 2
```

Expected: tests pass; baseline report exits `2` because the current APK exceeds 36 MB and DEX exceeds 25.5 MB.

- [ ] **Step 5: Commit**

```bash
git add scripts/wcx_apk_report.py scripts/tests/test_wcx_apk_report.py config/wcx-performance-budget.json
git commit -m "test(perf): add APK size budget report"
```

---

### Task 2: Integrate size reports into CI without blocking unrelated work yet

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/work-items/wcx-performance-package-size.md` (create if absent)

- [ ] **Step 1: Add a report-only CI step after the release build**

Add a step after `Replace standard arm64 APK with the dual-purpose build`:

```yaml
      - name: Report WCX APK size
        if: always()
        run: |
          mkdir -p dist/perf
          APK=$(find app/build/outputs/zygisk -name '*-arm64-v8a.apk' -type f | head -1)
          test -n "$APK" || { echo "::error::Zygisk APK missing"; exit 1; }
          set +e
          python3 scripts/wcx_apk_report.py \
            --apk "$APK" \
            --budget config/wcx-performance-budget.json \
            --mapping app/build/outputs/mapping/standardRelease \
            --output dist/perf/wcx-apk-report.json
          STATUS=$?
          set -e
          if [ "$STATUS" -eq 2 ]; then
            echo "::warning::WCX package still exceeds optimization budget"
          elif [ "$STATUS" -ne 0 ]; then
            exit "$STATUS"
          fi
```

- [ ] **Step 2: Include `dist/perf` in the existing artifact upload**

Extend the existing upload path with:

```yaml
            dist/perf/**
```

- [ ] **Step 3: Build and inspect the report**

```bash
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a
ssh debian13 'cd /root/wcx && python3 scripts/wcx_apk_report.py --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk --budget config/wcx-performance-budget.json --mapping app/build/outputs/mapping/standardRelease --output /tmp/report.json || test $? = 2; head -40 /tmp/report.json'
```

Expected: release build succeeds and report contains `apk_bytes`, `groups`, `dex_packages`, and `largest_entries`.

- [ ] **Step 4: Record the immutable baseline**

Create the work item with current commit, artifact SHA-256, APK/DEX/native bytes, and the command above. Do not claim the budget passes.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml docs/work-items/wcx-performance-package-size.md
git commit -m "ci(perf): publish WCX size reports"
```

---

### Task 3: Add one-line Java startup performance summaries

**Files:**
- Create: `app/src/main/java/com/Johnny/wcx/utils/perf/StartupPerformance.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/features/core/FeaturesLoader.kt`

- [ ] **Step 1: Implement the mark collector**

Create a collector that performs no file I/O until `emitOnce`:

```kotlin
package com.Johnny.wcx.utils.perf

import android.os.SystemClock
import com.Johnny.wcx.utils.WeLogger
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

object StartupPerformance {
    private const val TAG = "StartupPerf"
    private val lock = Any()
    private val emitted = AtomicBoolean(false)
    private var origin = 0L
    private val marks = LinkedHashMap<String, Long>()

    fun begin() = synchronized(lock) {
        if (origin == 0L) origin = SystemClock.elapsedRealtime()
    }

    fun mark(name: String) = synchronized(lock) {
        if (origin == 0L) origin = SystemClock.elapsedRealtime()
        marks.putIfAbsent(name, SystemClock.elapsedRealtime() - origin)
    }

    fun emitOnce(processName: String) {
        if (!emitted.compareAndSet(false, true)) return
        val snapshot = synchronized(lock) { marks.toMap() }
        val total = snapshot.values.maxOrNull() ?: 0L
        WeLogger.i(TAG, buildString {
            append("startup process=").append(processName)
            append(" total=").append(total).append("ms")
            snapshot.forEach { (name, value) ->
                append(' ').append(name).append('=').append(value).append("ms")
            }
        })
    }
}
```

- [ ] **Step 2: Mark exact lifecycle boundaries**

Add these calls:

```kotlin
// ZygiskEntry.init, first line after target validation
StartupPerformance.begin()
StartupPerformance.mark("zygisk_entry")

// after waitForLibaffBiz
StartupPerformance.mark("libaff_ready")

// startup gate callback, before LSPlant init
StartupPerformance.mark("activity_gate")

// after initializeLsPlant succeeds
StartupPerformance.mark("lsplant_ready")

// StartupAgent.startup, after NativeLoader.init
StartupPerformance.mark("native_ready")

// WeLauncher.init, after ParcelableFixer and after ActivityProxy
StartupPerformance.mark("parcelable_ready")
StartupPerformance.mark("activity_proxy_ready")
```

Change `startFeatureBatchWhenPrefsReady` to accept `onComplete: () -> Unit = {}` and invoke it exactly once after `startFeatureBatch`. The early batch callback must mark `early_features_ready` and call:

```kotlin
StartupPerformance.emitOnce(TargetProcesses.currentName)
```

The deferred batch callback marks `deferred_features_ready` but does not emit a second startup line.

- [ ] **Step 3: Build both native modes**

```bash
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a --wekite-hook
```

Expected: both builds print `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/Johnny/wcx/utils/perf/StartupPerformance.kt \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt \
  app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt \
  app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt \
  app/src/main/java/com/Johnny/wcx/features/core/FeaturesLoader.kt
git commit -m "perf(startup): add structured initialization timings"
```

---

### Task 4: Extend the probe to aggregate startup P50/P95

**Files:**
- Modify: `scripts/wcx-injection-probe.sh`
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Add a pure summary parser mode**

Add `--summarize-startup <log-file>` that reads `StartupPerf: startup` lines, extracts `total=<n>ms`, sorts values numerically, and prints count/P50/P95/max. The percentile index must use nearest-rank: `ceil(percent * count) - 1`.

- [ ] **Step 2: Verify against a fixed fixture**

```bash
cat >/tmp/wcx-startup-fixture.log <<'EOF'
2026-11-19 10:00:00.000 I/WCX StartupPerf: startup process=com.tencent.mm total=1000ms early_features_ready=1000ms
2026-11-19 10:00:01.000 I/WCX StartupPerf: startup process=com.tencent.mm total=1200ms early_features_ready=1200ms
2026-11-19 10:00:02.000 I/WCX StartupPerf: startup process=com.tencent.mm total=1500ms early_features_ready=1500ms
2026-11-19 10:00:03.000 I/WCX StartupPerf: startup process=com.tencent.mm total=2000ms early_features_ready=2000ms
EOF
scripts/wcx-injection-probe.sh --summarize-startup /tmp/wcx-startup-fixture.log
```

Expected:

```text
count=4 p50=1200ms p95=2000ms max=2000ms
```

- [ ] **Step 3: Record baseline collection procedure**

Document that device collection is postponed until the stable baseline can pass the existing boot/direct health probe. Keep the previous stop condition: one narrowed retry, then stop.

- [ ] **Step 4: Commit**

```bash
git add scripts/wcx-injection-probe.sh docs/work-items/wcx-performance-package-size.md
git commit -m "test(perf): summarize startup timing samples"
```

---

### Task 5: Add minified APK ABI contracts before changing R8

**Files:**
- Create: `scripts/wcx-minified-contracts.txt`
- Create: `scripts/wcx-minified-contract.sh`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Define fixed contracts**

Use pipe-separated records:

```text
class|com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry
method|com.Johnny.wcx.loader.entry.zygisk.ZygiskEntry|init(java.lang.String,java.lang.String,java.lang.String)
class|com.Johnny.wcx.loader.entry.zygisk.ArtHookBridge
class|com.Johnny.wcx.loader.entry.zygisk.ArtHookBridgeRuntime$HookEntry
class|com.Johnny.wcx.loader.entry.xp51.Xp51HookEntry
class|com.Johnny.wcx.loader.entry.lsp10x.Lsp10xUnifiedHookEntry
class|h.Hchat.hooks.items.script.ScriptPluginAbi
class|me.hd.wauxv.plugin.api.callback.PluginCallBack
class|bsh.Interpreter
```

Add every class currently named in `app/proguard-rules.pro` because native code, Xposed metadata or script text loads it by name.

- [ ] **Step 2: Implement the contract checker**

The script must:

1. resolve `apkanalyzer` from `ANDROID_SDK_ROOT`/`ANDROID_HOME`;
2. run `dex packages --defined-only --proguard-folder <mapping> <apk>` once;
3. require each `class|` record to appear on a `C` line;
4. require each `method|` record to appear below the matching class;
5. print all missing contracts before exiting `1`.

Interface:

```bash
scripts/wcx-minified-contract.sh \
  --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk \
  --mapping app/build/outputs/mapping/standardRelease \
  --contracts scripts/wcx-minified-contracts.txt
```

- [ ] **Step 3: Run against the current minified APK**

Expected: exit `0` and `contracts=<n> missing=0`.

- [ ] **Step 4: Add the checker to CI after APK reporting**

The CI command must use the Zygisk arm64 APK and `standardRelease` mapping. A missing contract is a hard failure even while size budgets remain warning-only.

- [ ] **Step 5: Commit**

```bash
git add scripts/wcx-minified-contracts.txt scripts/wcx-minified-contract.sh .github/workflows/ci.yml
git commit -m "test(r8): verify dynamic ABI in minified APK"
```

---

### Task 6: Remove the broad WCX feature keep rule

**Files:**
- Modify: `app/proguard-rules.pro`
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Remove only this rule**

Delete:

```proguard
-keep class com.Johnny.wcx.features.** { *; }
```

Do not change Hchat or Kotlin reflection rules in the same commit.

- [ ] **Step 2: Build release and run contracts**

```bash
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a
ssh debian13 'cd /root/wcx && scripts/wcx-minified-contract.sh --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk --mapping app/build/outputs/mapping/standardRelease --contracts scripts/wcx-minified-contracts.txt'
```

Expected: build and contracts pass.

- [ ] **Step 3: Generate before/after report**

Run `wcx_apk_report.py`, record APK/DEX delta, and inspect `usage.txt` for removed feature-internal methods. Reject the change if any generated feature class disappears from the mapped `FeaturesProvider` references.

- [ ] **Step 4: Commit**

```bash
git add app/proguard-rules.pro docs/work-items/wcx-performance-package-size.md
git commit -m "perf(r8): allow optimization of feature internals"
```

---

### Task 7: Narrow Hchat and script keep rules

**Files:**
- Modify: `app/proguard-rules.pro`
- Modify: `scripts/wcx-minified-contracts.txt`
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Expand script ABI contracts from actual string imports**

Search `ScriptPluginRuntime`, script prelude assets and `Class.forName` calls. Add each fixed class/member to `wcx-minified-contracts.txt`. Keep the existing explicit rules for `bsh.**`, `me.hd.wauxv.**`, callback/data ABI and `ScriptPluginAbi`.

- [ ] **Step 2: Replace the broad Hchat rule**

Delete:

```proguard
-keep class h.Hchat.** { *; }
```

Add only:

```proguard
-keep class h.Hchat.ModuleEntry { public *; }
-keep class h.Hchat.hooks.items.script.ScriptPluginAbi { *; }
-keepnames class h.Hchat.hooks.api.**
-keepclassmembers class h.Hchat.hooks.api.** { public *; }
```

Retain current script model/callback rules. Do not add another package-wide `{ *; }` fallback.

- [ ] **Step 3: Build, run contracts and inspect R8 usage**

Expected: contracts pass; `h.Hchat` bytecode decreases or remains equal. If R8 removes a dynamically used class, add that exact class/member contract and exact keep rule, then rebuild.

- [ ] **Step 4: Run the manual Hchat smoke checklist before device performance runs**

Verify on a test install after stable baseline recovery:

- Hchat settings page opens;
- BeanShell script evaluates `1 + 1`;
- snapshot load/save works;
- one callback API invocation succeeds;
- no `ClassNotFoundException`/`NoSuchMethodException` in persisted WCX logs.

- [ ] **Step 5: Commit**

```bash
git add app/proguard-rules.pro scripts/wcx-minified-contracts.txt docs/work-items/wcx-performance-package-size.md
git commit -m "perf(r8): narrow Hchat runtime keeps"
```

---

### Task 8: Remove unnecessary Kotlin reflection retention

**Files:**
- Delete: `app/src/main/java/com/Johnny/wcx/dev/ujhhgtg/reflekt/utils/FastJavaMethod.kt`
- Modify: `app/proguard-rules.pro`
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Prove the compatibility extension is unused**

```bash
git grep -n 'fastJavaMethod' -- app/src/main/java libs
```

Expected: only the extension declaration appears.

- [ ] **Step 2: Delete the extension and broad keep rule**

Delete the file and remove:

```proguard
-keep class kotlin.reflect.** { *; }
```

Keep `kotlin.Metadata` and coroutine rules.

- [ ] **Step 3: Build and inspect package report**

Expected: release build and contracts pass. `kotlin.reflect` package bytecode must decrease substantially; if another dependency still requires a reflection entry, add a precise rule for that entry rather than restoring the broad rule.

- [ ] **Step 4: Commit**

```bash
git add -A app/src/main/java/com/Johnny/wcx/dev/ujhhgtg/reflekt/utils/FastJavaMethod.kt app/proguard-rules.pro docs/work-items/wcx-performance-package-size.md
git commit -m "perf(r8): remove unused Kotlin reflection bridge"
```

---

### Task 9: Remove orphan build dependencies one at a time

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Modify: `.github/workflows/ci.yml`
- Modify: theme asset references if resource conversion is required
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Remove the duplicate Fastjson declaration**

Delete:

```kotlin
implementation("com.alibaba.fastjson2:fastjson2:2.0.61.android8")
```

Keep `implementation(libs.fastjson2)` as the single version. Build, run contracts, and commit:

```bash
git commit -am "build: unify Fastjson dependency"
```

- [ ] **Step 2: Remove ARSCLib and verify**

Delete `implementation(files("libs/ARSCLib-V1.3.8.jar"))`, build release, run contracts, and verify the following entries disappear from the APK unless another dependency owns them:

```text
clst/core.jcst
android/res-map.txt
android/attrs.xml
frameworks/android/android-*.apk
```

Run the Hchat/script smoke checklist. If all checks pass, commit:

```bash
git add app/build.gradle.kts app/proguard-rules.pro docs/work-items/wcx-performance-package-size.md
git commit -m "build: remove unused ARSCLib runtime"
```

If the build or smoke test proves a runtime owner, revert only this removal and record the owner in the work item.

- [ ] **Step 3: Remove JADX dex input, smali/baksmali and explicit Guava separately**

For each dependency below, repeat build → contracts → Hchat script smoke → size report → commit:

```kotlin
implementation("io.github.skylot:jadx-dex-input:1.5.5") { ... }
implementation("com.android.tools.smali:smali-baksmali:3.0.9")
implementation("com.google.guava:guava:33.5.0-android")
```

Use one commit per successful removal:

```text
build: remove unused JADX runtime
build: remove unused smali runtime
build: remove unused explicit Guava runtime
```

Do not remove `dalvik-dx`; it has direct imports in BeanShell conversion, DexMaker compatibility and settings proxy code.

- [ ] **Step 4: Optimize large source assets only if dependency removal is insufficient**

Run:

```bash
find app/src/main/assets app/src/main/res -type f -size +100k -printf '%s %p\n' | sort -nr
```

If APK or DEX still exceeds budget, install Debian's `webp` package and convert each opaque JPG/PNG candidate individually:

```bash
sudo apt-get install -y webp
cwebp -quiet -q 82 app/src/main/assets/theme_store/wallpaper/example.jpg \
  -o app/src/main/assets/theme_store/wallpaper/example.webp
```

Update every exact asset-name reference found by `git grep 'example.jpg'`. Compare dimensions and decoded output on device before deleting the original. Commit each conversion group separately with the measured APK delta. Do not convert assets with required alpha/animation unless the replacement preserves those properties.

- [ ] **Step 5: Turn the package budget into a hard CI gate when the branch first passes**

Change the report step so exit `2` fails CI after both APK and DEX are within budget. Keep native budget at 5.5 MB until Task 14.

- [ ] **Step 6: Record and commit the gate change**

```bash
git add .github/workflows/ci.yml docs/work-items/wcx-performance-package-size.md
git commit -m "ci(perf): enforce WCX package budgets"
```

---

### Task 10: Generate lazy feature descriptors

**Files:**
- Modify: `libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/core/Feature.kt`
- Create: `app/src/main/java/com/Johnny/wcx/features/core/FeatureDescriptor.kt`
- Create: `app/src/main/java/com/Johnny/wcx/features/core/FeatureRegistry.kt`
- Modify: `libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/FeaturesScanner.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/features/core/BaseFeature.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/features/core/SwitchFeature.kt`
- Modify: default-enabled feature files found by `git grep 'override val defaultEnabled'`

- [ ] **Step 1: Add annotation metadata**

```kotlin
annotation class Feature(
    val name: String,
    val categories: Array<String>,
    val description: String = "",
    val defaultEnabled: Boolean = false,
)
```

Move each current `override val defaultEnabled = true` into `@Feature(defaultEnabled = true)` and remove the override. Keep explicit false overrides removed because false is the annotation default.

- [ ] **Step 2: Add descriptor and registry**

```kotlin
package com.Johnny.wcx.features.core

class FeatureDescriptor(
    val id: String,
    val name: String,
    val categories: List<String>,
    val description: String,
    val isApi: Boolean,
    val defaultEnabled: Boolean,
    val hasDexResolver: Boolean,
    private val factory: () -> BaseFeature,
) {
    fun instance(): BaseFeature = FeatureRegistry.instance(this, factory)
}
```

```kotlin
package com.Johnny.wcx.features.core

import java.util.concurrent.ConcurrentHashMap

object FeatureRegistry {
    private val instances = ConcurrentHashMap<String, BaseFeature>()

    internal fun instance(
        descriptor: FeatureDescriptor,
        factory: () -> BaseFeature,
    ): BaseFeature = instances.computeIfAbsent(descriptor.id) {
        factory().apply {
            technicalId = descriptor.id
            name = descriptor.name
            categories = descriptor.categories
            description = descriptor.description
            startupDefaultEnabled = descriptor.defaultEnabled
        }
    }

    fun instantiated(): List<BaseFeature> = instances.values.toList()
}
```

Add `internal var startupDefaultEnabled = false` to `BaseFeature`. Change `SwitchFeature.defaultEnabled` to return `startupDefaultEnabled` and remove per-feature overrides migrated to annotations.

- [ ] **Step 3: Change KSP output**

Generate:

```kotlin
val ALL_FEATURES: List<FeatureDescriptor> = listOf(
    FeatureDescriptor(
        id = "com.Johnny.wcx.features.items.chat.Example",
        name = "Example",
        categories = listOf("聊天"),
        description = "...",
        isApi = false,
        defaultEnabled = false,
        hasDexResolver = true,
        factory = { Example },
    ),
)

val ALL_HOOK_ITEMS: List<BaseFeature>
    get() = ALL_FEATURES.map(FeatureDescriptor::instance)
```

Determine `isApi` and `hasDexResolver` through KSP supertype traversal, not class-name string matching.

- [ ] **Step 4: Build generated sources and inspect laziness**

```bash
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a
ssh debian13 'cd /root/wcx && grep -n "val ALL_FEATURES\|factory =" app/build/generated/ksp/standardRelease/kotlin/com/Johnny/wcx/features/core/FeaturesProvider.kt | head -20'
```

Expected: feature objects appear only inside `factory = { ... }`, not as immediate `listOf(Foo.apply { ... })` entries.

- [ ] **Step 5: Commit**

```bash
git add libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/core/Feature.kt \
  libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features/FeaturesScanner.kt \
  app/src/main/java/com/Johnny/wcx/features/core/FeatureDescriptor.kt \
  app/src/main/java/com/Johnny/wcx/features/core/FeatureRegistry.kt \
  app/src/main/java/com/Johnny/wcx/features/core/BaseFeature.kt \
  app/src/main/java/com/Johnny/wcx/features/core/SwitchFeature.kt \
  app/src/main/java/com/Johnny/wcx/features
git commit -m "refactor(features): generate lazy feature descriptors"
```

---

### Task 11: Make `FeaturesLoader` instantiate only required startup features

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/features/core/FeaturesLoader.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/features/core/FeatureSwitchSnapshot.kt`
- Modify: call sites that require all instances only when settings/diagnostics opens.

- [ ] **Step 1: Expose the enabled snapshot as a set**

Add:

```kotlin
fun enabledNames(): Set<String> {
    ensureLoaded()
    return snapshot
}
```

Return a copied immutable set if later code mutates the result.

- [ ] **Step 2: Select descriptors before instantiation**

At the start of `loadFeatures`, use `FeaturesProvider.ALL_FEATURES`. Define:

```kotlin
private fun FeatureDescriptor.enabledBeforePrefsReady(snapshot: Set<String>): Boolean =
    isApi || defaultEnabled || name in snapshot || WePrefs.getBoolOrDef(name, defaultEnabled)
```

Build early descriptors from existing category/name rules and `enabledBeforePrefsReady`. Instantiate only selected early descriptors before the first frame.

- [ ] **Step 3: Defer remaining descriptor instantiation**

After the first frame, evaluate the remaining descriptors against real preferences and instantiate only enabled features plus descriptors required for Dex repair. Opening settings or diagnostics may call `ALL_HOOK_ITEMS` and instantiate the rest; normal startup must not.

Keep these semantics:

- incomplete Dex cache skips the affected feature;
- `WeSettingsInjector` remains available;
- `StartupCrashGuard.scheduleHealthyReset()` remains after early startup completion;
- feature preference recheck remains active;
- `HookEnumCollector` works per batch.

- [ ] **Step 4: Add count/timing evidence**

Log one line per batch:

```text
FeatureRegistry: descriptors=281 instantiated=<n> early=<n> deferred=<n>
```

Use it in `StartupPerformance` to prove the early instance count falls.

- [ ] **Step 5: Build and compare generated report**

Expected: package contracts pass; release build succeeds; no feature behavior is removed. Device acceptance later requires the enabled feature count to match the stable baseline for the same preferences.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/Johnny/wcx/features/core/FeaturesLoader.kt \
  app/src/main/java/com/Johnny/wcx/features/core/FeatureSwitchSnapshot.kt \
  app/src/main/java/com/Johnny/wcx/activity/settings \
  app/src/main/java/com/Johnny/wcx/features/core
git commit -m "perf(features): instantiate hooks on demand"
```

---

### Task 12: Defer `ParcelableFixer` ART hooks until module Activity launch

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ParcelableFixer.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt`

- [ ] **Step 1: Split classloader setup from hook installation**

Expose:

```kotlin
fun prepareClassLoader()
fun ensureIntentHooksInstalled()
```

Both methods must be synchronized and idempotent. `prepareClassLoader` creates only `hybridClassLoader`; `ensureIntentHooksInstalled` calls `prepareClassLoader` and installs the existing eight hooks.

- [ ] **Step 2: Change startup to classloader-only preparation**

Replace:

```kotlin
ParcelableFixer.init()
```

with:

```kotlin
ParcelableFixer.prepareClassLoader()
```

Keep the phase timing name distinct: `ParcelableClassLoader`.

- [ ] **Step 3: Install hooks at the proxy boundary**

In `IActivityManagerHandler.createTokenWrapper`, before storing the token:

```kotlin
ParcelableFixer.ensureIntentHooksInstalled()
```

In `ProxyInstrumentation.newActivity`, call the same method only when the resolved class is a module Activity. This is the fallback for framework variants that bypass the ActivityManager proxy.

- [ ] **Step 4: Build and run module Activity smoke test**

Verify settings and transparent module activities open with Parcelable extras. Persisted logs must show the Intent hooks install only when the first module Activity is opened, not during ordinary WeChat startup.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/Johnny/wcx/loader/utils/ParcelableFixer.kt \
  app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt \
  app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt
git commit -m "perf(startup): defer Parcelable Intent hooks"
```

---

### Task 13: Split core and media Rust JNI libraries

**Files:**
- Modify: `Cargo.toml`
- Create: `app/src/main/rust/wekit-media/Cargo.toml`
- Create: `app/src/main/rust/wekit-media/build.rs`
- Create: `app/src/main/rust/wekit-media/src/lib.rs`
- Move: `audio_utils.rs`, `telegram_sticker.rs`, media-specific helpers, and `include/wekit_vpx_decoder.c` from `wekit-native` to `wekit-media`
- Modify: `app/src/main/rust/wekit-native/Cargo.toml`
- Modify: `app/src/main/rust/wekit-native/build.rs`
- Modify: `app/src/main/rust/wekit-native/src/lib.rs`
- Modify: `xtask/src/main.rs`

- [ ] **Step 1: Add the media crate with only media dependencies**

Use:

```toml
[package]
name = "wekit-media"
version = "0.1.0"
edition = "2024"

[lib]
name = "wekit_media"
crate-type = ["cdylib"]

[dependencies]
jni = { version = "0.22", default-features = false }
libc = "0.2"
markdown = "1"
silk-v3-sys = { git = "https://ghproxy.net/https://github.com/Ujhhgtg/silk-v3-sys.git" }
symphonia = { version = "0.6", features = ["aac", "flac", "isomp4", "mkv", "mp3", "ogg", "opt-simd", "pcm", "wav"], default-features = false }
mp3lame-encoder = "0.2"
anyhow = "1"
sha2 = { version = "0.11", default-features = false }
flate2 = { version = "1", default-features = false, features = ["rust_backend"] }
rasterlottie = { version = "0.2", default-features = false, features = ["images", "text"] }
gif = "0.14"
webm-iterable = "0.6"
```

Move media JNI exports unchanged so Java signatures remain identical. Move the libvpx download/configure/build logic and `wekit_vpx_decoder.c` into `wekit-media/build.rs`; include `sha2`, `cc` and `pkg-config` as media build dependencies.

- [ ] **Step 2: Reduce `wekit-native` to core dependencies**

Retain crash handler, native hook, signature verifier, logging, utils and optional WeKite hook. Remove all media dependencies from `wekit-native/Cargo.toml`. Reduce `wekit-native/build.rs` to native-hook bindgen and Android `log`/`dl`/`unwind` linkage; it must no longer download or compile libvpx.

- [ ] **Step 3: Build both libraries in xtask**

Refactor native build into a helper that builds and copies:

```text
wekit-native -> libwekit_native.so
wekit-media  -> libwekit_media.so
```

Generate the same Android cargo config for both crate working directories, or run both builds from a shared configured workspace directory. Preserve `--wekite-hook` only for `wekit-native`.

- [ ] **Step 4: Run native verification**

```bash
ssh debian13 'cd /root/wcx && . /root/wcx-env.sh && ./x configure && cargo test -p wekite-art && ./x build --native-only --release --abi arm64-v8a'
```

Expected: both `.so` files exist in `app/src/main/jniLibs/arm64-v8a/`; `libwekit_native.so` is materially smaller than 3.2 MB.

- [ ] **Step 5: Commit**

```bash
git add Cargo.toml app/src/main/rust/wekit-native app/src/main/rust/wekit-media xtask/src/main.rs app/src/main/jniLibs
git commit -m "refactor(native): split media JNI from startup core"
```

---

### Task 14: Load native capabilities on demand and remove duplicate Silk

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ZygiskNativePayload.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/utils/AudioUtils.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/utils/TelegramStickerConverter.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/features/items/chat/MarkdownRendering.kt`
- Modify: Hchat Silk loader/callers identified by `git grep 'silk_codec\|me.yun.silk'`
- Modify: `app/build.gradle.kts` or native packaging inputs after consolidation.

- [ ] **Step 1: Add a capability state machine**

In `NativeLoader` add:

```kotlin
private enum class Capability { CORE, DEXKIT, MEDIA, GRAPHICS_PATH }
private val loadedCapabilities = java.util.EnumSet.noneOf(Capability::class.java)

fun ensureMediaLoaded() = synchronized(nativeLoadLock) {
    ensureCoreLoaded()
    if (loadedCapabilities.add(Capability.MEDIA)) {
        loadPackagedLibrary("wekit_media")
    }
}
```

Implement equivalent `ensureDexKitLoaded` and `ensureGraphicsPathLoaded`. A failed load must remove the capability from the set before rethrowing so a later retry is possible.

- [ ] **Step 2: Make startup load only core and MMKV**

`NativeLoader.init` loads `wekit_native` and initializes MMKV. It must not load DexKit, Silk/media or graphics-path. `ZygiskNativePayload.loadLibraries` extracts/caches available libraries but only calls `System.load` for core.

- [ ] **Step 3: Guard every deferred JNI call**

Before native media calls:

```kotlin
NativeLoader.ensureMediaLoaded()
```

Before DexKit bridge creation, call `ensureDexKitLoaded`. Before the first graphics-path consumer, call `ensureGraphicsPathLoaded`.

- [ ] **Step 4: Consolidate Silk implementation**

Choose `wekit_media` as the single packaged Silk implementation. Redirect Hchat Silk API wrappers to the existing WCX JNI facade while preserving their Java/Kotlin public signatures. Remove `libsilk_codec.so` only after:

- audio-to-Silk succeeds;
- Silk-to-PCM succeeds;
- Hchat voice forwarding succeeds;
- script ABI class `me.yun.silk.*` still resolves and delegates correctly.

- [ ] **Step 5: Verify startup mappings and media first use**

Before using media features, `/proc/<pid>/maps` must not contain `libwekit_media.so`. After the first Markdown native render, audio conversion or Telegram sticker conversion, it must appear exactly once and the operation must succeed.

- [ ] **Step 6: Build matrix and report sizes**

```bash
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh release --abi arm64-v8a --wekite-hook
ssh debian13 'cd /root/wcx && python3 scripts/wcx_apk_report.py --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk --budget config/wcx-performance-budget.json --mapping app/build/outputs/mapping/standardRelease --output /tmp/final-size.json'
```

Expected: package budget passes; core library decreases; total native bytes do not exceed 5.5 MB and should decrease after duplicate Silk removal.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt \
  app/src/main/java/com/Johnny/wcx/loader/utils/ZygiskNativePayload.kt \
  app/src/main/java/com/Johnny/wcx/utils/AudioUtils.kt \
  app/src/main/java/com/Johnny/wcx/utils/TelegramStickerConverter.kt \
  app/src/main/java/com/Johnny/wcx/features/items/chat/MarkdownRendering.kt \
  app/src/main/java/h/Hchat app/build.gradle.kts app/src/main/jniLibs \
  docs/work-items/wcx-performance-package-size.md
git commit -m "perf(native): load media and DexKit on demand"
```

---

### Task 15: Run the full static and VM acceptance matrix

**Files:**
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Verify clean provenance**

```bash
git status --short
git rev-parse HEAD
```

Expected: clean worktree before syncing to VM.

- [ ] **Step 2: Run host-side tests and native tests**

```bash
ssh debian13 'cd /root/wcx && . /root/wcx-env.sh && python3 -m unittest scripts.tests.test_wcx_apk_report -v && cargo test -p wekite-art'
```

Expected: all tests pass.

- [ ] **Step 3: Build standard/legacy and baseline/WeKite variants**

```bash
ssh debian13 'cd /root/wcx && . /root/wcx-env.sh && ./x build --release --abi arm64-v8a && ./x build --release --abi arm64-v8a --wekite-hook && ./gradlew assembleLegacyRelease -Pwekit.abi=arm64-v8a'
```

Run armeabi-v7a release after arm64 passes:

```bash
ssh debian13 'cd /root/wcx && . /root/wcx-env.sh && ./x build --release --abi armeabi-v7a'
```

- [ ] **Step 4: Run package and dynamic ABI gates**

```bash
ssh debian13 'cd /root/wcx && scripts/wcx-minified-contract.sh --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk --mapping app/build/outputs/mapping/standardRelease --contracts scripts/wcx-minified-contracts.txt && python3 scripts/wcx_apk_report.py --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk --budget config/wcx-performance-budget.json --mapping app/build/outputs/mapping/standardRelease --output /tmp/final-report.json'
```

Expected: both commands exit `0`.

- [ ] **Step 5: Record exact artifact hashes and metrics**

Record APK SHA-256, APK/DEX/native sizes, package top 20, native sections and VM command outputs in the work item.

- [ ] **Step 6: Commit evidence**

```bash
git add docs/work-items/wcx-performance-package-size.md
git commit -m "docs(perf): record static optimization matrix"
```

---

### Task 16: Run guarded device acceptance after stable baseline recovery

**Files:**
- Modify: `docs/work-items/wcx-performance-package-size.md`

- [ ] **Step 1: Confirm device safety preconditions**

Read-only checks must confirm:

- stable module APK is installed before testing;
- injector MD5 remains `93dcbeea7a5ea0075c36c315696be62f` unless a separately approved injector change exists;
- `debug.wcx.wekite_m2b` and `debug.wcx.wekite_selftest` are absent;
- no framework, zygote or phone reboot is planned.

- [ ] **Step 2: Re-establish the stable health baseline**

Run the existing stable APK boot/direct health probe. If `startup_gate_not_triggered` reproduces, perform only the previously allowed narrowed retry and stop on a second failure. Do not deploy the optimization APK until the stable baseline passes.

- [ ] **Step 3: Deploy the optimization APK without replacing the injector**

Back up the stable APK, replace only `/data/adb/modules/zygisk-wcx/module.apk`, verify its SHA-256, and keep the injector MD5 unchanged.

- [ ] **Step 4: Run boot/direct and feature acceptance**

Run:

- boot 6/6;
- direct 6/6;
- settings/module Activity Parcelable smoke;
- Hchat/BeanShell smoke;
- DexKit cache hit and repair smoke;
- MCP/Ktor, map, Markdown modes, audio/Silk and sticker conversion samples;
- `:push` and `:appbrand*` process checks.

Require unexpected WCX E=0 and no new Java/native crash, reason=5, status=6/11 or recovery.

- [ ] **Step 5: Compare performance distributions**

Use `--summarize-startup` to compare baseline and optimized P50/P95. Acceptance requires:

```text
optimized_p50 <= baseline_p50 * 0.75
optimized_p95 <= baseline_p95
```

Also verify early instantiated feature count decreases and enabled feature count remains equal for the same preferences.

- [ ] **Step 6: Restore safe final device state**

After testing, restore the stable APK unless the user explicitly approves keeping the optimized build. Remove test properties and verify the injector MD5.

- [ ] **Step 7: Commit device evidence**

```bash
git add docs/work-items/wcx-performance-package-size.md
git commit -m "docs(perf): record device performance acceptance"
```

---

## Final completion criteria

The optimization is complete only when all conditions hold:

- arm64 full Zygisk APK is at most 36,000,000 bytes;
- DEX total is at most 25,500,000 bytes;
- all minified dynamic ABI contracts pass;
- standard/legacy and arm64/v7 builds pass;
- baseline and `--wekite-hook` builds pass;
- main-process WCX startup P50 improves by at least 25%;
- P95 does not regress;
- all existing feature families remain available;
- unexpected WCX E=0 in the acceptance window;
- stable device state is restored or explicitly approved.
