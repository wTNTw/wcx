use std::env;
use std::path::PathBuf;

/// 依次尝试 WCX_ANDROID_NDK / ANDROID_NDK_HOME / ANDROID_NDK_ROOT / $ANDROID_HOME/ndk/<最新>
fn find_ndk() -> PathBuf {
    if let Some(dir) = env::var_os("WCX_ANDROID_NDK").or_else(|| env::var_os("ANDROID_NDK_HOME"))
        .or_else(|| env::var_os("ANDROID_NDK_ROOT"))
    {
        let p = PathBuf::from(dir);
        if p.is_dir() {
            return p;
        }
    }
    let sdk = ["ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(|k| env::var_os(k))
        .map(PathBuf::from)
        .find(|p| p.is_dir());
    if let Some(sdk) = sdk {
        let ndk_root = sdk.join("ndk");
        if let Ok(entries) = std::fs::read_dir(&ndk_root) {
            let mut dirs: Vec<PathBuf> = entries
                .filter_map(|e| e.ok())
                .map(|e| e.path())
                .filter(|p| p.is_dir())
                .collect();
            dirs.sort();
            if let Some(last) = dirs.pop() {
                return last;
            }
        }
    }
    panic!("找不到 Android NDK：请设置 WCX_ANDROID_NDK 或 ANDROID_NDK_HOME");
}

fn main() {
    println!("cargo:rerun-if-env-changed=WCX_ANDROID_NDK");
    println!("cargo:rerun-if-env-changed=WCX_ANDROID_API");
    println!("cargo:rerun-if-env-changed=CMAKE");
    let android = env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android");
    let manifest = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").unwrap());
    let root = manifest.parent().unwrap().parent().unwrap();
    for source in [
        manifest.join("CMakeLists.txt"),
        manifest.join("cpp"),
        root.join("third_party/xz-embedded/linux"),
        root.join("third_party/xz-embedded/userspace"),
    ] {
        println!("cargo:rerun-if-changed={}", source.display());
    }

    let mut config = cmake::Config::new(&manifest);
    config.generator("Ninja").profile("RelWithDebInfo");
    if android {
        assert_eq!(
            env::var("TARGET").unwrap(),
            "aarch64-linux-android",
            "the Zygisk loader currently supports arm64-v8a only"
        );
        let ndk = find_ndk();
        let api = env::var("WCX_ANDROID_API").unwrap_or_else(|_| "28".to_string());
        config
            .define(
                "CMAKE_TOOLCHAIN_FILE",
                ndk.join("build/cmake/android.toolchain.cmake"),
            )
            .define("ANDROID_ABI", "arm64-v8a")
            .define("ANDROID_PLATFORM", format!("android-{api}"))
            .define("ANDROID_STL", "c++_static");
        let lsplant = root.join("third_party/lsplant");
        println!(
            "cargo:rerun-if-changed={}",
            lsplant.join("lsplant/src/main/jni").display()
        );
    }
    let install = config.build();

    println!(
        "cargo:rustc-link-search=native={}",
        install.join("lib").display()
    );
    println!("cargo:rustc-link-lib=static=wcx_xz");
    if !android {
        return;
    }
    for library in [
        "dobby",
        "wcx_lsplant_bridge",
        "lsplant_static",
        "dex_builder_static",
        "wcx_compiler_rt",
    ] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    // The loader is extracted on its own by the module installer. Do not add
    // libc++_shared.so (or another application-private shared library) dependencies.
    // CMake copies only these two archives into our native search directory.
    // Adding the NDK's generic library directory to -L would shadow the Clang
    // driver's API-specific libc.so with libc.a (and duplicate Rust symbols).
    // The Android Clang driver adds the matching libunwind.a automatically.
    for library in ["c++_static", "c++abi"] {
        println!("cargo:rustc-link-lib=static={library}");
    }
    for library in ["log", "z", "dl", "m"] {
        println!("cargo:rustc-link-lib={library}");
    }
    println!("cargo:rustc-link-arg=-Wl,--exclude-libs,ALL");
    println!("cargo:rustc-link-arg=-Wl,--no-undefined");
}
