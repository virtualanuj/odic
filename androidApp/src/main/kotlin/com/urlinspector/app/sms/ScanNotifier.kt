package com.urlinspector.app.sms

import com.urlinspector.core.model.Verdict

interface ScanNotifier {
    fun notify(url: String, verdict: Verdict)
}
