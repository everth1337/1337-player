package com.music.spotui.ui.components

import com.music.spotui.data.local.ArtistGenres
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSectionsTest {
    @Test fun onlyAnUnambiguousArtistWithRecognizedTagsGetsGenres() {
        val source = JSONArray("""
            [
              {"id":"1","name":"Fixture Artist","tags":[
                  {"name":"j-pop","count":7},{"name":"vocaloid","count":2},
                  {"name":"pop","count":3},{"name":"j-rock","count":0},{"name":"made-up-tag","count":20}
              ]},
              {"id":"2","name":"Equal Name","tags":[{"name":"rock","count":9}]},
              {"id":"3","name":"Equal Name","tags":[{"name":"j-pop","count":3}]},
              {"id":"4","name":"Missing Name Collective","tags":[{"name":"pop","count":3}]}
            ]
        """.trimIndent())
        val genres = ArtistGenres.classify(listOf("FIXTURE ARTIST", "Equal Name", "Missing Name"), source)
        assertEquals(listOf("J-pop", "Pop"), genres["fixture artist"])
        assertEquals(emptyList<String>(), genres["equal name"])
        assertEquals(emptyList<String>(), genres["missing name"])
    }

    @Test fun genreAndArtistSectionsIncludeOnlyTheirMembers() {
        val tags = mapOf("fixture artist" to listOf("J-pop", "Pop"))
        assertTrue(TrackSection(SectionKind.GENRE, "J-pop").matches("Fixture Artist, Guest", "Album", tags))
        assertFalse(TrackSection(SectionKind.GENRE, "Rock").matches("Fixture Artist", "Album", tags))
        assertTrue(TrackSection(SectionKind.GENRE, "").matches("Unclassified", "Album", tags))
        assertTrue(TrackSection(SectionKind.ARTIST, "Guest").matches("Fixture Artist, Guest", "Album", tags))
        assertTrue(TrackSection(SectionKind.ALBUM, "Album").matches("Fixture Artist", "Album", tags))
    }
}
