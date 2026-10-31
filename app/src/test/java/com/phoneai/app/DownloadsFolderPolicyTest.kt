package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsFolderPolicyTest {
    @Test fun acceptsSafeSingleFolderNames() {
        assertEquals("Orion_Test", DownloadsFolderPolicy.normalize("Orion_Test"))
        assertEquals("Orion", DownloadsFolderPolicy.normalize(""))
    }

    @Test fun rejectsTraversalAndNestedPaths() {
        assertNull(DownloadsFolderPolicy.normalize("../private"))
        assertNull(DownloadsFolderPolicy.normalize("Orion/secret"))
        assertNull(DownloadsFolderPolicy.normalize("/Android/data"))
    }
}
