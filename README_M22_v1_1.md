# M22 v1.1 — Runtime Dependency OSV Scope Hotfix

This hotfix addresses the M22 GitHub Action failure where OSV-Scanner scanned
`gradle/verification-metadata.xml` as if every dependency in that global Gradle
integrity file were part of the production APK.

Gradle dependency verification is intentionally global and covers build plugins,
Kotlin/Android tooling, KSP, buildscript artifacts and ordinary project dependencies.
The original M22 scan therefore reported build-tool packages such as the Kotlin Gradle
plugin, Netty, BouncyCastle, JDOM, Commons Compress and others together with runtime
dependencies.

M22 v1.1 does NOT suppress or ignore that inventory.

It separates two security questions:

1. **Blocking production APK scan**
   - Resolve `:app:releaseRuntimeClasspath`.
   - Export only actual external Maven modules in that runtime graph.
   - Convert them to OSV's documented custom lockfile format.
   - Run OSV on that custom runtime lockfile.
   - Any known runtime vulnerability still fails M22.

2. **Full Gradle/toolchain audit**
   - Continue scanning `gradle/verification-metadata.xml`.
   - Keep the result visible as security evidence.
   - Do not let build-tool-only advisories masquerade as APK runtime vulnerabilities.
   - The step is non-blocking because some advisories currently target build plugins and
     compiler/tooling components that are not packaged into the Android application.

Gradle SHA-256 dependency verification remains unchanged and continues to verify every
artifact consumed by the build, including plugins and build tooling.

## Apply

Overlay this ZIP onto the repository `main` branch, preserving paths, commit it, and rerun:

**Actions → M22 Security API-Key Permissions & Release Integrity → Run workflow → main**

No M22 trading/security runtime source needs to be reinstalled; this is a CI/security-scope
hotfix only.
