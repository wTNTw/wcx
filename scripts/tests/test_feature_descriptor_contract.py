import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FEATURES_ROOT = ROOT / "app/src/main/java/com/Johnny/wcx/features"
SCANNER_ROOT = ROOT / "libs/common/annotation-scanner/src/main/java/com/Johnny/wcx/features"


class FeatureDescriptorContractTest(unittest.TestCase):
    def test_annotation_and_runtime_descriptor_contract_exist(self):
        annotation = (SCANNER_ROOT / "core/Feature.kt").read_text(encoding="utf-8")
        self.assertIn("val defaultEnabled: Boolean = false", annotation)

        descriptor = (FEATURES_ROOT / "core/FeatureDescriptor.kt").read_text(encoding="utf-8")
        self.assertIn("val hasDexResolver: Boolean", descriptor)
        self.assertIn("fun instance(): BaseFeature", descriptor)

        registry = (FEATURES_ROOT / "core/FeatureRegistry.kt").read_text(encoding="utf-8")
        self.assertIn("ConcurrentHashMap<String, BaseFeature>", registry)
        self.assertIn("startupDefaultEnabled = descriptor.defaultEnabled", registry)

    def test_default_enabled_metadata_lives_only_in_annotations(self):
        offenders = []
        enabled_annotations = 0
        for source in FEATURES_ROOT.rglob("*.kt"):
            text = source.read_text(encoding="utf-8")
            if re.search(r"override\s+val\s+defaultEnabled", text):
                offenders.append(str(source.relative_to(ROOT)))
            enabled_annotations += len(re.findall(r"defaultEnabled\s*=\s*true", text))
        self.assertEqual([], offenders)
        self.assertEqual(15, enabled_annotations)

    def test_scanner_generates_lazy_descriptors_using_supertype_metadata(self):
        scanner = (SCANNER_ROOT / "FeaturesScanner.kt").read_text(encoding="utf-8")
        self.assertIn('PropertySpec.builder("ALL_FEATURES"', scanner)
        self.assertIn('PropertySpec.builder("ALL_HOOK_ITEMS"', scanner)
        self.assertIn("FeatureDescriptor", scanner)
        self.assertIn("isApi", scanner)
        self.assertIn("hasDexResolver", scanner)
        self.assertIn("factory", scanner)
        self.assertRegex(scanner, r"superTypes[\s\S]+resolve\(\)[\s\S]+qualifiedName")

    def test_startup_loader_selects_descriptors_before_instantiation(self):
        loader = (FEATURES_ROOT / "core/FeaturesLoader.kt").read_text(encoding="utf-8")
        self.assertIn("FeaturesProvider.ALL_FEATURES", loader)
        self.assertNotIn("FeaturesProvider.ALL_HOOK_ITEMS", loader)
        self.assertIn("enabledBeforePrefsReady", loader)
        self.assertIn("FeatureSwitchSnapshot.enabledNames()", loader)
        self.assertIn("FeatureRegistry.instantiated()", loader)
        self.assertIn("FeatureRegistry: descriptors=", loader)

        snapshot = (FEATURES_ROOT / "core/FeatureSwitchSnapshot.kt").read_text(encoding="utf-8")
        self.assertIn("fun enabledNames(): Set<String>", snapshot)
        self.assertIn(
            "if (TargetProcesses.isInMain && active > 0 && hostPrefsReady())",
            loader,
        )

    def test_startup_background_subsystems_do_not_eagerly_instantiate_all_features(self):
        adaptation = (
            ROOT / "app/src/main/java/com/Johnny/wcx/dynamic/LocalAdaptationEngine.kt"
        ).read_text(encoding="utf-8")
        self.assertNotIn("ALL_HOOK_ITEMS", adaptation)
        self.assertIn("FeaturesProvider.ALL_FEATURES", adaptation)

        launcher = (
            ROOT / "app/src/main/java/com/Johnny/wcx/loader/startup/WeLauncher.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("FeaturesLoader.runAfterFirstFrame", launcher)
        bg = launcher.index("FeaturesLoader.runAfterFirstFrame")
        self.assertLess(bg, launcher.index("LocalAdaptationEngine.init", bg))
        self.assertLess(bg, launcher.index("HchatBootstrap.install", bg))

        loader = (FEATURES_ROOT / "core/FeaturesLoader.kt").read_text(encoding="utf-8")
        self.assertIn("fun prewarmEarlyFeatures(", loader)
        self.assertIn("DexCacheManager.prewarmCacheFiles()", loader)

        dex_cache = (
            ROOT / "app/src/main/java/com/Johnny/wcx/dexkit/cache/DexCacheManager.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("fun prewarmCacheFiles()", dex_cache)
        self.assertIn("ConcurrentHashMap<String, JSONObject>", dex_cache)

    def test_non_switch_base_feature_declares_startup_default(self):
        april_fools = (
            FEATURES_ROOT / "items/easter_egg/AprilFools.kt"
        ).read_text(encoding="utf-8")
        self.assertRegex(
            april_fools,
            r"@Feature\([\s\S]*defaultEnabled\s*=\s*true[\s\S]*\)\s*object AprilFools",
        )


if __name__ == "__main__":
    unittest.main()
