import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java"


class NativeCapabilityLoadingContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8-sig")

    def test_native_loader_has_retryable_capabilities(self):
        source = self.read("app/src/main/java/com/Johnny/wcx/loader/utils/NativeLoader.kt")
        self.assertIn("enum class Capability", source)
        for capability in ("CORE", "DEXKIT", "MEDIA", "GRAPHICS_PATH"):
            self.assertIn(capability, source)
        for method in (
            "ensureCoreLoaded",
            "ensureDexKitLoaded",
            "ensureMediaLoaded",
            "ensureGraphicsPathLoaded",
        ):
            self.assertIn(f"fun {method}", source)
        self.assertIn("loadedCapabilities.remove(capability)", source)
        init_body = source[source.index("fun init(hostCtx: Context)") : source.index("fun ensureCoreLoaded()")]
        self.assertIn("ensureCoreLoaded", init_body)
        self.assertNotIn("ensureDexKitLoaded", init_body)
        self.assertNotIn("ensureMediaLoaded", init_body)
        self.assertNotIn("ensureGraphicsPathLoaded", init_body)

    def test_zygisk_payload_extracts_media_but_only_loads_core(self):
        source = self.read("app/src/main/java/com/Johnny/wcx/loader/utils/ZygiskNativePayload.kt")
        self.assertIn('"wekit_media"', source)
        self.assertNotIn('"silk_codec"', source)
        load_calls = [line.strip() for line in source.splitlines() if "System.load(" in line]
        self.assertEqual(1, len(load_calls))
        self.assertIn("wekit_native", source)

    def test_deferred_jni_facades_load_media_first(self):
        audio = self.read("app/src/main/java/com/Johnny/wcx/utils/AudioUtils.kt")
        telegram = self.read("app/src/main/java/com/Johnny/wcx/utils/TelegramStickerConverter.kt")
        markdown = self.read("app/src/main/java/com/Johnny/wcx/features/items/chat/MarkdownRendering.kt")
        self.assertIn("NativeLoader.ensureMediaLoaded()", audio)
        self.assertIn("NativeLoader.ensureMediaLoaded()", telegram)
        self.assertIn("NativeLoader.ensureMediaLoaded()", markdown)

    def test_dexkit_and_graphics_consumers_load_capabilities(self):
        dexkit = self.read("app/src/main/java/com/Johnny/wcx/utils/reflection/DexKitUtils.kt")
        hchat = self.read("app/src/main/java/h/Hchat/ModuleEntry.java")
        drawable = self.read("app/src/main/java/com/Johnny/wcx/ui/utils/DrawableIcons.kt")
        pull_down = self.read("app/src/main/java/com/Johnny/wcx/features/items/home_screen_menu/DisableMainPagePullDown.kt")
        self.assertIn("NativeLoader.ensureDexKitLoaded()", dexkit)
        self.assertIn("NativeLoader.INSTANCE.ensureDexKitLoaded()", hchat)
        self.assertIn("NativeLoader.ensureGraphicsPathLoaded()", drawable)
        self.assertIn("NativeLoader.ensureGraphicsPathLoaded()", pull_down)

    def test_duplicate_silk_library_is_removed_but_public_api_remains(self):
        silk = self.read("app/src/main/java/me/yun/silk/SilkCodec.java")
        all_java = "\n".join(
            path.read_text(encoding="utf-8-sig", errors="ignore")
            for path in JAVA.rglob("*.java")
        ) + "\n" + "\n".join(
            path.read_text(encoding="utf-8-sig", errors="ignore")
            for path in JAVA.rglob("*.kt")
        )
        self.assertIn("public int autoToSilk", silk)
        self.assertIn("public long getDuration", silk)
        self.assertNotIn(" native ", silk)
        self.assertNotIn('System.loadLibrary("silk_codec")', all_java)
        self.assertFalse((ROOT / "app/src/main/jniLibs/arm64-v8a/libsilk_codec.so").exists())


if __name__ == "__main__":
    unittest.main()
