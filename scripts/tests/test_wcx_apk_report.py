import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import scripts.wcx_apk_report as report_module
from scripts.wcx_apk_report import (
    Budget,
    build_report,
    check_budget,
    collect_zip_groups,
    main,
    parse_apkanalyzer_packages,
    parse_llvm_size_sections,
)


class WcxApkReportTest(unittest.TestCase):
    def test_groups_dex_native_assets_and_resources(self):
        with tempfile.TemporaryDirectory() as td:
            apk = Path(td) / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100, compress_type=zipfile.ZIP_STORED)
                zf.writestr("classes2.dex", b"e" * 50, compress_type=zipfile.ZIP_STORED)
                zf.writestr(
                    "lib/arm64-v8a/libx.so",
                    b"n" * 40,
                    compress_type=zipfile.ZIP_STORED,
                )
                zf.writestr(
                    "assets/a.txt",
                    b"a" * 30,
                    compress_type=zipfile.ZIP_DEFLATED,
                )
                zf.writestr(
                    "resources.arsc",
                    b"r" * 20,
                    compress_type=zipfile.ZIP_STORED,
                )

            report = collect_zip_groups(apk)

            self.assertEqual(report["groups"]["dex"]["raw"], 150)
            self.assertEqual(report["groups"]["native"]["raw"], 40)
            self.assertEqual(report["groups"]["assets"]["raw"], 30)
            self.assertEqual(report["groups"]["resources"]["raw"], 20)

    def test_budget_failure_lists_each_exceeded_metric(self):
        budget = Budget(apk_bytes=100, dex_bytes=60, native_bytes=30)

        failures = check_budget(
            {
                "apk_bytes": 101,
                "groups": {
                    "dex": {"raw": 61},
                    "native": {"raw": 31},
                },
            },
            budget,
        )

        self.assertEqual(len(failures), 3)
        self.assertIn("apk_bytes", failures[0])

    def test_parses_apkanalyzer_package_rows_by_bytecode_size(self):
        packages = parse_apkanalyzer_packages(
            "P d 10 20 300 com.example.small\n"
            "C d 1 2 3 com.example.small.Example\n"
            "P d 40 50 900 com.example.large\n"
        )

        self.assertEqual(
            packages,
            [
                {
                    "name": "com.example.large",
                    "bytecode_bytes": 900,
                    "metrics": [40, 50, 900],
                },
                {
                    "name": "com.example.small",
                    "bytecode_bytes": 300,
                    "metrics": [10, 20, 300],
                },
            ],
        )

    def test_ignores_apkanalyzer_total_row(self):
        packages = parse_apkanalyzer_packages(
            "P k 168527 175320 21417215 <TOTAL>\n"
            "P k 40 50 900 com.example\n"
        )

        self.assertEqual([item["name"] for item in packages], ["com.example"])

    def test_parses_llvm_size_sections(self):
        sections = parse_llvm_size_sections(
            "section size addr\n.text 120 0\n.rodata 40 120\nTotal 160\n"
        )

        self.assertEqual(sections, {".rodata": 40, ".text": 120})

    def test_build_report_collects_optional_dex_and_native_details(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100)
                zf.writestr("lib/arm64-v8a/libx.so", b"n" * 40)
            mapping = root / "mapping"
            mapping.mkdir()
            apkanalyzer = root / "apkanalyzer"
            apkanalyzer.write_text(
                "#!/bin/sh\nprintf '%s\\n' 'P d 10 20 300 com.example'\n",
                encoding="utf-8",
            )
            apkanalyzer.chmod(0o755)
            llvm_size = root / "llvm-size"
            llvm_size.write_text(
                "#!/bin/sh\nprintf '%s\\n' 'section size addr' '.text 120 0' 'Total 120'\n",
                encoding="utf-8",
            )
            llvm_size.chmod(0o755)

            report = build_report(apk, mapping, apkanalyzer, llvm_size)

            self.assertEqual(report["dex_packages"][0]["bytecode_bytes"], 300)
            self.assertEqual(
                report["native_sections"]["lib/arm64-v8a/libx.so"][".text"],
                120,
            )

    def test_cli_finds_apkanalyzer_under_android_sdk_root(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100)
            mapping = root / "mapping"
            mapping.mkdir()
            apkanalyzer = root / "sdk/cmdline-tools/latest/bin/apkanalyzer"
            apkanalyzer.parent.mkdir(parents=True)
            apkanalyzer.write_text(
                "#!/bin/sh\nprintf '%s\\n' 'P k 40 50 900 com.example'\n",
                encoding="utf-8",
            )
            apkanalyzer.chmod(0o755)
            output = root / "report.json"

            with patch.dict(
                "os.environ",
                {"ANDROID_SDK_ROOT": str(root / "sdk"), "PATH": ""},
                clear=False,
            ):
                status = main(
                    [
                        "--apk",
                        str(apk),
                        "--mapping",
                        str(mapping),
                        "--output",
                        str(output),
                        "--llvm-size",
                        "/bin/true",
                    ]
                )

            self.assertEqual(status, 0)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["dex_packages"][0]["name"], "com.example")

    def test_cli_finds_apkanalyzer_under_common_sdk_root(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100)
            mapping = root / "mapping"
            mapping.mkdir()
            sdk = root / "sdk"
            apkanalyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
            apkanalyzer.parent.mkdir(parents=True)
            apkanalyzer.write_text(
                "#!/bin/sh\nprintf '%s\\n' 'P k 40 50 900 com.example'\n",
                encoding="utf-8",
            )
            apkanalyzer.chmod(0o755)
            output = root / "report.json"

            with (
                patch.dict(
                    "os.environ",
                    {"PATH": "", "ANDROID_SDK_ROOT": "", "ANDROID_HOME": ""},
                    clear=False,
                ),
                patch.object(
                    report_module,
                    "COMMON_ANDROID_SDK_ROOTS",
                    (sdk,),
                    create=True,
                ),
            ):
                status = main(
                    [
                        "--apk",
                        str(apk),
                        "--mapping",
                        str(mapping),
                        "--output",
                        str(output),
                        "--llvm-size",
                        "/bin/true",
                    ]
                )

            self.assertEqual(status, 0)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["dex_packages"][0]["name"], "com.example")

    def test_cli_writes_report_and_returns_two_when_budget_is_exceeded(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk = root / "sample.apk"
            with zipfile.ZipFile(apk, "w") as zf:
                zf.writestr("classes.dex", b"d" * 100)
                zf.writestr("lib/arm64-v8a/libx.so", b"n" * 40)
            budget = root / "budget.json"
            budget.write_text(
                json.dumps(
                    {"apk_bytes": 1, "dex_bytes": 60, "native_bytes": 30}
                ),
                encoding="utf-8",
            )
            output = root / "report.json"

            with redirect_stdout(io.StringIO()):
                status = main(
                    [
                        "--apk",
                        str(apk),
                        "--budget",
                        str(budget),
                        "--output",
                        str(output),
                        "--llvm-size",
                        "/bin/true",
                    ]
                )

            self.assertEqual(status, 2)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(len(report["budget_failures"]), 3)


if __name__ == "__main__":
    unittest.main()
