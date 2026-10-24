package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentHooksTest {
 @Test fun blocksUnrequestedSend(){assertNotNull(AgentHooks.scopeBlockReason("Open WhatsApp and draft a message","click_text",JSONObject().put("text","Send")))}
 @Test fun allowsExplicitSend(){assertNull(AgentHooks.scopeBlockReason("Send this message to Alex","click_text",JSONObject().put("text","Send")))}
 @Test fun confirmationForDangerousTarget(){assertTrue(AgentHooks.requiresConfirmation("browser_click",JSONObject().put("target","Delete account")));assertFalse(AgentHooks.requiresConfirmation("read_screen",JSONObject()))}
 @Test fun completionNeedsProof(){val p=AgentHooks.ProofRecorder();assertFalse(AgentHooks.completionDecision("Open WhatsApp",p.snapshot()).allowed);p.record("open_app","WhatsApp opened");assertTrue(AgentHooks.completionDecision("Open WhatsApp",p.snapshot()).allowed)}
 @Test fun messageReplyTextAndEmailAreRecognizedAsPhoneActionRequests(){
  listOf("message Amma on WhatsApp","reply to Amma","text Amma","email Amma").forEach { request ->
   assertTrue("$request should route to the phone action agent",AgentHooks.isPhoneActionRequest(request))
  }
 }
 @Test fun consequentialCompletionNeedsActualSendEvidence(){
  val p=AgentHooks.ProofRecorder()
  p.record("open_app","WhatsApp opened")
  assertFalse(AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
  p.record("verify_action","PASS: visible screen state changed. Current screen evidence: message sent")
  assertFalse("Screen change/OCR is not confirmation",AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
  p.record("send","WhatsApp message sent to Amma")
  assertTrue(AgentHooks.completionDecision("Send a WhatsApp message",p.snapshot()).allowed)
 }
 @Test fun sendVerificationRequiresContactExactMessageAndVisibleStatus(){
  val sent="Amma\\nHi Amma, I am on my way.\\nSent 8:42 AM"
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.",sent).startsWith("PASS:"))
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.","Amma\\nHi Amma, I am on my way.").startsWith("UNVERIFIED:"))
  assertTrue(AgentHooks.verifySendEvidence("Amma","Hi Amma, I am on my way.","Amma\\nSent message to another person").startsWith("error:"))
 }
 @Test fun settlesScreen(){val a=listOf("Loading","Loaded","Loaded");var i=0;assertEquals("Loaded",AgentHooks.awaitStableSnapshot({a[minOf(i++,a.lastIndex)]},4,2,0))}
}
