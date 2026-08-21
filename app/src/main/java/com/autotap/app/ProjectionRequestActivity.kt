package com.autotap.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

class ProjectionRequestActivity : Activity() {

    companion object {
        private const val TAG = "AutoTapProjection"
        private const val PROJECTION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            // Android 14+: force entire screen, skip app picker
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val config = MediaProjectionConfig.createConfigForDefaultDisplay()
                startActivityForResult(mpm.createScreenCaptureIntent(config), PROJECTION_REQUEST)
            } else {
                startActivityForResult(mpm.createScreenCaptureIntent(), PROJECTION_REQUEST)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch projection request", e)
            OverlayService.instance?.onProjectionDenied()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PROJECTION_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Projection granted — entire screen")
                OverlayService.instance?.onProjectionGranted(resultCode, data)
            } else {
                Log.d(TAG, "Projection denied")
                OverlayService.instance?.onProjectionDenied()
            }
        }
        finish()
    }
}
