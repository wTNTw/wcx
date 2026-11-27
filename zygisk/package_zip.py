#!/usr/bin/env python3
"""Assemble and validate WCX Zygisk module archives.

Three manager profiles are produced from the same payload and loader:

    magisk     module.prop, customize.sh, uninstall.sh, installer scripts,
               zygisk/arm64-v8a.so, module.apk
    kernelsu   the above plus the target WebUI and its backend (config.sh/action.sh)
    apatch     same layout as kernelsu, separate module description

Nothing here installs, flashes or downloads anything. Every input is an
explicit path; the packer never scans build output directories.
"""

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

MODULE_ID = "wcx_zygisk"
PROBE_MODULE_ID = "wcx_zygisk_probe"
PAYLOAD_PACKAGE = "com.Johnny.wcx"
PROBE_PACKAGE = "dev.wcx.zygisk.probe"

LOADER = "zygisk/arm64-v8a.so"
PAYLOAD = "module.apk"
PROBE_PAYLOAD = "payload/probe.apk"

LEGACY_INSTALLER = {
    "META-INF/com/google/android/update-binary",
    "META-INF/com/google/android/updater-script",
}
WEBUI_ENTRIES = {
    "config.sh",
    "action.sh",
    "webroot/index.html",
    "webroot/css/app.css",
    "webroot/js/app.js",
    "webroot/js/bridge.js",
    "webroot/js/kernelsu.js",
}
COMMON_ENTRIES = {"module.prop", "customize.sh", "uninstall.sh", LOADER, PAYLOAD} | LEGACY_INSTALLER
REQUIRED_NATIVE_LIBS = {
    "libwekit_native.so",
    "libdexkit.so",
    "libmmkv.so",
    "libandroidx.graphics.path.so",
}
# Declarations that make an APK an Xposed module. Package-visibility <queries>
# entries and the LSPosed settings-shortcut category are deliberately not here:
# they do not make the payload loadable by a framework.
XPOSED_MANIFEST_MARKERS = {
    "xposedmodule",
    "xposeddescription",
    "xposedminversion",
    "xposedscope",
    "xposedsharedprefs",
}
XPOSED_SERVICE_MARKER = "io.github.libxposed.service.XposedProvider"
WECHAT_PACKAGE_PREFIX = "com.tencent.mm"


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def run(*command):
    result = subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise VerificationError(
            f"{Path(command[0]).name} failed: {detail[:400] or result.returncode}"
        )
    return result.stdout


# ── Android SDK / NDK tools ───────────────────────────────────────────────────


def android_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(sdk, "set ANDROID_HOME to the Android SDK directory")
    sdk = Path(sdk)
    build_tools = sdk / "build-tools/37.0.0"
    ndk = Path(os.environ.get("ANDROID_NDK_HOME") or sdk / "ndk/30.0.14904198")
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    return build_tools / "aapt2", build_tools / "apksigner", readelf


# ── Archive layout (pure Python, no Android tooling) ─────────────────────────


def inventory(zip_file):
    """Return the set of regular-file entries, rejecting unsafe archive paths."""
    names = set()
    files = set()
    for info in zip_file.infolist():
        name = info.filename.rstrip("/")
        require(
            name
            and "\\" not in name
            and info.filename == info.orig_filename
            and not any(part in {"", ".", ".."} for part in name.split("/")),
            f"invalid archive path: {info.filename!r}",
        )
        require(name not in names, f"duplicate archive path: {name}")
        require(
            not stat.S_ISLNK(info.external_attr >> 16),
            f"symlink is not allowed in archive: {name}",
        )
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
    require(
        re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+~-]{0,63}", props.get("version", "")),
        "invalid module version",
    )
    require(re.fullmatch(r"[1-9][0-9]{0,9}", props.get("versionCode", "")), "invalid module versionCode")
    return props["version"], props["versionCode"]


def expected_entries(manager):
    require(manager in MANAGERS, f"unknown manager profile: {manager}")
    entries = set(COMMON_ENTRIES)
    if manager != "magisk":
        entries |= WEBUI_ENTRIES
    return entries


def check_layout(names, manager):
    expected = expected_entries(manager)
    missing = sorted(expected - names)
    extra = sorted(names - expected)
    require(not missing and not extra, f"unexpected {manager} layout (missing={missing}, extra={extra})")


def check_installer_contents(zip_file, manager):
    """The installer must refuse non-ARM64 devices and unset Zygisk."""
    customize = zip_file.read("customize.sh").decode("utf-8")
    require("arm64" in customize, "customize.sh does not check the architecture")
    require("ZYGISK" in customize.upper(), "customize.sh does not check Zygisk availability")
    require("rm -rf /" not in customize, "customize.sh contains a destructive command")
    uninstall = zip_file.read("uninstall.sh").decode("utf-8")
    require("com.tencent.mm" not in uninstall, "uninstall.sh must not touch host app data")
    if manager != "magisk":
        config = zip_file.read("config.sh").decode("utf-8")
        require(
            WECHAT_PACKAGE_PREFIX in config,
            "config.sh must restrict targets to the WeChat package prefix",
        )
        require(
            "eval" not in config.split(),
            "config.sh must not evaluate dynamic shell input",
        )


# ── Payload / loader checks (need Android tooling) ───────────────────────────


def verify_apk(apk, expected_package, release, tools):
    aapt2, apksigner, _ = tools
    with ZipFile(apk) as archive:
        names = inventory(archive)
        require(
            {"AndroidManifest.xml", "classes.dex"} <= names,
            "payload is missing its manifest or classes.dex",
        )
        require(
            "assets/xposed_init" not in names
            and not any(n.startswith("META-INF/xposed/") for n in names),
            "payload contains Xposed entry metadata",
        )
        libs = {n for n in names if n.startswith("lib/") and n.endswith(".so")}
        require(all(n.startswith("lib/arm64-v8a/") for n in libs), "payload contains a non-ARM64 library")
        if release:
            require("resources.arsc" in names, "release payload is missing resources.arsc")
            require(
                {f"lib/arm64-v8a/{name}" for name in REQUIRED_NATIVE_LIBS} <= libs,
                "release payload is missing WCX ARM64 libraries",
            )
    run(apksigner, "verify", apk)
    manifest = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    require(
        not any(f'"{marker}"' in manifest for marker in XPOSED_MANIFEST_MARKERS),
        "payload manifest contains Xposed module declarations",
    )
    require(
        XPOSED_SERVICE_MARKER not in manifest,
        "payload manifest exposes the libxposed service provider",
    )
    badging = run(aapt2, "dump", "badging", apk)
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M
    )
    require(package and package[1] == expected_package, f"expected payload package {expected_package}")
    require(
        not release or "application-debuggable" not in badging.splitlines(),
        "release payload is debuggable",
    )
    return package[3], package[2]


def verify_loader(loader, tools):
    metadata = run(tools[2], "-h", "--dyn-syms", "--wide", loader)
    require(
        re.search(r"Class:\s+ELF64\b", metadata)
        and re.search(r"Machine:\s+AArch64\b", metadata)
        and re.search(r"Type:\s+DYN\b", metadata),
        "loader must be an AArch64 shared library",
    )
    require(
        re.search(r"\bFUNC\s+GLOBAL\s+DEFAULT\s+[0-9]+\s+zygisk_module_entry$", metadata, re.M),
        "loader does not export zygisk_module_entry",
    )


# ── Full verification ────────────────────────────────────────────────────────


def verify_zip(path, profile, tools=None):
    path = Path(path)
    require(path.is_file(), f"archive not found: {path}")
    with ZipFile(path) as archive:
        names = inventory(archive)
        require(archive.testzip() is None, "module ZIP CRC check failed")
        if profile == "probe":
            require(
                names == {"module.prop", LOADER, PROBE_PAYLOAD},
                "unexpected probe archive entries",
            )
            version = module_properties(archive, PROBE_MODULE_ID)
            if tools is not None:
                with tempfile.TemporaryDirectory() as work:
                    apk, loader = Path(work) / "payload.apk", Path(work) / "loader.so"
                    for entry, destination in ((PROBE_PAYLOAD, apk), (LOADER, loader)):
                        with archive.open(entry) as source, destination.open("wb") as out:
                            shutil.copyfileobj(source, out)
                    verify_loader(loader, tools)
                    require(
                        verify_apk(apk, PROBE_PACKAGE, False, tools) == version,
                        "probe APK and module versions differ",
                    )
            return version

        check_layout(names, profile)
        check_installer_contents(archive, profile)
        version = module_properties(archive, MODULE_ID)
        if tools is None:
            return version
        with tempfile.TemporaryDirectory() as work:
            apk, loader = Path(work) / "payload.apk", Path(work) / "loader.so"
            for entry, destination in ((PAYLOAD, apk), (LOADER, loader)):
                with archive.open(entry) as source, destination.open("wb") as out:
                    shutil.copyfileobj(source, out)
            verify_loader(loader, tools)
            require(
                verify_apk(apk, PAYLOAD_PACKAGE, True, tools) == version,
                "payload and module.prop versions differ",
            )
    return version


MANAGERS = ("magisk", "kernelsu", "apatch")
PROFILES = MANAGERS + ("probe",)


# ── Packing ──────────────────────────────────────────────────────────────────


def pack_release(apk, loader, template_dir, output, manager, tools):
    apk, loader, template_dir, output = (
        Path(apk),
        Path(loader),
        Path(template_dir),
        Path(output),
    )
    require(manager in MANAGERS, f"unknown manager profile: {manager}")
    require(apk.is_file(), f"payload APK not found: {apk}")
    require(loader.is_file(), f"loader not found: {loader}")
    require(template_dir.is_dir(), f"template directory not found: {template_dir}")
    require(output.suffix.lower() == ".zip", "output must have a .zip extension")
    require(
        output.resolve() not in {apk.resolve(), loader.resolve()},
        "output may not replace an input",
    )

    version, version_code = verify_apk(apk, PAYLOAD_PACKAGE, True, tools)
    verify_loader(loader, tools)

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(dir=output.parent, prefix=f".{output.name}.")
    ) / output.name
    try:
        with ZipFile(temporary, "w", compression=ZIP_DEFLATED) as archive:
            archive.writestr("module.prop", render_module_prop(template_dir, manager, version, version_code))
            for name in sorted(expected_entries(manager)):
                if name == "module.prop":
                    continue
                if name == LOADER:
                    archive.write(loader, LOADER)
                elif name == PAYLOAD:
                    archive.write(apk, PAYLOAD)
                else:
                    archive.write(template_dir / name, name)
        # Validate before publishing so a broken archive never replaces a good one.
        require(verify_zip(temporary, manager) == (version, version_code), "packed archive failed validation")
        os.replace(temporary, output)
    finally:
        shutil.rmtree(temporary.parent, ignore_errors=True)
    return version, version_code


def render_module_prop(template_dir, manager, version, version_code):
    template = (template_dir / "module.prop.in").read_text(encoding="utf-8")
    rendered = (
        template.replace("@VERSION_NAME@", version)
        .replace("@VERSION_CODE@", str(version_code))
        .replace("@MANAGER@", manager)
    )
    require("@MANAGER@" not in rendered, "module.prop template has an unrendered placeholder")
    require("@VERSION_NAME@" not in rendered, "module.prop template has an unrendered placeholder")
    return rendered


def pack_probe(apk, module, output, tools):
    require(output.resolve() not in {apk.resolve(), module.resolve()}, "output may not replace an input")
    require(output.suffix.lower() == ".zip", "output must have a .zip extension")
    with ZipFile(module) as source:
        require(inventory(source) == {"module.prop", LOADER}, "expected the log-only probe module archive")
        version = module_properties(source, PROBE_MODULE_ID)
        require(verify_apk(apk, PROBE_PACKAGE, False, tools) == version, "probe APK and module versions differ")
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=f".{output.name}.", delete=False) as temp:
            temporary_zip = Path(temp.name)
        try:
            with ZipFile(temporary_zip, "w", compression=ZIP_DEFLATED) as archive:
                archive.writestr("module.prop", source.read("module.prop"))
                with source.open(LOADER) as library, archive.open(LOADER, "w") as out:
                    shutil.copyfileobj(library, out)
                archive.write(apk, PROBE_PAYLOAD)
            verify_zip(temporary_zip, "probe", tools)
            os.replace(temporary_zip, output)
        finally:
            temporary_zip.unlink(missing_ok=True)
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    verify = commands.add_parser("verify", help="validate an archive without installing it")
    verify.add_argument("archive", type=Path)
    verify.add_argument("--profile", choices=PROFILES, required=True)

    pack = commands.add_parser("pack", help="assemble one manager archive from explicit inputs")
    pack.add_argument("--apk", type=Path, required=True)
    pack.add_argument("--loader", type=Path, required=True)
    pack.add_argument("--manager", choices=MANAGERS, required=True)
    pack.add_argument("--template-dir", type=Path)
    pack.add_argument("--output", type=Path, required=True)

    probe = commands.add_parser("pack-probe", help="package the diagnostic APK and log-only module")
    probe.add_argument("--apk", type=Path, required=True)
    probe.add_argument("--module", type=Path, required=True)
    probe.add_argument("--output", type=Path, required=True)

    args = parser.parse_args()
    try:
        tools = android_tools()
        if args.command == "pack-probe":
            version = pack_probe(args.apk, args.module, args.output, tools)
            print(f"Probe ZIP written: {args.output} ({version}); not a WCX release")
        elif args.command == "pack":
            template_dir = args.template_dir or Path(__file__).parent / "module"
            version, code = pack_release(
                args.apk, args.loader, template_dir, args.output, args.manager, tools
            )
            print(f"Archive written: {args.output} ({args.manager}, {version}, {code})")
        else:
            version = verify_zip(args.archive, args.profile, tools)
            print(f"Archive checks passed ({args.profile}, {version}); runtime not verified")
    except (BadZipFile, OSError, VerificationError, KeyError, RuntimeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
