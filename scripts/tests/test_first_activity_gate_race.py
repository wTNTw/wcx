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

    def test_activity_proxy_splits_interception_and_routing_state(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        self.assertIn("private var handlerInterceptorInstalled = false", source)
        self.assertIn("private var instrumentationInterceptorInstalled = false", source)
        self.assertIn("private var routingInitialized = false", source)
        self.assertNotIn("private var initialized = false", source)
        self.assertIn("if (!ensureHandlerInterceptor()) return false", source)
        self.assertIn("installInstrumentationFallback()", source)


if __name__ == "__main__":
    unittest.main()
