# WCX Zygisk Multi-Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated ARM64 Zygisk WCX deployment for Magisk, KernelSU and APatch, covering enabled WeChat instances and their child processes, with the user-approved WeKit runtime including narrowly scoped native-library map remapping.

**Architecture:** Pin and audit WeKit's Zygisk runtime, then adapt its lifecycle, target allow-list companion, payload/DEX bootstrap and LSPlant bridge to WCX's existing `ILoaderService`/`IHookBridge` and shared startup lifecycle. Build a Zygisk-only ARM64 payload and manager-specific module archives. Magisk uses fixed `com.tencent.mm`; KernelSU/APatch expose per-Android-user package selection. Migrate `so_hider` only for exact WCX runtime mappings and test it separately. Do not migrate Telegram root companion/data access.

**Tech Stack:** Kotlin/Java, AGP/Gradle, Rust, Android NDK/CMake, public Zygisk API, pinned LSPlant/ART backend, Python 3 standard library, KernelSU/APatch WebUI APIs.

**Design:** [WCX Zygisk 多管理器设计](../specs/2026-11-27-wcx-zygisk-design.md)

---

## File Boundaries

| File or directory | Responsibility |
|---|---|
| `zygisk/backend/` | Pinned upstream runtime components actually selected from WeKit `b51532bf3427348af824d7d704e55081fac9ca55`, license/provenance and WCX patch log. Exclude Telegram data companion code; companion allow-list protocol is permitted. |
| `zygisk/native/` | WCX process lifecycle, target filtering, APK/DEX bootstrap, ART integration and exact-path `so_hider` adaptation. Keep public Zygisk API symbols and Android-specific build files isolated. |
| `app/src/zygisk/` | Zygisk-only manifest, resources and Java/Kotlin loader service, hook bridge and app entry. |
| `app/src/main/java/com/Johnny/wcx/loader/startup/` | Minimal framework-neutral startup extraction; existing Xposed wrapper behavior remains unchanged. |
| `app/build.gradle.kts` | Explicit opt-in ARM64 payload variant and entry/resource isolation. |
| `zygisk/module/{magisk,kernelsu,apatch}/` | Manager-specific installer/uninstaller metadata and WebUI integrations. |
| `zygisk/config/` | Versioned target-config schema and shared fixtures. |
| `zygisk/package_zip.py`, `zygisk/tests/` | Explicit-input pack/verify command and negative tests. Probe and release profiles stay separate. |
| `zygisk/README.md` | Build, manager support, target behavior, `so_hider` disclosure, risk, install/rollback and evidence. |
| `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md` | Backend provenance, exact upstream behavior, security-relevant operations and test evidence. |

## Task 1: Upstream Runtime Audit and Import Inventory

**Files:**
- Modify: `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md`
- Create: `zygisk/backend/README.md`
- No behavior implementation until the imported modules and effects are recorded.

- [ ] **Step 1: Pin sources and licenses.** Record the selected WeKit immutable commit, all runtime submodules/dependencies, exact licenses and notices. The reference snapshot currently inspected is `b51532bf3427348af824d7d704e55081fac9ca55`; re-check the actual clone before copying. Do not use bootstrap APKs or artifacts with unverifiable source.
- [ ] **Step 2: Inventory native modules.** Trace `zygisk_module_entry`, specialize callbacks, companion protocol, target allow-list, payload publication, `InMemoryDexClassLoader`, JNI registration, LSPlant init/trust, map hiding and pre-server dlclose. Mark Telegram database discovery/copy APIs as excluded. Confirm there are no implicit shell commands or unrelated cross-app reads in the selected code.
- [ ] **Step 3: Inventory manager template effects.** Inspect `customize.sh`, `config.sh`, `action.sh`, `uninstall.sh`, WebUI JS/API calls, module metadata and `sepolicy.rule`. Record filesystem paths changed, shell commands invoked, manager API permissions, logging destinations and cleanup behavior. Preserve the empty SELinux policy unless separately justified and approved.
- [ ] **Step 4: Audit `so_hider` in isolation.** Trace `/proc/self/maps` matching, memfd allocation/copy, `mprotect`, `MAP_FIXED`, original permissions, failure handling, repeated invocation and mapping lifetime. Define exact allowed paths for the WCX Zygisk loader/runtime libraries and payload libraries; no substring match broad enough to touch unrelated mappings. Decide a config switch and default only after the observable behavior is demonstrated on a self-owned process.
- [ ] **Step 5: Write the import/patch matrix.** For every imported module list upstream path/revision/license, imported symbols/files, WCX changes, excluded code and security/compatibility effects. Stop if source provenance or behavior cannot be established.
- [ ] **Step 6: Validate the audit record.** Run `git diff --check`; ensure the report separates code inspection from device evidence and does not claim WCX runtime acceptance.

## Task 2: Validate ART/DEX Backend and `so_hider` on Owned Release Test Apps

**Files:**
- Modify: `zygisk/independent-hook-probe/` only if needed for a distinct release profile
- Create: `zygisk/backend/tests/` and test-only harness/build files
- Modify: `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md`

- [ ] **Step 1: Create release-mode backend fixture.** Build a self-owned non-debuggable ARM64 test APK and dedicated test module using pinned sources. Keep package/module IDs separate from WCX. Record APK signature, manifest, toolchain versions and SHA-256.
- [ ] **Step 2: Validate ART/DEX bootstrap.** On a self-owned ARM64 release process, verify exact LSPlant initialization and DEX trust sequence as implemented by the pinned upstream. Capture every required hidden API/ART operation; do not replace missing dependencies with unexplained system changes.
- [ ] **Step 3: Validate hook semantics.** Test ordinary/static/instance methods, constructors, priority and before/after order, argument/result mutation, original method invocation, original exceptions, callback unregistration and callback-free behavior. Any backend contract gap must be recorded and adapted before WCX startup integration.
- [ ] **Step 4: Test `so_hider` using a controlled mapping fixture.** With the module off/on, capture `/proc/self/maps` for only the fixture's known library path. Verify intended path change, unrelated mappings unchanged, readable/executable/writable permissions preserved, a second invocation does not corrupt mappings, and injected failures do not leave altered permissions or unmapped memory. Do not target system libraries or other apps.
- [ ] **Step 5: Decide whether implementation can proceed.** If release startup or any critical hook behavior fails, stop and report the precise failure. Do not make a WCX ZIP. If it passes, save reproducible commands/log summaries and continue.

## Task 3: Isolate WCX Startup and Implement the Zygisk Bridge

**Files:**
- Modify: `app/build.gradle.kts`
- Modify only as necessary: `app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt`, `XposedStartup.kt`, `WeLauncher.kt`, `UnifiedEntryPoint.kt`, `ModuleLoadStatus.kt`
- Create: `app/src/zygisk/AndroidManifest.xml`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskLoaderService.kt`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskHookBridge.kt`
- Modify: `app/proguard-rules.pro` only for JNI/entry symbols required at runtime.

- [ ] **Step 1: Capture ordinary-variant baselines.** On the supported build host, run `./x build --native-only` and `./gradlew :app:assembleStandardRelease :app:assembleLegacyRelease --no-daemon --max-workers=2`. Save task list, APK paths, badging, signing certificate fingerprints, ABI/library inventory and Xposed entry inventory with the base commit. Never put signing credentials in the repository or logs.
- [ ] **Step 2: Add opt-in variant configuration.** Gate a `zygisk` flavor/source set on an explicit Gradle property, restrict it to `arm64-v8a`, and keep ordinary `standard`/`legacy` task graph unchanged when the property is absent. Adjust entry metadata only if merged APK inspection shows the Zygisk APK receives Xposed declarations; preserve ordinary flavor contents.
- [ ] **Step 3: Add a failing variant-isolation test.** Add a repository-local check that fails if Zygisk tasks/entries appear without the property, or if Zygisk output includes `assets/xposed_init` or `META-INF/xposed/`. Verify it rejects a deliberately malformed temporary inventory before implementing the variant change.
- [ ] **Step 4: Split framework-neutral and Xposed startup.** Keep `StartupAgent.prepare` and legitimate host setup shared. Keep HiddenApiBypass and `app_qqprotect` deletion in the existing Xposed path only; create an explicit Zygisk startup that does not accidentally invoke the Xposed wrapper. Preserve current `ModuleLoader` behavior for Xposed variants.
- [ ] **Step 5: Implement Zygisk loader service.** Provide stable WCX version/module path/class-loader helper/logging/extension behavior via `ILoaderService`. Logging must work without `XposedBridge`.
- [ ] **Step 6: Implement the bridge using the tested backend.** Implement every `IHookBridge` member and accurately report deoptimization support. Preserve callback ordering, handles, constructor behavior and original invocation. Never package compile-only Xposed stubs as a runtime backend.
- [ ] **Step 7: Connect native bootstrap to WCX lifecycle.** Validate payload APK, build module class loader, initialize backend and register JNI before invoking the app entry. Pass process name, host data dir and APK path explicitly. Only start WCX after both loader service and bridge are usable; failure must update staged status and never report `LOADED`.
- [ ] **Step 8: Verify on the release fixture.** Run Task 2 contract against the actual packaged entry/bridge. Confirm `ModuleLoadStatus` reports success/failure accurately and standard/legacy behavior is unchanged.

## Task 4: Targets, Companion Allow-List, Manager Templates and WebUI

**Files:**
- Create: `zygisk/config/targets.schema.json` and shared parser/test fixtures
- Create: `zygisk/module/{magisk,kernelsu,apatch}/` manager-specific files
- Create: `zygisk/module/{kernelsu,apatch}/webroot/`
- Modify: `zygisk/native/` target and companion protocol
- Add tests under `zygisk/tests/`

- [ ] **Step 1: Define versioned target data.** Key rows by Android user ID plus exact installed WeChat package name; include enabled state and schema version. New rows default off. Refresh preserves switches for surviving rows and removes uninstalled rows. Magisk is fixed to `com.tencent.mm`. Specify atomic write, permissions, migration and malformed-file behavior.
- [ ] **Step 2: Write config tests first.** Cover empty/missing config, current schema, schema migration, duplicate user/package rows, invalid IDs, invalid WeChat package names, malformed enable values, unsupported version, and atomic publication failure. Run and observe expected failures before parser code.
- [ ] **Step 3: Implement strict process matching.** Main process matches `processName == package`; a child process matches only `processName.startsWith(package + ":")`. Package must be an enabled configured WeChat package. Reject lookalike prefixes, all other applications and system server. Derive Android user from trusted UID data and verify it against the configured target.
- [ ] **Step 4: Implement allow-list companion IPC.** Companion handles only target-enabled query and configuration lifecycle required by the manager. Bound request lengths, validate UID/package/process, use a fixed protocol, fail closed, and expose no arbitrary file copy, command execution or Telegram operations. Test malformed/short/oversized requests and disabled target responses.
- [ ] **Step 5: Implement KernelSU UI/API.** Use documented KernelSU APIs to enumerate Android users/packages. Provide refresh, toggle and status; new targets off; preserve surviving switches; safely persist shared config. Test multiple users, empty enumeration, failed scan, refresh merge and process-state display.
- [ ] **Step 6: Implement APatch UI/API separately.** Use APatch's documented interface and add adapter tests; do not assume KernelSU bridge compatibility. Match the same config semantics and fail closed if user identity/package listing is unavailable.
- [ ] **Step 7: Implement Magisk fixed-target configuration.** No fake WebUI. Target only `com.tencent.mm` and its `:...` subprocesses; module-private config may disable injection but cannot broaden targets.
- [ ] **Step 8: Add install/uninstall templates.** Validate manager, ARM64, Zygisk enabled, expected payload files and module identity. Preserve manager-owned lifecycle semantics. Never alter host app data or add SELinux allow rules. Uninstall removes only WCX module files/config.

## Task 5: Integrate Scoped `so_hider`, Payload, and ZIPs

**Files:**
- Create/import: `zygisk/native/src/so_hider.*` with explicit WCX path allow-list and tests
- Modify: `app/build.gradle.kts`, `app/src/zygisk/`, native build files
- Modify: `zygisk/package_zip.py`, `zygisk/tests/`, `zygisk/README.md`

- [ ] **Step 1: Test-first scoped mapping selector.** Add parser/selector tests from recorded `/proc/self/maps` fixtures. Require exact canonical path matching for only selected WCX runtime libraries; test similarly named unrelated libraries, deleted suffixes, spaces and anonymous mappings. Confirm tests fail before implementation.
- [ ] **Step 2: Implement mapping remap with opt-in config.** Preserve each segment's original protection and range; do not use a broad substring. On any remap/protection failure, log mapping identity and stop hiding that mapping without claiming success. Config must expose the approved default and disable path. Do not hide unrelated libraries or attempt to evade external security tools beyond the explicit approved scope.
- [ ] **Step 3: Run isolated native tests.** Run upstream relocation and `so_hider` unit tests on host; run Android release fixture from Task 2. Verify map changes, unrelated maps unchanged, permission preservation, repeated-call behavior and process stability. Save before/after evidence with sensitive paths redacted.
- [ ] **Step 4: Build opt-in WCX payload.** Build exactly one signed `com.Johnny.wcx` ARM64 payload. Verify WCX resources/native libraries, Zygisk-only entry, no `assets/xposed_init`, no `META-INF/xposed`, and no non-ARM64 libraries.
- [ ] **Step 5: Build and verify AArch64 loader.** Use pinned source and repository-declared NDK/Rust/CMake versions. Use NDK `llvm-readelf` to verify ELF64/AArch64 shared object and expected Zygisk exports; record upstream revision/license and SHA-256.
- [ ] **Step 6: Extend explicit archive packer.** Require explicit payload/loader/output and manager profile. Validate module metadata, payload package/version/signature/ABI, loader ELF/exports, Xposed metadata absence, archive paths, duplicate entries and symlinks. Build to a same-directory temporary file and atomically replace only after CRC and manifest validation. Keep `pack-probe` isolated from release mode.
- [ ] **Step 7: Test archive failures.** For Magisk, KernelSU and APatch profiles test missing loader, bad ELF, wrong architecture, missing payload, Xposed metadata, version mismatch, malformed metadata, path traversal, duplicate path and symlink. Confirm nonzero exit and existing output remains byte-for-byte unchanged.
- [ ] **Step 8: Package each manager ZIP.** Generate distinct archives and run profile verifier for all three. Record commit, dependency revisions, APK/loader/ZIP hashes and build versions.

## Task 6: Build Regression and Device Matrix

**Files:**
- Modify: `zygisk/README.md`
- Modify: `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md`

- [ ] **Step 1: Rebuild ordinary variants.** Without the Zygisk property run `./gradlew :app:assembleStandardRelease :app:assembleLegacyRelease --no-daemon --max-workers=2`. Compare to Task 3 baseline: tasks, package/version, signature fingerprint, ABI/library inventory and Xposed entry declarations. Fix any unintended difference before devices.
- [ ] **Step 2: Verify no stale artifact selection.** Use missing and mismatched explicit paths in a temporary directory. Confirm pack/build fails without globbing `app/build/outputs` or using historical artifacts; existing output is preserved.
- [ ] **Step 3: Prepare independent device records.** For each manager record device ABI, Android/API, manager/Zygisk version, WCX commit and WeChat version. Mark scope versions accurately; do not infer cross-manager compatibility.
- [ ] **Step 4: Validate Magisk.** With operator consent install Magisk ZIP, disable WCX LSPosed for the same instance, reboot as required, verify `com.tencent.mm` main process and child processes load, other packages do not, settings open, then disable/uninstall/reboot and confirm rollback.
- [ ] **Step 5: Validate KernelSU and APatch separately.** Install each manager ZIP; refresh users/packages; verify new targets default off and toggles persist; enable one instance and verify main plus `:...` processes load while other instances/apps do not; verify settings page; test disable/uninstall/reboot rollback.
- [ ] **Step 6: Validate `so_hider` and failure paths on device.** Compare enabled/disabled mapping state for exact WCX libraries, verify unrelated mappings and process stability, and test backend/DEX/config failure stage reporting. Confirm no false loaded state and no target leakage.
- [ ] **Step 7: Publish evidence-qualified support matrix.** Mark each manager `built`, `archive-verified`, `installed`, `runtime-verified` or `not-tested`. Include versions, main/child-process range, `so_hider` behavior, limitations and LSPosed conflict. Do not use the smoke probe as WCX runtime evidence.
- [ ] **Step 8: Final verification.** Run native tests, config/packer tests, all archive profile checks, ordinary APK comparison and `git diff --check`. State any unrun hardware validation explicitly; do not claim support without evidence.

## Plan Self-Review

- **Design coverage:** pinned upstream runtime, full WeChat process family matching, manager-specific UI/installer, fixed Magisk target, scoped `so_hider`, ART/DEX initialization, WCX bridge, build isolation and per-manager device evidence each have tasks.
- **Explicit exclusions:** Telegram companion/data reads, arbitrary file/command IPC, broad SELinux rules, non-WeChat processes, automatic flashing/release and non-ARM64 payload remain excluded.
- **Security-relevant changed scope:** user explicitly approved ART/DEX trust bootstrap and exact WCX library map remapping. The plan still bounds map selection, tests protection/failure semantics and prohibits expanding to unrelated mappings.
- **No placeholders:** tests, commands, files, expected negative behavior and stop conditions are stated. The exact upstream dependency graph is selected only after Task 1 audits the pinned tree.
