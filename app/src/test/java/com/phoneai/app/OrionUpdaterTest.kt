package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionUpdaterTest {
    private val payload = """{"tag_name":"build-412","name":"Phone AI build 412","assets":[
        {"name":"notes.txt","url":"u0","browser_download_url":"https://x/notes.txt","size":10},
        {"name":"app-debug.apk","url":"https://api.github.com/repos/a/b/releases/assets/9","browser_download_url":"https://github.com/a/b/releases/download/build-412/app-debug.apk","size":52000000}]}"""

    @Test fun picksTheApkAsset() {
        val r = OrionAppUpdater.parseRelease(payload)!!
        assertEquals("build-412", r.tag)
        assertTrue(r.apkUrl.endsWith("app-debug.apk"))
        assertTrue(OrionAppUpdater.safeApk(r))
        assertNull(OrionAppUpdater.parseRelease("""{"tag_name":"build-1","assets":[]}"""))
        assertNull(OrionAppUpdater.parseRelease("nope"))
    }

    @Test fun newerIsNumericAndUnknownIsConservative() {
        assertTrue(OrionAppUpdater.isNewer("build-412", "build-99"))
        assertFalse(OrionAppUpdater.isNewer("build-99", "build-412"))
        assertFalse(OrionAppUpdater.isNewer("build-412", "build-412"))
        assertTrue(OrionAppUpdater.isNewer("build-412", null))
        assertFalse(OrionAppUpdater.isNewer("nightly", "build-1"))
    }

    @Test fun refusesAbsurdAssetSizes() {
        val tiny = OrionAppUpdater.Release("build-1", "", "https://x/a.apk", "", 500)
        assertFalse(OrionAppUpdater.safeApk(tiny))
        assertNotNull(tiny)
    }
}
