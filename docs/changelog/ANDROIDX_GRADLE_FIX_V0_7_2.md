# v0.7.2 AndroidX Gradle Fix

GitHub Actions failed at `:app:checkDebugAarMetadata` because the project uses AndroidX dependencies such as Jetpack Compose, Activity Compose, Lifecycle, and Room, but `android.useAndroidX=true` was missing from the root `gradle.properties` file.

## Fix applied

Added root-level `gradle.properties`:

```properties
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
kotlin.code.style=official
```

## Why this fixes it

The Android Gradle Plugin checks AndroidX usage during `checkDebugAarMetadata`. Because this app directly depends on AndroidX libraries, AndroidX support must be explicitly enabled.

## Next command

```bash
gradle --no-daemon clean :app:assembleDebug
```
