import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "app/src/main/rust/wekit-native"
MEDIA = ROOT / "app/src/main/rust/wekit-media"


class NativeCrateSplitContractTest(unittest.TestCase):
    def test_workspace_contains_core_and_media_crates(self):
        workspace = (ROOT / "Cargo.toml").read_text(encoding="utf-8")
        self.assertIn('"app/src/main/rust/wekit-native/"', workspace)
        self.assertIn('"app/src/main/rust/wekit-media/"', workspace)

    def test_core_has_no_media_modules_or_dependencies(self):
        manifest = (CORE / "Cargo.toml").read_text(encoding="utf-8")
        source = (CORE / "src/lib.rs").read_text(encoding="utf-8")
        build = (CORE / "build.rs").read_text(encoding="utf-8")
        for dependency in (
            "markdown",
            "silk-v3-sys",
            "symphonia",
            "mp3lame-encoder",
            "rasterlottie",
            "webm-iterable",
        ):
            self.assertNotIn(dependency, manifest)
        self.assertNotIn("mod audio_utils", source)
        self.assertNotIn("mod telegram_sticker", source)
        self.assertNotIn("libvpx", build.lower())
        self.assertNotIn("wekit_vpx_decoder", build)

    def test_media_owns_media_jni_and_vpx_build(self):
        manifest = (MEDIA / "Cargo.toml").read_text(encoding="utf-8")
        source = (MEDIA / "src/lib.rs").read_text(encoding="utf-8")
        build = (MEDIA / "build.rs").read_text(encoding="utf-8")
        self.assertIn('name = "wekit_media"', manifest)
        self.assertIn("mod audio_utils", source)
        self.assertIn("mod telegram_sticker", source)
        self.assertIn("MarkdownRendering_convertMarkdownToHtmlNative", source)
        self.assertIn("wekit_vpx_decoder.c", build)

    def test_xtask_builds_and_copies_both_libraries(self):
        source = (ROOT / "xtask/src/main.rs").read_text(encoding="utf-8")
        self.assertIn('"libwekit_native.so"', source)
        self.assertIn('"libwekit_media.so"', source)
        self.assertIn("media_native_crate_dir", source)


if __name__ == "__main__":
    unittest.main()
