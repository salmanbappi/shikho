package eu.kanade.tachiyomi.animeextension.all.shikho

import eu.kanade.tachiyomi.animesource.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response

class Shikho : AnimeHttpSource() {

    override val name = "Shikho"

    override val baseUrl = "https://app.shikho.com"

    override val lang = "all"

    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/student/home")

    override fun popularAnimeParse(response: Response): AnimesPage {
        // TODO: Implement
        return AnimesPage(emptyList(), false)
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        // TODO: Implement
        return emptyList()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url)

    override fun videoListParse(response: Response): List<Video> {
        // TODO: Implement
        return emptyList()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        // TODO: Implement
        return AnimesPage(emptyList(), false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)
    
    override fun animeDetailsParse(response: Response): SAnime {
        // TODO: Implement
         return SAnime.create()
    }
}