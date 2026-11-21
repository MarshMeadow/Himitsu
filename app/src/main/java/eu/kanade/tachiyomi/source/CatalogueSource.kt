package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.util.lang.awaitSingle
import rx.Observable

interface CatalogueSource : MangaSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     */
    fun fetchPopularManga(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = throw UnsupportedOperationException()

    /**
     * Returns an observable containing a page with a list of latest manga updates.
     *
     * @param page the page number to retrieve.
     */
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()

    /**
     * [1.x API] Get a page with a list of popular manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).awaitSingle()

    /**
     * [1.x API] Get a page with a list of manga matching the search query.
     */
    @Suppress("DEPRECATION")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchSearchManga(page, query, filters).awaitSingle()

    /**
     * [1.x API] Get a page with a list of latest manga updates.
     */
    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).awaitSingle()

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList
}
