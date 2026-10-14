package tachiyomi.data.manga

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.chapter.ChapterMapper
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import java.util.Date
import kotlin.random.Random

// KMK -->
/** Verifies the library aggregates, cached or live, against a Kotlin recomputation. */
class MangaChapterStatsTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: Database
    private lateinit var handler: AndroidDatabaseHandler

    private val scanlators = listOf(null, "alpha", "beta", "gamma")

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        db = Database(
            driver = driver,
            historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        handler = AndroidDatabaseHandler(db = db, driver = driver)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `library matches a live recount with and without cached rows`() {
        val rng = Random(20260725)
        seedLibrary(count = 12)

        repeat(400) {
            applyRandomWrite(rng)
            // Rows the write dropped are aggregated live.
            assertAggregatesMatch()
            refill()
            hasMissing() shouldBe false
            assertAggregatesMatch()
        }
    }

    @Test
    fun `refill caches every library entry except merged ones`() {
        seedLibrary(count = 4)
        val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        insertChapter(ids[0], scanlator = null, read = true, bookmark = false)
        hasMissing() shouldBe true

        refill()

        // Entries without chapters get a zero row; the merge parent gets none.
        statsRowCount() shouldBe 3
        hasMissing() shouldBe false
        assertAggregatesMatch()
    }

    @Test
    fun `merged entry aggregates use its own scanlator exclusions`() {
        seedLibrary(count = 3)
        val (child1, child2, parent) = db.mangasQueries.getAll(MangaMapper::mapManga)
            .executeAsList().map { it.id }

        insertChapterNumbered(child1, number = 1.0, scanlator = "alpha", read = true)
        insertChapterNumbered(child1, number = 2.0, scanlator = null, read = false)
        insertChapterNumbered(child2, number = 1.0, scanlator = "beta", read = true)
        insertChapterNumbered(child2, number = 3.0, scanlator = null, read = false)

        // Excluding a scanlator on the merged entry filters its children's chapters...
        db.excluded_scanlatorsQueries.insert(parent, "alpha")
        libraryRow(parent).totalChapters shouldBe 3
        libraryRow(parent).readCount shouldBe 1

        // ...but an exclusion configured on a child no longer leaks into the aggregate.
        db.excluded_scanlatorsQueries.insert(child2, "beta")
        libraryRow(parent).totalChapters shouldBe 3
        // Each child's own row still honors its own exclusions.
        libraryRow(child1).totalChapters shouldBe 2
        libraryRow(child2).totalChapters shouldBe 1
    }

    @Test
    fun `merged entry aggregates dedupe chapters per sort mode`() {
        seedLibrary(count = 3)
        val (child1, child2, parent) = db.mangasQueries.getAll(MangaMapper::mapManga)
            .executeAsList().map { it.id }

        insertChapterNumbered(child1, number = 1.0, scanlator = "alpha", read = true)
        insertChapterNumbered(child1, number = 2.0, scanlator = null, read = false)
        insertChapterNumbered(child2, number = 1.0, scanlator = "beta", read = true)
        insertChapterNumbered(child2, number = 3.0, scanlator = null, read = false)

        val selfRowId = db.mergedQueries.selectByMergeId(parent).executeAsList()
            .first { it.manga_source == MERGED_SOURCE_ID }._id
        fun setSortMode(mode: Long) {
            db.mergedQueries.updateSettingsById(
                getChapterUpdates = null,
                downloadChapters = null,
                infoManga = null,
                chapterPriority = null,
                chapterSortMode = mode,
                id = selfRowId,
            )
        }

        // Mode 1: duplicate chapter numbers keep the lower-priority source (child1).
        setSortMode(1)
        libraryRow(parent).totalChapters shouldBe 3
        libraryRow(parent).readCount shouldBe 1

        // Mode 2: keep only the source with the most chapters (tie -> lower manga id).
        setSortMode(2)
        libraryRow(parent).totalChapters shouldBe 2

        // Mode 3: keep only the source with the highest chapter number.
        setSortMode(3)
        libraryRow(parent).totalChapters shouldBe 2
        libraryRow(parent).readCount shouldBe 1
    }

    @Test
    fun `writes the aggregates ignore keep the cached row`() {
        seedLibrary(count = 1)
        val mangaId = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsOne().id
        insertChapter(mangaId, scanlator = null, read = false, bookmark = false)
        refill()
        val chapter = chaptersOf(mangaId).single()

        // Reader page progress.
        db.chaptersQueries.update(
            mangaId = null, url = null, name = null, scanlator = null,
            read = null, bookmark = null, lastPageRead = 7, chapterNumber = null,
            sourceOrder = null, dateFetch = null, dateUpload = null,
            chapterId = chapter.id, version = null, isSyncing = 0, memo = null,
        )
        // Rewriting a value it already has.
        db.chaptersQueries.update(
            mangaId = null, url = null, name = null, scanlator = null,
            read = false, bookmark = false, lastPageRead = null, chapterNumber = null,
            sourceOrder = null, dateFetch = null, dateUpload = null,
            chapterId = chapter.id, version = null, isSyncing = 0, memo = null,
        )

        hasMissing() shouldBe false
    }

    @Test
    fun `adding an entry to the library marks it missing`() {
        seedLibrary(count = 1)
        refill()
        val mangaId = insertManga(favorite = false)
        insertChapter(mangaId, scanlator = null, read = true, bookmark = true)
        hasMissing() shouldBe false

        driver.execute(null, "UPDATE mangas SET favorite = 1 WHERE _id = $mangaId", 0)

        hasMissing() shouldBe true
        libraryRow(mangaId).readCount shouldBe 1
        refill()
        libraryRow(mangaId).readCount shouldBe 1
        assertAggregatesMatch()
    }

    @Test
    fun `deleting an entry leaves no orphaned stats`() {
        seedLibrary(count = 4)
        val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        ids.forEach { insertChapter(it, scanlator = "alpha", read = true, bookmark = true) }
        refill()

        ids.forEach { db.mangasQueries.deleteById(it) }

        statsRowCount() shouldBe 0
    }

    @Test
    fun `the library flow refills before it emits`() = runBlocking {
        seedLibrary(count = 4)
        val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        ids.forEach { insertChapter(it, scanlator = null, read = true, bookmark = false) }

        val library = MangaRepositoryImpl(handler).getLibraryMangaAsFlow().first()

        hasMissing() shouldBe false
        library.size shouldBe 4
        assertAggregatesMatch()
    }

    @Test
    fun `a one-shot library read refills too`(): Unit = runBlocking {
        seedLibrary(count = 4)
        val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        ids.forEach { insertChapter(it, scanlator = null, read = true, bookmark = false) }

        MangaRepositoryImpl(handler).getLibraryManga().size shouldBe 4

        hasMissing() shouldBe false
    }

    @Test
    fun `a failing refill still returns the live aggregates`() = runBlocking {
        seedLibrary(count = 4)
        val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        ids.forEach { insertChapter(it, scanlator = null, read = true, bookmark = true) }
        driver.execute(
            null,
            "CREATE TRIGGER fail_refill BEFORE INSERT ON manga_chapter_stats BEGIN SELECT RAISE(ABORT, 'full'); END",
            0,
        )

        MangaRepositoryImpl(handler).getLibraryMangaAsFlow().first().size shouldBe 4
        MangaRepositoryImpl(handler).getLibraryManga().size shouldBe 4

        hasMissing() shouldBe true
        assertAggregatesMatch()
    }

    // ------------------------------------------------------------------ oracle

    private fun assertAggregatesMatch() {
        db.libraryViewQueries.library(MangaMapper::mapLibraryManga).executeAsList().forEach { row ->
            val expected = recompute(row.id)
            withClue(row.id) {
                row.totalChapters shouldBe expected.total
                row.readCount shouldBe expected.read
                row.bookmarkCount shouldBe expected.bookmark
                row.bookmarkReadCount shouldBe expected.bookmarkRead
                row.latestUpload shouldBe expected.latestUpload
                row.chapterFetchedAt shouldBe expected.fetchedAt
                row.lastRead shouldBe expected.lastRead
            }
        }
    }

    private data class Aggregate(
        val total: Long = 0,
        val read: Long = 0,
        val bookmark: Long = 0,
        val bookmarkRead: Long = 0,
        val latestUpload: Long = 0,
        val fetchedAt: Long = 0,
        val lastRead: Long = 0,
    )

    private data class MemberChapter(val mangaId: Long, val chapter: Chapter, val priority: Long)

    /**
     * Mirrors the library query: plain entries aggregate their own chapters filtered by their own
     * excluded scanlators; merged entries aggregate the union of their children's chapters (plus
     * their own, via the self row) filtered by the *merged entry's* excluded scanlators and
     * deduped the way the merged entry displays them.
     */
    private fun recompute(mangaId: Long): Aggregate {
        val mergeRows = db.mergedQueries.selectByMergeId(mangaId).executeAsList()
        val selfRow = mergeRows.firstOrNull { it.manga_source == MERGED_SOURCE_ID }
        if (selfRow == null) {
            return aggregateChapters(listOf(mangaId to 0L), exclusionsOf(mangaId), sortMode = 0L)
        }
        val members = mergeRows
            .map { (it.manga_id ?: mangaId) to it.chapter_priority }
            .distinct()
        return aggregateChapters(members, exclusionsOf(mangaId), selfRow.chapter_sort_mode)
    }

    private fun exclusionsOf(mangaId: Long): Set<String> =
        db.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
            .executeAsList()
            .filterNotNull()
            .toSet()

    private fun aggregateChapters(
        members: List<Pair<Long, Long>>,
        excluded: Set<String>,
        sortMode: Long,
    ): Aggregate {
        val historyByManga = members.map { it.first }.distinct().associateWith { mangaId ->
            db.historyQueries.getHistoryByMangaId(mangaId) { _, chapterId, lastRead, _ ->
                chapterId to (lastRead?.time ?: 0L)
            }.executeAsList().toMap()
        }
        val chapters = members.flatMap { (mangaId, priority) ->
            db.chaptersQueries
                .getChaptersByMangaId(mangaId, 0L, 0L, 0L, ChapterMapper::mapChapter)
                .executeAsList()
                .filter { it.scanlator !in excluded }
                .map { MemberChapter(mangaId, it, priority) }
        }
        val deduped = when (sortMode) {
            // Prefer the lower-priority source on duplicate chapter numbers.
            1L -> chapters.filter { c ->
                c.chapter.chapterNumber < 0 || chapters.none { d ->
                    d.mangaId != c.mangaId &&
                        d.chapter.chapterNumber == c.chapter.chapterNumber &&
                        (d.priority < c.priority || (d.priority == c.priority && d.mangaId < c.mangaId))
                }
            }
            // Keep only the source with the most chapters.
            2L -> {
                val winner = chapters.groupBy { it.mangaId }
                    .maxWithOrNull(compareBy({ it.value.size }, { -it.key }))
                    ?.key
                chapters.filter { it.mangaId == winner }
            }
            // Keep only the source with the highest chapter number.
            3L -> {
                val winner = chapters
                    .maxWithOrNull(compareBy({ it.chapter.chapterNumber }, { -it.mangaId }))
                    ?.mangaId
                chapters.filter { it.mangaId == winner }
            }
            else -> chapters
        }
        var agg = Aggregate()
        deduped.forEach { (mangaId, chapter) ->
            val lastRead = historyByManga.getValue(mangaId)[chapter.id] ?: 0L
            agg = agg.copy(
                total = agg.total + 1,
                read = agg.read + if (chapter.read) 1 else 0,
                bookmark = agg.bookmark + if (chapter.bookmark) 1 else 0,
                bookmarkRead = agg.bookmarkRead + if (chapter.bookmark && chapter.read) 1 else 0,
                latestUpload = maxOf(agg.latestUpload, chapter.dateUpload),
                fetchedAt = maxOf(agg.fetchedAt, chapter.dateFetch),
                lastRead = maxOf(agg.lastRead, lastRead),
            )
        }
        return agg
    }

    // ------------------------------------------------------------------ writes

    private fun applyRandomWrite(rng: Random) {
        val mangaIds = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
        val chapters = mangaIds.flatMap { chaptersOf(it) }

        when (rng.nextInt(10)) {
            0, 1, 2 -> insertChapter(
                mangaIds.random(rng),
                scanlators.random(rng),
                rng.nextBoolean(),
                rng.nextBoolean(),
                rng,
            )
            3, 4 -> chapters.randomOrNull(rng)?.let { chapter ->
                // Match ChapterRepositoryImpl.partialUpdate.
                db.chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = rng.nextBoolean(),
                    bookmark = rng.nextBoolean(),
                    lastPageRead = rng.nextLong(50),
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id,
                    version = null,
                    isSyncing = 0,
                    memo = null,
                )
            }
            5 -> chapters.randomOrNull(rng)?.let { chapter ->
                // Simulate the reader page update.
                db.chaptersQueries.update(
                    mangaId = null, url = null, name = null, scanlator = null,
                    read = null, bookmark = null, lastPageRead = rng.nextLong(50),
                    chapterNumber = null, sourceOrder = null, dateFetch = null, dateUpload = null,
                    chapterId = chapter.id, version = null, isSyncing = 0, memo = null,
                )
            }
            6 -> chapters.randomOrNull(rng)?.let { chapter ->
                db.chaptersQueries.update(
                    mangaId = null, url = null, name = null, scanlator = scanlators.random(rng),
                    read = null, bookmark = null, lastPageRead = null, chapterNumber = null,
                    sourceOrder = null, dateFetch = rng.nextLong(1, 5_000),
                    dateUpload = rng.nextLong(1, 5_000),
                    chapterId = chapter.id, version = null, isSyncing = 0, memo = null,
                )
            }
            7 -> chapters.randomOrNull(rng)?.let { chapter ->
                db.historyQueries.upsert(chapter.id, Date(rng.nextLong(1, 9_000)), rng.nextLong(1, 60))
            }
            8 -> when (rng.nextInt(5)) {
                0 -> db.historyQueries.resetHistoryByMangaIds(listOf(mangaIds.random(rng)))
                1 -> db.historyQueries.removeAllHistory()
                3 -> db.historyQueries.removeResettedHistory()
                2 -> chapters.randomOrNull(rng)?.let { chapter ->
                    // Move the chapter to another entry, which can empty the source.
                    val target = mangaIds.filter { it != chapter.mangaId }.random(rng)
                    moveChapter(chapter.id, target)
                }
                else -> chapters.randomOrNull(rng)
                    ?.let { deleteChapters(listOf(it.id)) }
            }
            else -> {
                val mangaId = mangaIds.random(rng)
                val scanlator = scanlators.filterNotNull().random(rng)
                val current = db.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId)
                    .executeAsList()
                if (scanlator in current) {
                    db.excluded_scanlatorsQueries.remove(mangaId, listOf(scanlator))
                } else {
                    db.excluded_scanlatorsQueries.insert(mangaId, scanlator)
                }
            }
        }
    }

    private fun seedLibrary(count: Int) {
        repeat(count) { i ->
            // The final entry is the merge parent.
            insertManga(favorite = true, source = if (count >= 3 && i == count - 1) MERGED_SOURCE_ID else 1L)
        }
        if (count >= 3) {
            val ids = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().map { it.id }
            val mergeId = ids.last()
            // Add two merge children.
            listOf(ids[0], ids[1]).forEach { childId ->
                db.mergedQueries.insert(
                    infoManga = true,
                    getChapterUpdates = true,
                    chapterSortMode = 0,
                    chapterPriority = 0,
                    downloadChapters = true,
                    mergeId = mergeId,
                    mergeUrl = "/merge",
                    mangaId = childId,
                    mangaUrl = "/manga/$childId",
                    mangaSource = 1,
                )
            }
            // The merged entry's own settings row, as SmartSearchMerge writes it in production.
            db.mergedQueries.insert(
                infoManga = false,
                getChapterUpdates = false,
                chapterSortMode = 0,
                chapterPriority = -1,
                downloadChapters = false,
                mergeId = mergeId,
                mergeUrl = "/merge",
                mangaId = mergeId,
                mangaUrl = "/merge",
                mangaSource = MERGED_SOURCE_ID,
            )
        }
    }

    private fun insertManga(favorite: Boolean, source: Long = 1L): Long {
        val index = db.mangasQueries.getAll(MangaMapper::mapManga).executeAsList().size
        db.mangasQueries.insert(
            source = source,
            url = "/manga/$index",
            artist = null,
            author = null,
            description = null,
            genre = null,
            title = "Manga $index",
            status = 0,
            thumbnailUrl = null,
            favorite = favorite,
            lastUpdate = 0,
            nextUpdate = 0,
            initialized = true,
            viewerFlags = 0,
            chapterFlags = 0,
            coverLastModified = 0,
            dateAdded = 0,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            calculateInterval = 0,
            version = 0,
            notes = "",
            memo = JsonObject(emptyMap()),
        )
        return db.mangasQueries.selectLastInsertedRowId().executeAsOne()
    }

    private fun insertChapter(
        mangaId: Long,
        scanlator: String?,
        read: Boolean,
        bookmark: Boolean,
        rng: Random = Random(0),
        dates: Long? = null,
    ) {
        db.chaptersQueries.insert(
            mangaId = mangaId,
            url = "/chapter/${rng.nextLong()}",
            name = "Chapter",
            scanlator = scanlator,
            read = read,
            bookmark = bookmark,
            lastPageRead = 0,
            chapterNumber = 1.0,
            sourceOrder = 0,
            dateFetch = dates ?: rng.nextLong(1, 5_000),
            dateUpload = dates ?: rng.nextLong(1, 5_000),
            version = 0,
            memo = JsonObject(emptyMap()),
        )
    }

    private fun insertChapterNumbered(
        mangaId: Long,
        number: Double,
        scanlator: String?,
        read: Boolean,
    ) {
        db.chaptersQueries.insert(
            mangaId = mangaId,
            url = "/chapter/$mangaId/$number",
            name = "Chapter $number",
            scanlator = scanlator,
            read = read,
            bookmark = false,
            lastPageRead = 0,
            chapterNumber = number,
            sourceOrder = 0,
            dateFetch = 1000,
            dateUpload = 2000,
            version = 0,
            memo = JsonObject(emptyMap()),
        )
    }

    /** Matches ChapterRepositoryImpl.partialUpdate with a manga id set. */
    private fun moveChapter(chapterId: Long, mangaId: Long) {
        db.chaptersQueries.update(
            mangaId = mangaId, url = null, name = null, scanlator = null,
            read = null, bookmark = null, lastPageRead = null, chapterNumber = null,
            sourceOrder = null, dateFetch = null, dateUpload = null,
            chapterId = chapterId, version = null, isSyncing = 0, memo = null,
        )
    }

    private fun deleteChapters(chapterIds: List<Long>) {
        db.chaptersQueries.removeChaptersWithIds(chapterIds)
    }

    private fun chaptersOf(mangaId: Long) = db.chaptersQueries
        .getChaptersByMangaId(mangaId, 0L, 0L, 0L, ChapterMapper::mapChapter)
        .executeAsList()

    private fun libraryRow(mangaId: Long) = db.libraryViewQueries
        .library(MangaMapper::mapLibraryManga)
        .executeAsList()
        .single { it.id == mangaId }

    private fun refill() {
        db.manga_chapter_statsQueries.refill()
    }

    private fun hasMissing() = db.manga_chapter_statsQueries.hasMissing().executeAsOne()

    private fun statsRowCount(): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM manga_chapter_stats",
        parameters = 0,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0)!!)
        },
    ).value

    private fun <T> withClue(clue: Any, block: () -> T): T = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("manga $clue: ${e.message}", e)
    }

    companion object {
        private const val MERGED_SOURCE_ID = 6969L

        // Manga resolves custom entry info through Injekt as soon as one is built.
        @JvmStatic
        @BeforeAll
        fun registerCustomMangaInfo() {
            Injekt.addSingletonFactory {
                GetCustomMangaInfo(
                    object : CustomMangaRepository {
                        override fun get(mangaId: Long) = null
                        override fun set(mangaInfo: CustomMangaInfo) = Unit
                    },
                )
            }
        }
    }
}
// KMK <--
