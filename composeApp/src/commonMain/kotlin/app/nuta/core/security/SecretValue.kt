package app.nuta.core.security

/**
 * Wrapper preventing accidental disclosure through logs, string templates and debugger-friendly
 * data-class output. Access to the value must stay inside authentication and transport adapters.
 */
class SecretValue private constructor(private val value: String) {
    fun <T> use(block: (String) -> T): T = block(value)

    fun isBlank(): Boolean = value.isBlank()

    override fun toString(): String = "[REDACTED]"

    override fun equals(other: Any?): Boolean = other is SecretValue && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun of(value: String): SecretValue {
            require(value.isNotBlank()) { "Secret value cannot be blank" }
            return SecretValue(value)
        }
    }
}
