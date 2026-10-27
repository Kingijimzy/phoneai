# ARM64 PRoot source pin and build gate

## Selected source pin

- Upstream project: https://github.com/OpenMinis/proot
- Reviewed source commit: `2ca3bae3650988c0e098b4bb7686b79617b64194`
- Commit date: 2026-10-25
- Commit signature: GitHub API reports the commit as verified.
- Android packaging reference: https://github.com/OpenMinis/OpenMinis/blob/main/deps/build_proot.sh
- Reference implementation: https://github.com/nano-muse/nanoMuse/blob/main/android/deps/build_proot.sh

This is a source pin, **not a binary pin**. It does not prove that a binary has been built, that the matching loaders are available, or that the resulting APK works on Orion's supported devices.

## Build inputs that must be pinned before integrating artifacts

1. PRoot source tree at the exact commit above, including any submodules and patches.
2. Android NDK version and API target.
3. talloc source archive version **and independently verified archive checksum**.
4. The ARM64 PRoot executable and its required ARM64 guest loader. Include the 32-bit loader only if Orion explicitly supports 32-bit guest binaries.
5. SHA-256, byte count, ELF machine/type and license/provenance record for each packaged output.
6. A build-time smoke test that executes a guest `/bin/sh -c 'printf ORION_PROOT_OK'` using the same executable and loaders that will be packaged.

The upstream OpenMinis build script is useful evidence for the Android W^X and loader requirements, but it must not be blindly imported as a trusted build step. In particular, review its source-download/checksum behavior and loader provenance before using it in a release pipeline. Keep GPL-2.0 and LGPL notices/source obligations aligned with the exact components actually shipped.

## Integration status

- Source commit identified: **yes**.
- PRoot binary built from that source in Orion CI: **not yet**.
- Matching loaders independently verified and packaged in Orion: **not yet**.
- Production PRoot binary checksum and size pinned: **not yet**.
- Runtime connected and device-tested: **not yet**.

Do not create a production Linux manifest from a source commit alone. The production manifest must pin the actual built binary and loader artifacts as well as the Alpine rootfs.
