package app.nuta.ui

import java.awt.Desktop
import java.net.URI

actual fun openUrlInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
