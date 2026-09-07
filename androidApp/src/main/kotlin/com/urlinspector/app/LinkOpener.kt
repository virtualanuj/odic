package com.urlinspector.app

import android.app.Activity
import android.content.Intent
import android.net.Uri

fun Activity.openExternalLink(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
