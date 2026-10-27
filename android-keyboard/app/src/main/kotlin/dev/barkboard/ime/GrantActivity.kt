package dev.barkboard.ime

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * An `InputMethodService` is not an Activity and cannot show a runtime permission dialog, so
 * the keyboard launches this transparent Activity on the first mic tap. It requests the
 * permission, reports the outcome and closes.
 */
class GrantActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }

        Toast.makeText(this, R.string.grant_rationale, Toast.LENGTH_LONG).show()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return

        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(this, R.string.grant_rationale, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object {
        const val MIC_REQUEST = 2001
    }
}
