package com.urlinspector.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.urlinspector.app.share.extractFirstUrl

class ShareHandlerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedText = if (intent?.action == Intent.ACTION_SEND) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(4096)
        } else {
            null
        }
        val extractedUrl = sharedText?.let(::extractFirstUrl)

        setContent {
            AppRoot(
                onOpenLink = { url -> openExternalLink(url) },
                sharedUrl = extractedUrl,
                prefillText = sharedText,
            )
        }
    }
}
