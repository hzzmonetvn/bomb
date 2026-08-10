---
description: Build and verify the current Bomb Android APK for device testing
---

Build the Bomb APK from the repository root.

Variant: `$1` (default: `debug`). Accept only `debug` or `release`; stop and report an invalid argument otherwise.

1. Verify that `./gradlew`, `app-preview/build.gradle.kts`, and the `:app-preview` entry in `settings.gradle.kts` still exist. If the packaging module has been renamed, stop and report the mismatch instead of guessing a new task or output path.
2. Run the build in the foreground without filtering or truncating its output:
   - debug: `./gradlew --console=plain :app-preview:assembleDebug`
   - release: `./gradlew --console=plain :app-preview:assembleRelease`
3. Do not retry a failed build blindly. Diagnose the first actionable failure, make no unrelated edits, and report that no verified APK was produced.
4. On success, require the expected artifact to exist:
   - debug: `app-preview/build/outputs/apk/debug/app-preview-debug.apk`
   - release: `app-preview/build/outputs/apk/release/app-preview-release.apk`
5. Read the adjacent `output-metadata.json` when present. Report the exact Gradle command, variant, APK path, byte size, SHA-256, version name/code if available, and whether the build succeeded. For release, explicitly note that the current Gradle configuration uses the debug signing config unless the project configuration has changed.

Stop when one freshly built APK has been verified and its path reported, or when a concrete build failure/missing-artifact mismatch has been reported.
