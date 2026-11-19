# WCX performance and package-size optimization

## Scope and guardrails

- Design: `docs/superpowers/specs/2026-11-19-wcx-performance-package-size-design.md`
- Plan: `docs/superpowers/plans/2026-11-19-wcx-performance-package-size.md`
- Implementation branch: `perf/module-size-startup`
- Keep one full-featured APK; do not remove feature families or split release variants.
- Preserve the `libaff_biz` stability gate, first-Activity gate, LSPlant/Dobby split and `IHookBridge` semantics.
- Build only in Debian VM `/root/wcx`; device work remains blocked until the stable baseline health probe passes.

## Acceptance budgets

| Metric | Budget |
| --- | ---: |
| arm64 full Zygisk APK | ≤ 36,000,000 bytes |
| Uncompressed DEX total | ≤ 25,500,000 bytes |
| Native library total | ≤ 5,500,000 bytes |
| Main-process WCX startup P50 | At least 25% lower than baseline |
| Main-process WCX startup P95 | No regression |
| Unexpected WCX errors | 0 |

## Baseline package report

The baseline APK was built from `72d1fad100ca7a20054868f1b0b990d55c0da8ba`; Task 1 only added host-side reporting files and does not change APK contents. The report was generated with the Task 1 implementation at `4abd94cd`.

- APK: `app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk`
- SHA-256: `17795772071351340d70d5826f557e5c608ce70b57fb73a52e56bdee061d10bd`

| Group | Raw bytes | Compressed bytes | Entries |
| --- | ---: | ---: | ---: |
| APK file | 44,309,977 | — | — |
| DEX | 32,408,544 | 32,408,544 | 3 |
| Native | 5,456,152 | 5,456,152 | 7 |
| Assets | 1,793,506 | 1,557,896 | 25 |
| Resources | 1,765,526 | 1,429,295 | 944 |
| Other | 6,549,437 | 3,215,641 | 52 |
| META-INF | 84,206 | 31,690 | 117 |

Largest native entries:

| Entry | Bytes |
| --- | ---: |
| `libwekit_native.so` | 3,216,800 |
| `libwcx_zygisk.so` | 746,040 |
| `libmmkv.so` | 731,088 |
| `libdexkit.so` | 390,280 |
| `libsilk_codec.so` | 346,808 |
| `libhchat_crash.so` | 15,040 |
| `libandroidx.graphics.path.so` | 10,096 |

Baseline budget result: APK and DEX exceed their budgets; native total is 43,848 bytes below its initial guardrail.

## Reproduction

```bash
# Local source is synced with the project helper; build runs in Debian VM.
C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh \
  release --abi arm64-v8a

ssh debian13 'cd /root/wcx && python3 scripts/wcx_apk_report.py \
  --apk app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk \
  --budget config/wcx-performance-budget.json \
  --mapping app/build/outputs/mapping/standardRelease \
  --output /tmp/wcx-apk-report.json || test $? -eq 2'
```

## Startup distribution collection

Device timing collection is intentionally deferred until the installed stable APK passes the existing boot/direct health probe. If `startup_gate_not_triggered` recurs, perform only the previously approved narrowed retry and stop after a second failure; do not deploy the optimization APK in that state.

After a healthy stable run, collect persisted WCX log windows and summarize each cohort without touching device state:

```bash
sh scripts/wcx-injection-probe.sh --summarize-startup baseline-startup.log
sh scripts/wcx-injection-probe.sh --summarize-startup optimized-startup.log
```

Use separate boot and direct cohorts. The acceptance comparison is `optimized_p50 <= baseline_p50 * 0.75` and `optimized_p95 <= baseline_p95`.

## Phase ledger

| Phase | Commit | APK bytes | DEX bytes | Native bytes | Verification |
| --- | --- | ---: | ---: | ---: | --- |
| Baseline measurement | `4abd94cd` | 44,309,977 | 32,408,544 | 5,456,152 | 9 Python tests pass; report exits 2 as expected |
| Task 6: feature R8 optimization | `62d1fc2c` | 40,802,644 (-3,507,333) | 28,902,400 (-3,506,144) | 5,456,152 (0) | 37/37 ABI contracts pass; 295/295 feature classes and 134/134 Dex hash keys retained; 4,512 feature usage entries pruned |
| Task 7: narrow Hchat/script keeps | `83a45e89` | 36,591,186 (-4,211,458) | 24,699,344 (-4,203,056) | 5,456,152 (0) | Release build passes; 56/56 ABI contracts pass; Hchat bytecode 3,661,091 bytes; APK is 591,186 bytes over final budget |
| Task 8: remove broad kotlin-reflect keep | `6d2e5864` | 35,377,345 (-1,213,841) | 23,481,128 (-1,218,216) | 5,456,152 (0) | All package budgets pass; 56/56 ABI contracts pass; kotlin.reflect bytecode falls from 1,398,788 to 819,628 bytes |
| Task 9a: remove ARSCLib runtime | `136961d8` | 31,164,575 (-4,212,770) | 21,662,032 (-1,819,096) | 5,456,152 (0) | Release and 56/56 contracts pass; `frameworks/android/android-*.apk` removed; three ARSC metadata assets remain and will be checked against JADX ownership next |
| Task 9b: remove JADX dex-input runtime | `9ab2f158` | 30,406,882 (-757,693) | 21,659,988 (-2,044) | 5,456,152 (0) | Release and 56/56 contracts pass; `clst/core.jcst`, `android/res-map.txt` and `android/attrs.xml` removed |
| Task 9c: remove smali/baksmali runtime | `62c4b1b5` | 30,406,729 (-153) | 21,657,588 (-2,400) | 5,456,152 (0) | Release and 56/56 contracts pass; dependency was effectively unreachable after R8 |
| Task 9d: remove explicit Guava runtime | `84d87110` | 30,406,729 (0) | 21,655,720 (-1,868) | 5,456,152 (0) | Release and 56/56 contracts pass; no `com.google.common` package remains in the minified APK |
| Task 9e: enforce package budgets | `7f747717` | 30,406,729 | 21,655,720 | 5,456,152 | CI now fails on report exit 2; stale JADX/ARSCLib/smali warning suppressions removed |
| Task 10: lazy feature descriptors | `849eee19` | 30,423,113 (+16,384) | 21,679,816 (+24,096) | 5,456,152 (0) | Release and 56/56 contracts pass; KSP emits 295 descriptors/factories, 14 switch defaults, 41 APIs and 134 Dex resolvers |
| Task 11: instantiate startup features on demand | `795b4781` | 30,423,113 (0) | 21,683,364 (+3,548) | 5,456,152 (0) | Full `./x` release and 56/56 contracts pass; startup loader has no `ALL_HOOK_ITEMS` access; registry batch counts added; direct BaseFeature startup metadata brings total defaults to 15 |
| Task 12: defer Parcelable Intent hooks | `3d3322c9` | 30,423,113 (0) | 21,681,544 (-1,820) | 5,456,152 (0) | Full `./x` release, 56/56 contracts and deferred-boundary tests pass; module Activity/runtime log smoke remains deferred to Task 16 |
| Task 13: split core/media Rust JNI | `5b462f1d` | 30,699,854 (+276,741) | 21,681,544 (0) | 5,732,472 (+276,320) | Native build and `wekite-art` tests pass; core falls from 3,216,800 to 380,504 bytes and media is 3,112,616 bytes; the temporary native overage is removed by Task 14 Silk consolidation |
| Task 14: load native capabilities on demand | `94c00076` | 30,355,714 (-344,140) | 21,683,660 (+2,116) | 5,389,304 (-343,168) | Baseline and `--wekite-hook` arm64 releases pass all budgets; 71/71 ABI contracts and 29 Python tests pass; duplicate `libsilk_codec.so` removed |
| Task 15: static and VM acceptance matrix | `a35515c1` | 30,355,714 (0) | 21,683,660 (0) | 5,389,304 (0) | 30 Python tests, `wekite-art`, 71/71 ABI contracts, baseline/WeKite arm64 and standard/legacy arm64/v7 builds pass |
| Task 16: guarded device acceptance | `886e5e21` | 30,372,098 (+16,384) | 21,691,908 (+8,248) | 5,389,304 (0) | 39/39 Python tests, 71/71 ABI contracts, 19/19 device matrix (`direct` 10/10, `boot` 6/6, `screenoff` 3/3), `WeLauncher` P50 1,138→512 ms (-55.0%), P95 1,173→540 ms (-54.0%), WCX `E=0`, `/proc/<pid>/maps` 0 exposed `.so` |

Task 7 replaced the package-wide `h.Hchat.** { *; }` rule with exact BeanShell prelude, callback-input, public API and static-JNI contracts. The manual Hchat settings/BeanShell/snapshot/callback smoke checklist remains deferred to Task 16 because device deployment is blocked until the stable baseline health probe passes.

Task 8 removed the unused `fastJavaMethod` compatibility extension and the package-wide kotlin-reflect keep. Remaining `KClass`/`KProperty` references use standard-library interfaces; R8 now removes unreachable reflection implementation code.

Task 13 moves all media JNI exports and the libvpx build into `wekit-media`; `wekit-native` retains only crash handling, native hooks, signature verification and the optional WeKite bridge. Export inspection confirms the two libraries expose the intended disjoint JNI surfaces. Splitting duplicates a small amount of ELF/Rust runtime overhead, so the intermediate native total exceeds the 5.5 MB guardrail by 232,472 bytes; Task 14 removes the redundant 346,808-byte `libsilk_codec.so` before final budget acceptance.

Task 14 makes startup load only `libwekit_native.so` and MMKV. DexKit, media and AndroidX graphics-path libraries now use retryable capability guards at their first consumers. `me.yun.silk.SilkCodec` keeps its public script/Hchat ABI but delegates to `libwekit_media.so`; its 15 public methods are covered by minified contracts. Both baseline and experimental WeKite arm64 builds pass the package budgets. Device `/proc/<pid>/maps`, first-use Markdown/audio conversion and Hchat voice-forward smoke tests remain deferred to Task 16 under the existing stable-baseline gate.

## Final static acceptance matrix

The final matrix source was clean local commit `1177aa5c` (`fix(build): support v7 native release matrix`), synced to `/root/wcx`. The VM's copied worktree `.git` pointer is not usable outside the Windows worktree path, so provenance was verified locally before sync and by deterministic artifact hashes after build.

| Variant | Result | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Full arm64 Zygisk baseline | pass | 30,355,714 | `231cb698a630fb863a98d075d632ac2abcaf23bc6b6ddbe1c996cbea42acd3a8` |
| Full arm64 Zygisk `--wekite-hook` | pass | 30,390,850 | `b5983b06f672f757343f4d25ebb5f2e9d6278d4a9868b28d4678a73707d38ede` |
| Standard arm64 release | pass | 30,326,195 | `dcc9b016bcfa2248153c612072437584e9f85e3416ccdee446c584383ff21459` |
| Legacy arm64 release | pass | 30,325,690 | `c26fb703c3d3b8b554e0c31825082c81b0a1379b128e823ffc4b3d660f845a76` |
| Standard armeabi-v7a release | pass | 27,935,104 | `e49988498200d3cb85731e98d8c5c9ea52899e37e40b841b8e935d6bf21af9dc` |
| Legacy armeabi-v7a release | pass | 27,934,599 | `8ce5bd1a492dfd1549f08888fee8d27b577254acca8f4a9a056ef9bbe0cb106d` |

The first v7 matrix attempt completed native and Android assembly but failed its finalizer because `packageZygiskApk` always searched for an arm64 APK. Commit `1177aa5c` makes the arm64-only Zygisk packaging task skip v7-only builds; the rerun completed with `packageZygiskApk SKIPPED`. The final v7 core/media libraries are 283,392 and 2,465,804 bytes respectively.

Final arm64 budget report:

| Metric | Final | Budget | Baseline delta |
| --- | ---: | ---: | ---: |
| Full APK | 30,355,714 | 36,000,000 | -13,954,263 (-31.5%) |
| DEX | 21,683,660 | 25,500,000 | -10,724,884 (-33.1%) |
| Native | 5,389,304 | 5,500,000 | -66,848 (-1.2%) |

Largest 20 final APK entries:

| Entry | Raw bytes |
| --- | ---: |
| `classes.dex` | 13,052,424 |
| `classes2.dex` | 8,631,236 |
| `lib/arm64-v8a/libwekit_media.so` | 3,116,256 |
| `resources.arsc` | 759,680 |
| `lib/arm64-v8a/libwcx_zygisk.so` | 746,040 |
| `lib/arm64-v8a/libmmkv.so` | 731,088 |
| `lib/arm64-v8a/libdexkit.so` | 390,280 |
| `lib/arm64-v8a/libwekit_native.so` | 380,504 |
| `assets/theme_store/wallpaper/chiikawa.jpg` | 311,408 |
| `assets/theme_store/wallpaper/bear.jpg` | 305,105 |
| `assets/theme_store/wallpaper/bunny.jpg` | 205,389 |
| `assets/theme_store/wallpaper/puppy.jpg` | 177,984 |
| `assets/theme_store/wallpaper/food_puppy.jpg` | 173,246 |
| `assets/theme_store/wallpaper/hellokitty.jpg` | 155,556 |
| `assets/PublicSuffixDatabase.list` | 132,737 |
| `assets/SCRIPT_PLUGIN_API.md` | 127,910 |
| `assets/home_side_panel/xiaomi_weather.db` | 91,136 |
| `google/protobuf/descriptor.proto` | 58,877 |
| `res/pc.png` | 25,715 |
| `org/commonmark/internal/util/entities.properties` | 24,470 |

Final native section summary:

| Library | `.text` | `.rodata` | `.eh_frame` | `.data.rel.ro` |
| --- | ---: | ---: | ---: | ---: |
| `libandroidx.graphics.path.so` | 3,708 | 467 | 148 | 368 |
| `libdexkit.so` | 255,656 | 4,808 | 76,292 | 4,888 |
| `libhchat_crash.so` | 10,644 | 1,063 | 0 | 0 |
| `libmmkv.so` | 412,820 | 39,860 | 75,988 | 16,520 |
| `libwcx_zygisk.so` | 534,636 | 62,664 | 83,972 | 15,336 |
| `libwekit_media.so` | 2,077,504 | 411,472 | 314,312 | 191,576 |
| `libwekit_native.so` | 274,340 | 30,636 | 39,868 | 9,800 |

Static gates at the final baseline artifact: 71/71 minified ABI contracts, 30/30 Python report/contract tests, and 1/1 `wekite-art` unit test pass. Startup distribution, process maps and functional first-use checks remain the guarded Task 16 device work.

## Task 16: Guarded device acceptance — COMPLETE

### 1. Stable baseline prerequisite

- Stable first-Activity gate fix (`f56c5e4c8337c3b2a1c172d318e4f84fe284a987be85fe85e5a1cd63505df57e`, 44,309,977 bytes; see `docs/work-items/wcx-first-activity-gate-race.md`) passed the strict 18/18 matrix (`direct` 10/10, `screenoff` 5/5, `boot` 3/3) with 0 crashes, 0 tombstones, and injector MD5 `93dcbeea7a5ea0075c36c315696be62f`.

### 2. Final optimized candidate

- Source commit: `216422e3` (with probe log-window enhancement `886e5e21`)
- Artifact: `app/build/outputs/zygisk/wcx-v347-arm64-v8a.apk`
- SHA-256: `d4a7701e98a4d259abf8ef563a3bcdb3e5848b85ee2f044bbf74b599650606f4`
- Package metrics:
  - APK: **30,372,098 bytes** (-13,937,879 B / **-31.46%**, budget ≤ 36,000,000 B)
  - DEX: **21,691,908 bytes** (-10,716,636 B / **-33.07%**, budget ≤ 25,500,000 B)
  - Native: **5,389,304 bytes** (-66,848 B / **-1.23%**, budget ≤ 5,500,000 B)
- Static gates: **39/39** Python contract/report tests and **71/71** minified ABI contracts (`missing=0`).
- Additional startup optimizations verified on-device during Task 16:
  - Removed eager `ALL_HOOK_ITEMS` evaluation from `LocalAdaptationEngine` and deferred `WCX-BgInit` + `HchatBootstrap` past first frame (`a33ebcf6`); guarded `FeatureSwitchSnapshot.save` with `TargetProcesses.isInMain` so child processes no longer overwrite main-process snapshot state; invoked `hideLoadedModuleLibraries()` after lazy capability loads.
  - Added off-main-thread metadata/JSON prewarming (`FeaturesLoader.prewarmEarlyFeatures` + `DexCacheManager.prewarmCacheFiles`, `216422e3`) in the post-`libaff_biz` idle window without touching LSPlant, `NativeLoader`, `MMKV`, or hooks.

### 3. Controlled device matrix (20/20 runs clean)

| Scenario | Runs | Result | Key observations |
| --- | ---: | --- | --- |
| Pre-matrix smoke | 1/1 | PASS | `enabled_max=102`, `proxy=1`, `E=0`, `WeLauncher=493ms` |
| Direct cold start (`direct`) | 10/10 | PASS | `enabled_max=102`, `proxy=1`, `focused=1`, `E=0`, `startup_gate_wait_ms=0` |
| Background then foreground (`boot`) | 6/6 | PASS | `pre_lsplant=0`, `pre_started=0`, `same_main=1`, `enabled_max=102`, `proxy=1`, `E=0` |
| Screen-off cold start (`screenoff`) | 3/3 | PASS | `enabled_max=102`, `proxy=1`, `focused=1`, `E=0` |

### 4. Startup latency comparison (P50 / P95)

| Cohort & Metric | Stable Baseline | Optimized Candidate | Delta | Acceptance Gate |
| --- | ---: | ---: | ---: | --- |
| `direct` (n=10) `WeLauncher` P50 | 1,138 ms | **512 ms** | **-626 ms (-55.0%)** | ≤ 853 ms (-25%) — **PASS** |
| `direct` (n=10) `WeLauncher` P95 | 1,173 ms | **540 ms** | **-633 ms (-54.0%)** | ≤ 1,173 ms — **PASS** |
| `direct` (n=10) `activity_gate → started` P50 | 1,316 ms | **604 ms** | **-712 ms (-54.1%)** | ≤ 987 ms (-25%) — **PASS** |
| `direct` (n=10) `activity_gate → started` P95 | 1,346 ms | **637 ms** | **-709 ms (-52.7%)** | ≤ 1,346 ms — **PASS** |
| `boot` (n=6 vs n=3) `WeLauncher` P50 | 1,554 ms | **572 ms** | **-982 ms (-63.2%)** | ≤ 1,165 ms (-25%) — **PASS** |
| `boot` (n=6 vs n=3) `WeLauncher` P95 | 1,590 ms | **613 ms** | **-977 ms (-61.4%)** | ≤ 1,590 ms — **PASS** |
| `screenoff` (n=3 vs n=5) `WeLauncher` P50 | 1,345 ms | **399 ms** | **-946 ms (-70.3%)** | ≤ 1,008 ms (-25%) — **PASS** |

Raw `direct` 10-run cohorts:
- Stable `WeLauncher`: `[1153, 1117, 1167, 1173, 1138, 1127, 1115, 1137, 1146, 1152]` ms
- Optimized `WeLauncher`: `[502, 540, 531, 497, 518, 515, 520, 499, 512, 506]` ms
- Optimized `StartupPerf (early_features_ready - activity_gate)`: `[589, 628, 614, 578, 598, 594, 608, 580, 594, 582]` ms (P50 = 594 ms, P95 = 628 ms)
- `--summarize-startup` end-to-end process wall (`zygisk_entry → early_features_ready`): `direct` `count=10 p50=12520ms p95=12786ms max=12786ms`; `boot` `count=6 p50=23236ms p95=23431ms max=23431ms`.

### 5. Lazy instantiation, native maps, memory & subsystem checks

- **Feature instantiation**: `FeatureRegistry: descriptors=295 instantiated=102 early=102 deferred=0` at first screen (down from 295 eager singletons), and `instantiated=202 early=102 deferred=100` after deferred batch (93 disabled features never instantiated); active enabled counts remain identical (`102` early + `53` deferred = `155`).
- **`/proc/<main_pid>/maps`**: `0` `.wcx-native`, `0` `libwekit_media.so`, `0` `libsilk_codec.so`, `0` unhidden module `.so` entries (`16` `/memfd:wk` segments vs `36` before media split/lazy loading).
- **Memory (`dumpsys meminfo`)**: Java Heap `80,864 KB`, Native Heap `276,572 KB`, TOTAL PSS `1,166,685 KB`.
- **Runtime subsystems**: `WeDatabaseApi` (`zp0.b0 -> ai5.d0`), `HchatBootstrap` (`wechat_api` + `script_plugin`, `dbOperational=true msgReady=true msgInstalled=true`), `QuickRead` (`com.tencent.mm.storage.m4` captured), `MonetEngine`, `:push`, and `:appbrand0`/`:appbrand1` all verified with **WCX `E=0`**, **0** Java/native crashes (`reason=4/5 = 0`, `status=6/11 = 0`), and **0** tombstones.

### 6. Final device state

- Restored `/data/adb/modules/zygisk-wcx/module.apk` to the verified fixed-stable baseline (`f56c5e4c8337c3b2a1c172d318e4f84fe284a987be85fe85e5a1cd63505df57e`), verified injector MD5 `93dcbeea7a5ea0075c36c315696be62f` and empty debug properties, and confirmed `RESULT=PASS` on a final direct health probe. The verified optimized APK remains staged at `/data/local/tmp/wcx-optimized.apk` (`d4a7701e98a4d259abf8ef563a3bcdb3e5848b85ee2f044bbf74b599650606f4`) for immediate activation when desired.
