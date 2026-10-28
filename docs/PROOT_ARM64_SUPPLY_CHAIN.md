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

The review-only workflow `.github/workflows/build-proot-candidate.yml` now attempts a pinned candidate build using OpenMinis/OpenMinis commit `1a71df0f381cefde80bf912b723de6f9831bc177`, its checked-in submodule pointer for OpenMinis/proot, Android NDK r28 `28.0.12433566`, and talloc 2.4.2 with SHA-256 `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6`. It separately pins the upstream loader files by SHA-256 (`44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` for ARM64 and `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34` for ARM32) and emits a short-lived review artifact only. These inputs are candidates, not yet accepted production artifacts. The workflow passed in CI run [40597941057](https://github.com/Kingijimzy/phoneai/actions/runs/40597941057) on commit `3cc53b66a0ca7a94aec566dff164da251eb5cf7e`. It produced a review-only artifact `proot-arm64-review-candidate` (artifact ID `12776338113`, expires 2026-11-10). Candidate output measurements from the successful build: `proot-aarch64` 294,416 bytes, SHA-256 `91c3f3ce38482fba523c0dcbfa5eb883e8aae063236bff3d091da6a58f1c94c4` (see correction below); `libproot.so` 294,416 bytes, SHA-256 `91c3f3ce38482fba523c0dcbfa5eb883e8aae063236bff3d091da6a58f1c94c4`; `libproot-loader.so` 18,136 bytes, SHA-256 `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04`; `libproot-loader32.so` 6,244 bytes, SHA-256 `25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34`. The full 64-character PRoot SHA-256 values above were rechecked against the successful CI job log. The build checked ELF headers and uploaded the candidate, but did not run the Android guest-shell smoke test. Keep GPL-2.0 and LGPL notices/source obligations aligned with the exact components actually shipped.

## Integration status

- Source commit identified: **yes**.
- PRoot candidate build workflow committed: **yes; CI build succeeded (run 40597941057)**.
- PRoot binary built in CI: **yes; candidate only**.
- Guest `/bin/sh` smoke-tested by running PRoot on Android: **not yet**.
- Matching loader hashes pinned in candidate workflow: **yes; build verified the expected hashes**.
- Production PRoot binary checksum and size pinned: **not yet**.
- Runtime connected and device-tested: **not yet**.

Do not create a production Linux manifest from a source commit alone. The production manifest must pin the actual built binary and loader artifacts as well as the Alpine rootfs.


## Verified rootfs input (CI run 40594657368)

The separate Alpine verification workflow downloaded the archive and validated it against both official sidecars:

- URL: `https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.7-aarch64.tar.gz`
- Version / architecture: `3.22.7` / `aarch64`
- Exact archive size: `3971679` bytes
- SHA-256: `7e9b34ff3304381909e566e2c93de450664ccc724191261423974fc121c11623`
- SHA-512: `b8a60e0944dcf9e5f6f2caaf9bd260429aedebf56d5aa68d96b2eeaea4448526edaad00ee71712d96dbb74f70f2c3ae59f452afa7e2fe9b0f7635c251fdb533c`

The workflow run succeeded and uploaded the sidecars plus candidate metadata. These values are now backed by a successful CI verification, but the rootfs remains only one input to the full production manifest. Do not activate Linux execution until the PRoot binary and loader artifacts are independently pinned and smoke-tested.
