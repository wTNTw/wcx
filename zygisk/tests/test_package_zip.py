#!/usr/bin/env python3
"""Unit tests for the archive validator and packer layout rules.

These tests do not need the Android SDK: they exercise the pure-Python parts
(archive layout, module metadata, installer content) and the atomic packing
path with the Android tool checks stubbed out.

Run: python -m unittest discover -s zygisk/tests -v
"""

import importlib.util
import io
import os
import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "package_zip.py"
spec = importlib.util.spec_from_file_location("package_zip", MODULE_PATH)
pz = importlib.util.module_from_spec(spec)
sys.modules["package_zip"] = pz
spec.loader.exec_module(pz)

MODULE_PROP = """id=wcx_zygisk
name=WCX (Zygisk / {manager})
version=v348
versionCode=348
author=WCX
description=test
"""

CUSTOMIZE = """#!/system/bin/sh
[ "$ARCH" = "arm64" ] || abort "arm64 only"
zygisk_state=unknown
"""

UNINSTALL = """#!/system/bin/sh
rm -f /data/adb/wcx_zygisk/injection-targets.tsv
"""

CONFIG = """#!/system/bin/sh
TARGET_PACKAGE_PREFIX=com.tencent.mm
case "$1" in
  list) echo "$TARGET_PACKAGE_PREFIX" ;;
esac
"""


def template_dir(root: Path) -> Path:
    directory = root / "module"
    (directory / "webroot/css").mkdir(parents=True)
    (directory / "webroot/js").mkdir(parents=True)
    (directory / "module.prop.in").write_text(
        "id=wcx_zygisk\nname=WCX @MANAGER@\nversion=@VERSION_NAME@\n"
        "versionCode=@VERSION_CODE@\n",
        encoding="utf-8",
    )
    (directory / "customize.sh").write_text(CUSTOMIZE, encoding="utf-8")
    (directory / "uninstall.sh").write_text(UNINSTALL, encoding="utf-8")
    (directory / "config.sh").write_text(CONFIG, encoding="utf-8")
    (directory / "action.sh").write_text("#!/system/bin/sh\necho ok\n", encoding="utf-8")
    for name in ("index.html", "css/app.css", "js/app.js", "js/bridge.js", "js/kernelsu.js"):
        (directory / "webroot" / name).write_text("x", encoding="utf-8")
    for name in pz.LEGACY_INSTALLER:
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("#!/sbin/sh\n", encoding="utf-8")
    return directory


def write_module(archive_path: Path, manager: str, *, overrides=None, extra=None, drop=()):
    """Write a syntactically valid module archive for `manager`."""
    overrides = overrides or {}
    entries = dict()
    entries["module.prop"] = MODULE_PROP.format(manager=manager)
    entries["customize.sh"] = CUSTOMIZE
    entries["uninstall.sh"] = UNINSTALL
    entries[pz.LOADER] = b"\x7fELF fake loader"
    entries[pz.PAYLOAD] = b"PK fake payload"
    for name in pz.LEGACY_INSTALLER:
        entries[name] = "#!/sbin/sh\n"
    if manager != "magisk":
        entries["config.sh"] = CONFIG
        entries["action.sh"] = "#!/system/bin/sh\n"
        for name in (
            "webroot/index.html",
            "webroot/css/app.css",
            "webroot/js/app.js",
            "webroot/js/bridge.js",
            "webroot/js/kernelsu.js",
        ):
            entries[name] = "x"
    entries.update(overrides)
    for name in drop:
        entries.pop(name, None)
    if extra:
        entries.update(extra)

    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries.items():
            data = value.encode() if isinstance(value, str) else value
            archive.writestr(name, data)
    return entries


class LayoutTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_each_manager_layout_is_accepted(self):
        for manager in pz.MANAGERS:
            with self.subTest(manager=manager):
                archive = self.root / f"{manager}.zip"
                write_module(archive, manager)
                self.assertEqual(pz.verify_zip(archive, manager), ("v348", "348"))

    def test_missing_loader_is_rejected(self):
        archive = self.root / "magisk.zip"
        write_module(archive, "magisk", drop=(pz.LOADER,))
        with self.assertRaises(pz.VerificationError) as caught:
            pz.verify_zip(archive, "magisk")
        self.assertIn("missing", str(caught.exception))

    def test_extra_entry_is_rejected(self):
        archive = self.root / "magisk.zip"
        write_module(archive, "magisk", extra={"payload/extra.bin": b"x"})
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "magisk")

    def test_webui_entries_are_required_for_kernelsu(self):
        archive = self.root / "ksu.zip"
        write_module(archive, "kernelsu", drop=("webroot/js/app.js",))
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "kernelsu")

    def test_magisk_must_not_ship_a_webui(self):
        archive = self.root / "magisk.zip"
        write_module(archive, "magisk", extra={"webroot/index.html": "x"})
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "magisk")

    def test_wrong_module_id_is_rejected(self):
        archive = self.root / "magisk.zip"
        write_module(
            archive,
            "magisk",
            overrides={"module.prop": MODULE_PROP.format(manager="magisk").replace("wcx_zygisk", "other")},
        )
        with self.assertRaises(pz.VerificationError) as caught:
            pz.verify_zip(archive, "magisk")
        self.assertIn("module id", str(caught.exception))

    def test_installer_must_check_architecture_and_zygisk(self):
        archive = self.root / "magisk.zip"
        write_module(archive, "magisk", overrides={"customize.sh": "#!/system/bin/sh\necho hi\n"})
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "magisk")

    def test_uninstall_must_not_touch_host_data(self):
        archive = self.root / "magisk.zip"
        write_module(
            archive,
            "magisk",
            overrides={"uninstall.sh": "#!/system/bin/sh\nrm -rf /data/data/com.tencent.mm\n"},
        )
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "magisk")

    def test_traversal_path_is_rejected(self):
        archive = self.root / "bad.zip"
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr("../escape.sh", "x")
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "magisk")

    def test_duplicate_path_is_rejected(self):
        archive = self.root / "dup.zip"
        write_module(archive, "magisk")
        with zipfile.ZipFile(archive, "a") as zf:
            zf.writestr("module.prop", "id=wcx_zygisk\n")
        with self.assertRaises(pz.VerificationError) as caught:
            pz.verify_zip(archive, "magisk")
        self.assertIn("duplicate", str(caught.exception))

    def test_symlink_entry_is_rejected(self):
        archive = self.root / "link.zip"
        write_module(archive, "magisk")
        with zipfile.ZipFile(archive, "a") as zf:
            info = zipfile.ZipInfo("evil.so")
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            zf.writestr(info, "/system/bin/sh")
        with self.assertRaises(pz.VerificationError) as caught:
            pz.verify_zip(archive, "magisk")
        self.assertIn("symlink", str(caught.exception))

    def test_unknown_profile_is_rejected(self):
        archive = self.root / "magisk.zip"
        write_module(archive, "magisk")
        with self.assertRaises(pz.VerificationError):
            pz.verify_zip(archive, "nonsense")


class PackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.templates = template_dir(self.root)
        self.apk = self.root / "payload.apk"
        with zipfile.ZipFile(self.apk, "w") as zf:
            zf.writestr("classes.dex", b"dex")
        self.loader = self.root / "arm64-v8a.so"
        self.loader.write_bytes(b"\x7fELF fake")
        # The Android tool checks are exercised on the build host; here only the
        # archive assembly and atomic publication are under test.
        self._apk = pz.verify_apk
        self._loader = pz.verify_loader
        pz.verify_apk = lambda apk, package, release, tools: ("v348", "348")
        pz.verify_loader = lambda loader, tools: None

    def tearDown(self):
        pz.verify_apk = self._apk
        pz.verify_loader = self._loader
        self.temp.cleanup()

    def test_pack_writes_expected_archive(self):
        output = self.root / "out" / "wcx-zygisk-kernelsu.zip"
        version, code = pz.pack_release(
            self.apk, self.loader, self.templates, output, "kernelsu", tools=(None, None, None)
        )
        self.assertEqual((version, code), ("v348", "348"))
        with zipfile.ZipFile(output) as archive:
            names = set(archive.namelist())
            self.assertEqual(names, pz.expected_entries("kernelsu"))
            rendered = archive.read("module.prop").decode()
            self.assertNotIn("@MANAGER@", rendered)
            self.assertNotIn("@VERSION_NAME@", rendered)
            self.assertIn("kernelsu", rendered)

    def test_pack_replaces_output_only_after_validation(self):
        output = self.root / "wcx-zygisk-magisk.zip"
        output.write_bytes(b"previous")
        # A template without the architecture check must not replace the output.
        (self.templates / "customize.sh").write_text("#!/system/bin/sh\n", encoding="utf-8")
        with self.assertRaises(pz.VerificationError):
            pz.pack_release(
                self.apk, self.loader, self.templates, output, "magisk", tools=(None, None, None)
            )
        self.assertEqual(output.read_bytes(), b"previous")

    def test_pack_rejects_missing_inputs(self):
        output = self.root / "out.zip"
        with self.assertRaises(pz.VerificationError):
            pz.pack_release(
                self.root / "missing.apk", self.loader, self.templates, output, "magisk",
                tools=(None, None, None),
            )
        self.assertFalse(output.exists())

    def test_pack_rejects_output_overwriting_an_input(self):
        with self.assertRaises(pz.VerificationError):
            pz.pack_release(
                self.apk, self.loader, self.templates, self.loader, "magisk", tools=(None, None, None)
            )


class ZygiskEntryTests(unittest.TestCase):
    """The release payload check must catch an ordinary legacy APK."""

    def test_payload_without_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "plain.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"ordinary classes")
            with self.assertRaises(pz.VerificationError) as caught:
                pz.check_zygisk_entry(zipfile.ZipFile(apk))
            self.assertIn("Zygisk entry", str(caught.exception))

    def test_payload_with_entry_is_accepted(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "payload.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"no entry here")
                zf.writestr("classes2.dex", b"Lcom/Johnny/wcx/loader/entry/zygisk/ZygiskEntry;")
            pz.check_zygisk_entry(zipfile.ZipFile(apk))


class TemplateTests(unittest.TestCase):
    """The shipped templates must satisfy the same rules the packer enforces."""

    def test_shipped_templates_are_accepted(self):
        repository_templates = Path(__file__).resolve().parents[1] / "module"
        self.assertTrue((repository_templates / "module.prop.in").is_file())
        for manager in pz.MANAGERS:
            with tempfile.TemporaryDirectory() as temp:
                archive = Path(temp) / f"{manager}.zip"
                with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
                    rendered = (
                        (repository_templates / "module.prop.in")
                        .read_text(encoding="utf-8")
                        .replace("@VERSION_NAME@", "v348")
                        .replace("@VERSION_CODE@", "348")
                        .replace("@MANAGER@", manager)
                    )
                    zf.writestr("module.prop", rendered)
                    for name in sorted(pz.expected_entries(manager)):
                        if name == "module.prop":
                            continue
                        if name == pz.LOADER:
                            zf.writestr(name, b"\x7fELF")
                        elif name == pz.PAYLOAD:
                            zf.writestr(name, b"PK")
                        else:
                            zf.write(repository_templates / name, name)
                self.assertEqual(pz.verify_zip(archive, manager), ("v348", "348"))


if __name__ == "__main__":
    unittest.main()
