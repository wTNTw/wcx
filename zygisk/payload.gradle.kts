// Zygisk payload variant, applied from app/build.gradle.kts only when
// -PwcxZygisk=true is present.
//
// Kept in its own script so the upstream app/build.gradle.kts only carries a
// short conditional, which keeps merges from upstream cheap. A script plugin
// has no type-safe accessors, so the extensions are configured explicitly.

extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
    // AGP 9 keeps Kotlin and Java source directories separate; the Zygisk
    // sources are Kotlin, so both are registered.
    sourceSets["main"].java.srcDir("src/zygisk/java")
    sourceSets["main"].kotlin.srcDir("src/zygisk/java")
    // Overlay removes the Xposed module metadata from the payload manifest.
    sourceSets["legacy"].manifest.srcFile("src/zygisk/AndroidManifest.xml")
}

// The payload is only ever assembled by name; skip the vital lint pass, which
// would otherwise fail on the deliberately Xposed-free manifest.
extensions.configure<com.android.build.api.dsl.Lint>("lint") {
    checkReleaseBuilds = false
}

logger.lifecycle("WCX Zygisk payload build enabled (arm64-v8a only)")
