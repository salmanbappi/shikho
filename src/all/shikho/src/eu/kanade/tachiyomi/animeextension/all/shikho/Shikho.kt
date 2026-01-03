package eu.kanade.tachiyomi.animeextension.all.shikho

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Shikho : AnimeHttpSource() {

    override val name = "Shikho"

    override val baseUrl = "https://app.shikho.com"

    override val lang = "all"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("X-vendor", "shikho")
        .add("Referer", "$baseUrl/")

    private val json: Json = Injekt.get()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val PREF_PROGRAM_ID = "active_program_id"
    private val PREF_PROGRAM_TITLE = "active_program_title"

    private var activeProgramId: String?
        get() = preferences.getString(PREF_PROGRAM_ID, null)
        set(value) = preferences.edit().putString(PREF_PROGRAM_ID, value).apply()

    private var activeProgramTitle: String?
        get() = preferences.getString(PREF_PROGRAM_TITLE, "Select Program")
        set(value) = preferences.edit().putString(PREF_PROGRAM_TITLE, value).apply()

    override fun popularAnimeRequest(page: Int): Request {
        val programId = activeProgramId
        return if (programId == null) {
            GET("$baseUrl/student/home", headers)
        } else {
            GET("$baseUrl/student/my-courses/$programId?type=academic", headers)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val nextData = document.select("script#__NEXT_DATA__").firstOrNull()?.data()
            ?: throw Exception("Login required in WebView")

        val jsonObject = json.parseToJsonElement(nextData).jsonObject
        val props = jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject

        // Discovery Phase: Check both /student/home and /student/
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("/student/home") || requestUrl.contains("/student/my-courses")) {
            val enrolledPrograms = props?.get("enrolledPrograms")?.jsonArray
                ?: jsonObject["props"]?.jsonObject?.get("initialState")?.jsonObject?.get("enrolledPrograms")?.jsonArray

            if (enrolledPrograms != null && enrolledPrograms.isNotEmpty()) {
                // Find active or pick first
                val activeProgram = enrolledPrograms.firstOrNull {
                    it.jsonObject["is_active"]?.jsonPrimitive?.contentOrNull == "true"
                } ?: enrolledPrograms[0]

                val newId = activeProgram.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                val newTitle = activeProgram.jsonObject["title_bn"]?.jsonPrimitive?.contentOrNull

                if (activeProgramId == null && newId != null) {
                    activeProgramId = newId
                    activeProgramTitle = newTitle
                    // Retry once
                    val newRequest = popularAnimeRequest(1)
                    return popularAnimeParse(client.newCall(newRequest).execute())
                }
            }
        }

        // Parsing Phase: Extract courses
        val courses = mutableListOf<SAnime>()

        // Data can be in enrolledProgramData or academicPrograms
        val enrolledProgramData = props?.get("enrolledProgramData")?.jsonObject
        val phases = enrolledProgramData?.get("phases")?.jsonArray

        if (phases != null) {
            phases.forEach { phase ->
                val subjects = phase.jsonObject["subjects"]?.jsonArray
                subjects?.forEach { sub ->
                    val subjObj = sub.jsonObject
                    courses.add(
                        SAnime.create().apply {
                            title = subjObj["title_bn"]?.jsonPrimitive?.contentOrNull ?: "Unknown Subject"
                            url = "/student/my-courses/$activeProgramId?type=academic&phaseId=${phase.jsonObject["id"]}&subId=${subjObj["id"]}"
                            thumbnail_url = subjObj["image"]?.jsonPrimitive?.contentOrNull
                        },
                    )
                }
            }
        }

        return AnimesPage(courses, false)
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val nextData = document.select("script#__NEXT_DATA__").firstOrNull()?.data() ?: return emptyList()
        val jsonObject = json.parseToJsonElement(nextData).jsonObject
        val props = jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject

        val contentList = props?.get("contentList")?.jsonArray ?: return emptyList()

        return contentList.mapIndexed { index, content ->
            val contentObj = content.jsonObject
            SEpisode.create().apply {
                name = contentObj["title_bn"]?.jsonPrimitive?.contentOrNull ?: "Lesson ${index + 1}"
                url = "/student/watch/${contentObj["id"]?.jsonPrimitive?.contentOrNull}"
                episode_number = index.toFloat()
                date_upload = 0L
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val nextData = document.select("script#__NEXT_DATA__").firstOrNull()?.data() ?: return emptyList()
        val jsonObject = json.parseToJsonElement(nextData).jsonObject
        val props = jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject

        val videoData = props?.get("videoData")?.jsonObject
        val m3u8Url = videoData?.get("video_url")?.jsonPrimitive?.contentOrNull ?: return emptyList()

        return listOf(Video(m3u8Url, "Default", m3u8Url))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            return GET("$baseUrl/search?term=$query&types=academic", headers)
        }

        filters.forEach { filter ->
            when (filter) {
                is ProgramFilter -> {
                    val programId = filter.vals[filter.state].id
                    val programTitle = filter.vals[filter.state].name
                    activeProgramId = programId
                    activeProgramTitle = programTitle
                }
                else -> {}
            }
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return popularAnimeRequest(page)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return popularAnimeParse(response)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val nextData = document.select("script#__NEXT_DATA__").firstOrNull()?.data()
        val anime = SAnime.create()

        if (nextData != null) {
            val jsonObject = json.parseToJsonElement(nextData).jsonObject
            val props = jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject
            val enrolledProgramData = props?.get("enrolledProgramData")?.jsonObject

            anime.title = enrolledProgramData?.get("title_bn")?.jsonPrimitive?.contentOrNull ?: "Unknown"
            anime.description = enrolledProgramData?.get("description")?.jsonPrimitive?.contentOrNull
            anime.genre = "Education"
            anime.status = SAnime.COMPLETED
        }

        return anime
    }

    // --- Filters ---

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Current: $activeProgramTitle"),
        ProgramFilter("Switch Program", getProgramList()),
    )

    private class ProgramFilter(name: String, val vals: Array<Program>) :
        AnimeFilter.Select<String>(name, vals.map { it.name }.toTypedArray())

    private data class Program(val name: String, val id: String)

    private fun getProgramList(): Array<Program> {
        // This should ideally be populated from a previously cached discovery
        // For now, we'll return a default if discovery hasn't happened
        return arrayOf(Program(activeProgramTitle ?: "Default", activeProgramId ?: ""))
    }
}
