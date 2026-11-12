package com.phoneai.app

/** Alpine 3.22 aarch64 provides the static bubblewrap executable as bwrap.static. */
internal object CodexSandboxSetupPolicy {
    val ensureCommand = """
        set -eu
        if ! command -v bwrap >/dev/null 2>&1; then
          apk add --no-cache bubblewrap-static
        fi
        if ! command -v bwrap >/dev/null 2>&1; then
          bwrap_static="$(command -v bwrap.static || true)"
          test -n "$bwrap_static" || { echo 'bubblewrap-static did not install bwrap.static on this Alpine architecture' >&2; exit 42; }
          ln -sf "$bwrap_static" /usr/local/bin/bwrap
        fi
        command -v bwrap >/dev/null
        bwrap --version
    """.trimIndent()
}
