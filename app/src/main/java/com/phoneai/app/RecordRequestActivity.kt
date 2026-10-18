package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible screen that shows Android's "Start recording?" permission popup. */
class RecordRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            RecordService.lastError = "Cannot ask for recording permission: ${e.message}"
            RecordService.state = "error"
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecordService::class.java)
            i.putExtra(RecordService.EXTRA_CODE, resultCode)
            i.putExtra(RecordService.EXTRA_DATA, data)
            try {
                startForegroundService(i)
            } catch (e: Exception) {
                RecordService.lastError = "Could not start the recorder: ${e.message}"
                RecordService.state = "error"
            }
        } else {
            RecordService.state = "denied"
        }
        finish()
    }
}
