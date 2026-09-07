package com.urlinspector.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun Activity.openExternalLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
    }
}
