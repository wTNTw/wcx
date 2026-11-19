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
| Task 10: lazy feature descriptors | pending | 30,423,113 (+16,384) | 21,679,816 (+24,096) | 5,456,152 (0) | Release and 56/56 contracts pass; KSP emits 295 descriptors/factories, 14 defaults, 41 APIs and 134 Dex resolvers |

Task 7 replaced the package-wide `h.Hchat.** { *; }` rule with exact BeanShell prelude, callback-input, public API and static-JNI contracts. The manual Hchat settings/BeanShell/snapshot/callback smoke checklist remains deferred to Task 16 because device deployment is blocked until the stable baseline health probe passes.

Task 8 removed the unused `fastJavaMethod` compatibility extension and the package-wide kotlin-reflect keep. Remaining `KClass`/`KProperty` references use standard-library interfaces; R8 now removes unreachable reflection implementation code.
