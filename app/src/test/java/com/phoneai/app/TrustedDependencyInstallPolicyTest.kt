package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDependencyInstallPolicyTest {
    private val repos = "--repositories-file /dev/null --repository https://dl-cdn.alpinelinux.org/alpine/v3.22/main " +
        "--repository https://dl-cdn.alpinelinux.org/alpine/v3.22/community"

    @Test fun permitsOnlyPinnedSignedOfficialRepositoryInstalls() {
        assertTrue(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos add python3 git nodejs npm"))
        assertTrue(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos add python3=3.12.15-r0"))
    }

    @Test fun rejectsUnknownRepositoriesAndUntrustedOverrides() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall(
            "apk --no-cache --repositories-file /dev/null --repository https://example.com/alpine/v3.22/main --repository https://example.com/alpine/v3.22/community add python3"
        ))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos --allow-untrusted add python3"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos --keys-dir /tmp/keys add python3"))
    }

    @Test fun rejectsRemovalUpgradeAndUnpinnedRepositoryCommands() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos del python3"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos upgrade"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache add python3"))
    }

    @Test fun rejectsShellInjectionAndInvalidPackageTokens() {
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos add python3; rm -rf ."))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos add https://example.com/payload"))
        assertFalse(TrustedDependencyInstallPolicy.isSafeAlpineInstall("apk --no-cache $repos add $(id)"))
    }
}
