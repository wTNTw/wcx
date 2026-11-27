#!/usr/bin/env python3
"""Pack the log-only probe and check the structure of Zygisk archives without executing them."""

import argparse
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_DEFLATED, BadZipFile, ZipFile

LOADER = "zygisk/arm64-v8a.so"
PROFILES = {
    "probe": ("wcx_zygisk_probe", "dev.wcx.zygisk.probe", "payload/probe.apk"),
    "release": ("wcx_zygisk", "com.Johnny.wcx", "payload/wcx.apk"),
}
LEGACY_INSTALLER = {
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
}
REQUIRED_NATIVE_LIBS = {"libwekit_native.so", "libdexkit.so", "libmmkv.so"}
XPOSED_MANIFEST_NAMES = {
    "xposedmodule", "xposeddescription", "xposedminversion", "xposedscope",
    "xposedsharedprefs", "de.robv.android.xposed.category.MODULE_SETTINGS",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*command):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise ValueError(f"{Path(command[0]).name} failed: {detail[:400] or result.returncode}")
    return result.stdout


def android_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "set ANDROID_HOME to the Debian Android SDK directory")
    sdk = Path(sdk)
    build_tools = sdk / "build-tools/37.0.0"
    ndk = Path(os.environ.get("ANDROID_NDK_HOME") or sdk / "ndk/30.0.14904198")
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    return build_tools / "aapt2", build_tools / "apksigner", readelf


def inventory(zip_file):
    names = set()
    files = set()
    for info in zip_file.infolist():
        name = info.filename.rstrip("/")
        require(name and "\\" not in name and info.filename == info.orig_filename
                and not any(part in {"", ".", ".."} for part in name.split("/")),
                f"invalid archive path: {info.filename!r}")
        require(name not in names, f"duplicate archive path: {name}")
        require(not stat.S_ISLNK(info.external_attr >> 16), f"symlink is not allowed in archive: {name}")
        names.add(name)
        if not info.is_dir():
            files.add(name)
    return files


def module_properties(zip_file, expected_id):
    props = {}
    for line in zip_file.read("module.prop").decode("utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep and key and key not in props, f"invalid or duplicate module property: {key!r}")
        props[key] = value
    require(props.get("id") == expected_id, f"expected module id {expected_id}")
    require(props.get("name", "").strip(), "module.prop must declare a name")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]{0,63}", props.get("version", "")),
            "invalid module version")
    require(re.fullmatch(r"[1-9][0-9]{0,9}", props.get("versionCode", "")), "invalid module versionCode")
    return props["version"], props["versionCode"]


def verify_apk(apk, expected_package, release, tools):
    aapt2, apksigner, _ = tools
    with ZipFile(apk) as archive:
        names = inventory(archive)
        require({"AndroidManifest.xml", "classes.dex"} <= names, "payload is missing its manifest or classes.dex")
        require("assets/xposed_init" not in names and not any(n.startswith("META-INF/xposed/") for n in names),
                "payload contains Xposed entry metadata")
        libs = {n for n in names if n.startswith("lib/") and n.endswith(".so")}
        require(all(n.startswith("lib/arm64-v8a/") for n in libs), "payload contains a non-ARM64 library")
        if release:
            require("resources.arsc" in names, "release payload is missing resources.arsc")
            require({f"lib/arm64-v8a/{name}" for name in REQUIRED_NATIVE_LIBS} <= libs,
                    "release payload is missing WCX ARM64 libraries")
    run(apksigner, "verify", apk)
    manifest = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    require(not any(f'"{marker}"' in manifest for marker in XPOSED_MANIFEST_NAMES),
            "payload manifest contains Xposed module declarations")
    badging = run(aapt2, "dump", "badging", apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package and package[1] == expected_package, f"expected payload package {expected_package}")
    require(not release or "application-debuggable" not in badging.splitlines(), "release payload is debuggable")
    return package[3], package[2]


def verify_loader(loader, tools):
    metadata = run(tools[2], "-h", "--dyn-syms", "--wide", loader)
    require(re.search(r"Class:\s+ELF64\b", metadata) and re.search(r"Machine:\s+AArch64\b", metadata)
            and re.search(r"Type:\s+DYN\b", metadata), "loader must be an AArch64 shared library")
    require(re.search(r"\bFUNC\s+GLOBAL\s+DEFAULT\s+[0-9]+\s+zygisk_module_entry$", metadata, re.M),
            "loader does not export zygisk_module_entry")


def verify_zip(path, profile, tools):
    module_id, apk_package, payload = PROFILES[profile]
    release = profile == "release"
    required = {"module.prop", LOADER, payload}
    if release:
        required.add("customize.sh")
    with ZipFile(path) as archive, tempfile.TemporaryDirectory() as work:
        names = inventory(archive)
        legacy_layout = release and names == (required | LEGACY_INSTALLER)
        require(names == required or legacy_layout,
                f"unexpected {profile} archive entries (missing={sorted(required - names)}, extra={sorted(names - required)})")
        version = module_properties(archive, module_id)
        require(archive.testzip() is None, "module ZIP CRC check failed")
        work = Path(work)
        apk, loader = work / "payload.apk", work / "loader.so"
        for entry, destination in ((payload, apk), (LOADER, loader)):
            with archive.open(entry) as source, destination.open("wb") as out:
                shutil.copyfileobj(source, out)
        verify_loader(loader, tools)
        require(verify_apk(apk, apk_package, release, tools) == version,
                "payload and module.prop versions differ")
    return version


def pack_probe(apk, module, output, tools):
    require(output.resolve() not in {apk.resolve(), module.resolve()}, "output may not replace an input")
    require(output.suffix.lower() == ".zip", "output must have a .zip extension")
    with ZipFile(module) as source:
        require(inventory(source) == {"module.prop", LOADER}, "expected the log-only probe module archive")
        module_id, apk_package, payload = PROFILES["probe"]
        version = module_properties(source, module_id)
        require(verify_apk(apk, apk_package, False, tools) == version, "probe APK and module versions differ")
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=f".{output.name}.", delete=False) as temp:
            temporary_zip = Path(temp.name)
        try:
            with ZipFile(temporary_zip, "w", compression=ZIP_DEFLATED) as archive:
                archive.writestr("module.prop", source.read("module.prop"))
                with source.open(LOADER) as library, archive.open(LOADER, "w") as out:
                    shutil.copyfileobj(library, out)
                archive.write(apk, payload)
            verify_zip(temporary_zip, "probe", tools)
            os.replace(temporary_zip, output)
        finally:
            temporary_zip.unlink(missing_ok=True)
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify", help="validate an archive without installing or executing it")
    verify.add_argument("archive", type=Path)
    verify.add_argument("--profile", choices=PROFILES, required=True)
    probe = commands.add_parser("pack-probe", help="package only the existing diagnostic APK and log-only module")
    probe.add_argument("--apk", type=Path, required=True)
    probe.add_argument("--module", type=Path, required=True)
    probe.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        tools = android_tools()
        if args.command == "pack-probe":
            version, code = pack_probe(args.apk, args.module, args.output, tools)
            print(f"Probe ZIP written: {args.output} ({version}, {code}); not a WCX release")
        else:
            version, code = verify_zip(args.archive, args.profile, tools)
            print(f"Archive checks passed ({args.profile}, {version}, {code}); runtime not verified")
    except (BadZipFile, OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
