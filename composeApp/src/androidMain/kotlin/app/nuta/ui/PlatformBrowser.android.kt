package app.nuta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

private lateinit var appContext: Context

fun initPlatformBrowser(context: Context) {
    appContext = context.applicationContext
}

actual fun openUrlInBrowser(url: String) {
    runCatching {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
