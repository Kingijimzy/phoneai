# Orion Linux runtime — implementation gate

Status: **not enabled for command execution yet**.

This document records the hard gate between the current fail-closed shell router and a testable Alpine runtime. It is intentionally explicit: a green APK build is not evidence that a Linux guest can execute commands on Android.

## Why the current installer is not sufficient

Android 10+ applies executable-memory / W^X restrictions to app-private writable storage. The current `LinuxRuntimeInstaller` downloads PRoot into `filesDir/orion-linux/bin/proot` and calls `setExecutable(true)`. The permission bit alone does not make that location a reliable executable location on Android. PRoot's guest ELF loaders also need to be packaged into the APK's ABI-specific `jniLibs` so Android extracts them into `nativeLibraryDir`.

The nanoMuse implementation documents this exact failure mode and ships:
- an ARM64 PRoot executable in assets for controlled copying/use;
- `libproot.so` and the required loader ELF files under `jniLibs/arm64-v8a/`;
- a build/verification script and third-party notices.

Reference: https://github.com/nano-muse/nanoMuse/blob/main/android/deps/build_proot.sh

Do not copy its prebuilt binaries blindly. Pin the source commit, build inputs and checksums, preserve GPL-2.0/LGPL notices and source-offer obligations, and verify the resulting artifacts.

## Required completion gates

1. **Native PRoot supply chain**
   - Pin an audited PRoot source commit and compatible Android loader sources.
   - Build for `arm64-v8a` in CI with a pinned NDK/toolchain.
   - Record SHA-256, ELF architecture, source commit and license for every output.
   - Fail the build if the executable or required loader is absent, truncated, or the wrong architecture.
2. **Rootfs supply chain**
   - Pin an official Alpine ARM64 minirootfs URL, exact byte count and independently published SHA-256.
   - Verify the sidecar checksum independently before committing a manifest. Never calculate a checksum from a downloaded file and then trust that same file.
3. **Runtime installation**
   - Keep rootfs extraction in private writable storage, with traversal, link, size and rollback protections.
   - Do not download a new PRoot executable into `filesDir` and assume chmod defeats Android W^X.
   - Resolve the executable and loader from the installed APK's native-library directory and validate ABI before starting the guest.
4. **Readiness and execution**
   - Readiness must verify manifest version, rootfs marker, `/bin/sh`, PRoot/loader presence and a real `/bin/sh -c 'printf ...'` smoke test.
   - A failed smoke test means NOT READY; never silently route `where=linux` to Android shell or Termux.
   - Keep shell approval, bounded command length, timeout, output cap and untrusted-output handling.
5. **Test on device**
   - Test on a physical ARM64 Android 15/16 device, not only CI/emulator.
   - Test fresh install, upgrade/rollback, offline startup, corrupted download, timeout and a command that executes a guest binary.
   - Report device-tested separately from CI-tested.

## Current safe behavior

`where=builtin` runs the bounded Android shell in Orion's private workspace. `where=termux` is an optional Termux bridge. `where=linux` currently returns an explicit unavailable error rather than pretending Alpine is installed. Keep this behavior until all gates above pass.

## nanoMuse learning

The useful lesson from nanoMuse is not merely “bundle PRoot”: Android executable placement and the guest ELF loaders are part of the runtime contract. A PRoot process that starts is not proof that `/bin/sh` can run. Orion must validate the complete path before exposing Linux as ready.
