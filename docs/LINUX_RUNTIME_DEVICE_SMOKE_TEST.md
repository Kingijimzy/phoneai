# Orion Linux runtime device smoke test

This checklist is a release gate, not a claim that the test has already passed.

## Preconditions

- Install a debug APK built from the same commit as the candidate manifest.
- Use an ARM64 Android device. Record device model, Android version, API level, app version, and commit SHA.
- Do not copy the short-lived CI artifact into production or enable `where=linux` while `docs/linux-runtime-candidate-manifest.json` has `status: review-only`.
- Confirm the PRoot executable is loaded from Android's supported native-library location and that all loader files it needs are packaged and present. Do not execute a downloaded ELF from the app's ordinary files directory.

## Smoke-test sequence

1. **Install and first launch:** app starts without a crash; no accessibility permission is needed just to inspect the runtime status.
2. **Integrity gate:** corrupt one byte of a test copy of the rootfs or candidate executable. Installation must fail with a checksum error and must not write the ready marker.
3. **Install valid rootfs:** download the exact Alpine 3.22.7 ARM64 archive, verify its 3,971,679-byte length and SHA-256, extract to staging, then atomically activate it.
4. **Guest shell:** run the equivalent of `/bin/sh -c 'printf ORION_PROOT_OK'` through PRoot. Expected stdout contains exactly `ORION_PROOT_OK`; exit code must be zero.
5. **Guest identity:** run `uname -m` and `cat /etc/alpine-release`. Expected architecture is `aarch64` and release is `3.22.7`.
6. **Working directory and environment:** run `pwd`, `printf '%s' "$HOME"`, and `command -v sh`. Ensure they resolve inside the guest root as intended.
7. **Timeout:** run a bounded sleep longer than the allowed timeout. Orion must stop it and return a timeout result without hanging the UI.
8. **Recovery:** interrupt an installation, relaunch the app, and verify no partially installed runtime is reported READY.
9. **Upgrade rollback:** simulate a failed replacement and verify the previous valid rootfs and marker remain intact.
10. **Routing:** `where=linux` must execute inside Alpine only when the manifest, native executable, rootfs marker, hashes, and shell readiness checks all pass. If any gate fails, return an explicit error; never silently route to `where=builtin` or Termux.
11. **Persistence:** force-stop and reopen Orion, then repeat the guest-shell test.
12. **Resource check:** record install duration, peak storage, memory pressure, temperature, and whether HyperOS kills or delays the app.

## Required evidence before marking READY

Attach the app log with timestamps, exact APK SHA-256, tested commit, device/OS/API details, artifact hashes and sizes, guest-shell output and exit code, plus install/restart/rollback outcomes. A CI compilation or ELF header check is not a substitute for this device run.

## Current state

As of the review-only candidate run, CI has compiled the PRoot candidate and inspected its ELF headers, and the Android APK workflow passed its unit tests/build/signature checks. Guest execution, packaging behavior on the target device, licensing review, and physical-device verification remain pending. Keep production Linux routing disabled until those checks pass.
