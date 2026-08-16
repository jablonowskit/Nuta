package app.nuta.youtube

import app.nuta.settings.LoudnessNormalization
import kotlin.math.pow

/**
 * Wyrównanie głośności między utworami.
 *
 * YouTube podaje w odpowiedzi playera `loudnessDb` — o ile dany utwór jest głośniejszy od poziomu
 * odniesienia. Żeby wszystkie utwory grały podobnie głośno, ściszamy o tę różnicę.
 *
 * Umiemy tylko ściszać (liniowa głośność playera nie idzie powyżej 1.0), więc utwory cichsze od
 * odniesienia zostawiamy jak są — podbicie ich wymagałoby wzmacniacza i groziłoby przesterowaniem.
 */
object LoudnessGain {
    /** Pełne wyrównanie schodzi najwyżej o tyle dB — poniżej robi się nienaturalnie cicho. */
    private const val MAX_ATTENUATION_DB = 12.0

    /** Liniowa głośność playera (0..1) dla podanego utworu i trybu wyrównania. */
    fun volumeFor(loudnessDb: Double?, mode: LoudnessNormalization): Float {
        if (mode == LoudnessNormalization.OFF || loudnessDb == null) return 1f
        // tylko nadmiar ponad poziom odniesienia; wartości ujemne (utwór cichszy) zostawiamy
        val excessDb = loudnessDb.coerceAtLeast(0.0)
        val limitDb = when (mode) {
            LoudnessNormalization.GENTLE -> MAX_ATTENUATION_DB / 2
            else -> MAX_ATTENUATION_DB
        }
        // GENTLE koryguje połowę różnicy — słychać, że utwory są równiejsze, ale dynamika
        // między nimi nie znika całkowicie
        val factorDb = if (mode == LoudnessNormalization.GENTLE) excessDb / 2 else excessDb
        val attenuationDb = factorDb.coerceAtMost(limitDb)
        return 10.0.pow(-attenuationDb / 20.0).toFloat()
    }
}
