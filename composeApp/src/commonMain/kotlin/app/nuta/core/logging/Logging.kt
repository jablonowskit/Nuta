package app.nuta.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel(val priority: Int) {
    TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4)
}

data class LogEvent(
    val timestamp: String,
    val level: LogLevel,
    val module: String,
    val event: String,
    val operationId: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val throwable: String? = null,
)

interface NutaLogger {
    fun trace(module: String, event: String, message: String, operationId: String = "system", fields: Map<String, String> = emptyMap())
    fun debug(module: String, event: String, message: String, operationId: String = "system", fields: Map<String, String> = emptyMap())
    fun info(module: String, event: String, message: String, operationId: String = "system", fields: Map<String, String> = emptyMap())
    fun warn(module: String, event: String, message: String, operationId: String = "system", fields: Map<String, String> = emptyMap())
    fun error(module: String, event: String, message: String, operationId: String = "system", fields: Map<String, String> = emptyMap(), throwable: Throwable? = null)
}

class LogRedactor {
    private val sensitiveKeys = setOf(
        "authorization", "token", "access_token", "refresh_token", "cookie",
        "sp_dc", "totp", "signature", "sig", "secret", "password", "api_key",
    )

    fun redact(fields: Map<String, String>): Map<String, String> = fields.mapValues { (key, value) ->
        if (sensitiveKeys.any { key.lowercase().contains(it) }) "[REDACTED]" else redactText(value)
    }

    fun redactText(value: String): String {
        var result = value
        result = result.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [REDACTED]")
        result = result.replace(Regex("(?i)(Cookie\\s*:\\s*)[^\\r\\n]+"), "$1[REDACTED]")
        result = result.replace(Regex("(?i)(sp_dc|access_token|refresh_token|totp|signature|sig|secret|password)=([^&;\\s]+)"), "$1=[REDACTED]")
        result = result.replace(
            Regex("(?i)(\\\"(?:accessToken|access_token|sp_dc|totp)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")"),
            "$1[REDACTED]$2",
        )
        return result
    }
}

class MemoryLogger(
    private val now: () -> String,
    initialLevel: LogLevel = LogLevel.DEBUG,
    private val maxEvents: Int = 500,
    private val jsonSink: (String) -> Unit = {},
    private val redactor: LogRedactor = LogRedactor(),
) : NutaLogger {
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    private val _minimumLevel = MutableStateFlow(initialLevel)
    val minimumLevel: StateFlow<LogLevel> = _minimumLevel.asStateFlow()

    fun setMinimumLevel(level: LogLevel) {
        _minimumLevel.value = level
        info("Diagnostics", "log_level_changed", "Poziom logowania zmieniony", fields = mapOf("level" to level.name))
    }

    fun clear() {
        _events.value = emptyList()
    }

    override fun trace(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = emit(LogLevel.TRACE, module, event, message, operationId, fields)
    override fun debug(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = emit(LogLevel.DEBUG, module, event, message, operationId, fields)
    override fun info(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = emit(LogLevel.INFO, module, event, message, operationId, fields)
    override fun warn(module: String, event: String, message: String, operationId: String, fields: Map<String, String>) = emit(LogLevel.WARN, module, event, message, operationId, fields)
    override fun error(module: String, event: String, message: String, operationId: String, fields: Map<String, String>, throwable: Throwable?) = emit(LogLevel.ERROR, module, event, message, operationId, fields, throwable)

    private fun emit(
        level: LogLevel,
        module: String,
        event: String,
        message: String,
        operationId: String,
        fields: Map<String, String>,
        throwable: Throwable? = null,
    ) {
        if (level.priority < _minimumLevel.value.priority) return
        val item = LogEvent(
            timestamp = now(),
            level = level,
            module = module,
            event = event,
            operationId = redactor.redactText(operationId),
            message = redactor.redactText(message),
            fields = redactor.redact(fields),
            throwable = throwable?.stackTraceToString()?.let(redactor::redactText),
        )
        _events.value = (_events.value + item).takeLast(maxEvents)
        runCatching { jsonSink(item.toJsonLine()) }
    }
}

private fun String.jsonEscape(): String = buildString {
    this@jsonEscape.forEach { char ->
        append(
            when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> char
            },
        )
    }
}

fun LogEvent.toJsonLine(): String {
    val fieldJson = fields.entries.joinToString(",") { (key, value) -> "\"${key.jsonEscape()}\":\"${value.jsonEscape()}\"" }
    val throwableJson = throwable?.let { ",\"throwable\":\"${it.jsonEscape()}\"" } ?: ""
    return "{\"timestamp\":\"${timestamp.jsonEscape()}\",\"level\":\"${level.name}\",\"module\":\"${module.jsonEscape()}\",\"event\":\"${event.jsonEscape()}\",\"operationId\":\"${operationId.jsonEscape()}\",\"message\":\"${message.jsonEscape()}\",\"fields\":{$fieldJson}$throwableJson}"
}
