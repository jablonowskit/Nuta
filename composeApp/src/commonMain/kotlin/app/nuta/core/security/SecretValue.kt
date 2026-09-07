package app.nuta.core.security

/**
 * Wrapper preventing accidental disclosure through logs, string templates and debugger-friendly
 * data-class output. Access to the value must stay inside authentication and transport adapters.
 */
class SecretValue private constructor(private val value: String) {
    fun <T> use(block: (String) -> T): T = block(value)

    fun isBlank(): Boolean = value.isBlank()

    override fun toString(): String = "[REDACTED]"

    /**
     * Porównanie stałoczasowe — zwykłe `==` na stringach przerywa na pierwszej różnicy,
     * co przy porównywaniu sekretów daje wymierny kanał czasowy.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is SecretValue) return false
        val a = value
        val b = other.value
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].code xor b[index].code)
        return diff == 0
    }

    /**
     * Stała — hashCode wyliczany z sekretu wyciekałby jego wartość do logów kolekcji
     * i pozwalał na porównywanie sekretów po hashu. Kolizje są tu bez znaczenia,
     * bo SecretValue nie służy jako klucz w mapach o dużej liczności.
     */
    override fun hashCode(): Int = SECRET_HASH_CODE

    companion object {
        private const val SECRET_HASH_CODE = 0x5EC8E7

        fun of(value: String): SecretValue {
            require(value.isNotBlank()) { "Secret value cannot be blank" }
            return SecretValue(value)
        }
    }
}
