import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ZYGISK = ROOT / "app/src/main/java/com/Johnny/wcx/loader/entry/zygisk"
UTILS = ROOT / "app/src/main/java/com/Johnny/wcx/loader/utils"


class FirstActivityGateRaceContractTest(unittest.TestCase):
    def test_one_shot_gate_has_bounded_wait_and_rejects_late_publish(self):
        source = (ZYGISK / "MainProcessStartupGate.kt").read_text(encoding="utf-8")
        self.assertIn("internal class MainProcessStartupGate<T>", source)
        self.assertIn("completion.await(waitSliceMs, TimeUnit.MILLISECONDS)", source)
        self.assertIn("elapsed.coerceAtMost(waitSliceMs * 2)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Consumed)", source)
        self.assertIn("state.compareAndSet(State.Pending, State.Published(value))", source)
        self.assertIn("completion.countDown()", source)

    def test_activity_proxy_exposes_context_free_startup_gate(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        signature = "fun installStartupGate(startupGate: () -> Unit): Boolean"
        self.assertIn(signature, source)
        start = source.index(signature)
        end = source.index("fun installInstrumentationFallback", start)
        gate_body = source[start:end]
        self.assertNotIn("Context", gate_body)
        self.assertIn("beforeFirstActivity.compareAndSet", gate_body)
        self.assertIn("ensureHandlerInterceptor()", gate_body)
        self.assertIn("fun awaitStartupGate(", gate_body)

    def test_activity_proxy_splits_interception_and_routing_state(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        self.assertIn("private var handlerInterceptorInstalled = false", source)
        self.assertIn("private var instrumentationInterceptorInstalled = false", source)
        self.assertIn("private var routingInitialized = false", source)
        self.assertNotIn("private var initialized = false", source)
        self.assertIn("if (!ensureHandlerInterceptor()) return false", source)
        self.assertIn("installInstrumentationFallback()", source)
        self.assertIn('getDeclaredMethod("getTransactionItems")', source)
        self.assertIn('transaction.toString().contains("LaunchActivityItem")', source)

    def test_zygisk_arms_gate_before_waiting_for_libaff(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        prep = source[source.index("private fun prepareDeferredStartup"):]
        arm = prep.index("ActivityProxy.awaitStartupGate")
        libaff = prep.index("waitForLibaffBiz()")
        self.assertLess(arm, libaff)
        self.assertIn("ACTIVITY_GATE_WAIT_MS = 5_000L", source)

    def test_gate_has_structured_outcomes_and_drops_late_readiness(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        for marker in (
            "startup_gate_installed",
            "startup_gate_activity_arrived",
            "startup_gate_ready",
            "startup_gate_wait_ms=",
            "startup_gate_result=started",
            "startup_gate_result=prepare_failed",
            "startup_gate_result=lsplant_failed",
            "startup_gate_result=startup_failed",
            "startup_gate_late_result_dropped",
        ):
            self.assertIn(marker, source)
        self.assertIn("gate.publish", source)
        self.assertIn("gate.awaitAndConsume(ACTIVITY_GATE_WAIT_MS)", source)

    def test_early_lsplant_ab_still_bypasses_activity_gate(self):
        source = (ZYGISK / "ZygiskEntry.kt").read_text(encoding="utf-8")
        early = source.index("if (isEarlyLsplantEnabled())")
        direct = source.index("runEarlyDeferredStartup", early)
        worker = source.index("prepareDeferredStartup", early)
        self.assertLess(direct, worker)


if __name__ == "__main__":
    unittest.main()
