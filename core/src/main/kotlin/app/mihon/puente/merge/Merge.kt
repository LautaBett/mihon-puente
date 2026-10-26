package app.mihon.puente.merge

import app.mihon.puente.backup.Backup
import app.mihon.puente.backup.BackupCategory
import app.mihon.puente.backup.BackupChapter
import app.mihon.puente.backup.BackupHistory
import app.mihon.puente.backup.BackupManga
import app.mihon.puente.backup.BackupSource
import app.mihon.puente.backup.BackupTracking
import kotlinx.serialization.Serializable

@Serializable
data class Clocks(
    val manga: Map<String, Long> = emptyMap(),
    val chapters: Map<String, Long> = emptyMap(),
    val categories: Map<String, Long> = emptyMap(),
    val sources: Map<String, Long> = emptyMap(),
)

data class MergeState(
    val backup: Backup = Backup(),
    val clocks: Clocks = Clocks(),
)

data class MergeOutcome(
    val state: MergeState,
    val libraryChanged: Boolean,
    val clocksChanged: Boolean,
    val mangaAdded: Int,
    val mangaUpdated: Int,
    val chaptersAdded: Int,
    val chaptersProgressed: Int,
)

fun merge(canonical: MergeState, incoming: Backup, incomingAt: Long): MergeOutcome {
    val base = stripDeviceSettings(canonical.backup)
    val mangaClocks = canonical.clocks.manga.toMutableMap()
    val chapterClocks = canonical.clocks.chapters.toMutableMap()
    val categoryClocks = canonical.clocks.categories.toMutableMap()
    val sourceClocks = canonical.clocks.sources.toMutableMap()

    val (categories, categoryIds) = mergeCategories(
        base.backupCategories,
        incoming.backupCategories,
        incomingAt,
        categoryClocks,
    )
    val sources = mergeSources(base.backupSources, incoming.backupSources, incomingAt, sourceClocks)
    val manga = mergeManga(
        base.backupManga,
        incoming.backupManga,
        incomingAt,
        categoryIds,
        mangaClocks,
        chapterClocks,
    )
    val merged = Backup(
        backupManga = manga,
        backupCategories = categories,
        backupSources = sources,
    )
    val clocks = Clocks(mangaClocks, chapterClocks, categoryClocks, sourceClocks)
    val hadDeviceSettings = hasDeviceSettings(canonical.backup)
    return MergeOutcome(
        state = MergeState(merged, clocks),
        libraryChanged = hadDeviceSettings || !sameLibrary(base, merged),
        clocksChanged = clocks != canonical.clocks,
        mangaAdded = countAdded(base, merged),
        mangaUpdated = countUpdated(base, merged),
        chaptersAdded = countChaptersAdded(base, merged),
        chaptersProgressed = countProgressed(base, merged),
    )
}

fun stripDeviceSettings(backup: Backup) = backup.copy(
    backupPreferences = emptyList(),
    backupSourcePreferences = emptyList(),
    backupExtensionStores = emptyList(),
)

private fun hasDeviceSettings(backup: Backup) =
    backup.backupPreferences.isNotEmpty() ||
        backup.backupSourcePreferences.isNotEmpty() ||
        backup.backupExtensionStores.isNotEmpty()

private fun mergeCategories(
    canonical: List<BackupCategory>,
    incoming: List<BackupCategory>,
    incomingAt: Long,
    clocks: MutableMap<String, Long>,
): Pair<List<BackupCategory>, Map<Long, Long>> {
    val result = canonical.toMutableList()
    val usedIds = result.map { it.id }.toMutableSet()
    val remap = mutableMapOf<Long, Long>()
    var nextId = (usedIds.maxOrNull() ?: 0L) + 1
    for (inc in incoming.distinctBy { it.name }) {
        val index = result.indexOfFirst { it.name == inc.name }
        if (index >= 0) {
            val current = result[index]
            remap[inc.id] = current.id
            val clock = clocks[inc.name] ?: 0L
            if (incomingAt >= clock) {
                result[index] = current.copy(order = inc.order, flags = inc.flags)
                clocks[inc.name] = incomingAt
            }
        } else {
            var id = inc.id
            if (id in usedIds) {
                while (nextId in usedIds) nextId++
                id = nextId++
            }
            usedIds += id
            remap[inc.id] = id
            result += inc.copy(id = id)
            clocks[inc.name] = incomingAt
        }
    }
    return result to remap
}

private fun mergeSources(
    canonical: List<BackupSource>,
    incoming: List<BackupSource>,
    incomingAt: Long,
    clocks: MutableMap<String, Long>,
): List<BackupSource> {
    val byId = canonical.associateBy { it.sourceId }.toMutableMap()
    for (source in incoming) {
        val key = source.sourceId.toString()
        val clock = clocks[key] ?: 0L
        val existing = byId[source.sourceId]
        if (existing == null) {
            byId[source.sourceId] = source
            clocks[key] = incomingAt
        } else if (incomingAt >= clock && source.name.isNotBlank()) {
            byId[source.sourceId] = existing.copy(name = source.name)
            clocks[key] = incomingAt
        }
    }
    return byId.values.sortedBy { it.sourceId }
}

private fun mergeManga(
    canonical: List<BackupManga>,
    incoming: List<BackupManga>,
    incomingAt: Long,
    categoryIds: Map<Long, Long>,
    mangaClocks: MutableMap<String, Long>,
    chapterClocks: MutableMap<String, Long>,
): List<BackupManga> {
    val byKey = LinkedHashMap<String, BackupManga>()
    canonical.forEach { byKey[mangaKey(it.source, it.url)] = it }
    val incomingByKey = LinkedHashMap<String, BackupManga>()
    incoming.forEach { incomingByKey[mangaKey(it.source, it.url)] = it }
    for (inc in incomingByKey.values) {
        val key = mangaKey(inc.source, inc.url)
        val current = byKey[key]
        if (current == null) {
            byKey[key] = inc.copy(categories = remapCategories(inc.categories, categoryIds))
            mangaClocks[key] = incomingAt
            inc.chapters.forEach { chapterClocks[chapterKey(inc.source, inc.url, it.url)] = incomingAt }
        } else {
            val clock = mangaClocks[key] ?: 0L
            val takeEdits = incomingAt >= clock
            val (favorite, favoriteAt) = chooseFavorite(current, inc, incomingAt, clock)
            val edited = if (takeEdits) {
                current.copy(
                    title = inc.title.ifBlank { current.title },
                    artist = inc.artist ?: current.artist,
                    author = inc.author ?: current.author,
                    description = inc.description ?: current.description,
                    genre = inc.genre.ifEmpty { current.genre },
                    status = if (inc.status != 0) inc.status else current.status,
                    thumbnailUrl = inc.thumbnailUrl ?: current.thumbnailUrl,
                    viewer = inc.viewer,
                    categories = remapCategories(inc.categories, categoryIds),
                    chapterFlags = inc.chapterFlags,
                    viewerFlags = inc.viewerFlags ?: current.viewerFlags,
                    updateStrategy = inc.updateStrategy,
                    excludedScanlators = inc.excludedScanlators.ifEmpty { current.excludedScanlators },
                    notes = inc.notes.ifBlank { current.notes },
                    nextUpdate = inc.nextUpdate,
                    memo = if (inc.memo.isNotEmpty()) inc.memo else current.memo,
                )
            } else {
                current
            }
            val withChapters = edited.copy(
                favorite = favorite,
                favoriteModifiedAt = favoriteAt,
                dateAdded = earliest(current.dateAdded, inc.dateAdded),
                initialized = current.initialized || inc.initialized,
                chapters = mergeChapters(current, inc, incomingAt, chapterClocks),
                history = mergeHistory(current.history, inc.history),
                tracking = mergeTracking(current.tracking, inc.tracking, takeEdits),
            )
            byKey[key] = withChapters
            if (takeEdits) mangaClocks[key] = incomingAt
        }
    }
    return byKey.values.toList()
}

private fun mergeChapters(
    current: BackupManga,
    incoming: BackupManga,
    incomingAt: Long,
    clocks: MutableMap<String, Long>,
): List<BackupChapter> {
    val byUrl = LinkedHashMap<String, BackupChapter>()
    current.chapters.forEach { byUrl[it.url] = it }
    for (chapter in incoming.chapters) {
        val key = chapterKey(current.source, current.url, chapter.url)
        val existing = byUrl[chapter.url]
        val clock = clocks[key] ?: 0L
        if (existing == null) {
            byUrl[chapter.url] = chapter
            clocks[key] = incomingAt
        } else {
            val base = if (incomingAt >= clock) chapter else existing
            byUrl[chapter.url] = base.copy(
                read = existing.read || chapter.read,
                bookmark = if (incomingAt >= clock) chapter.bookmark else existing.bookmark,
                lastPageRead = maxOf(existing.lastPageRead, chapter.lastPageRead),
                memo = when {
                    incomingAt >= clock && chapter.memo.isNotEmpty() -> chapter.memo
                    existing.memo.isNotEmpty() -> existing.memo
                    else -> chapter.memo
                },
            )
            if (incomingAt >= clock) clocks[key] = incomingAt
        }
    }
    return byUrl.values.toList()
}

private fun mergeHistory(current: List<BackupHistory>, incoming: List<BackupHistory>): List<BackupHistory> {
    val byUrl = LinkedHashMap<String, BackupHistory>()
    current.forEach { byUrl[it.url] = it }
    for (item in incoming) {
        val existing = byUrl[item.url]
        byUrl[item.url] = if (existing == null) {
            item
        } else {
            existing.copy(
                lastRead = maxOf(existing.lastRead, item.lastRead),
                readDuration = maxOf(existing.readDuration, item.readDuration),
            )
        }
    }
    return byUrl.values.toList()
}

private fun mergeTracking(
    current: List<BackupTracking>,
    incoming: List<BackupTracking>,
    takeEdits: Boolean,
): List<BackupTracking> {
    val bySync = current.associateBy { it.syncId }.toMutableMap()
    for (track in incoming) {
        val existing = bySync[track.syncId]
        bySync[track.syncId] = when {
            existing == null -> track
            takeEdits -> track.copy(lastChapterRead = maxOf(existing.lastChapterRead, track.lastChapterRead))
            else -> existing.copy(lastChapterRead = maxOf(existing.lastChapterRead, track.lastChapterRead))
        }
    }
    return bySync.values.sortedBy { it.syncId }
}

private fun chooseFavorite(
    current: BackupManga,
    incoming: BackupManga,
    incomingAt: Long,
    clock: Long,
): Pair<Boolean, Long?> {
    val currentAt = current.favoriteModifiedAt.epochMillis()
    val incomingAtFavorite = incoming.favoriteModifiedAt.epochMillis()
    return when {
        currentAt != null && incomingAtFavorite != null && incomingAtFavorite >= currentAt ->
            incoming.favorite to incoming.favoriteModifiedAt
        currentAt != null && incomingAtFavorite != null ->
            current.favorite to current.favoriteModifiedAt
        incomingAtFavorite != null && currentAt == null ->
            incoming.favorite to incoming.favoriteModifiedAt
        incomingAt >= clock ->
            incoming.favorite to (incoming.favoriteModifiedAt ?: current.favoriteModifiedAt)
        else -> current.favorite to current.favoriteModifiedAt
    }
}

private fun Long?.epochMillis(): Long? {
    if (this == null || this <= 0L) return null
    return if (this < 1_000_000_000_000L) this * 1000 else this
}

private fun earliest(left: Long, right: Long) = when {
    left <= 0L -> right
    right <= 0L -> left
    else -> minOf(left, right)
}

private fun remapCategories(ids: List<Long>, remap: Map<Long, Long>) =
    ids.mapNotNull { remap[it] }.distinct()

internal fun mangaKey(source: Long, url: String) = "$source\u0001$url"

private fun chapterKey(source: Long, mangaUrl: String, chapterUrl: String) =
    "$source\u0001$mangaUrl\u0001$chapterUrl"

private fun countAdded(before: Backup, after: Backup): Int {
    val keys = before.backupManga.map { mangaKey(it.source, it.url) }.toSet()
    return after.backupManga.count { mangaKey(it.source, it.url) !in keys }
}

private fun countUpdated(before: Backup, after: Backup): Int {
    val previous = before.backupManga.associateBy { mangaKey(it.source, it.url) }
    return after.backupManga.count { manga ->
        val old = previous[mangaKey(manga.source, manga.url)]
        old != null && !sameManga(old, manga)
    }
}

private fun countChaptersAdded(before: Backup, after: Backup): Int {
    val previous = before.backupManga.associateBy { mangaKey(it.source, it.url) }
    return after.backupManga.sumOf { manga ->
        val known = previous[mangaKey(manga.source, manga.url)]?.chapters?.map { it.url }?.toSet().orEmpty()
        manga.chapters.count { it.url !in known }
    }
}

private fun countProgressed(before: Backup, after: Backup): Int {
    val previous = before.backupManga.associateBy { mangaKey(it.source, it.url) }
    var count = 0
    for (manga in after.backupManga) {
        val old = previous[mangaKey(manga.source, manga.url)] ?: continue
        val oldChapters = old.chapters.associateBy { it.url }
        val oldHistory = old.history.associateBy { it.url }
        for (chapter in manga.chapters) {
            val was = oldChapters[chapter.url] ?: continue
            val history = manga.history.firstOrNull { it.url == chapter.url }
            val wasHistory = oldHistory[chapter.url]
            val progressed = (!was.read && chapter.read) ||
                chapter.lastPageRead > was.lastPageRead ||
                (history != null && wasHistory != null && history.lastRead > wasHistory.lastRead) ||
                (history != null && wasHistory != null && history.readDuration > wasHistory.readDuration)
            if (progressed) count++
        }
    }
    return count
}

internal fun sameLibrary(left: Backup, right: Backup): Boolean {
    if (!sameCategories(left.backupCategories, right.backupCategories)) return false
    if (!sameSources(left.backupSources, right.backupSources)) return false
    val rightManga = right.backupManga.associateBy { mangaKey(it.source, it.url) }
    if (left.backupManga.size != right.backupManga.size) return false
    return left.backupManga.all { manga ->
        val other = rightManga[mangaKey(manga.source, manga.url)] ?: return false
        sameManga(manga, other)
    }
}

private fun sameCategories(left: List<BackupCategory>, right: List<BackupCategory>): Boolean {
    if (left.size != right.size) return false
    val byName = right.associateBy { it.name }
    return left.all { category ->
        val other = byName[category.name] ?: return false
        category.id == other.id && category.order == other.order && category.flags == other.flags
    }
}

private fun sameSources(left: List<BackupSource>, right: List<BackupSource>): Boolean {
    if (left.size != right.size) return false
    val byId = right.associateBy { it.sourceId }
    return left.all { source -> byId[source.sourceId] == source }
}

private fun sameManga(left: BackupManga, right: BackupManga): Boolean {
    if (left.source != right.source || left.url != right.url) return false
    if (left.title != right.title || left.artist != right.artist || left.author != right.author) return false
    if (left.description != right.description || left.genre != right.genre || left.status != right.status) return false
    if (left.thumbnailUrl != right.thumbnailUrl || left.dateAdded != right.dateAdded) return false
    if (left.viewer != right.viewer || left.categories != right.categories) return false
    if (left.favorite != right.favorite || left.chapterFlags != right.chapterFlags) return false
    if (left.viewerFlags != right.viewerFlags || left.updateStrategy != right.updateStrategy) return false
    if (left.favoriteModifiedAt != right.favoriteModifiedAt) return false
    if (left.excludedScanlators != right.excludedScanlators || left.notes != right.notes) return false
    if (left.initialized != right.initialized || left.nextUpdate != right.nextUpdate) return false
    if (!left.memo.contentEquals(right.memo)) return false
    if (!sameChapters(left.chapters, right.chapters)) return false
    if (!sameHistory(left.history, right.history)) return false
    return sameTracking(left.tracking, right.tracking)
}

private fun sameChapters(left: List<BackupChapter>, right: List<BackupChapter>): Boolean {
    if (left.size != right.size) return false
    val byUrl = right.associateBy { it.url }
    return left.all { chapter ->
        val other = byUrl[chapter.url] ?: return false
        chapter.name == other.name &&
            chapter.scanlator == other.scanlator &&
            chapter.read == other.read &&
            chapter.bookmark == other.bookmark &&
            chapter.lastPageRead == other.lastPageRead &&
            chapter.dateFetch == other.dateFetch &&
            chapter.dateUpload == other.dateUpload &&
            chapter.chapterNumber == other.chapterNumber &&
            chapter.sourceOrder == other.sourceOrder &&
            chapter.memo.contentEquals(other.memo)
    }
}

private fun sameHistory(left: List<BackupHistory>, right: List<BackupHistory>): Boolean {
    if (left.size != right.size) return false
    val byUrl = right.associateBy { it.url }
    return left.all { item -> byUrl[item.url] == item }
}

private fun sameTracking(left: List<BackupTracking>, right: List<BackupTracking>): Boolean {
    if (left.size != right.size) return false
    val bySync = right.associateBy { it.syncId }
    return left.all { track -> bySync[track.syncId] == track }
}
