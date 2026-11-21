package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionRouteRecordTest {
    @Test fun routeRecordSeparatesProvenFromUnreliable() {
        repeat(5) { OrionForesight.compare("zz_good_route", OrionForesight.Level.OK, false, "ok") }
        OrionForesight.restore("{\"zz_good_route\":[0,5,0,0,0,0],\"zz_bad_route\":[0,1,4,0,0,0]}")
        val r = OrionForesight.routeRecord()
        assertTrue(r.contains("PROVEN") && r.contains("zz_good_route"))
        assertTrue(r.contains("UNRELIABLE") && r.contains("zz_bad_route"))
    }
    @Test fun clearingATempCloneNeedsNoApproval() {
        val cmd = "cd /tmp && rm -rf trader-orion && (git clone --depth 50 https://github.com/Kingijimzy/trader-orion.git 2>&1 | tail -20)"
        assertFalse(IrreversibleActionConfirmationPolicy.destructiveAction("shell", org.json.JSONObject().put("command", cmd)) != null)
        assertNotNull(IrreversibleActionConfirmationPolicy.destructiveAction("shell", org.json.JSONObject().put("command", "rm -rf /sdcard/DCIM")))
    }
}
