# WCX Zygisk Multi-Manager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an isolated ARM64 Zygisk WCX deployment with an audited ART hook bridge and Magisk, KernelSU, and APatch module packages, without changing standard/legacy APK behavior.

**Architecture:** Build a Zygisk-only ARM64 payload and native loader that adapts to WCX's existing `ILoaderService`/`IHookBridge` and shared startup lifecycle. Use the public Zygisk API for process selection and a fixed, licensed ART hook backend only if its release-process initialization passes the no-bypass gate. Package manager-specific installers around one versioned target configuration model: Magisk defaults to the WeChat main process; KernelSU/APatch expose per-Android-user and per-package WebUI selection.

**Tech Stack:** Kotlin/Java, AGP/Gradle, Android NDK/CMake, Rust where required by the selected upstream backend, public Zygisk API, LSPlant if the backend gate passes, Python 3 standard library for archive assembly and validation, KernelSU/APatch WebUI APIs.

**Design:** [WCX Zygisk 多管理器设计](../specs/2026-11-27-wcx-zygisk-design.md)

---

## Scope Gate and File Boundaries

Implementation must stop before adding a WCX runtime backend or declaring a usable WCX ZIP unless all of these are satisfied on a self-owned, non-debuggable test APK and the pinned sources are auditable:

- The backend initializes with a release APK without hidden-API exemptions, trusted-DEX bypass, deleting host protection data, changing system policy, or using an unreviewed binary.
- It supports the required method/constructor hook contract: callback order/priority, argument and result mutation, original invocation, exception behavior, and callback unregistration.
- Its source, immutable upstream revision, license, build inputs, and WCX-specific patches are recorded.
- Failure of the gate is a stop condition. Record the concrete evidence in the backend report and return for a design decision; do not ship a probe, empty bridge, or installable-but-nonfunctional WCX payload as a successful result.

Expected ownership boundaries:

| File or directory | Responsibility |
|---|---|
| `zygisk/backend/` or `zygisk/native/` | Pinned public Zygisk API and only the native loader/backend code actually needed by WCX; separate licenses and provenance records. Choose one layout after examining the accepted upstream tree; do not copy unrelated runtime/UI code. |
| `app/src/zygisk/` | Zygisk-only manifest, resources and app entry/bridge implementation; excluded from standard/legacy. |
| `app/src/main/java/com/Johnny/wcx/loader/startup/` | Only minimal shared startup extraction needed to separate framework-neutral host initialization from Xposed-only compatibility and feature startup. Existing Xposed entry behavior must remain unchanged. |
| `app/build.gradle.kts` | Opt-in ARM64 Zygisk variant and source/resource isolation. No Zygisk task/entry is added to ordinary builds unless explicitly enabled. |
| `zygisk/module/{magisk,kernelsu,apatch}/` | Manager-specific install/uninstall metadata and scripts; WebUI files only for KernelSU/APatch. Shared payload/config contract is not duplicated across managers. |
| `zygisk/package_zip.py` | Explicit-input packaging and static verification; no implicit artifact discovery, install, or publish behavior. Extend existing probe-safe verifier without making probe packages count as releases. |
| `zygisk/README.md` | Build instructions, support matrix, configuration behavior, security boundary, install/rollback and evidence status. |
| `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md` | Evidence and go/no-go outcome for runtime backend candidates. |

## Task 1: Backend Release-Process Go/No-Go

**Files:**
- Modify: `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md`
- Create only if a candidate passes source review: `zygisk/backend/README.md`
- No WCX startup or module-package code changes in this task.

- [ ] **Step 1: Freeze one candidate's provenance.** Record exact repository URL, immutable commit/tag, license, transitive native dependencies, and hashes for downloaded artifacts. Compare the selected source revision to current WeKit and WeKite implementations; do not infer provenance from an APK.
- [ ] **Step 2: Audit its initialization requirements.** Trace Zygisk `postAppSpecialize` through native initialization, ART symbol resolution, code patch installation, DEX trust handling, JNI registration and Java entry. Mark each operation that uses hidden APIs, exemptions, trusted-DEX changes, protection-directory deletion or system-policy changes. Reject candidates requiring a prohibited operation.
- [ ] **Step 3: Specify the owned release test.** Use `zygisk/independent-hook-probe/` only as a starting point. Create a separate non-debuggable test APK and module profile; test method and constructor callbacks, priority/order, argument/result changes, original invocation, exception propagation, callback unregistration and no-callback behavior. Record Android build, manager, APK manifest/signature hashes and logs.
- [ ] **Step 4: Run the test and decide.** Run the candidate on an owned ARM64 Android release process under the intended safety constraints. If any requirement fails, update the report with observed output and stop this plan before Tasks 2-5. If all pass, preserve source and license in `zygisk/backend/`, pin all revisions, and record exact reproduction commands.
- [ ] **Step 5: Verify the audit record.** Run `git diff --check`; confirm the report distinguishes static inspection, self-owned probe evidence and WCX runtime evidence. No report may call the backend WCX-compatible until Task 5 device acceptance.

## Task 2: Isolate WCX Startup and Implement the Zygisk Bridge

**Files:**
- Modify: `app/build.gradle.kts`
- Modify, only as needed: `app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt`, `XposedStartup.kt`, `WeLauncher.kt`, `UnifiedEntryPoint.kt`, `ModuleLoadStatus.kt`
- Create: `app/src/zygisk/AndroidManifest.xml`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskLoaderService.kt`
- Create: `app/src/zygisk/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskHookBridge.kt`
- Modify: `app/proguard-rules.pro` only for Zygisk entry/JNI symbols that R8 would otherwise remove.

- [ ] **Step 1: Capture ordinary-variant baselines.** On the supported build host, run `./x build --native-only` and `./gradlew :app:assembleStandardRelease :app:assembleLegacyRelease --no-daemon --max-workers=2`. Save task list, APK paths, badging, signing certificate fingerprints, ABI/library inventory and Xposed entry inventory with the base commit. Do not copy signing credentials into the repository or logs.
- [ ] **Step 2: Add opt-in variant configuration.** Gate a `zygisk` flavor/source set on an explicit Gradle property, restrict it to `arm64-v8a`, and keep ordinary `standard`/`legacy` task graph unchanged when the property is absent. Move or overlay Xposed entry metadata only if variant merge inspection proves the Zygisk APK otherwise receives it; preserve ordinary flavor contents byte-for-byte where possible.
- [ ] **Step 3: Add a failing variant-isolation check.** Add a repository-local verification command/test that inspects Gradle variant outputs and fails when the opt-in property is absent but Zygisk tasks/entries appear, or when the Zygisk APK contains `assets/xposed_init` or `META-INF/xposed/` declarations. Run it against the current unmodified ordinary APKs and confirm it detects an intentionally malformed temporary inventory before implementation.
- [ ] **Step 4: Split only the Xposed-specific startup wrapper.** Keep `StartupAgent.prepare` and host setup framework-neutral. Keep hidden API compatibility and host protection-directory behavior exclusively in the Xposed startup path. Add a Zygisk startup path that initializes only what WCX needs and does not call those operations. Preserve the existing `ModuleLoader` and standard/legacy entry behavior.
- [ ] **Step 5: Implement `ZygiskLoaderService`.** Supply stable WCX version, module path, class-loader helper, extension behavior and logging through `ILoaderService`. Log to Android's process log using a WCX-specific tag; do not assume `XposedBridge.log` is available.
- [ ] **Step 6: Implement `ZygiskHookBridge` against the accepted backend.** Implement all `IHookBridge` members, including constructor invocation/creation and deoptimization reporting. Keep handles/callback ordering consistent with the accepted contract. Return unsupported status only for capabilities the backend genuinely lacks; never use Xposed API compile-time stubs as runtime implementation.
- [ ] **Step 7: Implement native-to-Java startup.** Load the payload from the path supplied by the manager, initialize backend before invoking Java entry, pass host and payload class loaders explicitly, and call the shared unified startup only after both loader service and bridge are valid. Record stage and failure reason; failed initialization must not report `LOADED`.
- [ ] **Step 8: Verify the bridge contract on the owned probe.** Run the complete Task 1 contract with the actual packaged non-debuggable payload classes and loader. Keep the probe package/module IDs separate from release IDs. A missing callback, incorrect original invocation or fake capability result fails the task.

## Task 3: Versioned Targets, Manager Templates, and ZIP Assembly

**Files:**
- Create: `zygisk/config/targets.schema.json` or an equivalent documented, versioned format
- Create: `zygisk/module/{magisk,kernelsu,apatch}/module.prop` and manager-specific install/uninstall scripts
- Create: `zygisk/module/{kernelsu,apatch}/webroot/` target management UI and API integration
- Modify: `zygisk/package_zip.py`
- Modify: `zygisk/README.md`
- Add focused tests under `zygisk/tests/` for configuration and archive validation.

- [ ] **Step 1: Define the shared target record and migration rules.** Store schema version and rows keyed by Android user ID plus exact package name. Specify enabled default false for discovered KSU/APatch targets, preserve existing switches during refresh, drop uninstalled targets, reject malformed IDs/packages and never interpret arbitrary shell/UI input as a command. Magisk's default is a fixed `com.tencent.mm` main-process target.
- [ ] **Step 2: Add config parser tests first.** Cover valid current config, missing config defaults, schema upgrade, duplicate user/package, malformed package, unsupported schema and atomic-write failure. Run the tests and confirm failures before adding parser/refresh implementation.
- [ ] **Step 3: Implement native target matching.** Match exact package and process identity from the Zygisk specialize arguments; match Android user only when the manager API exposes a trustworthy user identity. Main process only means process name equals package name. Reject `:push`, arbitrary suffixes, system processes and all other apps. If an API cannot provide required identity, fail closed and document it.
- [ ] **Step 4: Implement KernelSU WebUI.** Enumerate users/packages through documented KernelSU APIs. Show installed WeChat instances and enabled state, default new rows off, allow refresh and toggle only, persist using the versioned shared format, and render process status without shell interpolation. Test empty list, multiple users, refresh preserving toggles, newly discovered disabled target, removed package and malformed storage.
- [ ] **Step 5: Implement APatch WebUI against its documented API.** Reuse the UI/config model but keep manager API integration separate. Document any API limitations; do not assume KernelSU APIs exist unchanged in APatch. Add equivalent tests or a deterministic API adapter test for each manager-specific operation.
- [ ] **Step 6: Add Magisk fixed-target behavior.** Do not ship a fake WebUI. Its installer must declare the supported ARM64/Zygisk prerequisites and its runtime target config must resolve to `com.tencent.mm` main process only. Config paths and installer state must be module-private.
- [ ] **Step 7: Implement safe manager install/uninstall templates.** Validate architecture, required payload files and enabled Zygisk before activation. Do not change SELinux policy or touch host app data. Uninstall removes only module-owned files/configuration. Check manager-specific documented hooks, metadata keys and UI paths against pinned manager documentation/source.
- [ ] **Step 8: Extend explicit-input ZIP packaging.** Add one build/pack command requiring explicit payload and loader paths plus manager selection. Render module version from the payload version, assemble manager-specific ZIPs from controlled files, reject duplicate/traversal/symlink entries and unexpected payload ABIs, validate AArch64 ELF and Zygisk entry, and replace output atomically only after CRC and manifest checks pass. Preserve existing `pack-probe` behavior and probe/release profile separation.
- [ ] **Step 9: Test archive negatives.** For each manager profile test valid archive, missing loader, non-ELF loader, ELF wrong architecture, missing payload, Xposed metadata, mismatched version, malformed module metadata, duplicate path, traversal path and symlink. Confirm each failing invocation exits nonzero and leaves an existing output file unchanged.

## Task 4: Payload Integration and Build Isolation

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/zygisk/AndroidManifest.xml` and any required variant-only assets/resources
- Modify only if required by the native loader: `app/src/main/rust/wekit-native/` or add backend native build files beneath `zygisk/backend/`
- Modify: `zygisk/package_zip.py`, `zygisk/README.md`

- [ ] **Step 1: Build the opt-in payload.** Run the explicit Gradle Zygisk release task and verify exactly one signed `com.Johnny.wcx` ARM64 APK is produced. Confirm APK includes required WCX resources/native libraries and Zygisk-only entry; excludes Xposed entry metadata and all non-ARM64 libraries.
- [ ] **Step 2: Build loader reproducibly.** Use the pinned NDK/CMake/Rust sources from Task 1 and repository-declared toolchains. Produce only AArch64 shared library; verify ELF class, machine, dynamic symbols and the Zygisk API entry point using NDK `llvm-readelf`.
- [ ] **Step 3: Package each manager artifact explicitly.** Invoke the pack command three times with the same payload/loader and distinct manager template. Run `python3 zygisk/package_zip.py verify --profile <manager> <archive>` for each archive. Record commit, upstream dependency revisions, payload/ZIP SHA-256 and build tool versions.
- [ ] **Step 4: Rebuild ordinary APK variants.** Without the Zygisk Gradle property run `./gradlew :app:assembleStandardRelease :app:assembleLegacyRelease --no-daemon --max-workers=2`. Compare with Task 2 baseline: task availability, package/version, signing certificate, ABI/native library inventory, `assets/xposed_init`, and standard-only `META-INF/xposed` declarations. Investigate and fix any unintended difference before proceeding.
- [ ] **Step 5: Verify failure cannot select stale output.** Remove only a temporary test output directory, invoke build/pack with a missing explicit artifact and a mismatched artifact, and assert failure occurs without searching for or using another APK from `app/build/outputs`.

## Task 5: Runtime Device Matrix and Documentation

**Files:**
- Modify: `zygisk/README.md`
- Modify: `docs/superpowers/reports/2026-11-27-wcx-zygisk-backends.md`
- No automatic installation or manager/device mutation from build scripts.

- [ ] **Step 1: Prepare three independent device records.** For each Magisk, KernelSU and APatch device, record device ABI, Android/API, manager version/Zygisk state, WCX build hash and WeChat version. Do not infer compatibility across managers. Verify supported WeChat version scope separately.
- [ ] **Step 2: Install and validate Magisk.** With user/operator consent, install only the Magisk ZIP, disable the WCX LSPosed scope for the same WeChat instance, reboot if required, verify WCX starts in `com.tencent.mm` main process, settings opens, and no other package or child process logs WCX startup. Validate module disable/uninstall and reboot rollback.
- [ ] **Step 3: Validate KernelSU and APatch separately.** For each, install its matching ZIP, refresh users/packages, confirm newly found targets default off, enable only the intended WeChat instance, verify process filtering and settings startup, test refresh persistence, then disable/uninstall and reboot. Capture versioned config before/after without exposing private user data.
- [ ] **Step 4: Verify crash/failure behavior.** On a test install, exercise missing/corrupt target config and backend/payload initialization failure. Confirm initialization stops with a staged diagnostic and does not load other processes or leave stale `LOADED` status. Restore valid module state using manager controls and test rollback.
- [ ] **Step 5: Update support matrix with evidence.** For each manager mark `built`, `archive-verified`, `installed`, `runtime-verified`, or `not-tested`; include tested versions and unsupported limits. Do not claim supported for a manager without its own runtime evidence. Record that only the main process is supported and LSPosed plus Zygisk must not target the same instance.
- [ ] **Step 6: Run final verification.** Run archive tests, ordinary variant build comparison, `git diff --check`, and applicable backend probe tests. Report commands and exact results. If a manager device or backend gate was unavailable, leave it explicitly unverified; do not make a release-support claim.

## Plan Self-Review

- **Design coverage:** isolated ARM64 payload, `IHookBridge` and unified startup, no-bypass backend gate, Magisk fixed target, KSU/APatch per-user WebUI, versioned config, manager-specific templates, ZIP security validation, normal APK regression and per-manager device evidence are assigned to Tasks 1-5.
- **Stop conditions:** backend security/contract failure blocks Tasks 2-5 runtime delivery; missing manager API or user identity fails closed; no device result is promoted from a probe or another manager.
- **No placeholders:** all implementation outputs, validation commands/behaviors and failure handling are specified. Upstream revision is intentionally selected only after source audit; arbitrary candidate versions are not pre-approved.
- **Scope:** ARM64 and WeChat main process only. No auto-flash, automatic release, SELinux relaxation, arbitrary remote code, child processes, or ordinary APK changes are included.
