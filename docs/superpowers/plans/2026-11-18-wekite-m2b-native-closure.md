# WeKite M2b Native Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete WeKite's JNI-to-ArtMethod hook loop in `:push` and `:appbrand*` without changing the stable resident Zygisk injector or rebooting Android Runtime.

**Architecture:** Extract the existing WeKite ART engine into one shared Rust crate consumed by both the resident injector and the APK's `libwekit_native.so`. An opt-in child-process selector loads the APK native backend early, runs destructive-but-isolated fixture hooks, and only then publishes `WeKiteHookBridge`; all failures fall back to LSPlant. The main process remains on the already-validated first-Activity LSPlant startup gate.

**Tech Stack:** Kotlin/JVM, Android JNI, Rust 2024, Cargo workspace features, DexMaker 2.28.6, Zygisk, LSPlant/Dobby fallback, Debian VM build, adb/root shell verification.

---

## File map

### New files

- `libs/common/wekite-art/Cargo.toml` — shared ART engine crate manifest.
- `libs/common/wekite-art/src/lib.rs` — public engine API and logging macros.
- `libs/common/wekite-art/src/elf.rs` — ART ELF/symbol lookup.
- `libs/common/wekite-art/src/layout.rs` — Android API and ArtMethod layout helpers.
- `libs/common/wekite-art/src/trampoline.rs` — dual-mapped executable trampoline pool.
- `app/src/main/rust/wekit-native/src/wekite_hook.rs` — APK JNI exports for WeKite.
- `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteBackendSelector.kt` — property/process gate, initialization, fallback decision.
- `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteNativeFixture.kt` — isolated hook targets.
- `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteNativeSelfTest.kt` — real native hook acceptance suite.
- `scripts/wcx-wekite-m2b-probe.sh` — device-side M2b log/process verifier.

### Modified files

- `Cargo.toml` — add the shared crate to the workspace.
- `Cargo.lock` — resolve the shared dependency.
- `wcx-zygisk/native/Cargo.toml` — optional path dependency for future resident builds.
- `wcx-zygisk/native/src/lib.rs` — expose the shared crate under the existing feature.
- `app/src/main/rust/wekit-native/Cargo.toml` — optional `wekite_hook` feature/dependency.
- `app/src/main/rust/wekit-native/src/lib.rs` — feature-gated JNI module.
- `xtask/src/main.rs` — `--wekite-hook` build option and Cargo feature propagation.
- `app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt` — idempotent early payload-native load.
- `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteHookBridge.kt` — native initialization entry and JNI declarations.
- `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt` — child-process bridge selection while preserving LSPlant main startup.
- `app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt` — replace the old M2a-only self-test trigger with explicit M2b reporting; keep default path unchanged.
- `docs/work-items/wekite-hook-engine-feasibility.md` — record implementation and evidence.

### Removed files

- `wcx-zygisk/native/src/wekite/mod.rs`
- `wcx-zygisk/native/src/wekite/elf.rs`
- `wcx-zygisk/native/src/wekite/layout.rs`
- `wcx-zygisk/native/src/wekite/trampoline.rs`

These are moved, not duplicated.

---

### Task 1: Integrate the validated cold-start gate

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt`
- Create: `scripts/wcx-injection-probe.sh`

- [ ] **Step 1: Confirm a clean branch and record the pre-integration point**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git show --no-patch --oneline 2d84b5cd
```

Expected: branch is `feat/wekite-hook-engine`, worktree is clean, and `2d84b5cd` is `fix(zygisk): gate startup before first activity`.

- [ ] **Step 2: Cherry-pick the validated fix**

Run:

```bash
git cherry-pick 2d84b5cd
```

Expected: one new commit; no conflict in `ZygiskEntry.kt`.

- [ ] **Step 3: Verify the obsolete foreground wait is gone**

Run:

```bash
! rg 'awaitHostForeground|HOST_VISIBLE_POLL_MS' app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt
rg 'installMainStartupGate|startupGate' \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt \
  app/src/main/java/com/Johnny/wcx/loader/utils/ActivityProxy.kt
```

Expected: first command succeeds with no matches; second command finds the first-Activity gate.

- [ ] **Step 4: Push the integration commit**

Run:

```bash
git push origin feat/wekite-hook-engine
```

Expected: remote branch advances to the cherry-picked commit.

---

### Task 2: Extract the WeKite ART engine into a shared Rust crate

**Files:**
- Create: `libs/common/wekite-art/Cargo.toml`
- Create: `libs/common/wekite-art/src/lib.rs`
- Move: `wcx-zygisk/native/src/wekite/elf.rs` → `libs/common/wekite-art/src/elf.rs`
- Move: `wcx-zygisk/native/src/wekite/layout.rs` → `libs/common/wekite-art/src/layout.rs`
- Move: `wcx-zygisk/native/src/wekite/trampoline.rs` → `libs/common/wekite-art/src/trampoline.rs`
- Move/adapt: `wcx-zygisk/native/src/wekite/mod.rs` → `libs/common/wekite-art/src/lib.rs`
- Modify: `Cargo.toml`
- Modify: `wcx-zygisk/native/Cargo.toml`
- Modify: `wcx-zygisk/native/src/lib.rs`
- Modify: `Cargo.lock`

- [ ] **Step 1: Add the shared crate manifest**

Create `libs/common/wekite-art/Cargo.toml`:

```toml
[package]
name = "wekite-art"
version = "0.1.0"
edition = "2024"

[dependencies]
jni = { version = "0.22", default-features = false }
libc = "0.2"
```

Add the workspace member in root `Cargo.toml`:

```toml
[workspace]
members = [
    "app/src/main/rust/wekit-native/",
    "libs/common/wekite-art",
    "wcx-zygisk/native",
    "xtask",
]
resolver = "3"
```

- [ ] **Step 2: Move the engine sources without retaining a second copy**

Run:

```bash
mkdir -p libs/common/wekite-art/src
git mv wcx-zygisk/native/src/wekite/elf.rs libs/common/wekite-art/src/elf.rs
git mv wcx-zygisk/native/src/wekite/layout.rs libs/common/wekite-art/src/layout.rs
git mv wcx-zygisk/native/src/wekite/trampoline.rs libs/common/wekite-art/src/trampoline.rs
git mv wcx-zygisk/native/src/wekite/mod.rs libs/common/wekite-art/src/lib.rs
rmdir wcx-zygisk/native/src/wekite
```

Expected: Git records four renames and `wcx-zygisk/native/src/wekite` no longer exists.

- [ ] **Step 3: Make the shared crate self-contained**

At the top of `libs/common/wekite-art/src/lib.rs`, retain the engine modules and add crate-local logging:

```rust
#![allow(unsafe_op_in_unsafe_fn)]

pub mod elf;
pub mod layout;
pub mod trampoline;

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __android_log_write(
        priority: libc::c_int,
        tag: *const libc::c_char,
        text: *const libc::c_char,
    ) -> libc::c_int;
}

#[cfg(target_os = "android")]
fn android_log(priority: libc::c_int, message: &str) {
    let tag = c"WCX";
    let message = std::ffi::CString::new(message).unwrap_or_default();
    unsafe {
        __android_log_write(priority, tag.as_ptr(), message.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn android_log(_priority: libc::c_int, message: &str) {
    eprintln!("{message}");
}

macro_rules! logi {
    ($($arg:tt)*) => { crate::android_log(4, &format!($($arg)*)) };
}
macro_rules! logw {
    ($($arg:tt)*) => { crate::android_log(5, &format!($($arg)*)) };
}
macro_rules! loge {
    ($($arg:tt)*) => { crate::android_log(6, &format!($($arg)*)) };
}

pub(crate) use {loge, logi, logw};
```

Then update moved imports:

```rust
// lib.rs
use crate::elf::find_symbol_in_file;
use crate::trampoline::TrampolinePool;
use crate::{loge, logi, logw};

// layout.rs
use crate::elf::find_symbol_in_file;
use crate::{logi, logw};

// elf.rs / trampoline.rs
use crate::loge;
```

Do not leave `crate::wekite::` or injector-specific `Zygisk:` assumptions in type paths. Log message text may retain the `Zygisk:` prefix for continuity during this task; wording cleanup is optional and must not alter behavior.

- [ ] **Step 4: Preserve the existing trampoline unit test**

Keep this exact test in `libs/common/wekite-art/src/trampoline.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::arm64_ldur_x16_from_x0;

    #[test]
    fn arm64_ldur_reads_bridge_entry_from_x0() {
        assert_eq!(arm64_ldur_x16_from_x0(24), 0xF841_8010);
        assert_eq!((arm64_ldur_x16_from_x0(24) >> 5) & 0x1f, 0);
        assert_eq!(arm64_ldur_x16_from_x0(24) & 0x1f, 16);
    }
}
```

- [ ] **Step 5: Connect the resident crate through an optional dependency**

Update `wcx-zygisk/native/Cargo.toml`:

```toml
[features]
default = ["wcx_hook_lsplant"]
wcx_hook_lsplant = []
wcx_hook_wekite = ["dep:wekite-art"]

[dependencies]
wekite-art = { path = "../../libs/common/wekite-art", optional = true }
```

Replace the old module declaration in `wcx-zygisk/native/src/lib.rs`:

```rust
#[cfg(feature = "wcx_hook_wekite")]
pub(crate) use wekite_art as wekite;
```

- [ ] **Step 6: Run the shared crate test first**

Run in the Debian VM:

```bash
cargo test -p wekite-art
```

Expected: `arm64_ldur_reads_bridge_entry_from_x0 ... ok` and no compile errors.

- [ ] **Step 7: Check both resident feature combinations**

Run in the Debian VM:

```bash
cargo check -p wcx-zygisk
cargo check -p wcx-zygisk --features wcx_hook_wekite
```

Expected: both commands pass. If Android-only linker setup prevents host `cargo check`, use the configured Android target and require both feature sets to compile there.

- [ ] **Step 8: Commit and push**

Run:

```bash
git add Cargo.toml Cargo.lock libs/common/wekite-art wcx-zygisk/native
git commit -m "refactor(zygisk): share WeKite ART engine"
git push origin feat/wekite-hook-engine
```

---

### Task 3: Add feature-gated APK JNI exports and build plumbing

**Files:**
- Create: `app/src/main/rust/wekit-native/src/wekite_hook.rs`
- Modify: `app/src/main/rust/wekit-native/src/lib.rs`
- Modify: `app/src/main/rust/wekit-native/Cargo.toml`
- Modify: `xtask/src/main.rs`
- Modify: `Cargo.lock`

- [ ] **Step 1: Declare the optional APK backend feature**

Add to `app/src/main/rust/wekit-native/Cargo.toml`:

```toml
[features]
default = []
wekite_hook = ["dep:wekite-art"]

[dependencies]
wekite-art = { path = "../../../../../libs/common/wekite-art", optional = true }
```

Verify the relative path from `app/src/main/rust/wekit-native` reaches the repository root before accepting the lockfile change.

- [ ] **Step 2: Write JNI exports before wiring Java callers**

Create `app/src/main/rust/wekit-native/src/wekite_hook.rs`:

```rust
use jni::sys::{
    JNI_FALSE, JNI_TRUE, JNIEnv as RawJNIEnv, jboolean, jclass, jint, jlong, jobject,
};

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeInitialize(
    env: *mut RawJNIEnv,
    _class: jclass,
) -> jboolean {
    if wekite_art::init(env) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeGetArtMethod(
    env: *mut RawJNIEnv,
    _class: jclass,
    executable: jobject,
) -> jlong {
    wekite_art::get_art_method(env, executable) as jlong
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeHookMethod(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_art: jlong,
    backup_art: jlong,
    bridge_art: jlong,
    _hook_id: jlong,
) -> jint {
    wekite_art::hook_method(
        env,
        target_art as usize,
        backup_art as usize,
        bridge_art as usize,
    ) as jint
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeUnhookMethod(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_art: jlong,
    backup_art: jlong,
) -> jint {
    wekite_art::unhook_method(env, target_art as usize, backup_art as usize) as jint
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeTrustDexFile(
    env: *mut RawJNIEnv,
    _class: jclass,
    dex_file: jobject,
) -> jboolean {
    if wekite_art::trust_dex_file(env, dex_file) { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_Johnny_wcx_loader_entry_wekite_WeKiteHookBridge_nativeAllocateInstance(
    env: *mut RawJNIEnv,
    _class: jclass,
    target_class: jclass,
) -> jobject {
    wekite_art::allocate_instance(env, target_class)
}
```

Do not export `nativeHideLoadedModuleLibraries` from this backend; `StartupAgent` currently invokes it only for `ArtHookBridge`, and M2b must not broaden the SO-hiding change.

- [ ] **Step 3: Compile the JNI module only for the test feature**

Add to `app/src/main/rust/wekit-native/src/lib.rs`:

```rust
#[cfg(feature = "wekite_hook")]
mod wekite_hook;
```

Run in the Debian VM:

```bash
cargo check -p wekit-native --features wekite_hook --target aarch64-linux-android
```

Expected: PASS. A missing `nativeInitialize` Java declaration is not relevant yet because native symbol exports compile independently.

- [ ] **Step 4: Add `--wekite-hook` to xtask argument structs**

Add this field to both `BuildArgs` and `RunArgs` in `xtask/src/main.rs`:

```rust
/// Build libwekit_native.so with the experimental WeKite ART hook backend.
#[arg(long)]
wekite_hook: bool,
```

Change the native build calls:

```rust
task_build_native(
    &abis,
    args.zygisk || args.release,
    !args.dev_native,
    args.wekite_hook,
)?;
```

and:

```rust
task_build_native(&abis, args.zygisk, !args.dev_native, args.wekite_hook)?;
```

Change the function signature:

```rust
fn task_build_native(
    abi_names: &[String],
    build_zygisk: bool,
    release: bool,
    wekite_hook: bool,
) -> Result<()> {
```

Before `--target`, add the feature only when requested:

```rust
if wekite_hook {
    cargo_args.extend_from_slice(&["--features", "wekite_hook"]);
}
```

Update the `--native-only` caller in `task_build` to pass `args.wekite_hook` as well.

- [ ] **Step 5: Verify baseline and test native builds are distinct**

Run in the Debian VM:

```bash
./x build --native-only --release --abi arm64-v8a
llvm-nm -D app/src/main/jniLibs/arm64-v8a/libwekit_native.so \
  | grep WeKiteHookBridge_nativeInitialize && exit 1 || true

./x build --native-only --release --abi arm64-v8a --wekite-hook
llvm-nm -D app/src/main/jniLibs/arm64-v8a/libwekit_native.so \
  | grep WeKiteHookBridge_nativeInitialize
```

Expected: baseline has no WeKite JNI export; test build does.

- [ ] **Step 6: Commit and push**

Run:

```bash
git add Cargo.toml Cargo.lock app/src/main/rust/wekit-native xtask/src/main.rs
git commit -m "feat(native): expose WeKite ART JNI from payload"
git push origin feat/wekite-hook-engine
```

---

### Task 4: Make payload native loading safely reusable before module startup

**Files:**
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/utils/ZygiskNativePayload.kt`

- [ ] **Step 1: Add an explicit early-load API**

Add to `NativeLoader`:

```kotlin
/**
 * Loads the current Zygisk payload's native libraries before StartupAgent.
 * Used only by the opt-in WeKite child-process backend.
 */
fun loadZygiskNativeLibrariesEarly() = synchronized(nativeLoadLock) {
    check(zygiskPayload != null) { "Zygisk payload is not configured" }
    ensureNativeLibrariesLoaded()
}
```

Keep `ensureNativeLibrariesLoaded()` private and idempotent. Do not set `nativeLibrariesLoaded = true` until every required startup library has loaded successfully.

- [ ] **Step 2: Make concurrent extraction publication race-safe**

Replace the unconditional rename failure in `ZygiskNativePayload.extractLibrary()` with reuse of an already-published equal-size file:

```kotlin
if (!temporary.renameTo(destination)) {
    val publishedByPeer = destination.isFile && destination.length() == entry.size
    temporary.delete()
    check(publishedByPeer) { "cannot publish Zygisk native library: $destination" }
}
return destination
```

This is required because more than one WeChat process may load the same test APK concurrently.

- [ ] **Step 3: Verify the baseline call path remains unchanged**

Run:

```bash
rg 'loadZygiskNativeLibrariesEarly' app/src/main/java
rg 'ensureNativeLibrariesLoaded\(\)' app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt
```

Expected: the new API has no caller yet; `NativeLoader.init()` still calls the same idempotent implementation.

- [ ] **Step 4: Compile Kotlin before adding selector code**

Run in the Debian VM:

```bash
./gradlew :app:compileStandardDebugKotlin -Pwekit.abi=arm64-v8a
```

Expected: PASS.

- [ ] **Step 5: Commit and push**

Run:

```bash
git add app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt \
  app/src/main/java/com/Johnny/wcx/loader/utils/ZygiskNativePayload.kt
git commit -m "feat(zygisk): allow early payload native loading"
git push origin feat/wekite-hook-engine
```

---

### Task 5: Implement real native fixture tests and bridge initialization

**Files:**
- Create: `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteNativeFixture.kt`
- Create: `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteNativeSelfTest.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteHookBridge.kt`

- [ ] **Step 1: Create isolated fixture targets**

Create `WeKiteNativeFixture.kt`:

```kotlin
package com.Johnny.wcx.loader.entry.wekite

internal class WeKiteNativeFixture(var seed: Int) {
    fun add(delta: Int): Int = seed + delta

    fun echo(value: String): String = "origin:$value"

    fun fail(message: String): String = throw FixtureException(message)

    internal class FixtureException(message: String) : IllegalStateException(message)

    companion object {
        @JvmStatic
        fun multiply(left: Int, right: Int): Int = left * right
    }
}
```

These methods must remain ordinary non-native, non-interface, non-proxy Java methods.

- [ ] **Step 2: Add native initialization to the bridge**

In `WeKiteHookBridge` companion object add:

```kotlin
@JvmStatic private external fun nativeInitialize(): Boolean

internal fun initializeNativeBackend(): Boolean = nativeInitialize()
```

Keep all existing JNI signatures unchanged.

- [ ] **Step 3: Write the complete native self-test**

Create `WeKiteNativeSelfTest.kt`:

```kotlin
package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.InvocationTargetException

internal object WeKiteNativeSelfTest {
    private const val TAG = "WeKiteNativeSelfTest"

    data class Result(
        val passed: Boolean,
        val checks: List<String>,
        val failure: Throwable? = null,
    )

    fun run(bridge: WeKiteHookBridge): Result {
        val checks = mutableListOf<String>()
        return try {
            testStaticAndOriginal(bridge, checks)
            testInstanceReceiverAndArguments(bridge, checks)
            testExceptionAndAfterRecovery(bridge, checks)
            testConstructorAndNewInstanceOrigin(bridge, checks)
            Result(true, checks).also {
                WeLogger.i(TAG, "SELFTEST PASS: ${checks.joinToString()}")
            }
        } catch (t: Throwable) {
            Result(false, checks, t).also {
                WeLogger.e(TAG, "SELFTEST FAIL after ${checks.joinToString()}", t)
            }
        }
    }

    private fun callback(
        before: (IHookBridge.IMemberHookParam) -> Unit = {},
        after: (IHookBridge.IMemberHookParam) -> Unit = {},
    ) = object : IHookBridge.IMemberHookCallback {
        override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) = before(param)
        override fun afterHookedMember(param: IHookBridge.IMemberHookParam) = after(param)
    }

    private fun testStaticAndOriginal(bridge: WeKiteHookBridge, checks: MutableList<String>) {
        val method = WeKiteNativeFixture::class.java.getDeclaredMethod(
            "multiply",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val order = mutableListOf<String>()
        val high = bridge.hookMethod(method, callback(
            before = { order += "before-high" },
            after = { order += "after-high" },
        ), 100)
        val low = bridge.hookMethod(method, callback(
            before = {
                order += "before-low"
                it.args[0] = 4
            },
            after = { order += "after-low" },
        ), 0)
        try {
            check(method.invoke(null, 3, 5) == 20)
            check(bridge.invokeOriginalMethod(method, null, arrayOf(6, 7)) == 42)
            check(order == listOf("before-high", "before-low", "after-low", "after-high"))
        } finally {
            low.unhook()
            high.unhook()
        }
        val countAfterUnhook = order.size
        check(method.invoke(null, 3, 5) == 15)
        check(order.size == countAfterUnhook)
        checks += "static-original-unhook"
    }

    private fun testInstanceReceiverAndArguments(
        bridge: WeKiteHookBridge,
        checks: MutableList<String>,
    ) {
        val fixture = WeKiteNativeFixture(10)
        val add = WeKiteNativeFixture::class.java.getDeclaredMethod(
            "add",
            Int::class.javaPrimitiveType,
        )
        val normal = bridge.hookMethod(add, callback(before = {
            check(it.thisObject === fixture)
            it.args[0] = 5
        }), 0)
        var early: IHookBridge.MemberUnhookHandle? = null
        try {
            check(add.invoke(fixture, 2) == 15)
            early = bridge.hookMethod(add, callback(before = { it.result = 99 }), 100)
            check(add.invoke(fixture, 2) == 99)
        } finally {
            early?.unhook()
            normal.unhook()
        }

        val echo = WeKiteNativeFixture::class.java.getDeclaredMethod("echo", String::class.java)
        val echoHandle = bridge.hookMethod(echo, callback(before = { it.args[0] = "y" }), 0)
        try {
            check(echo.invoke(fixture, "x") == "origin:y")
            check(bridge.invokeOriginalMethod(echo, fixture, arrayOf("z")) == "origin:z")
        } finally {
            echoHandle.unhook()
        }
        checks += "instance-args-reference"
    }

    private fun testExceptionAndAfterRecovery(
        bridge: WeKiteHookBridge,
        checks: MutableList<String>,
    ) {
        val fixture = WeKiteNativeFixture(1)
        val fail = WeKiteNativeFixture::class.java.getDeclaredMethod("fail", String::class.java)
        var observed: Throwable? = null
        val observer = bridge.hookMethod(fail, callback(after = { observed = it.throwable }), 100)
        val failingAfter = bridge.hookMethod(fail, callback(after = {
            it.result = "temporary"
            throw IllegalArgumentException("callback")
        }), 0)
        var recovery: IHookBridge.MemberUnhookHandle? = null
        try {
            val thrown = runCatching { fail.invoke(fixture, "boom") }.exceptionOrNull()
            val cause = (thrown as? InvocationTargetException)?.targetException ?: thrown
            check(cause is WeKiteNativeFixture.FixtureException && cause.message == "boom")
            check(observed is WeKiteNativeFixture.FixtureException)

            recovery = bridge.hookMethod(fail, callback(after = {
                it.throwable = null
                it.result = "recovered"
            }), 200)
            check(fail.invoke(fixture, "again") == "recovered")
        } finally {
            recovery?.unhook()
            failingAfter.unhook()
            observer.unhook()
        }
        checks += "exception-after-recovery"
    }

    private fun testConstructorAndNewInstanceOrigin(
        bridge: WeKiteHookBridge,
        checks: MutableList<String>,
    ) {
        val ctor = WeKiteNativeFixture::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
        )
        var callbackHits = 0
        val handle = bridge.hookMethod(ctor, callback(before = {
            callbackHits++
            it.args[0] = 8
        }), 0)
        try {
            check(ctor.newInstance(5).seed == 8)
            check(callbackHits == 1)
            check(bridge.newInstanceOrigin(ctor, 11).seed == 11)
            check(callbackHits == 1)
        } finally {
            handle.unhook()
        }
        check(ctor.newInstance(5).seed == 5)
        checks += "constructor-origin-unhook"
    }
}
```

The test deliberately uses reflection for target invocation so the call cannot be Kotlin-inlined before the ArtMethod replacement is installed.

- [ ] **Step 8: Compile the Java/native contract**

Run in the Debian VM:

```bash
./x build --release --abi arm64-v8a --wekite-hook
```

Expected: release APK builds; no unresolved native signature is caught at build time, so save runtime verification for Task 8.

- [ ] **Step 9: Commit and push**

Run:

```bash
git add app/src/main/java/com/Johnny/wcx/loader/entry/wekite \
  app/src/main/rust/wekit-native/src/wekite_hook.rs
git commit -m "test(wekite): add native ArtMethod closure self-test"
git push origin feat/wekite-hook-engine
```

---

### Task 6: Select WeKite only after initialization and self-test pass

**Files:**
- Create: `app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteBackendSelector.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt`
- Modify: `app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt`

- [ ] **Step 1: Implement a narrow selector**

Create `WeKiteBackendSelector.kt`:

```kotlin
package com.Johnny.wcx.loader.entry.wekite

import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.WeLogger

internal object WeKiteBackendSelector {
    private const val TAG = "WeKiteBackendSelector"
    private const val PROPERTY = "debug.wcx.wekite_m2b"

    fun select(processName: String): WeKiteHookBridge? {
        if (!isTargetProcess(processName) || systemProperty(PROPERTY) != "1") return null
        return try {
            NativeLoader.loadZygiskNativeLibrariesEarly()
            val bridge = WeKiteHookBridge()
            check(bridge.initializeNativeBackend()) { "native ART engine initialization failed" }
            val result = WeKiteNativeSelfTest.run(bridge)
            check(result.passed) { "native self-test failed: ${result.failure}" }
            WeLogger.i(TAG, "backend=WeKite process=$processName checks=${result.checks.size}")
            bridge
        } catch (t: Throwable) {
            WeLogger.e(TAG, "backend=LSPlant fallback process=$processName", t)
            null
        }
    }

    internal fun isTargetProcess(processName: String): Boolean =
        processName == "com.tencent.mm:push" ||
            processName.startsWith("com.tencent.mm:appbrand")

    private fun systemProperty(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrDefault("")
}
```

Do not include the main process, tools, sandbox, recovery, or isolated processes.

- [ ] **Step 2: Generalize stored child bridge type without changing main startup**

In `ZygiskEntry.kt` change:

```kotlin
private var hookBridge: IHookBridge? = null
```

Keep an `ArtHookBridge` local named `lsPlantBridge`. After `NativeLoader.configureZygiskPayload(...)`, select only for child processes:

```kotlin
val lsPlantBridge = ArtHookBridge()
val selectedBridge: IHookBridge = if (processName.contains(':')) {
    WeKiteBackendSelector.select(processName) ?: lsPlantBridge
} else {
    lsPlantBridge
}

loaderService = service
hookBridge = selectedBridge

if (processName.contains(':')) {
    installEarlyStartupHooks(selectedBridge, targetPackage)
} else {
    startDeferredStartup(service, lsPlantBridge, apkPath)
}
```

Change `installEarlyStartupHooks` and `installFinalClassLoaderHook` parameters from `ArtHookBridge` to `IHookBridge`. Keep `startDeferredStartup`, `runDeferredStartup`, `installMainStartupGate`, and `postMainStartup` typed as `ArtHookBridge` so the main process cannot accidentally select WeKite.

- [ ] **Step 3: Remove the obsolete M2a startup self-test trigger**

Delete the `debug.wcx.wekite_selftest` block from `StartupAgent.kt`. The M2b selector now runs the stronger self-test before publishing the backend. Keep the rest of `StartupAgent.startup()` unchanged.

- [ ] **Step 4: Verify default behavior by source inspection**

Run:

```bash
rg 'debug\.wcx\.wekite_selftest' app/src/main/java && exit 1 || true
rg 'debug\.wcx\.wekite_m2b' app/src/main/java
rg 'startDeferredStartup\(service, lsPlantBridge' \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt
```

Expected: old property absent; new property appears only in selector; main startup passes concrete LSPlant bridge.

- [ ] **Step 5: Build both APK variants**

Run in the Debian VM:

```bash
./x build --release --abi arm64-v8a
./x build --release --abi arm64-v8a --wekite-hook
```

Expected: both pass. Record both APK SHA-256 hashes and ensure only the test build's `libwekit_native.so` exports WeKite JNI.

- [ ] **Step 6: Commit and push**

Run:

```bash
git add app/src/main/java/com/Johnny/wcx/loader/entry/wekite/WeKiteBackendSelector.kt \
  app/src/main/java/com/Johnny/wcx/loader/entry/zygisk/ZygiskEntry.kt \
  app/src/main/java/com/Johnny/wcx/loader/startup/StartupAgent.kt
git commit -m "feat(zygisk): gate WeKite backend to target child processes"
git push origin feat/wekite-hook-engine
```

---

### Task 7: Add an automated device verifier

**Files:**
- Create: `scripts/wcx-wekite-m2b-probe.sh`

- [ ] **Step 1: Write the device-side probe**

Create an Android shell script that records a log baseline, waits for target processes, and checks exact M2b evidence:

```sh
#!/system/bin/sh
WAIT=${1:-600}
LOG=$(ls -t /sdcard/Android/data/com.tencent.mm/wcx/logs/wcx-*.log 2>/dev/null | head -1)
[ -n "$LOG" ] || { echo "RESULT=HARNESS_ERROR no_log"; exit 2; }
BASE=$(wc -l < "$LOG")
BASE11=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11')
BASE5=$(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=5')
LOGCAT_MARK=$(date '+%m-%d %H:%M:%S.000')
DEADLINE=$(( $(date +%s) + WAIT ))

while [ $(date +%s) -lt $DEADLINE ]; do
  PUSH=$(pidof com.tencent.mm:push)
  AB0=$(pidof com.tencent.mm:appbrand0)
  AB1=$(pidof com.tencent.mm:appbrand1)
  [ -n "$PUSH" ] && { [ -n "$AB0" ] || [ -n "$AB1" ]; } && break
  sleep 5
done

sleep 10
OUT=/data/local/tmp/wcx-wekite-m2b-$(date +%Y%m%d-%H%M%S).log
tail -n +$((BASE + 1)) "$LOG" > "$OUT"
PASS_COUNT=$(grep -ac 'WeKiteNativeSelfTest.*SELFTEST PASS' "$OUT")
BACKENDS=$(grep -ac 'WeKiteBackendSelector.*backend=WeKite' "$OUT")
FAILS=$(grep -acE 'SELFTEST FAIL|backend=LSPlant fallback| E/' "$OUT")
CRASHES=$(logcat -d -v brief -T "$LOGCAT_MARK" 2>/dev/null \
  | grep -cE 'FATAL EXCEPTION|SIGABRT|SIGSEGV')
NEW11=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'status=11') - BASE11 ))
NEW5=$(( $(dumpsys activity exit-info com.tencent.mm 2>/dev/null | grep -c 'reason=5') - BASE5 ))
RECOVERY=$(pidof com.tencent.mm:recovery)

OK=1
[ -z "$PUSH" ] && OK=0
[ -z "$AB0$AB1" ] && OK=0
[ "$PASS_COUNT" -lt 2 ] && OK=0
[ "$BACKENDS" -lt 2 ] && OK=0
[ "$FAILS" -gt 0 ] && OK=0
[ "$CRASHES" -gt 0 ] && OK=0
[ "$NEW11" -gt 0 ] && OK=0
[ "$NEW5" -gt 0 ] && OK=0
[ -n "$RECOVERY" ] && OK=0

echo "push=[$PUSH] appbrand0=[$AB0] appbrand1=[$AB1] recovery=[$RECOVERY]"
echo "selftest_pass=$PASS_COUNT backends=$BACKENDS failures=$FAILS new_status11=$NEW11 new_reason5=$NEW5 crash_markers=$CRASHES"
if [ "$OK" = 1 ]; then
  echo "RESULT=PASS"
else
  echo "RESULT=FAIL"
  echo "ARTIFACT=$OUT"
  grep -aE 'WeKite|ZygiskEntry|StartupAgent| E/' "$OUT" | tail -120
  exit 1
fi
```

- [ ] **Step 2: Make the script executable and syntax-check it**

Run:

```bash
chmod +x scripts/wcx-wekite-m2b-probe.sh
bash -n scripts/wcx-wekite-m2b-probe.sh
```

Expected: PASS.

- [ ] **Step 3: Commit and push**

Run:

```bash
git add scripts/wcx-wekite-m2b-probe.sh
git commit -m "test(zygisk): add WeKite M2b device probe"
git push origin feat/wekite-hook-engine
```

---

### Task 8: Run the full VM build matrix

**Files:**
- No intended source changes; failures create focused fix commits.

- [ ] **Step 1: Verify local Git provenance before sync**

Run:

```bash
git status --short --branch
git rev-parse HEAD
```

Expected: clean branch and local HEAD equals `origin/feat/wekite-hook-engine`.

- [ ] **Step 2: Run shared/native checks in the Debian VM**

Using `C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-remote.sh`, sync and run:

```bash
cargo test -p wekite-art
cargo check -p wekit-native --target aarch64-linux-android
cargo check -p wekit-native --features wekite_hook --target aarch64-linux-android
cargo check -p wcx-zygisk --target aarch64-linux-android
cargo check -p wcx-zygisk --features wcx_hook_wekite --target aarch64-linux-android
```

Expected: all PASS.

- [ ] **Step 3: Build a clean release test APK**

Run through the remote helper:

```bash
wcx-remote.sh release --abi arm64-v8a --wekite-hook
```

Expected: `.git-provenance` reports the current clean HEAD with `dirty=0` and release build succeeds.

- [ ] **Step 4: Pull and identify the exact module APK**

Run:

```bash
wcx-remote.sh pull C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-m2b-dist
APK=$(find C:/Users/kwu89/AppData/Roaming/PiDeck/chat-workspace/wcx-m2b-dist/outputs/zygisk \
  -name '*.apk' -type f | sort | tail -1)
test -n "$APK"
printf 'APK=%s\n' "$APK"
stat -c 'size=%s' "$APK"
sha256sum "$APK"
```

Record path, size, SHA-256, commit, and `dirty=0` in the work log.

- [ ] **Step 5: Build the baseline variant again**

Run:

```bash
wcx-remote.sh release --abi arm64-v8a
```

Expected: baseline release still builds without `wekite_hook`. A test-only feature must not become default.

- [ ] **Step 6: Fix failures with evidence, then rerun the entire matrix**

For each failure:

1. Save the complete command output.
2. Identify the first causal error, not downstream failures.
3. Add or strengthen the smallest reproducer.
4. Make one focused fix.
5. Commit and push the fix.
6. Restart Task 8 from Step 2.

Do not proceed to a device while any VM command fails.

---

### Task 9: Deploy, validate, repair, and restore automatically

**Files:**
- No intended source changes; failures create focused commits and restart Tasks 8-9.

- [ ] **Step 1: Capture and verify the stable device baseline**

Run as root on the device:

```sh
sha256sum /data/adb/modules/zygisk-wcx/module.apk
md5sum /data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so
cp -f /data/adb/modules/zygisk-wcx/module.apk /data/local/tmp/wcx-module-pre-m2b.apk
```

Required values:

```text
module.apk  24de905db1df639df5d43889a2866578a3025e50a24e4dadb4f1a5b43eec1a32
injector    93dcbeea7a5ea0075c36c315696be62f
```

If either value differs, stop and diagnose the baseline before deployment.

- [ ] **Step 2: Install a rollback trap before replacing the APK**

Create `/tmp/wcx-m2b-run.sh` on the host and run all deployment commands from inside it:

```bash
#!/usr/bin/env bash
set -euo pipefail
cleanup() {
  adb shell su -c '
    resetprop --delete debug.wcx.wekite_m2b
    cp -f /data/local/tmp/wcx-module-pre-m2b.apk /data/adb/modules/zygisk-wcx/module.apk
    chmod 0644 /data/adb/modules/zygisk-wcx/module.apk
    chown 0:0 /data/adb/modules/zygisk-wcx/module.apk
  ' || true
}
trap cleanup EXIT INT TERM

# Steps 3-6 are executed here. The trap restores the stable APK on every exit path.
```

Run `chmod +x /tmp/wcx-m2b-run.sh`. Do not deploy outside this wrapper.

- [ ] **Step 3: Deploy only the test module APK and probe script**

Push the clean test APK to `/data/local/tmp/wcx-m2b.apk`, then run:

```sh
cp -f /data/local/tmp/wcx-m2b.apk /data/adb/modules/zygisk-wcx/module.apk
chmod 0644 /data/adb/modules/zygisk-wcx/module.apk
chown 0:0 /data/adb/modules/zygisk-wcx/module.apk
cp -f /data/local/tmp/wcx-wekite-m2b-probe.sh /data/local/tmp/wcx-wekite-m2b-probe.sh
chmod 0755 /data/local/tmp/wcx-wekite-m2b-probe.sh
resetprop debug.wcx.wekite_m2b 1
```

Immediately recheck injector MD5. It must remain `93dc…`.

- [ ] **Step 4: Restart only WeChat and force target child processes**

Run:

```sh
for p in $(pidof com.tencent.mm); do kill -9 "$p" 2>/dev/null; done
sleep 4
am start -n com.tencent.mm/.ui.LauncherUI
```

Start the AppBrand activity directly as root and verify that an appbrand process appears:

```sh
am start -n com.tencent.mm/.plugin.appbrand.ui.AppBrandUI >/data/local/tmp/wcx-appbrand-start.txt 2>&1 || \
am start -n com.tencent.mm/.plugin.appbrand.ui.AppBrandUI1 >>/data/local/tmp/wcx-appbrand-start.txt 2>&1
for i in $(seq 1 30); do
  [ -n "$(pidof com.tencent.mm:appbrand0)$(pidof com.tencent.mm:appbrand1)" ] && break
  sleep 1
done
[ -n "$(pidof com.tencent.mm:appbrand0)$(pidof com.tencent.mm:appbrand1)" ] || {
  cat /data/local/tmp/wcx-appbrand-start.txt
  exit 1
}
```

Do not reboot the phone or kill zygote.

- [ ] **Step 5: Run M2b acceptance and main-process cold-start probes**

Run:

```sh
sh /data/local/tmp/wcx-wekite-m2b-probe.sh 600
sh /data/local/tmp/wcx-injection-probe.sh boot 25
sh /data/local/tmp/wcx-injection-probe.sh direct 25
```

Required:

- at least two `WeKiteNativeSelfTest SELFTEST PASS` records (`:push` plus one appbrand);
- matching `backend=WeKite` records;
- main process still reports LSPlant initialization, at least 100 enabled features, and LauncherUI proxy;
- no new WCX E, recovery process, status=11, reason=5, SIGABRT, or SIGSEGV.

- [ ] **Step 6: Repeat stability cycles**

Run the validated injection probe matrix:

```text
boot mode:   6 consecutive PASS
 direct mode: 4 consecutive PASS
```

After the final cycle, keep WeChat alive for at least 10 minutes and verify that `:push` and the tested appbrand PID do not enter a crash/restart loop.

- [ ] **Step 7: On failure, restore first and diagnose second**

Run the rollback trap immediately. Preserve:

- the per-run WCX log window;
- `dumpsys activity exit-info com.tencent.mm`;
- relevant logcat timestamps;
- tombstone path if present;
- test APK hash and Git provenance.

Then apply systematic debugging:

1. reproduce with the smallest fixture or one target process;
2. identify Java dispatch vs JNI vs ArtMethod mutation vs startup timing;
3. add a deterministic assertion/log for that boundary;
4. create one focused fix commit and push;
5. restart Task 8 from Step 2 and Task 9 from Step 1.

Continue until the entire matrix passes; do not waive a failing check as flaky without a demonstrated harness defect.

- [ ] **Step 8: Restore the stable baseline even after success**

Run the rollback trap and verify:

```sh
[ -z "$(resetprop debug.wcx.wekite_m2b)" ]
sha256sum /data/adb/modules/zygisk-wcx/module.apk
md5sum /data/adb/modules/zygisk-wcx/zygisk/arm64-v8a.so
```

Then restart WeChat once and verify 102 features, LauncherUI proxy, no recovery, no self-test logs, and zero new WCX E.

---

### Task 10: Record evidence and close M2b

**Files:**
- Modify: `docs/work-items/wekite-hook-engine-feasibility.md`

- [ ] **Step 1: Add an M2b evidence section**

Record:

- final commit SHA and remote branch SHA;
- test APK path, size, SHA-256, and clean provenance;
- stable APK and injector hashes before/after;
- processes tested and their `SELFTEST PASS` evidence;
- static, instance, constructor, exception, invokeOriginal, and unhook results;
- cold-start pass counts and 10-minute PID observation;
- any failure/fix iterations;
- explicit limitation that resident LSPlant was still initialized in child processes.

- [ ] **Step 2: Self-review the documentation against actual command output**

Run:

```bash
rg 'TBD|TODO|待定|待补' docs/work-items/wekite-hook-engine-feasibility.md
git diff --check
git status --short
```

Expected: no placeholders, no whitespace errors, and only the intended documentation change.

- [ ] **Step 3: Commit and push the evidence**

Run:

```bash
git add docs/work-items/wekite-hook-engine-feasibility.md
git commit -m "docs(wekite): record M2b native closure"
git push origin feat/wekite-hook-engine
```

- [ ] **Step 4: Final verification before declaring completion**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git rev-parse origin/feat/wekite-hook-engine
```

Expected: clean worktree and identical local/remote SHAs. Completion may be reported only with the VM matrix, device matrix, rollback verification, and exact hashes summarized.
