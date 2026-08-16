package com.tcrrry.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectLyricsRepositoryTest {

    @Test
    fun `accepts localized subgroup display names when title album and duration confirm one recording`() {
        val query = LyricsLookup(
            track = "Twinkle",
            artist = "少女时代-太蒂徐",
            album = "Twinkle Mini Album",
            durationMs = 206_796L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-twinkle",
                    track = "Twinkle",
                    artist = "少女时代-TaeTiSeo",
                    album = "'Twinkle' Mini Album",
                    durationMs = 208_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-twinkle",
                    track = "Twinkle",
                    artist = "少女时代-TaeTiSeo",
                    album = "'Twinkle' Mini Album",
                    durationMs = 208_720L
                )
            )
        )

        assertEquals(
            EvidenceLevel.NEAR,
            artistEvidence(
                firstTrack = "Twinkle",
                firstArtist = "少女时代-太蒂徐",
                secondTrack = "Twinkle",
                secondArtist = "少女时代-TaeTiSeo"
            )
        )
        assertEquals(
            EvidenceReason.CONTIGUOUS_SUBJECT,
            artistMetadataEvidence(
                firstTrack = "Twinkle",
                firstArtist = "少女时代-太蒂徐",
                secondTrack = "Twinkle",
                secondArtist = "少女时代-TaeTiSeo"
            ).reason
        )
        assertEquals(listOf("QQ音乐", "网易云音乐"), selected.map { it.source })
    }

    @Test
    fun `accepts a single source when artist title and duration match despite release naming`() {
        val query = LyricsLookup(
            track = "Super Girl",
            artist = "SUPER JUNIOR-M",
            album = "Super Girl",
            durationMs = 216_827L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "super-girl-mandarin",
                    track = "Super Girl",
                    artist = "Super Junior-M",
                    album = "The First Mini Album - Super Girl",
                    durationMs = 218_000L
                )
            )
        )

        assertEquals(listOf("super-girl-mandarin"), selected.map { it.sourceId })
    }

    @Test
    fun `accepts a small title spelling difference as near evidence`() {
        val query = LyricsLookup(
            track = "Beautiful Tonight",
            artist = "Artist",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "title-typo",
                    track = "Beautifull Tonight",
                    artist = "Artist",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("title-typo"), selected.map { it.sourceId })
    }

    @Test
    fun `accepts a small artist spelling difference as near evidence`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "The Weeknd",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "artist-typo",
                    track = "A Song",
                    artist = "The Weekend",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("artist-typo"), selected.map { it.sourceId })
    }

    @Test
    fun `accepts bilingual artist display name and single release suffix for City Zoo`() {
        val query = LyricsLookup(
            track = "摩天动物园",
            artist = "邓紫棋",
            album = "摩天动物园 - Single",
            durationMs = 270_676L
        )
        val candidates = listOf(
            candidate(
                source = "QQ音乐",
                sourceId = "000Fz7zP3FDuSz",
                track = "摩天动物园",
                artist = "G.E.M. 邓紫棋",
                album = "摩天动物园",
                durationMs = 270_000L
            ),
            candidate(
                source = "网易云音乐",
                sourceId = "1409382131",
                track = "摩天动物园",
                artist = "G.E.M.邓紫棋",
                album = "摩天动物园",
                durationMs = 270_676L
            )
        )

        candidates.forEach { sourceCandidate ->
            assertEquals(
                listOf(sourceCandidate.sourceId),
                LyricsCandidateSelector.selectCandidates(query, listOf(sourceCandidate))
                    .map { it.sourceId }
            )
        }
    }

    @Test
    fun `accepts a complete underscore delimited artist display name component`() {
        val query = LyricsLookup(
            track = "童话镇",
            artist = "李雨霏",
            album = "童话镇 - Single",
            durationMs = 212_571L
        )
        val candidate = candidate(
            source = "网易云音乐",
            sourceId = "netease-fairy-tale-town",
            track = "童话镇",
            artist = "李雨霏_晚饭",
            album = "童话镇",
            durationMs = 212_571L
        )

        assertEquals(EvidenceLevel.NEAR, artistEvidence(query, candidate))
        assertEquals(
            listOf(candidate.sourceId),
            LyricsCandidateSelector.selectCandidates(query, listOf(candidate)).map { it.sourceId }
        )
    }

    @Test
    fun `accepts an ordered continuous subject across formatting boundaries`() {
        assertEquals(
            EvidenceLevel.NEAR,
            titleEvidence(
                titleIdentity("AA  BB  CC.DD_EE"),
                titleIdentity("AA BB CC")
            )
        )
        assertEquals(
            EvidenceLevel.NEAR,
            artistEvidence(
                firstTrack = "A Song",
                firstArtist = "AA  BB  CC.DD_EE",
                secondTrack = "A Song",
                secondArtist = "AA BB CC"
            )
        )
    }

    @Test
    fun `accepts a complete Latin display name segment`() {
        assertEquals(
            EvidenceLevel.NEAR,
            artistEvidence(
                firstTrack = "千年泪",
                firstArtist = "Tank Lu",
                secondTrack = "千年泪",
                secondArtist = "Tank"
            )
        )
        assertEquals(
            EvidenceReason.CONTIGUOUS_SUBJECT,
            artistMetadataEvidence(
                firstTrack = "千年泪",
                firstArtist = "Tank Lu",
                secondTrack = "千年泪",
                secondArtist = "Tank"
            ).reason
        )
    }

    @Test
    fun `diagnostics retain a hard duration rejection reason`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )
        val summary = LyricsCandidateSelector.selectionSummary(
            query,
            listOf(
                candidate(
                    source = SOURCE_QQ,
                    sourceId = "too-long",
                    track = query.track,
                    artist = query.artist,
                    album = query.album,
                    durationMs = 205_000L
                )
            )
        )

        assertTrue(summary.contains("DURATION_CONFLICT"))
    }

    @Test
    fun `does not join noncontiguous display name segments`() {
        assertEquals(
            EvidenceLevel.DIFFERENT,
            titleEvidence(
                titleIdentity("AA XX CC"),
                titleIdentity("AA BB CC")
            )
        )
        assertEquals(
            EvidenceLevel.DIFFERENT,
            artistEvidence(
                firstTrack = "A Song",
                firstArtist = "AA XX CC",
                secondTrack = "A Song",
                secondArtist = "AA BB CC"
            )
        )
    }

    @Test
    fun `does not invent an artist display component without a separator`() {
        assertEquals(
            EvidenceLevel.DIFFERENT,
            artistEvidence(
                firstTrack = "童话镇",
                firstArtist = "李雨霏",
                secondTrack = "童话镇",
                secondArtist = "李雨霏晚饭"
            )
        )
    }

    @Test
    fun `keeps duration as a hard boundary for underscore artist display names`() {
        val query = LyricsLookup(
            track = "童话镇",
            artist = "李雨霏",
            album = "童话镇 - Single",
            durationMs = 219_196L
        )
        val candidates = listOf(
            candidate(
                source = "QQ音乐",
                sourceId = "qq-fairy-tale-town",
                track = "童话镇",
                artist = "李雨霏",
                album = "童话镇",
                durationMs = 212_000L
            ),
            candidate(
                source = "网易云音乐",
                sourceId = "netease-fairy-tale-town",
                track = "童话镇",
                artist = "李雨霏_晚饭",
                album = "童话镇",
                durationMs = 212_571L
            )
        )

        assertEquals(EvidenceLevel.NEAR, artistEvidence(query, candidates.last()))
        assertTrue(LyricsCandidateSelector.selectCandidates(query, candidates).isEmpty())
    }

    @Test
    fun `does not treat a same script artist substring as a complete display name`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "First Release",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "substring-a",
                        track = "A Song",
                        artist = "AnotherArtist",
                        album = "Second Collection",
                        durationMs = 200_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "substring-b",
                        track = "A Song",
                        artist = "AnotherArtist",
                        album = "Second Collection",
                        durationMs = 200_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `does not strip an undelimited album word as a release suffix`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Stage Name",
            album = "Life Single",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "album-word-a",
                        track = "A Song",
                        artist = "Legal Name",
                        album = "Life",
                        durationMs = 200_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "album-word-b",
                        track = "A Song",
                        artist = "Legal Name",
                        album = "Life",
                        durationMs = 200_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `ranks exact Super Girl candidates ahead of a one-sided version annotation`() {
        val query = LyricsLookup(
            track = "Super Girl",
            artist = "SUPER JUNIOR-M",
            album = "Super Girl",
            durationMs = 216_827L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-mandarin",
                    track = "Super Girl",
                    artist = "Super Junior-M",
                    album = "The First Mini Album - Super Girl",
                    durationMs = 218_000L
                ),
                candidate(
                    source = "LRCLIB",
                    sourceId = "lrclib-mandarin",
                    track = "Super Girl",
                    artist = "Super Junior-M",
                    album = "The First Mini Album - Super Girl",
                    durationMs = 218_720L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-too-long",
                    track = "Super Girl",
                    artist = "Super Junior M",
                    album = "Super Girl",
                    durationMs = 220_290L
                ),
                candidate(
                    source = "LRCLIB",
                    sourceId = "korean-version",
                    track = "Super Girl (Korean Version)",
                    artist = "Super Junior-M",
                    album = "Super Girl",
                    durationMs = 217_000L
                )
            )
        )

        assertEquals(
            listOf("qq-mandarin", "lrclib-mandarin", "korean-version"),
            selected.map { it.sourceId }
        )
        assertFalse(selected.any { it.sourceId == "netease-too-long" })
    }

    @Test
    fun `accepts Maria from independent sources when localized metadata forms one recording`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            album = "María - EP",
            durationMs = 199_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-maria",
                    track = "마리아",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-maria",
                    track = "마리아 (Maria)",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_053L
                )
            )
        )

        assertEquals(listOf("qq-maria", "netease-maria"), selected.map { it.sourceId })
    }

    @Test
    fun `keeps one unconfirmed localized result out without independent support`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            album = "Different Release",
            durationMs = 199_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "qq-maria",
                        track = "마리아",
                        artist = "华莎",
                        album = "María",
                        durationMs = 199_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `accepts unknown localized metadata only after two sources describe the same recording`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            durationMs = 199_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-maria",
                    track = "마리아",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-maria",
                    track = "마리아 (Maria)",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_053L
                )
            )
        )

        assertEquals(listOf("qq-maria", "netease-maria"), selected.map { it.sourceId })
    }

    @Test
    fun `uses a bilingual source to bridge an independently confirmed translated title`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            album = "María - EP",
            durationMs = 199_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "lrclib-maria",
                    track = "Maria",
                    artist = "HWASA",
                    album = "María - EP",
                    durationMs = 199_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-maria",
                    track = "마리아 (Maria)",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_053L
                )
            )
        )

        assertEquals(listOf("lrclib-maria", "netease-maria"), selected.map { it.sourceId })
    }

    @Test
    fun `does not treat an ordinary album prefix as a release marker`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Localized Artist",
            album = "Album",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "other-album",
                        track = "A Song",
                        artist = "本地歌手",
                        album = "Other Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects placeholder candidate artists even when title album and duration match`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "placeholder-artist",
                        track = "A Song",
                        artist = "Unknown",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `does not count duplicate candidates from one source as independent support`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            album = "Different Release",
            durationMs = 199_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "qq-maria-a",
                        track = "마리아",
                        artist = "华莎",
                        album = "María",
                        durationMs = 199_000L
                    ),
                    candidate(
                        source = "QQ音乐",
                        sourceId = "qq-maria-b",
                        track = "마리아 (Maria)",
                        artist = "华莎",
                        album = "María",
                        durationMs = 199_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `two sources can confirm an artist that cannot be compared across writing systems`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            durationMs = 199_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "localized-a",
                    track = "마리아",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "localized-b",
                    track = "마리아 (Maria)",
                    artist = "华莎",
                    album = "María",
                    durationMs = 199_100L
                )
            )
        )

        assertEquals(listOf("localized-a", "localized-b"), selected.map { it.sourceId })
    }

    @Test
    fun `source consensus cannot override both a different artist and a different album`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist A",
            album = "Album A",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "different-a",
                        track = "A Song",
                        artist = "Artist B",
                        album = "Album B",
                        durationMs = 200_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "different-b",
                        track = "A Song",
                        artist = "Artist B",
                        album = "Album B",
                        durationMs = 200_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects a different artist even when title album duration and sources agree`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Stage Name",
            album = "Release",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "legal-name-a",
                        track = "A Song",
                        artist = "Legal Name",
                        album = "Release",
                        durationMs = 200_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "legal-name-b",
                        track = "A Song",
                        artist = "Legal Name",
                        album = "Release",
                        durationMs = 200_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `moya ignores an unrelated cross-script title beside three matching sources`() {
        val query = LyricsLookup(
            track = "MOYA",
            artist = "AOA",
            album = "MOYA - EP",
            durationMs = 220_427L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "QQ音乐",
                    sourceId = "qq-moya",
                    track = "MOYA (모야)",
                    artist = "AOA",
                    album = "MOYA",
                    durationMs = 220_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "netease-moya",
                    track = "MOYA (모야)",
                    artist = "AOA",
                    album = "MOYA",
                    durationMs = 220_355L
                ),
                candidate(
                    source = "LRCLIB",
                    sourceId = "lrclib-moya",
                    track = "MOYA",
                    artist = "AOA",
                    album = "MOYA",
                    durationMs = 220_000L
                ),
                candidate(
                    source = "网易云音乐",
                    sourceId = "unrelated-korean-title",
                    track = "사뿐사뿐",
                    artist = "AOA",
                    album = "사뿐사뿐",
                    durationMs = 219_533L
                )
            )
        )

        assertEquals(
            setOf("qq-moya", "netease-moya", "lrclib-moya"),
            selected.map { it.sourceId }.toSet()
        )
        assertFalse(selected.any { it.sourceId == "unrelated-korean-title" })
    }

    @Test
    fun `unknown cross-script title cannot qualify from matching artist and album alone`() {
        val query = LyricsLookup(
            track = "MOYA",
            artist = "AOA",
            album = "Shared Release",
            durationMs = 220_427L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "网易云音乐",
                        sourceId = "unrelated-korean-title",
                        track = "사뿐사뿐",
                        artist = "AOA",
                        album = "Shared Release",
                        durationMs = 219_533L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `ranks stronger evidence ahead of a simpler title annotation`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Current Release",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "source",
                    sourceId = "simple-title-weaker-evidence",
                    track = "A Song",
                    artist = "Artist",
                    album = "Other Release",
                    durationMs = 200_000L
                ),
                candidate(
                    source = "source",
                    sourceId = "annotated-title-stronger-evidence",
                    track = "A Song (Movie Theme)",
                    artist = "Artist",
                    album = "Current Release",
                    durationMs = 201_500L
                )
            )
        )

        assertEquals(
            listOf("annotated-title-stronger-evidence", "simple-title-weaker-evidence"),
            selected.map { it.sourceId }
        )
    }

    @Test
    fun `independent support cannot override conflicting explicit recording versions`() {
        val query = LyricsLookup(
            track = "마리아 (Remix)",
            artist = "HWASA",
            album = "María - EP",
            durationMs = 199_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "qq-live",
                        track = "마리아 (Live)",
                        artist = "华莎",
                        album = "María",
                        durationMs = 199_000L
                    ),
                    candidate(
                        source = "网易云音乐",
                        sourceId = "netease-live",
                        track = "마리아 (Live at Seoul)",
                        artist = "华莎",
                        album = "María",
                        durationMs = 199_100L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `accepts a single live source when playback title omits the version annotation`() {
        val query = LyricsLookup(
            track = "晒",
            artist = "Tizzy T & GALI",
            album = "中国说唱巅峰对决 第三期",
            durationMs = 220_264L
        )
        val selection = LyricsCandidateSelector.selectCandidatesWithProof(
            query,
            listOf(
                candidate(
                    source = "网易云音乐",
                    sourceId = "1962368708",
                    track = "晒 (LIVE版)",
                    artist = "TizzyT / GALI",
                    album = "中国说唱巅峰对决 第三期",
                    durationMs = 220_215L
                )
            )
        ).single()

        assertEquals("1962368708", selection.candidate.sourceId)
        assertTrue(
            LyricsCandidateSelector.isProofValid(
                query,
                selection.candidate,
                selection.proof
            )
        )
        assertTrue(
            LyricsCandidateSelector.selectionSummary(
                query,
                listOf(selection.candidate),
                selection.candidate
            ).contains("title=NEAR")
        )
    }

    @Test
    fun `accepts a missing source version annotation as near title evidence`() {
        val query = LyricsLookup(
            track = "A Song (Live)",
            artist = "Artist",
            album = "Live Album",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "unmarked-live",
                    track = "A Song",
                    artist = "Artist",
                    album = "Live Album",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("unmarked-live"), selected.map { it.sourceId })
    }

    @Test
    fun `uses the base title for catalog search when featured artists are written in brackets`() {
        val terms = LyricsSearchPlanner.primaryTerms(
            LyricsLookup(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲",
                album = "被伤过的心还可以爱谁",
                durationMs = 289_250L
            )
        )

        assertEquals("错错错", terms.track)
        assertEquals("六哲", terms.artist)
    }

    @Test
    fun `builds exact LRCLIB terms from the recording identity`() {
        val terms = LyricsSearchPlanner.lrcLibExactTerms(
            LyricsLookup(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲",
                album = "被伤过的心还可以爱谁",
                durationMs = 289_250L
            )
        )

        assertEquals("错错错", terms.track)
        assertEquals("六哲, 陈娟儿", terms.artist)
        assertEquals("被伤过的心还可以爱谁", terms.album)
        assertEquals(289_250L, terms.durationMs)
    }

    @Test
    fun `uses the album core for exact LRCLIB terms`() {
        val terms = LyricsSearchPlanner.lrcLibExactTerms(
            LyricsLookup(
                track = "摩天动物园",
                artist = "邓紫棋",
                album = "摩天动物园 - Single",
                durationMs = 270_676L
            )
        )

        assertEquals("摩天动物园", terms.album)
    }

    @Test
    fun `selection proof preserves independent unknown artist support`() {
        val query = LyricsLookup(
            track = "마리아",
            artist = "HWASA",
            durationMs = 199_000L
        )
        val candidates = listOf(
            candidate(
                source = "QQ音乐",
                sourceId = "localized-a",
                track = "마리아",
                artist = "华莎",
                album = "María",
                durationMs = 199_000L
            ),
            candidate(
                source = "网易云音乐",
                sourceId = "localized-b",
                track = "마리아 (Maria)",
                artist = "华莎",
                album = "María",
                durationMs = 199_100L
            )
        )

        val selection = LyricsCandidateSelector.selectCandidatesWithProof(query, candidates).first()

        assertEquals(2, selection.proof.supportingCandidates.size)
        assertTrue(
            LyricsCandidateSelector.isProofValid(
                query,
                selection.candidate,
                selection.proof
            )
        )
        assertFalse(
            LyricsCandidateSelector.isProofValid(
                query,
                selection.candidate,
                selection.proof.copy(
                    supportingCandidates = listOf(selection.candidate.candidateSnapshot())
                )
            )
        )
        assertFalse(
            LyricsCandidateSelector.isProofValid(
                query,
                selection.candidate,
                selection.proof.copy(matcherPolicyVersion = 0)
            )
        )
    }

    @Test
    fun `does not duplicate featured artists already present in the artist field`() {
        val terms = LyricsSearchPlanner.lrcLibExactTerms(
            LyricsLookup(
                track = "错错错 (feat. 陈娟儿)",
                artist = "六哲/陈娟儿",
                album = "未知专辑",
                durationMs = 289_250L
            )
        )

        assertEquals("六哲/陈娟儿", terms.artist)
        assertEquals("", terms.album)
    }

    @Test
    fun `keeps explicit recording versions in catalog search terms`() {
        val terms = LyricsSearchPlanner.primaryTerms(
            LyricsLookup(
                track = "Super Girl (Korean Version) (Anniversary Release)",
                artist = "SUPER JUNIOR-M",
                durationMs = 217_000L
            )
        )

        assertEquals("Super Girl Korean Version", terms.track)
    }

    @Test
    fun `ranks stronger metadata before bracket detail without losing the recording`() {
        val query = LyricsLookup(
            track = "错错错 (feat. 陈娟儿)",
            artist = "六哲",
            album = "被伤过的心还可以爱谁",
            durationMs = 289_250L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "source-c",
                    sourceId = "unrelated-brackets",
                    track = "错错错 (电视剧主题曲)",
                    artist = "六哲",
                    album = "被伤过的心还可以爱谁",
                    durationMs = 289_100L
                ),
                candidate(
                    source = "source-b",
                    sourceId = "omitted-feature",
                    track = "错错错",
                    artist = "六哲",
                    album = "被伤过的心还可以爱谁",
                    durationMs = 289_000L
                ),
                candidate(
                    source = "source-a",
                    sourceId = "feature-in-artist",
                    track = "错错错",
                    artist = "六哲/陈娟儿",
                    album = "单身必听情歌",
                    durationMs = 289_186L
                )
            )
        )

        assertEquals(
            listOf("omitted-feature", "unrelated-brackets", "feature-in-artist"),
            selected.map { it.sourceId }
        )
    }

    @Test
    fun `keeps conflicting featured artist annotations as a lower priority candidate`() {
        val query = LyricsLookup(
            track = "A Song (feat. Guest A)",
            artist = "Main Artist",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "different-feature",
                    track = "A Song (feat. Guest B)",
                    artist = "Main Artist",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("different-feature"), selected.map { it.sourceId })
    }

    @Test
    fun `ranks exact similar and unrelated ordinary bracket information in that order`() {
        val query = LyricsLookup(
            track = "A Song (Movie Theme)",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "source-c",
                    sourceId = "exact",
                    track = "A Song (Movie Theme)",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 201_500L
                ),
                candidate(
                    source = "source-b",
                    sourceId = "unrelated",
                    track = "A Song (Anniversary Release)",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_000L
                ),
                candidate(
                    source = "source-a",
                    sourceId = "similar",
                    track = "A Song (Theme from Movie)",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_500L
                )
            )
        )

        assertEquals(listOf("exact", "similar", "unrelated"), selected.map { it.sourceId })
    }

    @Test
    fun `treats soundtrack context as ordinary bracket information rather than a recording version`() {
        val query = LyricsLookup(
            track = "A Song (From the Original Motion Picture Soundtrack)",
            artist = "Artist",
            album = "Soundtrack",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "without-soundtrack-note",
                    track = "A Song",
                    artist = "Artist",
                    album = "Soundtrack",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("without-soundtrack-note"), selected.map { it.sourceId })
    }

    @Test
    fun `accepts compatible details within the same explicit recording version`() {
        val query = LyricsLookup(
            track = "A Song (Live at Wembley)",
            artist = "Artist",
            album = "Live Album",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "live",
                    track = "A Song (Live)",
                    artist = "Artist",
                    album = "Live Album",
                    durationMs = 200_000L
                )
            )
        )

        assertEquals(listOf("live"), selected.map { it.sourceId })
    }

    @Test
    fun `ranks an exact version annotation ahead of differently worded details of the same version`() {
        val query = LyricsLookup(
            track = "A Song (Remastered)",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "source-b",
                    sourceId = "same-version-detail",
                    track = "A Song (2020 Remaster)",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_000L
                ),
                candidate(
                    source = "source-a",
                    sourceId = "exact-version",
                    track = "A Song (Remastered)",
                    artist = "Artist",
                    album = "Album",
                    durationMs = 200_500L
                )
            )
        )

        assertEquals(
            listOf("exact-version", "same-version-detail"),
            selected.map { it.sourceId }
        )
    }

    @Test
    fun `rejects a version when the duration differs by more than two seconds`() {
        val query = LyricsLookup(
            track = "World (Remastered)",
            artist = "Artist",
            album = "Album",
            durationMs = 258_763L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "网易云音乐",
                        sourceId = "wrong-duration",
                        track = "World (Remastered)",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 255_546L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects conflicting version qualifiers even when other metadata matches`() {
        val query = LyricsLookup(
            track = "Number Nine (Japanese Version)",
            artist = "T-ara",
            album = "Summer of Pop",
            durationMs = 228_920L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "QQ音乐",
                        sourceId = "korean-version",
                        track = "Number Nine (Korean Version)",
                        artist = "T-ara",
                        album = "Summer of Pop",
                        durationMs = 228_920L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `accepts the same language version written in different languages`() {
        val query = LyricsLookup(
            track = "Number Nine (韩语版)",
            artist = "T-ara",
            album = "Album",
            durationMs = 228_920L
        )

        val selected = LyricsCandidateSelector.selectCandidates(
            query,
            listOf(
                candidate(
                    source = "LRCLIB",
                    sourceId = "korean-version",
                    track = "Number Nine (Korean Version)",
                    artist = "T-ara",
                    album = "Album",
                    durationMs = 228_920L
                )
            )
        )

        assertEquals(listOf("korean-version"), selected.map { it.sourceId })
    }

    @Test
    fun `accepts a continuously contained title subject`() {
        val query = LyricsLookup(
            track = "Example Song Extended",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L
        )

        assertEquals(
            listOf("partial-title"),
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "partial-title",
                        track = "Example Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).map { it.sourceId }
        )
    }

    @Test
    fun `requires a direct artist match when an album cannot confirm the recording`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist A",
            durationMs = 200_000L
        )
        val matchingArtist = candidate(
            source = "LRCLIB",
            sourceId = "artist-a",
            track = "A Song",
            artist = "Artist A",
            durationMs = 200_000L
        )
        val otherArtist = candidate(
            source = "QQ音乐",
            sourceId = "artist-b",
            track = "A Song",
            artist = "Artist B",
            durationMs = 200_000L
        )

        assertEquals(
            listOf("artist-a"),
            LyricsCandidateSelector.selectCandidates(query, listOf(matchingArtist, otherArtist))
                .map { it.sourceId }
        )
    }

    @Test
    fun `additional releases cannot invalidate independently matching candidates`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Current Release",
            durationMs = 200_000L
        )

        assertEquals(
            listOf("current-release", "other-release"),
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "source-b",
                        sourceId = "other-release",
                        track = "A Song",
                        artist = "Artist",
                        album = "Other Release",
                        durationMs = 200_050L
                    ),
                    candidate(
                        source = "source-a",
                        sourceId = "current-release",
                        track = "A Song",
                        artist = "Artist",
                        album = "Current Release",
                        durationMs = 201_500L
                    )
                )
            ).map { it.sourceId }
        )
    }

    @Test
    fun `rejects a candidate when neither artist nor album confirms it`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist A",
            album = "Album A",
            durationMs = 200_000L
        )

        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "unrelated",
                        track = "A Song",
                        artist = "Artist B",
                        album = "Album B",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `waits for a known duration before confirming a recording`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Artist",
            album = "Album",
            durationMs = 0L
        )

        assertFalse(LyricsCandidateSelector.canConfirm(query))
        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "candidate",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `rejects placeholder artist metadata even when the album matches`() {
        val query = LyricsLookup(
            track = "A Song",
            artist = "Unknown",
            album = "Album",
            durationMs = 200_000L
        )

        assertFalse(LyricsCandidateSelector.canConfirm(query))
        assertTrue(
            LyricsCandidateSelector.selectCandidates(
                query,
                listOf(
                    candidate(
                        source = "LRCLIB",
                        sourceId = "candidate",
                        track = "A Song",
                        artist = "Artist",
                        album = "Album",
                        durationMs = 200_000L
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun `recognizes only synchronized lyrics`() {
        assertEquals(LyricsKind.NONE, classifyLyrics("null"))
        assertEquals(LyricsKind.PLAIN, classifyLyrics("plain fallback"))
        assertEquals(LyricsKind.SYNCHRONIZED, classifyLyrics("[00:15.44]Timed lyric"))
    }

    @Test
    fun `keeps only synchronized source translations`() {
        assertEquals("", synchronizedLyricsOrEmpty(null))
        assertEquals("", synchronizedLyricsOrEmpty("plain translation"))
        assertEquals(
            "[00:15.44]Translated lyric",
            synchronizedLyricsOrEmpty("  [00:15.44]Translated lyric  ")
        )
    }

    private fun candidate(
        source: String,
        sourceId: String,
        track: String,
        artist: String,
        album: String = "",
        durationMs: Long
    ) = LyricsResult(
        durationMs = durationMs,
        source = source,
        sourceId = sourceId,
        candidateTrack = track,
        candidateArtist = artist,
        candidateAlbum = album
    )
}
