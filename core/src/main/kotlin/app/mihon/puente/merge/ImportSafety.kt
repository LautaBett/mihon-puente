package app.mihon.puente.merge

import app.mihon.puente.backup.Backup

fun unsafeReasons(local: Backup, canonical: Backup): List<String> {
    val canon = canonical.backupManga.associateBy { mangaKey(it.source, it.url) }
    val reasons = mutableListOf<String>()
    for (manga in local.backupManga) {
        val title = manga.title.ifBlank { manga.url }
        val other = canon[mangaKey(manga.source, manga.url)]
        if (other == null) {
            reasons += "Falta «$title» en la copia compartida."
            continue
        }
        val chapters = other.chapters.associateBy { it.url }
        for (chapter in manga.chapters) {
            val remote = chapters[chapter.url]
            val label = chapter.name.ifBlank { chapter.url }
            if (remote == null) {
                if (chapter.read || chapter.lastPageRead > 0) {
                    reasons += "Falta el capítulo $label de «$title»."
                }
                continue
            }
            if (chapter.read && !remote.read) {
                reasons += "«$title» / $label está leído en este aparato y no en la copia."
            }
            if (chapter.lastPageRead > remote.lastPageRead && !remote.read) {
                reasons += "«$title» / $label va más adelante en este aparato."
            }
        }
        val history = other.history.associateBy { it.url }
        for (item in manga.history) {
            val remote = history[item.url]
            if (remote == null && (item.lastRead > 0 || item.readDuration > 0)) {
                reasons += "Falta historial de «$title»."
            } else if (remote != null && item.lastRead > remote.lastRead) {
                reasons += "El historial de «$title» es más reciente en este aparato."
            } else if (remote != null && item.readDuration > remote.readDuration) {
                reasons += "El tiempo de lectura de «$title» es mayor en este aparato."
            }
        }
    }
    return reasons.distinct().take(8)
}
