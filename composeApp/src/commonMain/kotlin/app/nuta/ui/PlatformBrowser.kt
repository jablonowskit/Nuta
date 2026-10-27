package app.nuta.ui

expect fun openUrlInBrowser(url: String)

/** Android: systemowe „Udostępnij”. Desktop: kopiuje do schowka (nie ma wspólnego arkusza udostępniania). */
expect fun shareText(text: String)
