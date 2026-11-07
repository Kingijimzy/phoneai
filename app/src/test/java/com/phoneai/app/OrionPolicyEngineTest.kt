package com.phoneai.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for OrionPolicyEngine - central policy enforcement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionPolicyEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPolicyCheckCategories() {
        val categories = OrionPolicyEngine.PolicyCheck.Category.values()
        assertTrue(categories.size >= 8)
        
        val categoryNames = categories.map { it.name }.toSet()
        assertTrue(categoryNames.contains("CAPABILITY"))
        assertTrue(categoryNames.contains("PERMISSION"))
        assertTrue(categoryNames.contains("SAFETY"))
        assertTrue(categoryNames.contains("SCOPE"))
        assertTrue(categoryNames.contains("RESOURCE"))
        assertTrue(categoryNames.contains("SECURITY"))
        assertTrue(categoryNames.contains("GOVERNANCE"))
        assertTrue(categoryNames.contains("QUALITY"))
        assertTrue(categoryNames.contains("COMPLIANCE"))
    }

    @Test
    fun testPolicyCheckStatuses() {
        val statuses = OrionPolicyEngine.PolicyCheck.Status.values()
        assertEquals(5, statuses.size)
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyCheck.Status.PASS))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyCheck.Status.FAIL))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyCheck.Status.SOFT_VIOLATION))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyCheck.Status.WARNING))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyCheck.Status.BLOCKED))
    }

    @Test
    fun testPolicyEvaluationStatuses() {
        val statuses = OrionPolicyEngine.PolicyEvaluation.OverallStatus.values()
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyEvaluation.OverallStatus.ALLOW))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyEvaluation.OverallStatus.ALLOW_WITH_CONDITIONS))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyEvaluation.OverallStatus.REQUIRE_APPROVAL))
        assertTrue(statuses.contains(OrionPolicyEngine.PolicyEvaluation.OverallStatus.BLOCK))
    }

    @Test
    fun testRequiredApprovalTypes() {
        val types = OrionPolicyEngine.RequiredApproval.ApprovalType.values()
        assertEquals(5, types.size)
        assertTrue(types.contains(OrionPolicyEngine.RequiredApproval.ApprovalType.TRADING_ORDER))
        assertTrue(types.contains(OrionPolicyEngine.RequiredApproval.ApprovalType.SYSTEM_CHANGE))
        assertTrue(types.contains(OrionPolicyEngine.RequiredApproval.ApprovalType.IRREVERSIBLE_ACTION))
        assertTrue(types.contains(OrionPolicyEngine.RequiredApproval.ApprovalType.SENSITIVE_DATA))
        assertTrue(types.contains(OrionPolicyEngine.RequiredApproval.ApprovalType.EXTERNAL_API))
    }

    @Test
    fun testPolicyCheckConstruction() {
        val check = OrionPolicyEngine.PolicyCheck(
            checkId = "check_1",
            category = OrionPolicyEngine.PolicyCheck.Category.CAPABILITY,
            target = "open_app",
            status = OrionPolicyEngine.PolicyCheck.Status.PASS,
            message = "Capability available",
            severity = OrionPolicyEngine.PolicyCheck.Severity.INFO
        )

        assertEquals("check_1", check.checkId)
        assertEquals(OrionPolicyEngine.PolicyCheck.Category.CAPABILITY, check.category)
        assertEquals("open_app", check.target)
        assertEquals(OrionPolicyEngine.PolicyCheck.Status.PASS, check.status)
    }

    @Test
    fun testPolicyEvaluationConstruction() {
        val evaluation = OrionPolicyEngine.PolicyEvaluation(
            overallStatus = OrionPolicyEngine.PolicyEvaluation.OverallStatus.ALLOW_WITH_CONDITIONS,
            checks = listOf(
                OrionPolicyEngine.PolicyCheck(
                    checkId = "c1",
                    category = OrionPolicyEngine.PolicyCheck.Category.CAPABILITY,
                    target = "open_app",
                    status = OrionPolicyEngine.PolicyCheck.Status.PASS,
                    message = "Available",
                    severity = OrionPolicyEngine.PolicyCheck.Severity.INFO
                ),
                OrionPolicyEngine.PolicyCheck(
                    checkId = "c2",
                    category = OrionPolicyEngine.PolicyCheck.Category.RESOURCE,
                    target = "api_quota",
                    status = OrionPolicyEngine.PolicyCheck.Status.SOFT_VIOLATION,
                    message = "Near quota limit",
                    severity = OrionPolicyEngine.PolicyCheck.Severity.MEDIUM
                )
            ),
            blockedActions = emptyList(),
            requiredApprovals = emptyList(),
            warnings = listOf("Near quota limit"),
            recommendations = listOf("Batch requests")
        )

        assertEquals(OrionPolicyEngine.PolicyEvaluation.OverallStatus.ALLOW_WITH_CONDITIONS, evaluation.overallStatus)
        assertEquals(2, evaluation.checks.size)
        assertEquals(1, evaluation.warnings.size)
        assertEquals(1, evaluation.recommendations.size)
    }
}
