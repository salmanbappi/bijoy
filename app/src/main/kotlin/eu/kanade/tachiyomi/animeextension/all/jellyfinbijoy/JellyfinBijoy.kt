package eu.kanade.tachiyomi.animeextension.all.jellyfinbijoy

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelFlatMap
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.get
import extensions.utils.parseAs
import extensions.utils.toJsonBody
import extensions.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.apache.commons.text.StringSubstitutor
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

@Serializable(with = ItemTypeSerializer::class)
enum class ItemType {
    BoxSet, Movie, Season, Series, Episode, Other;
    companion object {
        fun fromString(value: String): ItemType = entries.find { it.name.equals(value, ignoreCase = true) } ?: Other
    }
}

object ItemTypeSerializer : KSerializer<ItemType> {
    override val descriptor = PrimitiveSerialDescriptor("ItemType", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ItemType) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder) = ItemType.fromString(decoder.decodeString())
}

@Serializable data class ItemListDto(val items: List<ItemDto>, val totalRecordCount: Int)
@Serializable data class ItemDto(
    val name: String, val type: ItemType, val id: String, val locationType: String, val imageTags: ImageDto,
    val collectionType: String? = null, val seriesId: String? = null, val seriesName: String? = null,
    val seasonName: String? = null, val seriesPrimaryImageTag: String? = null, val status: String? = null,
    val overview: String? = null, val genres: List<String>? = null, val studios: List<StudioDto>? = null,
    val originalTitle: String? = null, val sortName: String? = null, val indexNumber: Int? = null,
    val premiereDate: String? = null, val runTimeTicks: Long? = null, val dateCreated: String? = null,
    val mediaSources: List<MediaDto>? = null
) {
    @Serializable data class ImageDto(val primary: String? = null)
    @Serializable class StudioDto(val name: String)
    fun toSAnime(baseUrl: String, userId: String): SAnime = SAnime.create().apply {
        val typeMap = mapOf(ItemType.Season to "seriesId,$seriesId", ItemType.Movie to "movie", ItemType.BoxSet to "boxSet", ItemType.Series to "series")
        url = baseUrl.toHttpUrl().newBuilder().addPathSegment("Users").addPathSegment(userId).addPathSegment("Items").addPathSegment(id).fragment(typeMap[type]).build().toString()
        thumbnail_url = imageTags.primary?.getImageUrl(baseUrl, id)
        title = name
        description = overview?.let { Jsoup.parseBodyFragment(it.replace("<br>", "br2n")).text().replace("br2n", "\n") }
        genre = genres?.joinToString(", ")
        author = studios?.joinToString(", ") { it.name }
        status = if (type == ItemType.Movie) SAnime.COMPLETED else this@ItemDto.status.parseStatus()
        if (type == ItemType.Season) {
            if (locationType == "Virtual") {
                title = seriesName ?: "Season"
                seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
            } else { title = "$seriesName $name" }
            if (imageTags.primary == null) seriesId?.let { thumbnail_url = seriesPrimaryImageTag?.getImageUrl(baseUrl, it) }
        }
    }
    private fun String?.parseStatus(): Int = when (this?.lowercase()) { "ended" -> SAnime.COMPLETED; "continuing" -> SAnime.ONGOING; else -> SAnime.UNKNOWN }
    fun toSEpisode(baseUrl: String, userId: String, prefix: String, epDetails: Set<String>, episodeTemplate: String): SEpisode = SEpisode.create().apply {
        val runtimeInSec = runTimeTicks?.div(10_000_000); val size = mediaSources?.firstOrNull()?.size?.formatBytes(); val runTime = runtimeInSec?.formatSeconds()
        val epTitle = buildString { append(prefix); if (type != ItemType.Movie) append(this@ItemDto.name) }
        val values = mapOf("title" to epTitle, "originalTitle" to (originalTitle ?: ""), "sortTitle" to (sortName ?: ""), "type" to type.name, "typeShort" to type.name.replace("Episode", "Ep."), "seriesTitle" to (seriesName ?: ""), "seasonTitle" to (seasonName ?: ""), "number" to (indexNumber?.toString() ?: ""), "createdDate" to (dateCreated?.substringBefore("T") ?: ""), "releaseDate" to (premiereDate?.substringBefore("T") ?: ""), "size" to (size ?: ""), "sizeBytes" to (mediaSources?.firstOrNull()?.size?.toString() ?: ""), "runtime" to (runTime ?: ""), "runtimeS" to (runtimeInSec?.toString() ?: ""))
        val sub = StringSubstitutor(values, "{", "}")
        val extraInfo = buildList { if (epDetails.contains("Overview") && overview != null && type == ItemType.Episode) add(overview); if (epDetails.contains("Size") && size != null) add(size); if (epDetails.contains("Runtime") && runTime != null) add(runTime) }
        name = sub.replace(episodeTemplate).trim().removeSuffix("-").removePrefix("-").trim()
        url = "$baseUrl/Users/$userId/Items/$id"
        scanlator = extraInfo.joinToString(" • ")
        premiereDate?.let { date_upload = parseDateTime(it) }
        indexNumber?.let { episode_number = it.toFloat() }
        if (type == ItemType.Movie) episode_number = 1F
    }
    private fun Long.formatSeconds(): String { val minutes = this / 60; val hours = minutes / 60; val rs = this % 60; val rm = minutes % 60; return "${if(hours>0)"${hours}h " else ""}${if(rm>0)"${rm}m " else ""}${rs}s".trim() }
    private fun parseDateTime(date: String) = try { FORMATTER_DATE_TIME.parse(date.removeSuffix("Z"))!!.time } catch (_: Exception) { 0L }
    companion object { private val FORMATTER_DATE_TIME = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH) }
}
@Serializable data class LoginDto(val accessToken: String, val sessionInfo: LoginSessionDto) { @Serializable data class LoginSessionDto(val userId: String) }
@Serializable data class PlaybackInfoDto(val userId: String, val isPlayback: Boolean, val mediaSourceId: String, val maxStreamingBitrate: Long, val enableTranscoding: Boolean, val audioStreamIndex: String? = null, val subtitleStreamIndex: String? = null, val alwaysBurnInSubtitleWhenTranscoding: Boolean, val deviceProfile: DeviceProfileDto)
@Serializable data class DeviceProfileDto(val name: String, val maxStreamingBitrate: Long, val maxStaticBitrate: Long, val musicStreamingTranscodingBitrate: Long, val transcodingProfiles: List<ProfileDto>, val directPlayProfiles: List<ProfileDto>, val responseProfiles: List<ProfileDto>, val containerProfiles: List<ProfileDto>, val codecProfiles: List<ProfileDto>, val subtitleProfiles: List<SubtitleProfileDto>) {
    @Serializable data class ProfileDto(val type: String, val container: String? = null, val protocol: String? = null, val audioCodec: String? = null, val videoCodec: String? = null, val codec: String? = null, val maxAudioChannels: String? = null, val conditions: List<ProfileConditionDto>? = null) { 
        @Serializable data class ProfileConditionDto(val condition: String, val property: String, val value: String)
    }
    @Serializable data class SubtitleProfileDto(val format: String, val method: String)
}
@Serializable data class SessionDto(val mediaSources: List<MediaDto>, val playSessionId: String)
@Serializable data class MediaDto(val size: Long? = null, val id: String? = null, val bitrate: Long? = null, val transcodingUrl: String? = null, val supportsTranscoding: Boolean, val supportsDirectStream: Boolean, val mediaStreams: List<MediaStreamDto>) { 
    @Serializable data class MediaStreamDto(val codec: String, val index: Int, val type: String, val supportsExternalStream: Boolean, val isExternal: Boolean, val language: String? = null, val displayTitle: String? = null, val bitRate: Long? = null)
}

fun Long.formatBytes(): String = when {
    this >= 1_000_000_000L -> "%.2f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000L -> "%.2f MB".format(this / 1_000_000.0)
    this >= 1_000L -> "%.2f KB".format(this / 1_000.0)
    this > 1L -> "$this bytes"
    this == 1L -> "$this byte"
    else -> ""
}
fun String.getImageUrl(baseUrl: String, id: String): String = baseUrl.toHttpUrl().newBuilder().addPathSegment("Items").addPathSegment(id).addPathSegment("Images").addPathSegment("Primary").addQueryParameter("tag", this).build().toString()
object PascalCaseToCamelCase : JsonNamingStrategy { override fun serialNameForJson(descriptor: SerialDescriptor, elementIndex: Int, serialName: String): String = serialName.replaceFirstChar { it.uppercase() } }
fun getAuthHeader(deviceInfo: JellyfinBijoy.DeviceInfo, token: String? = null): String {
    val params = listOf("Client" to deviceInfo.clientName, "Version" to deviceInfo.version, "DeviceId" to deviceInfo.id, "Device" to deviceInfo.name, "Token" to token)
    return params.filterNot { it.second == null }.joinToString(separator = ", ", prefix = "MediaBrowser ", transform = { "${it.first}=\"" + URLEncoder.encode(it.second!!.trim().replace("\n", " "), "UTF-8") + "\"" })
}

class JellyfinBijoy : Source(), UnmeteredSource {
    override val baseUrl = "http://10.20.30.50"
    override val name = "Bijoy"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 73658291047123456L

    override val json = Json { isLenient = true; ignoreUnknownKeys = true; namingStrategy = PascalCaseToCamelCase }
    private val deviceInfo by lazy { getDeviceInfo(Injekt.get<Application>()) }

    private var accessToken: String by preferences.delegate("access_token", "")
    private var userId: String by preferences.delegate("user_id", "")

    override val client = network.client.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("AuthenticateByName")) return@addInterceptor chain.proceed(request)
            if (accessToken.isBlank()) runBlockingLogin()
            val authRequest = request.newBuilder().addHeader("Authorization", getAuthHeader(deviceInfo, accessToken)).build()
            chain.proceed(authRequest)
        }.build()

    private fun runBlockingLogin() {
        val authHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo))
        val body = buildJsonObject { put("Username", "bijoy"); put("Pw", "") }.toRequestBody(json)
        try {
            val resp = network.client.newCall(POST("$baseUrl/Users/AuthenticateByName", authHeaders, body)).execute()
            if (resp.isSuccessful) {
                val loginDto = resp.parseAs<LoginDto>(json)
                accessToken = loginDto.accessToken
                userId = loginDto.sessionInfo.userId
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearchAnime(page, "", AnimeFilterList())
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply { addQueryParameter("SortBy", "DateCreated,SortName"); addQueryParameter("SortOrder", "Descending") }.build()
        return parseItemsPage(url, page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("SearchTerm", query)
            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> if (filter.toValue().isNotBlank()) setQueryParameter("ParentId", filter.toValue())
                    is SortFilter -> {
                        setQueryParameter("SortBy", filter.toSortValue())
                        setQueryParameter("SortOrder", if (filter.isAscending()) "Ascending" else "Descending")
                    }
                    else -> {}
                }
            }
        }.build()
        return parseItemsPage(url, page)
    }

    private suspend fun parseItemsPage(url: HttpUrl, page: Int): AnimesPage {
        val items = client.newCall(GET(url)).await().parseAs<ItemListDto>(json)
        val animeList = items.items.map { it.toSAnime(baseUrl, userId) }
        return AnimesPage(animeList, 20 * page < items.totalRecordCount)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val item = client.newCall(GET(anime.url)).await().parseAs<ItemDto>(json)
        return item.toSAnime(baseUrl, userId)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.toHttpUrl()
        val itemId = url.pathSegments.last()
        val frag = url.fragment ?: ""
        val epUrl = when {
            frag.startsWith("series") -> "$baseUrl/Shows/$itemId/Episodes?Fields=DateCreated,OriginalTitle,SortName"
            else -> anime.url
        }
        val resp = client.newCall(GET(epUrl)).await()
        val items = if (epUrl.contains("Episodes")) resp.parseAs<ItemListDto>(json).items else listOf(resp.parseAs<ItemDto>(json))
        return items.map { it.toSEpisode(baseUrl, userId, "", emptySet(), "{number} - {title}") }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.newCall(GET(episode.url)).await().parseAs<ItemDto>(json)
        val mediaSource = item.mediaSources?.firstOrNull() ?: return emptyList()
        val videoHeaders = Headers.headersOf("Authorization", getAuthHeader(deviceInfo, accessToken))
        val staticUrl = "$baseUrl/Videos/${item.id}/stream?static=True"
        return listOf(Video(staticUrl, "Source", staticUrl, headers = videoHeaders))
    }

    private fun getItemsUrl(startIndex: Int): HttpUrl = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
        addQueryParameter("StartIndex", startIndex.toString()); addQueryParameter("Limit", "20"); addQueryParameter("Recursive", "true")
        addQueryParameter("IncludeItemTypes", "Movie,Series"); addQueryParameter("ImageTypeLimit", "1"); addQueryParameter("EnableImageTypes", "Primary")
    }.build()

    data class DeviceInfo(val clientName: String, val version: String, val id: String, val name: String)
    private fun getDeviceInfo(context: Application): DeviceInfo {
        val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { preferences.edit().putString("device_id", it).apply() }
        return DeviceInfo("Aniyomi", "1.0.0", deviceId, Build.MODEL)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    // Filters
    override fun getFilterList() = AnimeFilterList(
        CategoryFilter(),
        SortFilter()
    )

    private class CategoryFilter : AnimeFilter.Select<String>("Category", arrayOf("All", "Movies (Anime)", "Movies (Asian)", "Movies (English)", "Movies (Foreign)", "Movies (Indian)", "TV Shows (Asian)", "TV Shows (English)", "TV Shows (Indian)")) {
        private val ids = arrayOf("", "9403711afa65061e9967086eac702a66", "27dcfec804e37e6fb3104d8f631ea57f", "6bb2c3ec9d67c18652f0dab47bd9ee2e", "ac15933b5a8f0721ce5f929f1cc3668e", "76c327c29a53c7380a05858d7c871402", "2dfef46d25ad65cf8fc4b0d882567a25", "58b107e1ff3124b9a07ffb3501cc89f2", "83f92a8e94d2e1a200fa9d5399a801f6")
        fun toValue() = ids[state]
    }
    private class SortFilter : AnimeFilter.Sort("Sort by", arrayOf("Name", "Date Added", "Premiere Date"), Selection(0, false)) {
        private val sortables = arrayOf("SortName", "DateCreated", "ProductionYear")
        fun toSortValue() = sortables[state!!.index]
        fun isAscending() = state!!.ascending
    }

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }
}
