package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionBuiltInSpecialistsTest {
    private fun context(): Context = RuntimeEnvironment.getApplication<Context>().also { c ->
        c.filesDir.listFiles()?.filter { it.name.startsWith("orion_evidence_") }?.forEach { it.delete() }
    }

    @Test fun allFiveConcreteSpecialistsAreRegisteredWithTheirRealTaskKinds() {
        val workers = OrionBuiltInSpecialists(context()).workers()
        assertEquals(5, workers.size)
        assertEquals(setOf("orion-research", "orion-coding", "orion-diagnostics", "orion-trading", "orion-verification"),
            workers.map { it.workerId }.toSet())
        assertTrue(workers.any { OrionTaskEnvelope.Kind.RESEARCH in it.supportedKinds })
        assertTrue(workers.any { OrionTaskEnvelope.Kind.CODE in it.supportedKinds })
        assertTrue(workers.any { OrionTaskEnvelope.Kind.DIAGNOSTICS in it.supportedKinds })
        assertTrue(workers.any { OrionTaskEnvelope.Kind.TRADING_ANALYSIS in it.supportedKinds })
        assertTrue(workers.any { OrionTaskEnvelope.Kind.VERIFICATION in it.supportedKinds })
    }

    @Test fun diagnosticsAdapterPersistsEvidenceFromLiveCapabilityHub() {
        val c = context()
        val worker = OrionBuiltInSpecialists(c).workers().first { it.workerId == "orion-diagnostics" }
        val task = OrionTaskEnvelope.create("inspect current Orion capabilities", OrionTaskEnvelope.Kind.DIAGNOSTICS,
            capabilities = listOf("diagnostics"))
        val result = worker.execute(task)
        assertTrue(result is OrionWorkerResult.Success)
        val ref = (result as OrionWorkerResult.Success).evidenceRef
        assertTrue(ref.startsWith("evidence:"))
        val digest = ref.removePrefix("evidence:")
        assertTrue(java.io.File(c.filesDir, "orion_evidence_$digest.json").isFile)
    }

    @Test fun codingAdapterNeverWritesWithoutASelectedWorkspace() {
        val c = context()
        c.getSharedPreferences("phoneai_workspace", Context.MODE_PRIVATE).edit().clear().commit()
        val worker = OrionBuiltInSpecialists(c).workers().first { it.workerId == "orion-coding" }
        val task = OrionTaskEnvelope.create("review MainActivity.kt", OrionTaskEnvelope.Kind.CODE,
            capabilities = listOf("coding"))
        assertTrue(worker.execute(task) is OrionWorkerResult.NeedsOwner)
    }

    @Test fun researchAndVerificationRefuseToInventMissingInputs() {
        val workers = OrionBuiltInSpecialists(context()).workers()
        val research = workers.first { it.workerId == "orion-research" }
            .execute(OrionTaskEnvelope.create("research the latest market", OrionTaskEnvelope.Kind.RESEARCH, capabilities = listOf("research")))
        val verify = workers.first { it.workerId == "orion-verification" }
            .execute(OrionTaskEnvelope.create("verify this result", OrionTaskEnvelope.Kind.VERIFICATION, capabilities = listOf("verification")))
        assertTrue(research is OrionWorkerResult.NeedsOwner)
        assertTrue(verify is OrionWorkerResult.NeedsOwner)
    }
}
