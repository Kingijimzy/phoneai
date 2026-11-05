package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDependencyInstallPolicyTest {
    private val repos = "--repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main " +
        "--repository https://dl-cdn.alpinelinux.org/alpine/v3.22/community"

    @Test fun permitsOnlyPinnedSignedOfficialRepositoryInstalls() {
        assertTrue(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos python3 git nodejs npm"))
        assertTrue(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos python3=3.12.15-r0"))
    }

    @Test fun rejectsUnknownRepositoriesAndUntrustedOverrides() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall(
            "apk add --no-cache --repositories-file /dev/null --repository https://example.com/alpine/v3.22/main --repository https://example.com/alpine/v3.22/community python3"
        ))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache --repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/community --allow-untrusted python3"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos --keys-dir /tmp/keys python3"))
    }

    @Test fun rejectsRemovalUpgradeAndUnpinnedRepositoryCommands() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk del --no-cache $repos python3"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk upgrade --no-cache $repos"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache python3"))
    }

    @Test fun rejectsShellInjectionAndInvalidPackageTokens() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos python3; rm -rf ."))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos https://example.com/payload"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk add --no-cache $repos $(id)"))
    }
}
