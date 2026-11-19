import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ZYGISK = ROOT / "app/src/main/java/com/Johnny/wcx/loader/entry/zygisk"
UTILS = ROOT / "app/src/main/java/com/Johnny/wcx/loader/utils"


class FirstActivityGateRaceContractTest(unittest.TestCase):
    def test_one_shot_gate_has_bounded_wait_and_rejects_late_publish(self):
        source = (ZYGISK / "MainProcessStartupGate.kt").read_text(encoding="utf-8")
        self.assertIn("internal class MainProcessStartupGate<T>", source)
        self.assertIn("completion.await(timeoutMs, TimeUnit.MILLISECONDS)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Consumed)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Published(value))", source)
        self.assertIn("completion.countDown()", source)


if __name__ == "__main__":
    unittest.main()
