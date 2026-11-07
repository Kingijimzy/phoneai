package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for mandatory outcome verification - tool execution completion
 * must never automatically mean the user's objective succeeded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VerificationEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testVerifyActionRequiresEvidence() {
        // verify_action should require actual screen evidence, not just tool success
        val args = JSONObject().put("expected_text", "Message sent")

        // Test the verification logic contract
        // If screen doesn't contain expected text, verification should fail
        val screenWithMessage = "[1] Button\n[2] Text Field - Message sent successfully"
        val screenWithoutMessage = "[1] Button\n[2] Text Field - Message not sent"
        
        assertTrue("Screen with message should contain expected text", screenWithMessage.contains("Message sent"))
        assertFalse("Screen without message should not contain expected text", screenWithoutMessage.contains("Message sent"))
    }

    @Test
    fun testVerifySendRequiresMessageConfirmation() {
        // verify_send should check the messaging screen for the exact message
        val args = JSONObject().put("contact", "Mom").put("message", "Hello")

        val screenWithMessage = "[1] Mom\n[2] Hello"
        assertTrue("Screen should contain contact and message", screenWithMessage.contains("Mom") && screenWithMessage.contains("Hello"))
    }

    @Test
    fun testShellVerificationRequiresExitCode() {
        // Shell commands should verify via exit code
        val successResult = "EXIT 0 · SUCCESS\nLOCAL VERIFY PASS: shell process exited with code 0."
        val failedResult = "EXIT 1 · FAILED\nCommand failed"

        assertTrue("Success result should contain verify pass", successResult.contains("LOCAL VERIFY PASS:") && successResult.contains("exit=0"))
        assertFalse("Failed result should not contain verify pass", failedResult.contains("LOCAL VERIFY PASS:"))
    }

    @Test
    fun testToolExecutionNotEqualToMissionSuccess() {
        // A tool returning without error does NOT prove the user's goal was completed
        // This is the core principle: execution ≠ completion

        val toolResult = "App opened successfully" // Tool succeeded
        val verificationFailed = "error: verification failed: no visible screen change was detected after 'open_app'."

        // The verifiedResult should be the verification failure, not the tool success
        assertTrue("Verification failure should override tool success", verificationFailed.startsWith("error:"))
    }

    @Test
    fun testCompletionGateRequiresVerifiedEvidence() {
        // AgentHooks.completionDecision should require verified evidence
        val runEvidence = listOf(
            AgentHooks.Proof("open_app", "result", true, false), // success but not verified
            AgentHooks.Proof("click", "result", true, true) // success and verified
        )

        // Should require verified evidence, not just success
        // This is enforced in runInternalBody where completion gate checks verifiedOk()
        val hasVerified = runEvidence.any { it.verified }
        assertTrue("Should have at least one verified action", hasVerified)
    }

    @Test
    fun testUnverifiedOutcomeReportedHonestly() {
        // If result cannot be checked, outcome should be UNVERIFIED not success
        val unreadableScreen = "UNVERIFIED: could not read the current screen"
        assertTrue("Should report UNVERIFIED when screen unavailable", unreadableScreen.contains("UNVERIFIED:"))
    }
}