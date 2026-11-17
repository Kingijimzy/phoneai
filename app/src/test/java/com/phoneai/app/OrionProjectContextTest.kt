package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionProjectContextTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("orion_project_context", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("studio_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun selectionPersistsAcrossConsumersAndAllowsWorkspaceRoot() {
        assertEquals("projects/demo", OrionProjectContext.set(context, "projects/demo"))
        assertEquals("projects/demo", OrionProjectContext.get(context))
        assertEquals("", OrionProjectContext.set(context, ""))
        assertEquals("", OrionProjectContext.get(context))
    }

    @Test fun migratesThePreviouslySavedStudioProject() {
        context.getSharedPreferences("studio_state", Context.MODE_PRIVATE)
            .edit().putString("project", "apps/orion").commit()
        assertEquals("apps/orion", OrionProjectContext.get(context))
        assertEquals("apps/orion", context.getSharedPreferences("orion_project_context", Context.MODE_PRIVATE)
            .getString("active_project", null))
    }

    @Test fun rejectsAbsoluteAndTraversalPathsInsteadOfSilentlyChangingProject() {
        assertThrows(IllegalArgumentException::class.java) {
            OrionProjectContext.set(context, "../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrionProjectContext.set(context, "/data/local/tmp")
        }
    }
}
