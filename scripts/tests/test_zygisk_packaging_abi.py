import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ZygiskPackagingAbiContractTest(unittest.TestCase):
    def test_v7_only_release_skips_arm64_zygisk_packaging(self):
        source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        task_start = source.index('tasks.register<PackageZygiskApkTask>("packageZygiskApk")')
        task_end = source.index("// assembleRelease", task_start)
        task = source[task_start:task_end]
        self.assertIn('providers.gradleProperty("wekit.abi")', source)
        self.assertIn("onlyIf", task)
        self.assertIn('"arm64-v8a"', task)


if __name__ == "__main__":
    unittest.main()
