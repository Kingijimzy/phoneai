package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionCognitivePushbackTest {
    @Test fun recognizesAWeightySystemLevelProposal() {
        assertTrue(OrionCognitivePushback.isWeightyProposal("I think we should change the entire Orion architecture."))
        assertTrue(OrionCognitivePushback.isWeightyProposal("let's rewrite the whole system from scratch"))
    }

    @Test fun ordinaryRequestsAreNotWeightyProposals() {
        assertFalse(OrionCognitivePushback.isWeightyProposal("can you fix this one test"))
        assertFalse(OrionCognitivePushback.isWeightyProposal("push and merge"))
    }

    @Test fun bareComplianceIsCaught() {
        assertTrue(OrionCognitivePushback.looksLikeBareAgreement("Sure, I'll start replacing it now."))
        assertTrue(OrionCognitivePushback.looksLikeBareAgreement("On it."))
    }

    @Test fun aRealPositionIsNotFlagged() {
        val reply = "I understand why you want that. But I don't think replacing everything is the right move. " +
            "We already have useful infrastructure in Central Brain, Cerebro and the runtime. I suspect our bigger " +
            "problem is coordination and reasoning quality. I'd test that before introducing another architecture."
        assertFalse(OrionCognitivePushback.looksLikeBareAgreement(reply))
    }
}
