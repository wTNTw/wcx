import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / "scripts/wcx-injection-probe.sh"


class FirstActivityGateProbeContractTest(unittest.TestCase):
    def test_probe_recognizes_structured_gate_markers(self):
        source = PROBE.read_text(encoding="utf-8")
        for marker in (
            "startup_gate_installed",
            "startup_gate_activity_arrived",
            "startup_gate_ready",
            "startup_gate_result=started",
            "startup_gate_result=prepare_failed",
            "startup_gate_result=lsplant_failed",
            "startup_gate_result=startup_failed",
        ):
            self.assertIn(marker, source)

    def test_probe_has_direct_screenoff_and_boot_modes(self):
        source = PROBE.read_text(encoding="utf-8")
        self.assertIn("direct)", source)
        self.assertIn("screenoff)", source)
        self.assertIn("boot)", source)
        self.assertIn("PRE_LSPLANT", source)
        self.assertIn("background_initialized_early", source)
        self.assertIn("reason=5 (APP CRASH(NATIVE))", source)
        self.assertIn("FeaturesLoader: 本批 [0-9]+ 个：已启用", source)


if __name__ == "__main__":
    unittest.main()
