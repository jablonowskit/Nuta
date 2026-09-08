package app.nuta.listenbrainz

/**
 * Kiedy odsłuchanie „się liczy" i można je zgłosić do ListenBrainz.
 *
 * Reguła jest wzięta wprost ze specyfikacji ListenBrainz (ta sama, którą stosują oficjalne
 * klienty i Last.fm): utwór zaliczamy po przesłuchaniu połowy jego długości **albo** po
 * 4 minutach — co nastąpi wcześniej. Dzięki temu 20-minutowy set nie musi lecieć do końca,
 * a 90-sekundowa miniatura nie zostaje zgłoszona po kilku sekundach.
 *
 * Wydzielone jako czysta funkcja (bez sieci i bez stanu), bo to jedyny fragment scrobblowania
 * z nietrywialną logiką — resztę [ListenBrainzScrobbler] robi już tylko na wywołaniach HTTP.
 */
object ListenThreshold {
    /** 4 minuty — górna granica z reguły ListenBrainz, niezależna od długości utworu. */
    const val AbsoluteThresholdMs = 4 * 60 * 1000L

    /**
     * Najkrótszy utwór, który zgłaszamy (30 s). To decyzja **klienta**, nie ograniczenie API —
     * sprawdzone curlem 2026-09-08: serwis przyjmuje też 20-sekundowe wpisy (`{"status":"ok"}`).
     * Odcinamy je, bo jingle i interludia zaśmiecałyby historię, z której ListenBrainz liczy
     * rekomendacje, a to ta sama konwencja co w Last.fm i oficjalnych klientach ListenBrainz.
     */
    const val MinimumTrackLengthMs = 30 * 1000L

    /**
     * Ile trzeba odsłuchać utworu o długości [durationMs], żeby zaliczyć go jako odsłuchanie.
     *
     * Gdy [durationMs] jest nieznane (0 — patrz utwory z ListenBrainz mające tylko MSID albo
     * wyniki MusicBrainz bez `length`, ten sam przypadek co przy klampowaniu `seekTo`),
     * nie możemy policzyć połowy, więc zostaje sam próg 4 minut.
     */
    fun requiredMs(durationMs: Long): Long =
        if (durationMs <= 0L) AbsoluteThresholdMs else minOf(durationMs / 2, AbsoluteThresholdMs)

    /**
     * Czy utwór o długości [durationMs] odsłuchany do pozycji [positionMs] kwalifikuje się do
     * zgłoszenia. Utwory krótsze niż [MinimumTrackLengthMs] odrzucamy zawsze; przy nieznanej
     * długości (0) nie wiemy, czy są za krótkie, więc dopuszczamy je i polegamy na progu czasowym.
     */
    fun isReached(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs in 1 until MinimumTrackLengthMs) return false
        return positionMs >= requiredMs(durationMs)
    }
}
