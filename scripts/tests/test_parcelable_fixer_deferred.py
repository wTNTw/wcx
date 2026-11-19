import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
UTILS = ROOT / "app/src/main/java/com/Johnny/wcx/loader/utils"
STARTUP = ROOT / "app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt"


class ParcelableFixerDeferredContractTest(unittest.TestCase):
    def test_fixer_splits_classloader_and_hook_installation(self):
        source = (UTILS / "ParcelableFixer.kt").read_text(encoding="utf-8")
        self.assertIn("fun prepareClassLoader()", source)
        self.assertIn("fun ensureIntentHooksInstalled()", source)
        self.assertNotIn("fun init()", source)
        self.assertIn("@Synchronized", source)

    def test_startup_only_prepares_classloader(self):
        source = STARTUP.read_text(encoding="utf-8")
        self.assertIn(
            'phase("ParcelableClassLoader") { ParcelableFixer.prepareClassLoader() }',
            source,
        )
        self.assertNotIn("ParcelableFixer.init()", source)

    def test_activity_proxy_installs_hooks_at_module_boundaries(self):
        source = (UTILS / "ActivityProxy.kt").read_text(encoding="utf-8")
        wrapper = source.index("private fun createTokenWrapper")
        ensure = source.index("ParcelableFixer.ensureIntentHooksInstalled()", wrapper)
        token = source.index("IntentTokenCache.put", wrapper)
        self.assertLess(ensure, token)

        module_branch = source.index(
            "if (ActProxyMgr.isModuleProxyActivity(resolvedClass))"
        )
        fallback_ensure = source.index(
            "ParcelableFixer.ensureIntentHooksInstalled()", module_branch
        )
        module_load = source.index("ClassLoaders.MODULE.loadClass", module_branch)
        self.assertLess(fallback_ensure, module_load)


if __name__ == "__main__":
    unittest.main()
