package com.mywatt.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mywatt.app.R
import com.mywatt.app.data.DiscoveryLog
import com.mywatt.app.data.RideLog

/**
 * Hands the most recent ride CSV and GATT discovery file to the Android share sheet in
 * one intent. Both are flushed line by line, so sharing mid-ride is fine. Used by the ride
 * screen's share button and the session results page.
 */
fun shareLatestLogs(activity: Activity) {
    val files = listOfNotNull(RideLog.latestFile(activity), DiscoveryLog.latestFile(activity))
    if (files.isEmpty()) {
        Toast.makeText(activity, R.string.share_log_none, Toast.LENGTH_SHORT).show()
        return
    }
    val authority = "${activity.packageName}.fileprovider"
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(activity, authority, it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("text/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .putExtra(Intent.EXTRA_SUBJECT, files.first().name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    activity.startActivity(Intent.createChooser(send, activity.getString(R.string.action_share_log)))
}
