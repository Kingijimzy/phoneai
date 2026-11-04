package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/** Creates and inspects bounded multi-file projects in the same workspace mounted by CPython and Alpine. */
internal class OrionProjectWorkspace(context: Context) {
    private val files = OrionPythonWorkspace(context.applicationContext)
    private val prefix = "projects/"
    private val namePattern = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$")

    fun create(rawName: String, rawLanguage: String): String {
        val name = rawName.trim()
        val language = rawLanguage.trim().lowercase()
        if (!namePattern.matches(name)) return "error: project name must be 1-40 letters, numbers, underscores or hyphens."
        if (language !in setOf("python", "javascript", "kotlin", "android")) {
            return "error: supported project templates are python, javascript, kotlin and android."
        }
        val base = prefix + name
        if (files.list().lineSequence().any { it.startsWith("$base/") }) {
            return "error: project '$name' already exists; existing files were left unchanged."
        }
        val manifest = JSONObject()
            .put("name", name)
            .put("language", language)
            .put("schema", 1)
            .put("createdAt", System.currentTimeMillis())
        val entries = linkedMapOf("project.json" to manifest.toString(2))
        entries.putAll(template(name, language))
        val written = mutableListOf<String>()
        for ((relative, body) in entries) {
            val path = "$base/$relative"
            val result = files.write(path, body)
            if (!result.startsWith("saved ")) {
                written.asReversed().forEach { files.delete(it) }
                return "error: project creation failed at $relative; created files were rolled back. $result"
            }
            written += path
        }
        return "PROJECT CREATED: $name ($language)\nFiles:\n" +
            written.joinToString("\n") { it.removePrefix("$base/") } +
            "\nProject root: /workspace/$base\nUse shell where=linux to run commands in this shared workspace."
    }

    fun list(): String {
        val paths = files.list().lineSequence().filter { it.startsWith(prefix) }.toList()
        val projects = paths.mapNotNull { path ->
            val parts = path.removePrefix(prefix).split('/')
            if (parts.size < 2 || parts.last() != "project.json") null else parts.first()
        }.distinct().sorted()
        if (projects.isEmpty()) return "No projects yet. Use project_create with a name and language."
        return projects.joinToString("\n") { name ->
            val metadata = files.read("$prefix$name/project.json")
            val language = runCatching { JSONObject(metadata).optString("language", "unknown") }.getOrDefault("unknown")
            "$name ($language)"
        }
    }

    fun inspect(rawName: String): String {
        val name = rawName.trim()
        if (!namePattern.matches(name)) return "error: invalid project name."
        val base = "$prefix$name"
        val metadata = files.read("$base/project.json")
        if (metadata.startsWith("error:")) return "error: project '$name' was not found."
        val paths = files.list().lineSequence().filter { it.startsWith("$base/") }.toList()
        return "PROJECT: $name\nMETADATA: $metadata\nFILES:\n" +
            paths.joinToString("\n") { it.removePrefix("$base/") }
    }

    private fun template(name: String, language: String): Map<String, String> = when (language) {
        "python" -> mapOf(
            "README.md" to "# $name\n\nPython project. Run with: python3 main.py\nTest with: python3 -m unittest discover -s tests -v\n",
            "main.py" to "def main():\n    print(\"Hello from $name\")\n\nif __name__ == \"__main__\":\n    main()\n",
            "tests/test_main.py" to "import unittest\nfrom main import main\n\nclass MainTests(unittest.TestCase):\n    def test_example(self):\n        self.assertTrue(callable(main))\n\nif __name__ == \"__main__\":\n    unittest.main()\n"
        )
        "javascript" -> mapOf(
            "README.md" to "# $name\n\nJavaScript project. Install packages with npm install. Run with npm start. Test with npm test.\n",
            "package.json" to JSONObject().put("name", name.lowercase()).put("version", "1.0.0")
                .put("private", true).put("scripts", JSONObject().put("start", "node src/index.js").put("test", "node --test"))
                .toString(2) + "\n",
            "src/index.js" to "export function main() {\n  console.log(\"Hello from $name\");\n}\n\nmain();\n",
            "test/main.test.js" to "import test from 'node:test';\nimport assert from 'node:assert/strict';\nimport { main } from '../src/index.js';\n\ntest('main is callable', () => { assert.equal(typeof main, 'function'); });\n"
        )
        "kotlin" -> mapOf(
            "README.md" to "# $name\n\nKotlin/JVM project. Requires JDK 17+ and Gradle 8.7+. Run: gradle test.\n",
            "settings.gradle.kts" to "rootProject.name = \"$name\"\n",
            "build.gradle.kts" to "plugins { kotlin(\"jvm\") version \"2.0.21\" }\n\nrepositories { mavenCentral() }\n\ndependencies { testImplementation(kotlin(\"test\")) }\n\ntasks.test { useJUnitPlatform() }\n",
            "src/main/kotlin/Main.kt" to "fun main() {\n    println(\"Hello from $name\")\n}\n",
            "src/test/kotlin/MainTest.kt" to "import kotlin.test.Test\nimport kotlin.test.assertEquals\n\nclass MainTest {\n    @Test fun example() { assertEquals(42, 40 + 2) }\n}\n"
        )
        else -> mapOf(
            "README.md" to "# $name\n\nAndroid application starter. Build with Gradle after installing a compatible JDK and Android SDK (platform 35 and build tools).\n",
            "settings.gradle.kts" to "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name = \"$name\"\ninclude(\":app\")\n",
            "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.7.3\" apply false; id(\"org.jetbrains.kotlin.android\") version \"2.0.21\" apply false }\n",
            "app/build.gradle.kts" to "plugins { id(\"com.android.application\"); id(\"org.jetbrains.kotlin.android\") }\n\nandroid { namespace = \"com.example.$name\"; compileSdk = 35; defaultConfig { applicationId = \"com.example.$name\"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = \"1.0\" } }\n",
            "app/src/main/AndroidManifest.xml" to "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@android:style/Theme.Material.Light.NoActionBar\" android:label=\"$name\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>\n",
            "app/src/main/java/com/example/$name/MainActivity.kt" to "package com.example.$name\n\nimport android.app.Activity\nimport android.os.Bundle\nimport android.widget.TextView\n\nclass MainActivity : Activity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n        setContentView(TextView(this).apply { text = \"Hello from $name\"; textSize = 24f })\n    }\n}\n"
        )
    }
}
