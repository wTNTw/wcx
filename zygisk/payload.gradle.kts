// Zygisk payload variant, applied from app/build.gradle.kts only when
// -PwcxZygisk=true is present.
//
// Keeping this in its own script means the upstream app/build.gradle.kts only
// carries a three-line conditional, which keeps merges from upstream cheap.

android {
    // AGP 9 keeps Kotlin and Java source directories separate; the Zygisk
    // sources are Kotlin, so both are registered.
    sourceSets["main"].java.srcDir("src/zygisk/java")
    sourceSets["main"].kotlin.srcDir("src/zygisk/java")
    // Overlay removes the Xposed module metadata from the payload manifest.
    sourceSets["legacy"].manifest.srcFile("src/zygisk/AndroidManifest.xml")
    // The loader is packaged separately as zygisk/arm64-v8a.so; the packer
    // takes the arm64-v8a payload split by explicit path.
    lint {
        checkReleaseBuilds = false
    }
}

logger.lifecycle("WCX Zygisk payload build enabled (arm64-v8a only)")
