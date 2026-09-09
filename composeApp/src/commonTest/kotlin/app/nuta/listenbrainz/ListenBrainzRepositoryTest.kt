package app.nuta.listenbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testy parserów odpowiedzi ListenBrainz. Wejście to **prawdziwe** odpowiedzi API (pobrane
 * curlem z konta testowego, przycięte do pól, które parser czyta) — bo dokładnie tutaj
 * wystąpiły wszystkie awarie tej integracji: pusta odpowiedź 204, `mbid_mapping` jako jawny
 * JSON null, gubione wpisy z samym MSID i paginacja zatrzymująca się na pierwszej stronie.
 */
class ListenBrainzRepositoryTest {
    /** Prawdziwa odpowiedź `get-feedback?metadata=true` (2 z 99 polubień). */
    private val realFeedbackPage = """
        {
          "count": 2,
          "total_count": 99,
          "feedback": [
            {
              "recording_mbid": "ba0ccd95-4dbe-412e-a258-03946c7a676e",
              "recording_msid": null,
              "score": 1,
              "track_metadata": {
                "track_name": "What Is Love",
                "artist_name": "Haddaway",
                "release_name": "Haddaway",
                "mbid_mapping": {
                  "artist_mbids": ["6508cf1d-4da2-4d71-81ec-0e072338991f"],
                  "recording_mbid": "ba0ccd95-4dbe-412e-a258-03946c7a676e"
                }
              }
            },
            {
              "recording_mbid": "5e9192e5-0b43-487b-92ea-15b7c2a100f6",
              "recording_msid": null,
              "score": 1,
              "track_metadata": {
                "track_name": "Das Boot",
                "artist_name": "U96",
                "release_name": "Das Boot",
                "mbid_mapping": {
                  "artist_mbids": ["5a2e73e9-15b3-4bd5-bd4b-7a331ead808a"],
                  "recording_mbid": "5e9192e5-0b43-487b-92ea-15b7c2a100f6"
                }
              }
            }
          ]
        }
    """.trimIndent()

    /** Prawdziwa odpowiedź `metadata/recording/?inc=artist` (obcięta o pola nieczytane). */
    private val realMetadataLookup = """
        {
          "bf8e28bb-b4ef-4b9a-bc64-8f58dd4ff5dd": {
            "artist": {
              "artist_credit_id": 141,
              "artists": [
                { "artist_mbid": "b4d32cff-f19e-455f-86c4-f347d824ca61", "name": "Eurythmics" }
              ],
              "name": "Eurythmics"
            },
            "recording": {
              "first_release_date": "1983-01-21",
              "length": 216346,
              "name": "Sweet Dreams (Are Made of This)"
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesRealFeedbackPage() {
        val page = ListenBrainzRepository.parseFeedbackPage(realFeedbackPage)
        requireNotNull(page)
        assertEquals(2, page.entriesOnPage)
        // total_count przychodzi jako liczba JSON, nie string — paginacja na tym się opiera.
        assertEquals(99, page.totalCount)
        assertEquals(2, page.entries.size)
        val first = page.entries[0]
        assertEquals("ba0ccd95-4dbe-412e-a258-03946c7a676e", first.id)
        assertTrue(first.hasMbid)
        assertEquals("What Is Love", first.title)
        assertEquals("Haddaway", first.artist)
        assertEquals("Haddaway", first.album)
        assertEquals("6508cf1d-4da2-4d71-81ec-0e072338991f", first.artistMbid)
    }

    @Test
    fun explicitNullMbidMappingDoesNotThrow() {
        // Realny crash: "Element class ... is not a JsonObject". `mbid_mapping` bywa jawnym
        // JSON null-em, a `.jsonObject` rzuca na JsonNull zamiast zwrócić null.
        val body = """
            {"count":1,"total_count":1,"feedback":[
              {"recording_mbid":"ba0ccd95-4dbe-412e-a258-03946c7a676e","score":1,
               "track_metadata":{"track_name":"Bez mapowania","artist_name":"Ktos","mbid_mapping":null}}
            ]}
        """.trimIndent()
        val page = ListenBrainzRepository.parseFeedbackPage(body)
        requireNotNull(page)
        assertEquals(1, page.entries.size)
        assertNull(page.entries[0].artistMbid)
        assertEquals("Bez mapowania", page.entries[0].title)
        // release_name w ogóle nie przyszło — album ma być pusty, nie null/wyjątek.
        assertEquals("", page.entries[0].album)
    }

    @Test
    fun keepsMsidOnlyEntries() {
        // Realny błąd: wpisy bez dopasowania w MusicBrainz (tylko MessyBrainz ID) były gubione,
        // dlatego ekran Ulubionych pokazywał ~20 z 99 utworów.
        val body = """
            {"count":1,"total_count":1,"feedback":[
              {"recording_mbid":null,"recording_msid":"c54166a4-ccfb-4008-bf5e-72d1e3d0b926","score":1,
               "track_metadata":{"track_name":"Tylko MSID","artist_name":"Nieznany"}}
            ]}
        """.trimIndent()
        val page = ListenBrainzRepository.parseFeedbackPage(body)
        requireNotNull(page)
        assertEquals(1, page.entries.size)
        assertEquals("c54166a4-ccfb-4008-bf5e-72d1e3d0b926", page.entries[0].id)
        // hasMbid=false decyduje o pominięciu wpisu w batch-lookupie czasu trwania.
        assertTrue(!page.entries[0].hasMbid)
    }

    @Test
    fun blankOrMalformedBodyStopsPagination() {
        // Zwrócenie null przerywa pętlę paginacji; wcześniej pusta odpowiedź (204) powodowała
        // wyjątek "unexpected end of the input" przy rotacji ekranu.
        assertNull(ListenBrainzRepository.parseFeedbackPage(""))
        assertNull(ListenBrainzRepository.parseFeedbackPage("   "))
        assertNull(ListenBrainzRepository.parseFeedbackPage("<html>error</html>"))
        // Poprawny JSON, ale bez tablicy `feedback` — też przerywamy, zamiast rzucać.
        assertNull(ListenBrainzRepository.parseFeedbackPage("""{"code":404,"error":"user not found"}"""))
    }

    @Test
    fun skipsEntriesWithoutIdOrTitle() {
        val body = """
            {"count":4,"total_count":4,"feedback":[
              {"score":1,"track_metadata":{"track_name":"Bez id"}},
              {"recording_mbid":"11111111-1111-1111-1111-111111111111","score":1},
              {"recording_mbid":"22222222-2222-2222-2222-222222222222","score":1,"track_metadata":{"artist_name":"Bez tytulu"}},
              {"recording_mbid":"33333333-3333-3333-3333-333333333333","score":1,"track_metadata":{"track_name":"Dobry"}}
            ]}
        """.trimIndent()
        val page = ListenBrainzRepository.parseFeedbackPage(body)
        requireNotNull(page)
        assertEquals(1, page.entries.size)
        assertEquals("Dobry", page.entries[0].title)
        // entriesOnPage liczy WSZYSTKIE wpisy z API (też pominięte) — offset kolejnej strony
        // musi się zgadzać z tym, co zwróciło API, inaczej paginacja przeskakuje wpisy.
        assertEquals(4, page.entriesOnPage)
    }

    @Test
    fun parsesRealMetadataLookup() {
        val tracks = ListenBrainzRepository.parseMetadataLookup(
            realMetadataLookup,
            listOf("bf8e28bb-b4ef-4b9a-bc64-8f58dd4ff5dd"),
        )
        assertEquals(1, tracks.size)
        val track = tracks[0]
        assertEquals("Sweet Dreams (Are Made of This)", track.title)
        // Ten czas trwania jest powodem istnienia batch-lookupu: get-feedback go nie zwraca,
        // a bez niego pasek przewijania nie działał.
        assertEquals(216346L, track.durationMs)
        assertEquals(listOf("Eurythmics"), track.artists)
        assertEquals("b4d32cff-f19e-455f-86c4-f347d824ca61", track.artistMbid)
    }

    @Test
    fun metadataLookupOmitsUnknownMbids() {
        // Sprawdzone curlem: API pomija nieznane MBID-y (nie zwraca ich jako null), więc brak
        // klucza to normalna ścieżka i nie może dać wpisu-widma ani wyjątku.
        val tracks = ListenBrainzRepository.parseMetadataLookup(
            realMetadataLookup,
            listOf("bf8e28bb-b4ef-4b9a-bc64-8f58dd4ff5dd", "00000000-0000-0000-0000-000000000000"),
        )
        assertEquals(1, tracks.size)
    }

    @Test
    fun metadataLookupToleratesNullSectionsAndBlankBody() {
        assertTrue(ListenBrainzRepository.parseMetadataLookup("", listOf("x")).isEmpty())
        assertTrue(ListenBrainzRepository.parseMetadataLookup("nie json", listOf("x")).isEmpty())
        val nulls = """
            {"11111111-1111-1111-1111-111111111111": {"recording": null, "artist": null},
             "22222222-2222-2222-2222-222222222222": {"recording": {"name":"Bez artysty"}, "artist": null}}
        """.trimIndent()
        val tracks = ListenBrainzRepository.parseMetadataLookup(
            nulls,
            listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"),
        )
        // Pierwszy wpis (recording: null) pomijamy, drugi wchodzi bez artysty i bez długości.
        assertEquals(1, tracks.size)
        assertEquals("Bez artysty", tracks[0].title)
        assertTrue(tracks[0].artists.isEmpty())
        assertEquals(0L, tracks[0].durationMs)
        assertNull(tracks[0].artistMbid)
    }

    @Test
    fun parsesRealPlaylistSearchResponse() {
        // Prawdziwa odpowiedź playlist/search — utwory przychodzą puste, dociąga je
        // getPlaylistTracks przy otwarciu playlisty (tak samo jak dla Spotify).
        val body = """
            {"playlist_count":695,"playlists":[
              {"playlist":{"identifier":"https://listenbrainz.org/playlist/9aaa0aa5-997d-483e-b86d-68d316120189",
                           "title":"Dance","creator":"Zutalor","track":[]}},
              {"playlist":{"identifier":"https://listenbrainz.org/playlist/ae6c6738-c740-499f-b875-e59536eedafa",
                           "title":"dance","creator":"unrealapex","track":[]}}
            ]}
        """.trimIndent()
        val playlists = ListenBrainzRepository.parsePlaylistSearch(body)
        assertEquals(2, playlists.size)
        // mbid wyciągany z końca URI, nie z osobnego pola.
        assertEquals("9aaa0aa5-997d-483e-b86d-68d316120189", playlists[0].id)
        assertEquals("Dance", playlists[0].name)
        assertEquals("Zutalor", playlists[0].description)
        assertTrue(playlists[0].tracks.isEmpty())
    }

    @Test
    fun playlistSearchToleratesMissingFieldsAndBadBodies() {
        val body = """
            {"playlists":[
              {"playlist":{"title":"Bez identyfikatora"}},
              {"playlist":{"identifier":"https://listenbrainz.org/playlist/abc"}},
              {"nieplaylista":true},
              {"playlist":{"identifier":"https://listenbrainz.org/playlist/def","title":"Dobra"}}
            ]}
        """.trimIndent()
        val playlists = ListenBrainzRepository.parsePlaylistSearch(body)
        assertEquals(1, playlists.size)
        assertEquals("def", playlists[0].id)
        // Brak `creator` daje pusty opis, nie null ani wyjątek.
        assertEquals("", playlists[0].description)
        assertTrue(ListenBrainzRepository.parsePlaylistSearch("").isEmpty())
        assertTrue(ListenBrainzRepository.parsePlaylistSearch("<html>502</html>").isEmpty())
    }
}
