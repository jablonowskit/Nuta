package app.nuta.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooseMatchTest {
    @Test
    fun asciiQueryMatchesPolishDiacritics() {
        // Realny błąd: MusicBrainz zwracał ten utwór, a filtr go ukrywał, więc ekran
        // pokazywał „brak wyników" dla czegoś, co było już pobrane.
        assertTrue("Zegarmistrz światła".matchesLoosely("swiatla"))
        assertTrue("Tadeusz Woźniak".matchesLoosely("Wozniak"))
        assertTrue("Zażółć gęślą jaźń".matchesLoosely("zazolc gesla jazn"))
    }

    @Test
    fun matchingStaysCaseInsensitive() {
        assertTrue("Zegarmistrz światła".matchesLoosely("ZEGARMISTRZ"))
        assertTrue("HADDAWAY".matchesLoosely("haddaway"))
    }

    @Test
    fun worksInBothDirections() {
        // Użytkownik może też wpisać z ogonkami, a katalog mieć wersję bez nich.
        assertTrue("Zegarmistrz swiatla".matchesLoosely("światła"))
    }

    @Test
    fun strokedLettersFoldToo() {
        // ł/ø nie rozkładają się w NFD, więc wymagają jawnego mapowania.
        assertTrue("Miłość".matchesLoosely("milosc"))
        assertTrue("Łódź".matchesLoosely("Lodz"))
        assertTrue("Sigur Rós".matchesLoosely("Sigur Ros"))
    }

    @Test
    fun ligaturesAreNotExpanded() {
        // Świadome ograniczenie: NFD nie rozkłada ligatur (æ, œ, ß), więc „agaetis" NIE trafi
        // w „Ágætis". Rozwijanie ligatur to osobna tablica mapowań — dodać, jeśli kiedyś
        // okaże się potrzebne; polskie znaki, o które tu chodziło, działają.
        assertEquals("Agætis", "Ágætis".foldDiacritics())
        assertFalse("Ágætis byrjun".matchesLoosely("agaetis"))
    }

    @Test
    fun stillRejectsGenuineMismatches() {
        // Zwijanie diakrytyków nie może zamienić filtra w „pasuje wszystko".
        assertFalse("Zegarmistrz światła".matchesLoosely("Haddaway"))
        assertFalse("What Is Love".matchesLoosely("Boot"))
    }

    @Test
    fun foldingLeavesPlainAsciiUnchanged() {
        assertEquals("What Is Love", "What Is Love".foldDiacritics())
        assertEquals("AC/DC", "AC/DC".foldDiacritics())
    }
}
