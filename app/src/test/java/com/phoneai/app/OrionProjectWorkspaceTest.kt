package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionProjectWorkspaceTest {
    private lateinit var context: Context
    private lateinit var workspace: OrionPythonWorkspace
    private lateinit var projects: OrionProjectWorkspace

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        workspace = OrionPythonWorkspace(context)
        workspace.ensureRootForRepair()
        workspace.list()
        listOf("demo", "webapp", "mobile_app", "android_demo").forEach { name ->
            val prefix = "projects/$name/"
            workspace.list().lineSequence().filter { it.startsWith(prefix) }.forEach { workspace.delete(it) }
        }
    }

    @After fun tearDown() {
        listOf("demo", "webapp", "mobile_app", "android_demo").forEach { name ->
            val prefix = "projects/$name/"
            workspace.list().lineSequence().filter { it.startsWith(prefix) }.forEach { workspace.delete(it) }
        }
    }

    @Test fun createsInspectableMultiFilePythonProject() {
        projects = OrionProjectWorkspace(context)
        val created = projects.create("demo", "python")
        assertTrue(created.startsWith("PROJECT CREATED:"))
        assertTrue(workspace.read("projects/demo/main.py").contains("def main"))
        assertTrue(workspace.read("projects/demo/.gitignore").contains("__pycache__/"))
        assertTrue(workspace.list().contains("projects/demo/.gitignore"))
        assertTrue(workspace.read("projects/demo/tests/test_main.py").contains("unittest"))
        assertTrue(projects.list().contains("demo (python)"))
        assertTrue(projects.inspect("demo").contains("tests/test_main.py"))
    }

    @Test fun createsJavaScriptKotlinAndAndroidProjectScaffolds() {
        projects = OrionProjectWorkspace(context)
        assertTrue(projects.create("webapp", "javascript").startsWith("PROJECT CREATED:"))
        assertTrue(workspace.read("projects/webapp/package.json").contains("node --test"))
        assertTrue(workspace.read("projects/webapp/src/cli.js").contains("main()"))
        assertTrue(projects.create("mobile_app", "kotlin").startsWith("PROJECT CREATED:"))
        assertTrue(workspace.read("projects/mobile_app/build.gradle.kts").contains("kotlin(\"jvm\")"))
        assertTrue(projects.create("android_demo", "android").startsWith("PROJECT CREATED:"))
        assertTrue(workspace.read("projects/android_demo/app/build.gradle.kts").contains("compileSdk = 35"))
        assertTrue(workspace.read("projects/android_demo/app/src/main/java/com/example/app_android_demo/MainActivity.kt").contains("class MainActivity"))
    }

    @Test fun refusesDuplicateOrUnsafeProjectNamesWithoutOverwriting() {
        projects = OrionProjectWorkspace(context)
        assertTrue(projects.create("demo", "python").startsWith("PROJECT CREATED:"))
        val before = workspace.read("projects/demo/main.py")
        assertTrue(projects.create("demo", "javascript").startsWith("error:"))
        assertTrue(projects.create("../escape", "python").startsWith("error:"))
        assertTrue(workspace.read("projects/demo/main.py") == before)
        assertFalse(workspace.read("projects/escape/project.json").startsWith("saved"))
    }

    @Test fun unsupportedLanguageFailsWithoutCreatingProjectFiles() {
        projects = OrionProjectWorkspace(context)
        assertTrue(projects.create("demo", "ruby").startsWith("error:"))
        assertTrue(workspace.read("projects/demo/project.json").startsWith("error:"))
    }
}
