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
     * Porównanie stałoczasowe — zwykłe `==` na stringach przerywa na pierwszej różnicy, a
     * wcześniejsza wersja tej metody przerywała wcześnie przy różnej długości — oba warianty
     * dają wymierny kanał czasowy. Pętla zawsze przechodzi przez dłuższy z dwóch ciągów, a
     * niezgodność długości jest wliczona w wynik zamiast powodować wcześniejszy powrót.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is SecretValue) return false
        val a = value
        val b = other.value
        var diff = a.length xor b.length
        for (index in 0 until maxOf(a.length, b.length)) {
            val ca = if (index < a.length) a[index].code else 0
            val cb = if (index < b.length) b[index].code else 0
            diff = diff or (ca xor cb)
        }
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
