# GitHub Actions sdkmanager Fix

## Problem

The previous workflow called:

```bash
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platforms;android-35" "build-tools;35.0.0"
```

On the GitHub runner, `sdkmanager` was not available on `PATH`, so the build failed with:

```text
sdkmanager: command not found
Error: Process completed with exit code 127
```

## Fix

The workflows now install and expose the Android SDK command-line tools first:

```yaml
- name: Set up Android SDK
  uses: android-actions/setup-android@v3
```

Then they call `sdkmanager` by its full path:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
```

This avoids relying on `sdkmanager` being preinstalled or already available on `PATH`.

## Updated workflows

- `.github/workflows/android-debug-apk.yml`
- `.github/workflows/android-release-apk.yml`

## Build steps

After pushing this fixed version to GitHub:

1. Open your repository.
2. Go to **Actions**.
3. Select **Android Debug APK**.
4. Press **Run workflow**.
5. Download the artifact named `BelgiumCryptoBot-debug-apk`.

