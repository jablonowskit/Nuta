package app.nuta.musicbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testy parsera odpowiedzi MusicBrainz. Dane wejściowe to **prawdziwe** odpowiedzi
 * `ws/2/recording/` (pobrane curlem, przycięte do pól, które parser czyta) — sztuczny JSON
 * nie sprawdziłby tego, co realnie psuło apkę: brakujących pól i jawnych JSON-owych null-i.
 */
class MusicBrainzRepositoryTest {
    /** Prawdziwa odpowiedź na zapytanie "Scream Ice MC" — drugi wpis ma `length: null`. */
    private val realResponse = """
        {
          "recordings": [
            {
              "id": "d63e776f-d7cf-4bf9-b50f-0465c90265df",
              "title": "ICE SCREAM!",
              "length": 336226,
              "artist-credit": [
                { "artist": { "id": "fe823c4b-8e98-418c-b7c3-2fc0f408d75c", "name": "Sha' Cho Mouse" } }
              ]
            },
            {
              "id": "1d0cbb61-ea66-4454-bb1a-4adef59d964e",
              "title": "Ice Scream",
              "length": null,
              "artist-credit": [
                { "artist": { "id": "511f9ced-532b-4bda-8749-e68ffbe1ab8e", "name": "Warum Joe" } }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesRealSearchResponse() {
        val tracks = MusicBrainzRepository.parseRecordings(realResponse)
        assertEquals(2, tracks.size)
        val first = tracks[0]
        assertEquals("d63e776f-d7cf-4bf9-b50f-0465c90265df", first.id)
        assertEquals("ICE SCREAM!", first.title)
        assertEquals(listOf("Sha' Cho Mouse"), first.artists)
        assertEquals(336226L, first.durationMs)
        assertEquals("fe823c4b-8e98-418c-b7c3-2fc0f408d75c", first.artistMbid)
    }

    @Test
    fun explicitNullLengthBecomesZeroInsteadOfCrashing() {
        // To był realny błąd: `length: null` (nie brak pola) w wynikach wyszukiwania dawał
        // durationMs=0, a odtwarzacz klampował pozycję do zera — pasek przewijania stale wracał
        // na początek. Sam parser musi to znieść bez wyjątku.
        val tracks = MusicBrainzRepository.parseRecordings(realResponse)
        assertEquals(0L, tracks[1].durationMs)
        assertEquals("Ice Scream", tracks[1].title)
    }

    @Test
    fun blankAndMalformedBodiesYieldEmptyList() {
        // Puste ciało realnie przychodzi (odpowiedzi 204/przerwane połączenie) i wcześniej
        // wysypywało parsowanie: "unexpected end of the input".
        assertTrue(MusicBrainzRepository.parseRecordings("").isEmpty())
        assertTrue(MusicBrainzRepository.parseRecordings("   ").isEmpty())
        assertTrue(MusicBrainzRepository.parseRecordings("not json at all").isEmpty())
        assertTrue(MusicBrainzRepository.parseRecordings("""{"error":"rate limited"}""").isEmpty())
        // Poprawny JSON, ale tablica zamiast obiektu — też nie może rzucić.
        assertTrue(MusicBrainzRepository.parseRecordings("""["a","b"]""").isEmpty())
    }

    @Test
    fun skipsEntriesMissingIdOrTitle() {
        val body = """
            {"recordings":[
              {"title":"Bez id","length":1000},
              {"id":"11111111-1111-1111-1111-111111111111","length":2000},
              {"id":"22222222-2222-2222-2222-222222222222","title":"Kompletny","length":3000}
            ]}
        """.trimIndent()
        val tracks = MusicBrainzRepository.parseRecordings(body)
        assertEquals(1, tracks.size)
        assertEquals("Kompletny", tracks[0].title)
    }

    @Test
    fun toleratesMissingOrNullArtistCredit() {
        val body = """
            {"recordings":[
              {"id":"33333333-3333-3333-3333-333333333333","title":"Bez artystow","length":1000},
              {"id":"44444444-4444-4444-4444-444444444444","title":"Null credit","length":1000,"artist-credit":null},
              {"id":"55555555-5555-5555-5555-555555555555","title":"Pusty credit","length":1000,"artist-credit":[]}
            ]}
        """.trimIndent()
        val tracks = MusicBrainzRepository.parseRecordings(body)
        assertEquals(3, tracks.size)
        tracks.forEach {
            assertTrue(it.artists.isEmpty(), "brak artysty powinien dać pustą listę, było ${it.artists}")
            assertNull(it.artistMbid)
        }
    }
}
