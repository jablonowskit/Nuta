package app.nuta.ui

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

actual fun shareText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

actual fun openUrlInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}
