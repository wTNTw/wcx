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
        self.assertEqual(14, enabled_annotations)

    def test_scanner_generates_lazy_descriptors_using_supertype_metadata(self):
        scanner = (SCANNER_ROOT / "FeaturesScanner.kt").read_text(encoding="utf-8")
        self.assertIn('PropertySpec.builder("ALL_FEATURES"', scanner)
        self.assertIn('PropertySpec.builder("ALL_HOOK_ITEMS"', scanner)
        self.assertIn("FeatureDescriptor", scanner)
        self.assertIn("isApi", scanner)
        self.assertIn("hasDexResolver", scanner)
        self.assertIn("factory", scanner)
        self.assertRegex(scanner, r"superTypes[\s\S]+resolve\(\)[\s\S]+qualifiedName")


if __name__ == "__main__":
    unittest.main()
