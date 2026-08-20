package com.ninepointnine.desktoplyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class TitleIdentity(
    val base: String,
    val baseSegments: List<String>,
    val alternateBases: Set<String>,
    val searchText: String,
    val versionQualifiers: Set<String>,
    val annotations: Set<String>,
    val annotationTokens: Set<String>,
    val featuredArtists: Set<String>
) {
    val isValid: Boolean get() = base.isNotBlank()
}

internal data class TitleAnnotationIdentity(
    val content: String,
    val featuredArtists: Set<String>,
    val versionQualifiers: Set<String>
)

internal data class ArtistIdentity(
    val declared: Set<String>,
    val featured: Set<String>,
    val displayNameSegments: List<List<String>>
) {
    val effective: Set<String> = declared + featured
}

internal fun titleIdentity(value: String): TitleIdentity {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
    val versionQualifiers = mutableSetOf<String>()
    val annotations = mutableSetOf<String>()
    val annotationTokens = mutableSetOf<String>()
    val featuredArtists = mutableSetOf<String>()
    val bracketIdentities = BRACKET_PATTERN.findAll(normalized).map { match ->
        val content = match.groupValues[1].trim()
        val featured = extractFeaturedArtists(content)
        val versions = if (featured.isEmpty()) versionQualifiers(content) else emptySet()
        TitleAnnotationIdentity(content, featured, versions)
    }.toList()
    bracketIdentities.forEach { identity ->
        val content = identity.content
        val featured = identity.featuredArtists
        val versions = identity.versionQualifiers
        when {
            featured.isNotEmpty() -> featuredArtists += featured
            versions.isNotEmpty() -> versionQualifiers += versions
        }
        if (featured.isEmpty()) {
            normalizeText(content)
                .takeIf(String::isNotBlank)
                ?.let(annotations::add)
            annotationTokens += annotationTokens(content)
        }
    }
    val identityIterator = bracketIdentities.iterator()
    val searchText = BRACKET_PATTERN.replace(normalized) {
        val identity = identityIterator.next()
        if (identity.versionQualifiers.isEmpty()) " " else " ${identity.content} "
    }
        .replace(WHITESPACE_PATTERN, " ")
        .trim()
    val baseText = normalized.replace(BRACKET_PATTERN, " ")
    val base = normalizeText(baseText)
    val alternateBases = bracketIdentities.asSequence()
        .filter { it.featuredArtists.isEmpty() && it.versionQualifiers.isEmpty() }
        .map { normalizeText(it.content) }
        .filter(String::isNotBlank)
        .filter { alternate -> haveDisjointScripts(base, alternate) }
        .toSet()
    return TitleIdentity(
        base = base,
        baseSegments = structuredTextSegments(baseText),
        alternateBases = alternateBases,
        searchText = searchText,
        versionQualifiers = versionQualifiers,
        annotations = annotations,
        annotationTokens = annotationTokens,
        featuredArtists = featuredArtists
    )
}

internal fun artistNames(value: String): List<String> = value
    .split(ARTIST_SEPARATOR_PATTERN)
    .map(::normalizeText)
    .filter(String::isNotBlank)

internal fun artistIdentity(track: String, artist: String): ArtistIdentity {
    val featured = titleIdentity(track).featuredArtists
    return ArtistIdentity(
        declared = artistNames(artist).toSet(),
        featured = featured,
        displayNameSegments = artistDisplayNameSegments(artist)
    )
}

internal fun isValidArtist(value: String): Boolean = normalizeText(value).let {
    it.isNotBlank() && it !in PLACEHOLDER_ARTISTS
}

internal fun normalizeText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(COMBINING_MARK_PATTERN, "")
    .replace(NON_LETTER_OR_DIGIT_PATTERN, "")

internal fun normalizedAlbum(value: String): String = normalizeText(value)
    .takeUnless { it in PLACEHOLDER_ALBUMS }
    .orEmpty()

internal fun normalizedAlbumCore(value: String): String {
    val album = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    val withoutReleaseSuffix = ALBUM_RELEASE_SUFFIX_PATTERN.replace(album, "").trim()
    return normalizedAlbum(withoutReleaseSuffix)
}

internal fun hasContiguousArtistSubject(
    first: ArtistIdentity,
    second: ArtistIdentity
): Boolean = first.displayNameSegments.any { firstSegments ->
    second.displayNameSegments.any { secondSegments ->
        hasContiguousSubjectRelation(firstSegments, secondSegments)
    }
}

private fun artistDisplayNameSegments(value: String): List<List<String>> = value
    .split(ARTIST_SEPARATOR_PATTERN)
    .map(::structuredTextSegments)
    .filter(List<String>::isNotEmpty)

internal fun artistSearchAnchor(value: String): String? = artistDisplayNameSegments(value)
    .asSequence()
    .flatten()
    .filter { segment -> segment.length >= MINIMUM_STRUCTURED_CORE_LENGTH }
    .withIndex()
    .maxWithOrNull(
        compareBy<IndexedValue<String>> { indexed -> indexed.value.length }
            .thenByDescending { indexed -> indexed.index }
    )
    ?.value

private fun structuredTextSegments(value: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var currentScript: Character.UnicodeScript? = null

    fun flushSegment() {
        normalizeText(current.toString())
            .takeIf(String::isNotBlank)
            ?.let(segments::add)
        current.setLength(0)
        currentScript = null
    }

    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(COMBINING_MARK_PATTERN, "")
        .forEach { character ->
        if (!character.isLetterOrDigit()) {
            flushSegment()
            return@forEach
        }
        val script = Character.UnicodeScript.of(character.code).takeUnless {
            it == Character.UnicodeScript.COMMON ||
                it == Character.UnicodeScript.INHERITED
        }
        if (script != null && currentScript != null && script != currentScript) {
            flushSegment()
        }
        current.append(character)
        if (script != null) currentScript = script
    }
    flushSegment()
    return segments
}

internal fun hasContiguousSubjectRelation(
    first: List<String>,
    second: List<String>
): Boolean {
    if (first.isEmpty() || second.isEmpty()) return false
    val shared = longestCommonContiguousSpan(first, second)
    if (shared.length == 0) return false
    val sharedText = first.subList(shared.firstStart, shared.firstStart + shared.length)
        .joinToString("")
    if (sharedText.length < MINIMUM_STRUCTURED_CORE_LENGTH) return false

    if (shared.length == first.size || shared.length == second.size) return true
    if (shared.length * 2 > first.size && shared.length * 2 > second.size) return true
    if (shared.length * 2 < first.size || shared.length * 2 < second.size) return false

    val firstRemainder = first.subList(0, shared.firstStart) +
        first.subList(shared.firstStart + shared.length, first.size)
    val secondRemainder = second.subList(0, shared.secondStart) +
        second.subList(shared.secondStart + shared.length, second.size)
    return firstRemainder.isNotEmpty() && secondRemainder.isNotEmpty() &&
        haveDisjointScripts(firstRemainder.joinToString(""), secondRemainder.joinToString(""))
}

private fun longestCommonContiguousSpan(
    first: List<String>,
    second: List<String>
): CommonSegmentSpan {
    var best = CommonSegmentSpan(0, 0, 0)
    first.indices.forEach { firstStart ->
        second.indices.forEach { secondStart ->
            var length = 0
            while (firstStart + length < first.size &&
                secondStart + length < second.size &&
                first[firstStart + length] == second[secondStart + length]
            ) {
                length += 1
            }
            if (length > best.length) {
                best = CommonSegmentSpan(firstStart, secondStart, length)
            }
        }
    }
    return best
}

private data class CommonSegmentSpan(
    val firstStart: Int,
    val secondStart: Int,
    val length: Int
)

internal fun nearMetadataText(first: String, second: String): Boolean {
    val firstNormalized = normalizeText(first)
    val secondNormalized = normalizeText(second)
    val firstTokens = metadataTokens(first)
    val secondTokens = metadataTokens(second)
    if (firstTokens.isEmpty() || secondTokens.isEmpty()) return false
    if (firstTokens == secondTokens) return true
    if (hasMinimumTextSimilarity(
            firstNormalized,
            secondNormalized,
            MINIMUM_ALBUM_SIMILARITY_PERCENT
        )
    ) {
        return true
    }
    val (shorter, longer) = if (firstNormalized.length <= secondNormalized.length) {
        firstNormalized to secondNormalized
    } else {
        secondNormalized to firstNormalized
    }
    return longer.contains(shorter) &&
        shorter.length * 100 >= longer.length * MINIMUM_NEAR_TEXT_PERCENT
}

internal fun hasMinimumTextSimilarity(
    first: String,
    second: String,
    minimumPercent: Int
): Boolean {
    if (first.isBlank() || second.isBlank()) return false
    if (first == second) return true
    val maximumLength = maxOf(first.length, second.length)
    if (maximumLength < MINIMUM_FUZZY_TEXT_LENGTH) return false
    val allowedDistance = maximumLength * (100 - minimumPercent) / 100
    if (abs(first.length - second.length) > allowedDistance) return false
    return editDistanceAtMost(first, second, allowedDistance)
}

private fun editDistanceAtMost(first: String, second: String, maximumDistance: Int): Boolean {
    if (maximumDistance < 0) return false
    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)
    for (firstIndex in first.indices) {
        current[0] = firstIndex + 1
        for (secondIndex in second.indices) {
            val substitutionCost = if (first[firstIndex] == second[secondIndex]) 0 else 1
            current[secondIndex + 1] = minOf(
                previous[secondIndex + 1] + 1,
                current[secondIndex] + 1,
                previous[secondIndex] + substitutionCost
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.length] <= maximumDistance
}

private fun metadataTokens(value: String): Set<String> =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(COMBINING_MARK_PATTERN, "")
        .split(NON_ALPHANUMERIC_PATTERN)
        .filter(String::isNotBlank)
        .toSet()

internal fun haveDisjointScripts(first: String, second: String): Boolean {
    val firstScripts = scripts(first)
    val secondScripts = scripts(second)
    return firstScripts.isNotEmpty() && secondScripts.isNotEmpty() &&
        firstScripts.intersect(secondScripts).isEmpty()
}

private fun scripts(value: String): Set<Character.UnicodeScript> = value.asSequence()
    .filter(Char::isLetter)
    .map { character -> Character.UnicodeScript.of(character.code) }
    .filterNot { script ->
        script == Character.UnicodeScript.COMMON ||
            script == Character.UnicodeScript.INHERITED
    }
    .toSet()

private fun extractFeaturedArtists(value: String): Set<String> =
    FEATURED_ARTIST_PATTERN.matchEntire(value.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::artistNames)
        ?.toSet()
        .orEmpty()

internal fun featuredArtistDisplayNames(value: String): List<String> =
    BRACKET_PATTERN.findAll(value)
        .mapNotNull { match ->
            FEATURED_ARTIST_PATTERN.matchEntire(match.groupValues[1].trim())
                ?.groupValues
                ?.getOrNull(1)
        }
        .flatMap { featured -> featured.split(ARTIST_SEPARATOR_PATTERN).asSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(::normalizeText)
        .toList()

private fun versionQualifiers(value: String): Set<String> {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val qualifiers = VERSION_QUALIFIER_PATTERNS
        .mapNotNull { (qualifier, pattern) -> qualifier.takeIf { pattern.containsMatchIn(normalized) } }
        .toMutableSet()
    if (qualifiers.isEmpty()) {
        explicitVersionQualifier(normalized)?.let(qualifiers::add)
    }
    return qualifiers
}

private fun explicitVersionQualifier(value: String): String? {
    val explicitDescriptor = ENGLISH_VERSION_PATTERN.matchEntire(value)
        ?.groupValues
        ?.getOrNull(1)
        ?: CJK_VERSION_PATTERN.matchEntire(value)?.groupValues?.getOrNull(1)
    val descriptor = explicitDescriptor ?: value
    LANGUAGE_VERSION_ALIASES[normalizeText(descriptor)]?.let { language ->
        return "language:$language"
    }
    return explicitDescriptor
        ?.let(::normalizeText)
        ?.takeIf(String::isNotBlank)
        ?.let { "version:$it" }
}

private fun annotationTokens(value: String): Set<String> {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    val words = normalized.split(NON_ALPHANUMERIC_PATTERN)
        .map(::normalizeText)
        .filter { it.length >= 2 && it !in ANNOTATION_STOP_WORDS }
    val cjkBigrams = CJK_SEQUENCE_PATTERN.findAll(normalized)
        .flatMap { it.value.windowed(2).asSequence() }
    return (words.asSequence() + cjkBigrams).toSet()
}

internal fun normalizedAlbumQueryText(value: String): String {
    val album = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    val core = ALBUM_RELEASE_SUFFIX_PATTERN.replace(album, "").trim()
    return core.takeIf { normalizedAlbum(it).isNotBlank() }.orEmpty()
}

private val BRACKET_PATTERN = Regex("[（(\\[【{]([^）)\\]】}]{1,80})[）)\\]】}]")
private val WHITESPACE_PATTERN = Regex("\\s+")
private val FEATURED_ARTIST_PATTERN = Regex(
    "^\\s*(?:feat(?:uring)?\\.?|ft\\.?|with)\\s*[:：-]?\\s*(.+)$",
    RegexOption.IGNORE_CASE
)
private val ARTIST_SEPARATOR_PATTERN = Regex(
    "\\s*(?:[,，、/&／;；+＋]|\\bfeat(?:uring)?\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\band\\b)\\s*",
    RegexOption.IGNORE_CASE
)
private val ALBUM_RELEASE_SUFFIX_PATTERN = Regex(
    """\s*(?:[-‐‑‒–—―:：]\s*(?:single|ep)|[（(\[【]\s*(?:single|ep)\s*[）)\]】])\s*$""",
    RegexOption.IGNORE_CASE
)
private val VERSION_QUALIFIER_PATTERNS = listOf(
    "live" to Regex("\\blive\\b|现场|演唱会", RegexOption.IGNORE_CASE),
    "remix" to Regex("\\bremix\\b|混音", RegexOption.IGNORE_CASE),
    "remaster" to Regex("\\bremaster(?:ed)?\\b|重制", RegexOption.IGNORE_CASE),
    "acoustic" to Regex("\\bacoustic\\b|不插电|清唱", RegexOption.IGNORE_CASE),
    "instrumental" to Regex("\\binstrumental\\b|伴奏|纯音乐", RegexOption.IGNORE_CASE),
    "karaoke" to Regex("\\bkaraoke\\b|卡拉ok", RegexOption.IGNORE_CASE),
    "demo" to Regex("\\bdemo\\b|小样", RegexOption.IGNORE_CASE),
    "radio-edit" to Regex("\\bradio\\s*edit\\b|电台剪辑", RegexOption.IGNORE_CASE),
    "edit" to Regex("(?<!radio\\s)\\bedit\\b|剪辑版", RegexOption.IGNORE_CASE),
    "mix" to Regex("\\bmix\\b", RegexOption.IGNORE_CASE),
    "dj" to Regex("\\bdj\\b|dj版", RegexOption.IGNORE_CASE),
    "sped-up" to Regex("\\bsped\\s*up\\b|加速", RegexOption.IGNORE_CASE),
    "slowed" to Regex("\\bslowed(?:\\s*down)?\\b|慢速|降速", RegexOption.IGNORE_CASE),
    "nightcore" to Regex("\\bnightcore\\b", RegexOption.IGNORE_CASE),
    "mono" to Regex("\\bmono\\b|单声道", RegexOption.IGNORE_CASE),
    "stereo" to Regex("\\bstereo\\b|立体声", RegexOption.IGNORE_CASE),
    "cover" to Regex("\\bcover\\b|翻唱", RegexOption.IGNORE_CASE),
    "original" to Regex("^\\s*(?:original(?:\\s+(?:ver(?:sion)?\\.?))?|原版)\\s*$", RegexOption.IGNORE_CASE),
    "rerecorded" to Regex("\\bre-?recorded\\b|重新录制", RegexOption.IGNORE_CASE)
)
private val ENGLISH_VERSION_PATTERN = Regex(
    "^\\s*(.+?)\\s+ver(?:sion)?\\.?\\s*$",
    RegexOption.IGNORE_CASE
)
private val CJK_VERSION_PATTERN = Regex("^\\s*(.+?)(?:版本|版)\\s*$")
private val NON_ALPHANUMERIC_PATTERN = Regex("[^\\p{L}\\p{N}]+")
private val NON_LETTER_OR_DIGIT_PATTERN = Regex("[^\\p{L}\\p{N}]")
private val COMBINING_MARK_PATTERN = Regex("\\p{M}+")
private val CJK_SEQUENCE_PATTERN = Regex("[\\u3400-\\u9fff\\uF900-\\uFAFF]+")
private val ANNOTATION_STOP_WORDS = setOf("the", "and", "from", "with", "of")
private const val MINIMUM_NEAR_TEXT_PERCENT = 70
private const val MINIMUM_FUZZY_TEXT_LENGTH = 8
internal const val MINIMUM_TITLE_SIMILARITY_PERCENT = 85
internal const val MINIMUM_ARTIST_SIMILARITY_PERCENT = 90
private const val MINIMUM_ALBUM_SIMILARITY_PERCENT = 80
private const val MINIMUM_STRUCTURED_CORE_LENGTH = 2
private val LANGUAGE_DISPLAY_LOCALES = listOf(
    Locale.ENGLISH,
    Locale.SIMPLIFIED_CHINESE,
    Locale.TRADITIONAL_CHINESE
)
private val LANGUAGE_VERSION_ALIASES: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { languageCode ->
                val language = languageCode.lowercase(Locale.ROOT)
                val locale = Locale(language)
                put(normalizeText(language), language)
                LANGUAGE_DISPLAY_LOCALES.forEach { displayLocale ->
                    normalizeText(locale.getDisplayLanguage(displayLocale))
                        .takeIf(String::isNotBlank)
                        ?.let { put(it, language) }
                }
            }
        put("mandarin", "cmn")
        put("国语", "cmn")
        put("普通话", "cmn")
        put("华语", "cmn")
        put("cantonese", "yue")
        put("粤语", "yue")
        put("广东话", "yue")
    }
}
private val PLACEHOLDER_ARTISTS = setOf(
    "unknown",
    "unkown",
    "null",
    "undefined",
    "未知",
    "未知歌手"
)
private val PLACEHOLDER_ALBUMS = setOf(
    "unknown",
    "unkown",
    "null",
    "undefined",
    "未知",
    "未知专辑"
)
