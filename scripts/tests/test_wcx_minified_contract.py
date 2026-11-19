import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "wcx-minified-contract.sh"


class WcxMinifiedContractTest(unittest.TestCase):
    def _fixture(self, root: Path, apkanalyzer_lines: list[str]) -> tuple[Path, Path, Path]:
        apk = root / "sample.apk"
        apk.write_bytes(b"apk")
        mapping = root / "mapping"
        mapping.mkdir()
        apkanalyzer = root / "apkanalyzer"
        body = "\\n".join(apkanalyzer_lines)
        apkanalyzer.write_text(
            f"#!/bin/sh\nprintf '%b\\n' \"{body}\"\n",
            encoding="utf-8",
        )
        apkanalyzer.chmod(0o755)
        return apk, mapping, apkanalyzer

    def test_passes_when_all_class_and_method_contracts_exist(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk, mapping, apkanalyzer = self._fixture(
                root,
                [
                    "C k 1\t1\t10\tcom.example.Entry",
                    "M k 1\t1\t20\tcom.example.Entry void init(java.lang.String)",
                    "M k 1\t1\t12\tcom.example.Entry <init>()",
                ],
            )
            contracts = root / "contracts.txt"
            contracts.write_text(
                "# comment\n"
                "class|com.example.Entry\n"
                "method|com.example.Entry|init(java.lang.String)\n"
                "method|com.example.Entry|<init>()\n",
                encoding="utf-8",
            )

            completed = subprocess.run(
                [
                    "sh",
                    str(SCRIPT),
                    "--apk",
                    str(apk),
                    "--mapping",
                    str(mapping),
                    "--contracts",
                    str(contracts),
                    "--apkanalyzer",
                    str(apkanalyzer),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr + completed.stdout)
            self.assertIn("contracts=3 missing=0", completed.stdout)

    def test_reports_every_missing_contract_and_exits_one(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk, mapping, apkanalyzer = self._fixture(
                root,
                [
                    "C k 1\t1\t10\tcom.example.Entry",
                    "M k 1\t1\t20\tcom.example.Other void init(java.lang.String)",
                ],
            )
            contracts = root / "contracts.txt"
            contracts.write_text(
                "class|com.example.Entry\n"
                "class|com.example.Missing\n"
                "method|com.example.Entry|init(java.lang.String)\n",
                encoding="utf-8",
            )

            completed = subprocess.run(
                [
                    "sh",
                    str(SCRIPT),
                    "--apk",
                    str(apk),
                    "--mapping",
                    str(mapping),
                    "--contracts",
                    str(contracts),
                    "--apkanalyzer",
                    str(apkanalyzer),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

            self.assertEqual(completed.returncode, 1)
            self.assertIn("missing: class|com.example.Missing", completed.stdout)
            self.assertIn(
                "missing: method|com.example.Entry|init(java.lang.String)",
                completed.stdout,
            )
            self.assertIn("contracts=3 missing=2", completed.stdout)

    def test_rejects_class_or_method_renamed_in_mapping_file(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            apk, mapping, apkanalyzer = self._fixture(
                root,
                [
                    "C d 1\t1\t10\tcom.example.RenamedClass",
                    "C k 1\t1\t10\tcom.example.Entry",
                    "M d 1\t1\t20\tcom.example.Entry void init(java.lang.String)",
                ],
            )
            (mapping / "mapping.txt").write_text(
                "com.example.RenamedClass -> a.b:\n"
                "com.example.Entry -> com.example.Entry:\n"
                "    1:1:void init(java.lang.String):10:10 -> a\n",
                encoding="utf-8",
            )
            contracts = root / "contracts.txt"
            contracts.write_text(
                "class|com.example.RenamedClass\n"
                "method|com.example.Entry|init(java.lang.String)\n",
                encoding="utf-8",
            )

            completed = subprocess.run(
                [
                    "sh",
                    str(SCRIPT),
                    "--apk",
                    str(apk),
                    "--mapping",
                    str(mapping),
                    "--contracts",
                    str(contracts),
                    "--apkanalyzer",
                    str(apkanalyzer),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )

            self.assertEqual(completed.returncode, 1)
            self.assertIn("missing: class|com.example.RenamedClass", completed.stdout)
            self.assertIn(
                "missing: method|com.example.Entry|init(java.lang.String)",
                completed.stdout,
            )


if __name__ == "__main__":
    unittest.main()
