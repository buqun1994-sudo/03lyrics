package com.ninepointnine.desktoplyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMetadataPolicyTest {
    @Test
    fun `standard duration remains milliseconds by default`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = 214_000L
            )
        )

        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `android 10 bluetooth browser duration is converted from seconds`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.SECONDS
            )
        )

        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `duration conversion treats nonpositive values as unknown`() {
        listOf(0L, -1L).forEach { rawDuration ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    title = "Song",
                    artist = "Artist",
                    durationMs = rawDuration,
                    durationUnit = MediaSessionDurationUnit.SECONDS
                )
            )
            assertEquals(0L, metadata.durationMs)
        }
    }

    @Test
    fun `duration conversion caps milliseconds and seconds without overflow`() {
        val fromSeconds = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = Long.MAX_VALUE,
                durationUnit = MediaSessionDurationUnit.SECONDS
            )
        )
        val fromMilliseconds = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = Long.MAX_VALUE,
                durationUnit = MediaSessionDurationUnit.MILLISECONDS
            )
        )

        assertEquals(86_400_000L, fromSeconds.durationMs)
        assertEquals(86_400_000L, fromMilliseconds.durationMs)
    }

    @Test
    fun `position evidence can identify an otherwise unknown seconds duration`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.UNKNOWN,
                reportedPositionMs = 5_000L
            )
        )

        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `unknown duration unit does not guess at the evidence boundary`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                durationMs = 214L,
                durationUnit = MediaSessionDurationUnit.UNKNOWN,
                reportedPositionMs = 2_214L
            )
        )

        assertEquals(0L, metadata.durationMs)
    }

    @Test
    fun `bluetooth avrcp decodes the vehicle composite without using lyric title`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "也会有人 又走进星梦",
                descriptionSubtitle = "星梦-XingMeng-GAI周延",
                displayTitle = "也会有人 又走进星梦",
                descriptionDescription = "REAL G",
                title = "也会有人 又走进星梦",
                artist = "星梦-XingMeng-GAI周延",
                album = "REAL G",
                durationMs = 198_544L,
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("星梦-XingMeng", metadata.track)
        assertEquals("GAI周延", metadata.artist)
        assertEquals("REAL G", metadata.album)
        assertEquals(198_544L, metadata.durationMs)
    }

    @Test
    fun `bluetooth keeps an independently published album artist separate`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "动态歌词行",
                artist = "星梦-XingMeng-GAI周延",
                albumArtist = "GAI周延",
                album = "REAL G",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("星梦-XingMeng", metadata.track)
        assertEquals("GAI周延", metadata.artist)
        assertEquals("REAL G", metadata.album)
    }

    @Test
    fun `bluetooth ignores changing display title and uses an independent artist hint`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "Song",
                displayTitle = "Song",
                title = "Song",
                artist = "Song-Name-Sia-Furler",
                albumArtist = "Sia-Furler",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song-Name", metadata.track)
        assertEquals("Sia-Furler", metadata.artist)
    }

    @Test
    fun `bluetooth accepts title punctuation when an artist hint confirms the boundary`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                displayTitle = "AC/DC",
                descriptionTitle = "当前歌词行",
                title = "当前歌词行",
                artist = "AC/DC-Sia",
                albumArtist = "Sia",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("AC/DC", metadata.track)
        assertEquals("Sia", metadata.artist)
    }

    @Test
    fun `bluetooth uses an independent album artist to resolve an artist dash`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "当前歌词行",
                title = "当前歌词行",
                artist = "Song-Sia-Furler",
                albumArtist = "Sia-Furler",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("Sia-Furler", metadata.artist)
    }

    @Test
    fun `bluetooth lyric title updates keep one recording generation and query`() {
        val tracker = MediaRecordingStateTracker()
        val lines = listOf(
            "你是真的player",
            "所以从不说bye",
            "回想你走二十几年没觉得快",
            "记得回家随时有外婆的菜",
            // A lyric line may coincidentally equal a title fragment. It must
            // never change the AVRCP composite boundary.
            "星梦"
        )
        val states = lines.map { line ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    descriptionTitle = line,
                    descriptionSubtitle =
                        "无期-GAI周延/功夫胖KUNGFU-PEN/KEY.L刘聪/盛宇D-SHINE",
                    displayTitle = line,
                    descriptionDescription = "G-BLOCK Mixtape",
                    title = line,
                    artist = "无期-GAI周延/功夫胖KUNGFU-PEN/KEY.L刘聪/盛宇D-SHINE",
                    album = "G-BLOCK Mixtape",
                    durationMs = 308_046L,
                    transport = MediaSessionTransport.BLUETOOTH_AVRCP
                )
            )
            requireNotNull(tracker.update("bluetooth-session", metadata))
        }

        assertEquals(setOf(1L), states.map { it.recordingGeneration }.toSet())
        assertEquals(setOf(1L), states.map { it.queryRevision }.toSet())
        assertEquals(setOf(false), states.map { it.recordingChanged }.drop(1).toSet())
        assertEquals(setOf(false), states.map { it.queryChanged }.drop(1).toSet())
        assertTrue(states.all { it.metadata.track == "无期" })
        assertTrue(
            states.all {
                it.metadata.artist ==
                    "GAI周延/功夫胖KUNGFU-PEN/KEY.L刘聪/盛宇D-SHINE"
            }
        )
        assertTrue(states.all { it.metadata.album == "G-BLOCK Mixtape" })
    }

    @Test
    fun `observed qq bluetooth fields keep the song tuple stable across lyric and credit text`() {
        val tracker = MediaRecordingStateTracker()
        val displayLines = listOf(
            "说不尽万种滋味",
            "却不料死得可悲",
            "词：红手指Red Finger",
            "制作人：不可说厂牌/赵兴宇Siri@膛词厂"
        )

        val states = displayLines.map { displayLine ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    descriptionTitle = displayLine,
                    descriptionSubtitle = "天地-RANZER叶润泽",
                    displayTitle = displayLine,
                    title = displayLine,
                    artist = "天地-RANZER叶润泽",
                    album = "天地",
                    durationMs = 205_000L,
                    transport = MediaSessionTransport.BLUETOOTH_AVRCP
                )
            )
            requireNotNull(tracker.update("qq-avrcp", metadata))
        }

        assertEquals(setOf(1L), states.map { it.recordingGeneration }.toSet())
        assertEquals(setOf(1L), states.map { it.queryRevision }.toSet())
        assertTrue(states.all { it.metadata.track == "天地" })
        assertTrue(states.all { it.metadata.artist == "RANZER叶润泽" })
        assertTrue(states.all { it.metadata.album == "天地" })
    }

    @Test
    fun `bluetooth composite handles single artist recordings`() {
        val cases = listOf(
            Triple("Love is over", "逝去的爱-李安", "逝去的爱" to "李安"),
            Triple(
                "Said you like the risk",
                "Cold Blooded (Moon Version)-严浩翔",
                "Cold Blooded (Moon Version)" to "严浩翔"
            ),
            Triple(
                "歌词展示行",
                "My job (老本行) (Live)-ICE杨长青/那奇沃夫/张峻豪SHUN",
                "My job (老本行) (Live)" to "ICE杨长青/那奇沃夫/张峻豪SHUN"
            ),
            Triple(
                "动态展示行",
                "从前以后PT.2-盛宇D-SHINE / RANZER叶润泽",
                "从前以后PT.2" to "盛宇D-SHINE / RANZER叶润泽"
            ),
            Triple(
                "当前歌词行",
                "好男儿志在远方, 投名状 (The Oath)-GAI周延/盛宇D-SHINE/RANZER叶润泽",
                "好男儿志在远方, 投名状 (The Oath)" to
                    "GAI周延/盛宇D-SHINE/RANZER叶润泽"
            ),
            Triple(
                "我的音乐永远不会放到超市卖",
                "The Gentlemen (绅士们) (Live)-弹壳/Vinz-T",
                "The Gentlemen (绅士们) (Live)" to "弹壳/Vinz-T"
            ),
            Triple(
                "动态展示行",
                "作品名—歌手甲＆歌手乙",
                "作品名" to "歌手甲＆歌手乙"
            )
        )

        cases.forEach { (displayTitle, compositeArtist, expected) ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    title = displayTitle,
                    artist = compositeArtist,
                    album = "album",
                    transport = MediaSessionTransport.BLUETOOTH_AVRCP
                )
            )
            assertEquals(expected.first, metadata.track)
            assertEquals(expected.second, metadata.artist)
        }
    }

    @Test
    fun `malformed bluetooth composite with a short prefix falls back`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "T-ara",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.track)
        assertEquals("T-ara", metadata.artist)
    }

    @Test
    fun `bluetooth standard metadata keeps apple music fields independent`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Artist",
                album = "Album",
                durationMs = 214_000L,
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("Artist", metadata.artist)
        assertEquals("Album", metadata.album)
        assertEquals(214_000L, metadata.durationMs)
    }

    @Test
    fun `bluetooth standard projection uses raw title instead of a derived display line`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "当前展示行",
                descriptionSubtitle = "不应覆盖歌手",
                displayTitle = "如果你也听说",
                displaySubtitle = "另一条展示文本",
                title = "如果你也听说",
                artist = "张惠妹",
                album = "STAR",
                durationMs = 246_000L,
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("如果你也听说", metadata.track)
        assertEquals("张惠妹", metadata.artist)
        assertEquals("STAR", metadata.album)
        assertEquals(246_000L, metadata.durationMs)
    }

    @Test
    fun `bluetooth never parses a media description subtitle as a second composite`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "动态歌词行",
                descriptionSubtitle = "误导标题-误导歌手",
                displaySubtitle = "另一条展示文本-另一位歌手",
                title = "动态歌词行",
                artist = "T-ara",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.track)
        assertEquals("T-ara", metadata.artist)
    }

    @Test
    fun `bluetooth standard fields use the independent title when artist is plain`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "Song",
                displayTitle = "Song",
                title = "Song",
                artist = "Artist",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("Artist", metadata.artist)
    }

    @Test
    fun `bluetooth standard metadata preserves a hyphenated artist when album artist corroborates it`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Sia-Furler",
                albumArtist = "Sia-Furler",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("Sia-Furler", metadata.artist)
    }

    @Test
    fun `bluetooth standard metadata does not split a non latin hyphenated artist when corroborated`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "张三-李四",
                albumArtist = "张三-李四",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("张三-李四", metadata.artist)
    }

    @Test
    fun `bluetooth does not treat a title comma as an artist separator`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                displayTitle = "当前歌词行",
                title = "当前歌词行",
                artist = "Song-Part, Two-Artist",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.track)
        assertEquals("Song-Part, Two-Artist", metadata.artist)
    }

    @Test
    fun `bluetooth does not promote a title while the raw artist channel is incomplete`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "当前歌词行",
                descriptionSubtitle = "Song-Artist",
                title = "当前歌词行",
                artist = "",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.track)
        assertEquals("", metadata.artist)
    }

    @Test
    fun `bluetooth does not promote a display description into the album field`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "动态歌词行",
                descriptionDescription = "制作人：someone",
                displayDescription = "另一条展示文本",
                artist = "Song-Artist",
                album = "",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.album)
    }

    @Test
    fun `bluetooth keeps a plain hyphenated artist when no composite evidence exists`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                descriptionTitle = "动态歌词行",
                title = "Song",
                artist = "Sia-Furler",
                album = "Album",
                transport = MediaSessionTransport.BLUETOOTH_AVRCP
            )
        )

        assertEquals("", metadata.track)
        assertEquals("Sia-Furler", metadata.artist)
    }

    @Test
    fun `standard transport preserves a hyphenated artist as one field`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "Song",
                artist = "Sia-Furler",
                album = "Album",
                transport = MediaSessionTransport.STANDARD
            )
        )

        assertEquals("Song", metadata.track)
        assertEquals("Sia-Furler", metadata.artist)
    }

    @Test
    fun `controller description wins when a raw title carries changing lyric lines`() {
        val tracker = MediaRecordingStateTracker()
        val generations = (1..20).map { line ->
            val metadata = MediaSessionMetadataPolicy.normalize(
                MediaSessionMetadataFields(
                    descriptionTitle = "轻轻地告诉你",
                    descriptionSubtitle = "杨钰莹",
                    title = "第 $line 行歌词",
                    artist = "杨钰莹",
                    album = "毛宁+杨钰莹-上海金秋演唱会",
                    durationMs = 238_000L
                )
            )
            tracker.update("online-session", metadata)?.recordingGeneration
        }

        assertEquals(setOf(1L), generations.toSet())
    }

    @Test
    fun `raw standard fields remain a valid fallback for bluetooth and local players`() {
        val metadata = MediaSessionMetadataPolicy.normalize(
            MediaSessionMetadataFields(
                title = "跨时代",
                artist = "周杰伦",
                album = "跨时代",
                durationMs = 234_000L
            )
        )

        assertEquals("跨时代", metadata.track)
        assertEquals("周杰伦", metadata.artist)
        assertEquals("跨时代", metadata.album)
        assertEquals(234_000L, metadata.durationMs)
    }

    @Test
    fun `missing optional fields enrich one recording without creating a new generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "", "", 0L)
            )
        )
        val enriched = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )

        assertEquals(first.recordingGeneration, enriched.recordingGeneration)
        assertTrue(enriched.queryChanged)
        assertEquals(first.queryRevision + 1L, enriched.queryRevision)
        assertEquals("Artist", enriched.metadata.artist)
        assertEquals("Album", enriched.metadata.album)
    }

    @Test
    fun `known metadata is not degraded by a transient empty callback`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val transient = requireNotNull(
            tracker.update(
                "session",
                MediaRecordingMetadata("", "", "", 0L)
            )
        )

        assertEquals(first.recordingGeneration, transient.recordingGeneration)
        assertEquals(first.metadata, transient.metadata)
        assertFalse(transient.queryChanged)
    }

    @Test
    fun `source or real recording changes advance the generation`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update(
                "bluetooth",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val sourceChanged = requireNotNull(
            tracker.update(
                "online",
                MediaRecordingMetadata("Song", "Artist", "Album", 180_000L)
            )
        )
        val trackChanged = requireNotNull(
            tracker.update(
                "online",
                MediaRecordingMetadata("Next Song", "Artist", "Album", 200_000L)
            )
        )

        assertEquals(first.recordingGeneration + 1L, sourceChanged.recordingGeneration)
        assertEquals(sourceChanged.recordingGeneration + 1L, trackChanged.recordingGeneration)
        assertTrue(sourceChanged.recordingChanged)
        assertTrue(trackChanged.recordingChanged)
    }

    @Test
    fun `duration only revises a query when it crosses a meaningful boundary`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L))
        )
        val smallDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_500L))
        )
        val materialDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 184_001L))
        )

        assertEquals(first.queryRevision, smallDrift.queryRevision)
        assertFalse(smallDrift.queryChanged)
        assertEquals(first.queryRevision + 1L, materialDrift.queryRevision)
        assertTrue(materialDrift.queryChanged)
    }

    @Test
    fun `small cumulative duration changes are measured from the last query`() {
        val tracker = MediaRecordingStateTracker()
        val first = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 180_000L))
        )
        requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 181_000L))
        )
        requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_000L))
        )
        val cumulativeDrift = requireNotNull(
            tracker.update("session", MediaRecordingMetadata("Song", "Artist", "", 182_001L))
        )

        assertEquals(first.queryRevision + 1L, cumulativeDrift.queryRevision)
        assertTrue(cumulativeDrift.queryChanged)
    }
}
