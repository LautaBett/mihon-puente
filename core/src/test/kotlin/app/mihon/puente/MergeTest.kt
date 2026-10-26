package app.mihon.puente

import app.mihon.puente.backup.Backup
import app.mihon.puente.backup.BackupCategory
import app.mihon.puente.backup.BackupChapter
import app.mihon.puente.backup.BackupHistory
import app.mihon.puente.backup.BackupManga
import app.mihon.puente.backup.BackupPreference
import app.mihon.puente.backup.BackupSource
import app.mihon.puente.backup.IntPreferenceValue
import app.mihon.puente.backup.StringSetPreferenceValue
import app.mihon.puente.backup.decodeTachibk
import app.mihon.puente.backup.encodeTachibk
import app.mihon.puente.merge.MergeState
import app.mihon.puente.merge.merge
import app.mihon.puente.merge.unsafeReasons
import app.mihon.puente.sync.LocalCanonicalStore
import app.mihon.puente.sync.NamedBackup
import app.mihon.puente.sync.PuenteConfig
import app.mihon.puente.sync.PuenteEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MergeTest {
    @Test
    fun roundtripKeepsLibraryAndPreferenceTypes() {
        val backup = Backup(
            backupManga = listOf(series(notes = "nota", read = true, lastPage = 4, duration = 90)),
            backupCategories = listOf(BackupCategory(name = "Leyendo", order = 1, id = 3)),
            backupSources = listOf(BackupSource(name = "MangaDex", sourceId = 9)),
            backupPreferences = listOf(
                BackupPreference("theme", IntPreferenceValue(2)),
                BackupPreference("langs", StringSetPreferenceValue(setOf("es", "en"))),
            ),
        )
        val decoded = decodeTachibk(encodeTachibk(backup))
        val manga = decoded.backupManga.single()
        assertEquals("One Piece", manga.title)
        assertTrue(manga.chapters.single().read)
        assertEquals(4, manga.chapters.single().lastPageRead)
        assertEquals(90, manga.history.single().readDuration)
        assertEquals(3, decoded.backupCategories.single().id)
        assertEquals(2, (decoded.backupPreferences[0].value as IntPreferenceValue).value)
        assertEquals(setOf("es", "en"), (decoded.backupPreferences[1].value as StringSetPreferenceValue).value)
    }

    @Test
    fun newerUnreadDoesNotEraseReadAndDurationTakesMax() {
        val phone = series(read = true, lastPage = 2, duration = 40, lastRead = 100)
        val mac = series(read = false, lastPage = 5, duration = 10, lastRead = 50)
        val merged = merge(MergeState(Backup(backupManga = listOf(phone))), Backup(backupManga = listOf(mac)), incomingAt = 2_000)
        val chapter = merged.state.backup.backupManga.single().chapters.single()
        val history = merged.state.backup.backupManga.single().history.single()
        assertTrue(chapter.read)
        assertEquals(5, chapter.lastPageRead)
        assertEquals(40, history.readDuration)
        assertEquals(100, history.lastRead)
        assertEquals(1, merged.chaptersProgressed)
    }

    @Test
    fun newerNotesWinAndBlankNotesDoNotErase() {
        val current = series(notes = "en el teléfono")
        val withNotes = merge(
            MergeState(Backup(backupManga = listOf(current))),
            Backup(backupManga = listOf(series(notes = "en la mac"))),
            incomingAt = 5_000,
        )
        assertEquals("en la mac", withNotes.state.backup.backupManga.single().notes)

        val blank = merge(
            withNotes.state,
            Backup(backupManga = listOf(series(notes = ""))),
            incomingAt = 9_000,
        )
        assertEquals("en la mac", blank.state.backup.backupManga.single().notes)
    }

    @Test
    fun olderNotesDoNotReplaceNewerOnes() {
        val first = merge(MergeState(), Backup(backupManga = listOf(series(notes = "nueva"))), incomingAt = 5_000)
        val second = merge(first.state, Backup(backupManga = listOf(series(notes = "vieja"))), incomingAt = 1_000)
        assertEquals("nueva", second.state.backup.backupManga.single().notes)
        assertFalse(second.libraryChanged)
    }

    @Test
    fun missingSeriesStaysAndFavoriteTimestampCanRemoveIt() {
        val kept = merge(
            MergeState(Backup(backupManga = listOf(series(url = "/a", title = "Alpha"), series(url = "/b", title = "Beta")))),
            Backup(backupManga = listOf(series(url = "/a", title = "Alpha"))),
            incomingAt = 2_000,
        )
        assertEquals(setOf("/a", "/b"), kept.state.backup.backupManga.map { it.url }.toSet())

        val removed = merge(
            kept.state,
            Backup(backupManga = listOf(series(url = "/b", title = "Beta", favorite = false, favoriteAt = 1_800_000_000))),
            incomingAt = 1_000,
        )
        val beta = removed.state.backup.backupManga.first { it.url == "/b" }
        assertFalse(beta.favorite)
    }

    @Test
    fun categoriesWithDifferentIdsMergeByName() {
        val canonical = Backup(
            backupCategories = listOf(BackupCategory("Leyendo", order = 1, id = 3)),
            backupManga = listOf(series(categoryIds = listOf(3))),
        )
        val incoming = Backup(
            backupCategories = listOf(BackupCategory("Leyendo", order = 4, id = 7), BackupCategory("Hecho", order = 2, id = 3)),
            backupManga = listOf(series(categoryIds = listOf(7))),
        )
        val merged = merge(MergeState(canonical), incoming, incomingAt = 2_000)
        val leyendo = merged.state.backup.backupCategories.first { it.name == "Leyendo" }
        val hecho = merged.state.backup.backupCategories.first { it.name == "Hecho" }
        assertEquals(3, leyendo.id)
        assertEquals(4, leyendo.order)
        assertTrue(hecho.id != 3L)
        assertEquals(listOf(3L), merged.state.backup.backupManga.single().categories)
    }

    @Test
    fun secondMergeOfTheSameBackupChangesNothing() {
        val incoming = Backup(
            backupManga = listOf(series(read = true, notes = "ok")),
            backupCategories = listOf(BackupCategory("Leyendo", id = 1)),
            backupPreferences = listOf(BackupPreference("theme", IntPreferenceValue(1))),
        )
        val first = merge(MergeState(), incoming, incomingAt = 2_000)
        val second = merge(first.state, incoming, incomingAt = 2_000)
        assertTrue(first.state.backup.backupPreferences.isEmpty())
        assertFalse(second.libraryChanged)
        assertFalse(second.clocksChanged)
    }

    @Test
    fun importWaitsWhenLocalBackupIsAhead() {
        val shared = series(read = false)
        val local = series(read = true)
        val reasons = unsafeReasons(Backup(backupManga = listOf(local)), Backup(backupManga = listOf(shared)))
        assertTrue(reasons.any { "leído" in it })
    }

    @Test
    fun engineIngestsOnceAndOffersImport() {
        val directory = kotlin.io.path.createTempDirectory("puente").toFile()
        val engine = PuenteEngine(PuenteConfig(), directory, platform = "mac")
        val bytes = encodeTachibk(
            Backup(
                backupManga = listOf(series(read = true)),
                backupCategories = listOf(BackupCategory("Leyendo", id = 1)),
            ),
        )
        val file = NamedBackup("app.mihon_2026-10-26_09-00.tachibk", bytes, modifiedAtMillis = 1_000)
        val first = engine.synchronize(listOf(file))
        assertTrue(first.lines.any { it.startsWith("Incorporado") })
        assertEquals("puente-importar.tachibk", first.importName)
        val offered = decodeTachibk(first.importBytes!!)
        assertTrue(offered.backupManga.single().chapters.single().read)
        assertTrue(offered.backupPreferences.isEmpty())
        engine.markOffered(first.importSha!!)
        val second = engine.synchronize(listOf(file))
        assertTrue(second.lines.isEmpty())
        assertNull(second.importBytes)
        val stored = LocalCanonicalStore(directory.resolve("local")).load()
        assertEquals(1, stored.libraryGeneration)
    }
}

private fun series(
    url: String = "/one-piece",
    title: String = "One Piece",
    notes: String = "",
    read: Boolean = false,
    lastPage: Long = 0,
    duration: Long = 0,
    lastRead: Long = 0,
    favorite: Boolean = true,
    favoriteAt: Long? = null,
    categoryIds: List<Long> = emptyList(),
) = BackupManga(
    source = 9,
    url = url,
    title = title,
    notes = notes,
    favorite = favorite,
    favoriteModifiedAt = favoriteAt,
    categories = categoryIds,
    chapters = listOf(
        BackupChapter(url = "/ch-1", name = "Capítulo 1", read = read, lastPageRead = lastPage),
    ),
    history = listOf(BackupHistory(url = "/ch-1", lastRead = lastRead, readDuration = duration)),
)
