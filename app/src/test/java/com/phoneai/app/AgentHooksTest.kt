package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentHooksTest {
 @Test fun blocksUnrequestedSend(){assertNotNull(AgentHooks.scopeBlockReason("Open WhatsApp and draft a message","click_text",JSONObject().put("text","Send")))}
 @Test fun allowsExplicitSend(){assertNull(AgentHooks.scopeBlockReason("Send this message to Alex","click_text",JSONObject().put("text","Send")))}
 @Test fun negatedAndDraftOnlyRequestsNeverAuthorizeConsequentialActions(){
  listOf("Do not send this message to Alex","Don't send this message to Alex","Never send this message","Draft a message to Amma without sending","Prepare a reply but do not send it").forEach { request ->
   assertNotNull("$request must not authorize clicking Send",AgentHooks.scopeBlockReason(request,"click_text",JSONObject().put("text","Send")))
  }
  assertNotNull("A negated delete must not authorize deleting",AgentHooks.scopeBlockReason("Do not delete the account","click_text",JSONObject().put("text","Delete account")))
 }
 @Test fun positiveSendRequestIsNotBlockedByDraftVerbAlone(){
  assertNull(AgentHooks.scopeBlockReason("Write and send this message to Amma","click_text",JSONObject().put("text","Send")))
  assertNull(AgentHooks.scopeBlockReason("Prepare and send the reply to Amma","click_text",JSONObject().put("text","Send")))
 }
 @Test fun confirmationForDangerousTarget(){assertTrue(AgentHooks.requiresConfirmation("browser_click",JSONObject().put("target","Delete account")));assertFalse(AgentHooks.requiresConfirmation("read_screen",JSONObject()))}
 @Test fun completionNeedsProof(){val p=AgentHooks.ProofRecorder();assertFalse(AgentHooks.completionDecision("Open WhatsApp",p.snapshot()).allowed);p.record("open_app","WhatsApp opened");assertTrue(AgentHooks.completionDecision("Open WhatsApp",p.snapshot()).allowed)}
 @Test fun proofRecorderRecognizesExplicitPostActionUiVerification() {
  val p=AgentHooks.ProofRecorder()
  p.record("click_text","Action returned. LOCAL VERIFY PASS: visible state changed after this action.")
  assertTrue(p.snapshot().single().success)
  assertTrue(p.snapshot().single().verified)
  assertTrue(AgentHooks.completionDecision("Open Settings",p.snapshot()).allowed)
 }
 @Test fun clearingProofsPreventsPriorTaskSuccessFromAuthorizingNewTask() {
  val proofs=AgentHooks.ProofRecorder(); proofs.record("open_app","WhatsApp opened")
  assertTrue(AgentHooks.completionDecision("Open WhatsApp",proofs.snapshot()).allowed)
  proofs.clear(); assertTrue(proofs.snapshot().isEmpty())
  assertFalse(AgentHooks.completionDecision("Open Settings",proofs.snapshot()).allowed)
 }
 @Test fun messageReplyTextAndEmailAreRecognizedAndAuthorizeRequestedSend(){
  listOf("message Amma on WhatsApp","reply to Amma","text Amma","email Amma").forEach { request ->
   assertTrue("$request should route to the phone action agent",AgentHooks.isPhoneActionRequest(request))
   assertNull("$request should authorize its requested messaging send",AgentHooks.scopeBlockReason(request,"click_text",JSONObject().put("text","Send")))
  }
  assertNotNull("A draft request must not authorize sending",AgentHooks.scopeBlockReason("Draft a message to Amma","click_text",JSONObject().put("text","Send")))
 }
 @Test fun consequentialCompletionNeedsActualSendEvidence(){
  val p=AgentHooks.ProofRecorder(); p.record("open_app","WhatsApp opened")
  assertFalse(AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
  p.record("verify_action","PASS: visible screen state changed. Current screen evidence: message sent")
  assertFalse("Screen change/OCR is not confirmation",AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
  p.record("send","WhatsApp message sent to Amma")
  assertTrue(AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
 }
 @Test fun sendVerificationRequiresContactExactMessageAndVisibleStatus(){
  val sent=listOf("Amma","Hi Amma, I am on my way.","Sent 8:42 AM").joinToString(System.lineSeparator())
  val draft=listOf("Amma","Hi Amma, I am on my way.").joinToString(System.lineSeparator())
  val wrong=listOf("Amma","Sent message to another person").joinToString(System.lineSeparator())
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.",sent).startsWith("PASS:"))
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.",draft).startsWith("UNVERIFIED:"))
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.",wrong).startsWith("error:"))
 }
 @Test fun settlesScreen(){val a=listOf("Loading","Loaded","Loaded");var i=0;assertEquals("Loaded",AgentHooks.awaitStableSnapshot({a[minOf(i++,a.lastIndex)]},4,2,0))}
 @Test fun failedReadDoesNotCountAsAStableSnapshot(){
  var calls=0
  val snapshot=AgentHooks.awaitStableSnapshot({
   calls++
   when(calls){1->"Loading";2->throw IllegalStateException("accessibility unavailable");else->"Ready"}
  },maxSamples=4,stableSamples=2,intervalMs=0)
  assertEquals("Ready",snapshot)
  assertEquals(4,calls)
 }
 @Test fun actionWordsInsideNormalSentencesDoNotBecomeCommands() {
  assertFalse(AgentHooks.isPhoneActionRequest("I want to write a poem"))
  assertFalse(AgentHooks.isPhoneActionRequest("Explain how to open a file"))
  assertFalse(AgentHooks.isPhoneActionRequest("Show me how computer memory works"))
  assertTrue(AgentHooks.isPhoneActionRequest("Can you please open WhatsApp"))
  assertTrue(AgentHooks.isPhoneActionRequest("What is the BTC price on Bybit"))
  assertTrue(AgentHooks.isPhoneActionRequest("Write a message to Amma on WhatsApp"))
 }
 @Test fun ordinaryMemoryAndTaskMentionsDoNotBecomeCommands() {
  assertFalse(AgentHooks.isPhoneActionRequest("I remember when we first talked"))
  assertFalse(AgentHooks.isPhoneActionRequest("I have a task about learning memory"))
  assertFalse(AgentHooks.isPhoneActionRequest("Tell me how computer memory works"))
  assertTrue(AgentHooks.isPhoneActionRequest("Remember that I prefer solid graphite UI"))
  assertTrue(AgentHooks.isPhoneActionRequest("Create a task to call Amma tomorrow"))
 }
 @Test fun requestBudgetStopsAtConfiguredLimit() {
  val budget=AgentHooks.RequestBudget(2)
  assertEquals(0,budget.consumed); assertEquals(2,budget.remaining)
  assertTrue(budget.tryConsume()); assertTrue(budget.tryConsume()); assertFalse(budget.tryConsume())
  assertEquals(2,budget.consumed); assertEquals(0,budget.remaining)
 }
 @Test fun identicalFailedToolCallIsBlockedButDifferentArgumentsMayRecover() {
  val guard=AgentHooks.FailedCallGuard(); val same=JSONObject().put("text","Send")
  assertFalse(guard.isBlocked("click_text",same)); guard.recordFailure("click_text",same)
  assertTrue(guard.isBlocked("click_text",JSONObject().put("text","Send")))
  assertFalse(guard.isBlocked("click_text",JSONObject().put("text","Retry")))
  assertFalse(guard.isBlocked("read_screen",same)); guard.clear(); assertFalse(guard.isBlocked("click_text",same))
 }
 @Test fun screenComparisonIgnoresIndexAndDetectorNoiseButDetectsMeaningfulChange() {
  val before="Screen map\n1: WhatsApp\n2: Amma\nDetector 7 ms"
  val sameContent="Screen map\nWhatsApp\nAmma\nDetector 14 ms"
  val after="Screen map\nWhatsApp\nAmma\nSent 8:42 AM"
  assertFalse(AgentHooks.screenChanged(before,sameContent)); assertTrue(AgentHooks.screenChanged(before,after))
  assertEquals(AgentHooks.screenFingerprint(before),AgentHooks.screenFingerprint(sameContent))
 }

    @Test fun forgetLetsOnlyThatExactCallRunAgain() {
        val g = AgentHooks.FailedCallGuard()
        val a = org.json.JSONObject("{\"q\":\"x\"}"); val b = org.json.JSONObject("{\"q\":\"y\"}")
        g.recordFailure("web_search", a); g.recordFailure("web_search", b)
        g.forget("web_search", a)
        assertFalse(g.isBlocked("web_search", a))
        assertTrue(g.isBlocked("web_search", b))
    }

    @Test fun durableVerifiedEvidenceCanRestoreCompletionAfterProcessRestart() {
        val event = OrionEvidenceLedger.Event("1", "task", "device", "click",
            OrionEvidenceLedger.Outcome.VERIFIED, 1L, 2L, "hash", "visible state changed")
        val decision = AgentHooks.completionDecision("Open the app", emptyList(), listOf(event))
        assertTrue(decision.allowed)
    }

    @Test fun durableDispatchAcceptanceAloneCannotVerifyConsequentialGoal() {
        val event = OrionEvidenceLedger.Event("2", "task", "shell", "shell",
            OrionEvidenceLedger.Outcome.EXECUTED_UNVERIFIED, 1L, 2L, "hash", "command returned")
        val decision = AgentHooks.completionDecision("Send a message to Amma", emptyList(), listOf(event))
        assertFalse(decision.allowed)
    }

    @Test fun durableFailureAndRecoveryEventsNeverCountAsSuccess() {
        val events = listOf(
            OrionEvidenceLedger.Event("3", "task", "mcp", "mcp_call",
                OrionEvidenceLedger.Outcome.FAILED, 1L, 2L, "hash", "offline"),
            OrionEvidenceLedger.Event("4", "task", "orchestrator", "mcp_call",
                OrionEvidenceLedger.Outcome.RECOVERY, 2L, 3L, "", "stop and report")
        )
        val decision = AgentHooks.completionDecision("Read my connected data", emptyList(), events)
        assertFalse(decision.allowed)
    }
}
