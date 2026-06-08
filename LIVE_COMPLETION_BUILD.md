# v1.5.0 Live Completion Build

This release is a truth-audited Kraken live build.

The project does not claim that every idea ever discussed is magically live-tested. Instead, it now documents every major feature category and separates:

- live Kraken features,
- partial advanced automation modules,
- scaffolds,
- impossible/unsafe requests that cannot be implemented.

The app is intended to be used with Kraken for live trading, Paper for simulation, and Manual mode for trade plans.

Read:
- `docs/live/FEATURE_COMPLETION_AUDIT.md`
- `docs/verification/LIVE_VERIFICATION_PLAN.md`


## v1.7.3 GitHub Actions SDK fix

This build removes android-actions/setup-android@v3 from the workflows and uses the preinstalled Android SDK/sdmanager directly. It installs only platform-tools, Android 35 platform, and build-tools 35.0.0, avoiding the failing Android Emulator package download.
