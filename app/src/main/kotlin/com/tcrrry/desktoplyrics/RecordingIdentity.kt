package com.tcrrry.desktoplyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class TitleIdentity(
    val base: String,
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
    val featured: Set<String>
) {
    val effective: Set<String> = declared + featured
}

internal enum class EvidenceLevel {
    EXACT,
    NEAR,
    UNKNOWN,
    DIFFERENT;

    fun isConfirmed(): Boolean = this == EXACT || this == NEAR

    fun titleScore(): Int = when (this) {
        EXACT -> 4
        NEAR -> 3
        UNKNOWN -> 0
        DIFFERENT -> -5
    }

    fun artistScore(): Int = when (this) {
        EXACT -> 3
        NEAR -> 2
        UNKNOWN -> 0
        DIFFERENT -> -2
    }

    fun albumScore(): Int = when (this) {
        EXACT -> 3
        NEAR -> 2
        UNKNOWN -> 0
        DIFFERENT -> -1
    }
}

internal fun titleAnnotationRank(
    query: LyricsLookup,
    candidate: LyricsResult
): Int {
    val wanted = titleIdentity(query.track)
    val found = titleIdentity(candidate.candidateTrack)
    val wantedArtists = artistIdentity(query.track, query.artist).effective
    val foundArtists = artistIdentity(candidate.candidateTrack, candidate.candidateArtist).effective
    val featuredExact = wanted.featuredArtists.all(foundArtists::contains) &&
        found.featuredArtists.all(wantedArtists::contains)
    if (wanted.annotations == found.annotations && featuredExact) return 0

    val wantedHasAnnotations = wanted.annotations.isNotEmpty() ||
        wanted.featuredArtists.isNotEmpty()
    val foundHasAnnotations = found.annotations.isNotEmpty() ||
        found.featuredArtists.isNotEmpty()
    if (!wantedHasAnnotations || !foundHasAnnotations) return 1

    val featuredRelated = wanted.featuredArtists.any(foundArtists::contains) ||
        found.featuredArtists.any(wantedArtists::contains)
    val versionRelated = wanted.versionQualifiers.isNotEmpty() &&
        wanted.versionQualifiers == found.versionQualifiers
    val annotationRelated = wanted.annotationTokens.any(found.annotationTokens::contains)
    return if (featuredRelated || versionRelated || annotationRelated) 1 else 2
}

internal fun albumEvidence(first: String, second: String): EvidenceLevel {
    val firstAlbum = normalizedAlbum(first)
    val secondAlbum = normalizedAlbum(second)
    if (firstAlbum.isBlank() || secondAlbum.isBlank()) return EvidenceLevel.UNKNOWN
    if (firstAlbum == secondAlbum) return EvidenceLevel.EXACT
    val firstCore = normalizedAlbumCore(first)
    val secondCore = normalizedAlbumCore(second)
    if (firstCore.isNotBlank() && firstCore == secondCore) return EvidenceLevel.NEAR
    if (nearMetadataText(first, second)) return EvidenceLevel.NEAR
    return if (haveDisjointScripts(firstAlbum, secondAlbum)) {
        EvidenceLevel.UNKNOWN
    } else {
        EvidenceLevel.DIFFERENT
    }
}

internal fun titlesMatch(first: String, second: String): Boolean {
    val firstTitle = titleIdentity(first)
    val secondTitle = titleIdentity(second)
    return firstTitle.isValid && secondTitle.isValid &&
        titleEvidence(firstTitle, secondTitle).isConfirmed()
}

internal fun versionEvidence(first: TitleIdentity, second: TitleIdentity): EvidenceLevel = when {
    first.versionQualifiers == second.versionQualifiers -> EvidenceLevel.EXACT
    first.versionQualifiers.isEmpty() || second.versionQualifiers.isEmpty() -> EvidenceLevel.NEAR
    else -> EvidenceLevel.DIFFERENT
}

internal fun titleEvidence(first: TitleIdentity, second: TitleIdentity): EvidenceLevel {
    if (!first.isValid || !second.isValid) return EvidenceLevel.UNKNOWN
    val baseEvidence = when {
        first.base == second.base -> EvidenceLevel.EXACT
        else -> {
            val firstBases = first.alternateBases + first.base
            val secondBases = second.alternateBases + second.base
            when {
                firstBases.any(secondBases::contains) -> EvidenceLevel.NEAR
                firstBases.any { firstBase ->
                    secondBases.any { secondBase ->
                        hasMinimumTextSimilarity(
                            firstBase,
                            secondBase,
                            MINIMUM_TITLE_SIMILARITY_PERCENT
                        )
                    }
                } -> EvidenceLevel.NEAR
                haveDisjointScripts(first.base, second.base) -> EvidenceLevel.UNKNOWN
                else -> EvidenceLevel.DIFFERENT
            }
        }
    }
    val versionEvidence = versionEvidence(first, second)
    return when {
        baseEvidence == EvidenceLevel.DIFFERENT ||
            versionEvidence == EvidenceLevel.DIFFERENT -> EvidenceLevel.DIFFERENT
        baseEvidence == EvidenceLevel.UNKNOWN -> EvidenceLevel.UNKNOWN
        baseEvidence == EvidenceLevel.NEAR ||
            versionEvidence == EvidenceLevel.NEAR -> EvidenceLevel.NEAR
        else -> EvidenceLevel.EXACT
    }
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
    val base = normalizeText(normalized.replace(BRACKET_PATTERN, " "))
    val alternateBases = bracketIdentities.asSequence()
        .filter { it.featuredArtists.isEmpty() && it.versionQualifiers.isEmpty() }
        .map { normalizeText(it.content) }
        .filter(String::isNotBlank)
        .filter { alternate -> haveDisjointScripts(base, alternate) }
        .toSet()
    return TitleIdentity(
        base = base,
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

internal fun artistEvidence(
    query: LyricsLookup,
    candidate: LyricsResult
): EvidenceLevel = artistEvidence(
    query.track,
    query.artist,
    candidate.candidateTrack,
    candidate.candidateArtist
)

internal fun artistEvidence(
    firstTrack: String,
    firstArtist: String,
    secondTrack: String,
    secondArtist: String
): EvidenceLevel {
    if (!isValidArtist(firstArtist) || !isValidArtist(secondArtist)) {
        return EvidenceLevel.UNKNOWN
    }
    val first = artistIdentity(firstTrack, firstArtist)
    val second = artistIdentity(secondTrack, secondArtist)
    if (first.effective == second.effective) return EvidenceLevel.EXACT
    if (first.declared == second.declared) return EvidenceLevel.NEAR

    if (first.effective.containsAll(second.effective)) {
        val omitted = first.effective - second.effective
        if (omitted.isNotEmpty() && omitted.all(first.featured::contains)) {
            return EvidenceLevel.NEAR
        }
    }
    if (second.effective.containsAll(first.effective)) {
        val omitted = second.effective - first.effective
        if (omitted.isNotEmpty() && omitted.all(second.featured::contains)) {
            return EvidenceLevel.NEAR
        }
    }
    if (hasCrossScriptArtistDisplayName(first.declared, second.declared)) {
        return EvidenceLevel.NEAR
    }
    if (first.effective.any { firstName ->
            second.effective.any { secondName ->
                hasMinimumTextSimilarity(
                    firstName,
                    secondName,
                    MINIMUM_ARTIST_SIMILARITY_PERCENT
                )
            }
        }
    ) {
        return EvidenceLevel.NEAR
    }
    return if (haveDisjointScripts(firstArtist, secondArtist)) {
        EvidenceLevel.UNKNOWN
    } else {
        EvidenceLevel.DIFFERENT
    }
}

internal fun artistIdentity(track: String, artist: String): ArtistIdentity {
    val featured = titleIdentity(track).featuredArtists
    return ArtistIdentity(
        declared = artistNames(artist).toSet(),
        featured = featured
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

private fun hasCrossScriptArtistDisplayName(
    firstNames: Set<String>,
    secondNames: Set<String>
): Boolean = firstNames.any { firstName ->
    secondNames.any { secondName ->
        val firstSegments = artistScriptSegments(firstName)
        val secondSegments = artistScriptSegments(secondName)
        (firstSegments.size > 1 && secondName in firstSegments) ||
            (secondSegments.size > 1 && firstName in secondSegments)
    }
}

private fun artistScriptSegments(value: String): Set<String> {
    val segments = linkedSetOf<String>()
    val current = StringBuilder()
    var currentScript: Character.UnicodeScript? = null
    normalizeText(value).forEach { character ->
        val script = Character.UnicodeScript.of(character.code).takeUnless {
            it == Character.UnicodeScript.COMMON ||
                it == Character.UnicodeScript.INHERITED
        }
        if (script != null && currentScript != null && script != currentScript) {
            current.toString().takeIf(String::isNotBlank)?.let(segments::add)
            current.setLength(0)
        }
        current.append(character)
        if (script != null) currentScript = script
    }
    current.toString().takeIf(String::isNotBlank)?.let(segments::add)
    return segments
}

private fun nearMetadataText(first: String, second: String): Boolean {
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

private fun hasMinimumTextSimilarity(
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

private fun haveDisjointScripts(first: String, second: String): Boolean {
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
private const val MINIMUM_TITLE_SIMILARITY_PERCENT = 85
private const val MINIMUM_ARTIST_SIMILARITY_PERCENT = 90
private const val MINIMUM_ALBUM_SIMILARITY_PERCENT = 80
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
