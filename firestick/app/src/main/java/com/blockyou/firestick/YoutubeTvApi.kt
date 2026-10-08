package com.blockyou.firestick

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

data class Account(val name: String, val email: String, val photoUrl: String?)

/** Uma página de vídeos da conta; [continuation] busca a próxima (null = acabou). */
data class FeedPage(val items: List<StreamInfoItem>, val continuation: String?)

/**
 * Chamadas autenticadas à API interna do YouTube (InnerTube) como o app de TV (TVHTML5).
 * O login só vale para o cliente de TV, por isso não usamos o NewPipeExtractor aqui.
 */
object YoutubeTvApi {
    const val SUBSCRIPTIONS = "FEsubscriptions"
    const val HISTORY = "FEhistory"
    const val LIKED = "VLLL"        // playlist "Vídeos curtidos"
    const val WATCH_LATER = "VLWL"  // playlist "Assistir mais tarde"
    const val PLAYLISTS = "FEplaylist_aggregation" // playlists da conta

    private const val API = "https://www.youtube.com/youtubei/v1"
    private const val CLIENT_VERSION = "7.20260901.15.00"
    private const val PUBLIC_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8" // chave do app de TV, usada sem login
    private val JSON = "application/json".toMediaType()

    @Volatile
    private var cachedVisitorData: String? = null

    /** Identificador de "visitante" que o YouTube espera nas chamadas; lido uma vez do youtube.com/tv. */
    private fun visitorData(): String? {
        cachedVisitorData?.let { return it }
        return try {
            val request = Request.Builder().url("https://www.youtube.com/tv")
                .header("User-Agent", YoutubeAuth.TV_USER_AGENT).build()
            OkHttpDownloader.client.newCall(request).execute().use { response ->
                Regex(""""visitorData":"([^"]+)"""").find(response.body?.string().orEmpty())?.groupValues?.get(1)
            }?.also { cachedVisitorData = it }
        } catch (e: Exception) {
            null
        }
    }

    fun browse(context: Context, browseId: String, continuation: String? = null): FeedPage {
        val body = baseBody()
        if (continuation != null) body.put("continuation", continuation) else body.put("browseId", browseId)
        val json = post(context, "$API/browse?prettyPrint=false", body)

        val items = mutableListOf<StreamInfoItem>()
        collectTiles(json, items)
        return FeedPage(items.distinctBy { it.url }, findContinuation(json))
    }

    /**
     * Página de tema do Explorar (Música, Notícias...): uma fileira por prateleira, com título.
     * Funciona sem login. Carrega até [maxPages] páginas (as páginas seguintes trazem mais fileiras).
     */
    fun browseShelves(context: Context, browseId: String, maxPages: Int = 3): List<HomeRow> {
        val rows = mutableListOf<HomeRow>()
        var continuation: String? = null
        for (page in 0 until maxPages) {
            val body = baseBody()
            if (continuation != null) body.put("continuation", continuation) else body.put("browseId", browseId)
            val json = post(context, "$API/browse?prettyPrint=false", body)
            collectShelves(json, rows)
            continuation = findContinuation(json) ?: break
        }
        return rows
    }

    private fun collectShelves(node: Any?, out: MutableList<HomeRow>) {
        when (node) {
            is JSONObject -> {
                val shelf = node.optJSONObject("shelfRenderer")
                if (shelf != null) {
                    val items = mutableListOf<StreamInfoItem>()
                    collectTiles(shelf, items)
                    val header = shelf.optJSONObject("headerRenderer")?.optJSONObject("shelfHeaderRenderer")
                    val title = text(header?.optJSONObject("avatarLockup")?.optJSONObject("avatarLockupRenderer")?.optJSONObject("title"))
                        .ifEmpty { text(header?.optJSONObject("title")) }
                        .ifEmpty { text(shelf.optJSONObject("title")) }
                    if (items.isNotEmpty()) out += HomeRow(title, items.distinctBy { it.url })
                    return // não desce: as fileiras não ficam umas dentro das outras
                }
                node.keys().forEach { collectShelves(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectShelves(node.opt(i), out)
        }
    }

    fun account(context: Context): Account? {
        val body = baseBody().put(
            "accountReadMask",
            JSONObject().put("returnOwner", true).put("returnBrandAccounts", true).put("returnPersonaAccounts", false),
        )
        val json = post(context, "$API/account/accounts_list?prettyPrint=false", body)
        val accounts = json.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("accountSectionListRenderer")?.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("accountItemSectionRenderer")?.optJSONArray("contents") ?: return null

        val items = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it)?.optJSONObject("accountItem") }
        val selected = items.firstOrNull { it.optBoolean("isSelected") } ?: items.firstOrNull() ?: return null
        return Account(
            name = text(selected.optJSONObject("accountName")),
            email = text(selected.optJSONObject("accountByline")),
            photoUrl = selected.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")?.let { thumbs ->
                thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")
            },
        )
    }

    private fun baseBody(): JSONObject {
        val client = JSONObject()
            .put("clientName", "TVHTML5")
            .put("clientVersion", CLIENT_VERSION)
            .put("userAgent", YoutubeAuth.TV_USER_AGENT)
            .put("tvAppInfo", JSONObject().put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION"))
            .put("hl", "pt")
            .put("gl", "BR")
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("racyCheckOk", true)
            .put("contentCheckOk", true)
    }

    /** Com login usa o token da conta; sem login, a chave pública do app de TV. */
    private fun post(context: Context, url: String, body: JSONObject): JSONObject {
        val token = YoutubeAuth.accessToken(context)
        val visitor = visitorData()
        visitor?.let { body.getJSONObject("context").getJSONObject("client").put("visitorData", it) }

        val builder = Request.Builder()
            .header("User-Agent", YoutubeAuth.TV_USER_AGENT)
            .header("Referer", "https://www.youtube.com/tv")
            .post(body.toString().toRequestBody(JSON))
        visitor?.let { builder.header("X-Goog-Visitor-Id", it) }
        if (token != null) {
            builder.url(url).header("Authorization", "Bearer $token")
        } else {
            builder.url("$url&key=$PUBLIC_KEY")
        }
        val request = builder.build()
        OkHttpDownloader.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    /**
     * As respostas da TV são árvores grandes (abas, grades, prateleiras); em vez de seguir
     * um caminho fixo que muda com frequência, procuramos todos os "tileRenderer" de vídeo.
     */
    private fun collectTiles(node: Any?, out: MutableList<StreamInfoItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("tileRenderer")?.let { tile -> parseTile(tile)?.let(out::add) }
                node.keys().forEach { key -> if (key != "tileRenderer") collectTiles(node.opt(key), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collectTiles(node.opt(i), out)
        }
    }

    private fun parseTile(tile: JSONObject): StreamInfoItem? = when (tile.optString("contentType")) {
        "TILE_CONTENT_TYPE_VIDEO" -> parseVideoTile(tile)
        "TILE_CONTENT_TYPE_PLAYLIST" -> parsePlaylistTile(tile)
        else -> null
    }

    /** Playlist: vira um item com URL de playlist; o selo mostra a quantidade ("49 vídeos"). */
    private fun parsePlaylistTile(tile: JSONObject): StreamInfoItem? {
        val command = tile.optJSONObject("onSelectCommand") ?: return null
        val playlistId = command.optJSONObject("browseEndpoint")?.optString("browseId")?.removePrefix("VL")
            ?: command.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")
            ?: command.optJSONObject("watchEndpoint")?.optString("playlistId")
        if (playlistId.isNullOrEmpty()) return null

        val metadata = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val header = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val item = StreamInfoItem(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/playlist?list=$playlistId",
            text(metadata?.optJSONObject("title")),
            StreamType.NONE,
        )
        item.uploaderName = firstLine(metadata)
        item.duration = -1
        item.shortDescription = header?.optJSONArray("thumbnailOverlays")?.let { overlays ->
            (0 until overlays.length()).firstNotNullOfOrNull { i ->
                overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                    ?.optJSONObject("text")?.let(::text)?.takeIf { it.isNotEmpty() }
            }
        }
        item.thumbnails = thumbnails(header)
        return item
    }

    private fun parseVideoTile(tile: JSONObject): StreamInfoItem? {
        val command = tile.optJSONObject("onSelectCommand") ?: return null
        val isShort = command.has("reelWatchEndpoint") || tile.optString("style") == "TILE_STYLE_YTLR_SHORTS"
        val videoId = (command.optJSONObject("watchEndpoint") ?: command.optJSONObject("reelWatchEndpoint"))
            ?.optString("videoId")?.takeIf { it.isNotEmpty() } ?: return null

        val metadata = tile.optJSONObject("metadata")?.optJSONObject("tileMetadataRenderer")
        val header = tile.optJSONObject("header")?.optJSONObject("tileHeaderRenderer")
        val title = text(metadata?.optJSONObject("title"))

        val item = StreamInfoItem(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/watch?v=$videoId",
            title,
            StreamType.VIDEO_STREAM,
        )
        item.uploaderName = firstLine(metadata)
        item.duration = parseDuration(header)
        item.isShortFormContent = isShort
        item.thumbnails = thumbnails(header)
        return item
    }

    /** Primeira linha de metadados do cartão: normalmente o nome do canal. */
    private fun firstLine(metadata: JSONObject?): String =
        metadata?.optJSONArray("lines")?.optJSONObject(0)
            ?.optJSONObject("lineRenderer")?.optJSONArray("items")?.optJSONObject(0)
            ?.optJSONObject("lineItemRenderer")?.optJSONObject("text")?.let(::text).orEmpty()

    private fun thumbnails(header: JSONObject?): List<Image> {
        val thumbs = header?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return emptyList()
        return (0 until thumbs.length()).mapNotNull { i ->
            thumbs.optJSONObject(i)?.let {
                Image(it.getString("url"), it.optInt("height", -1), it.optInt("width", -1), Image.ResolutionLevel.UNKNOWN)
            }
        }
    }

    /** "12:34" ou "1:02:03" → segundos; ao vivo/sem duração → -1. */
    private fun parseDuration(header: JSONObject?): Long {
        val overlays = header?.optJSONArray("thumbnailOverlays") ?: return -1
        for (i in 0 until overlays.length()) {
            val status = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayTimeStatusRenderer") ?: continue
            val parts = text(status.optJSONObject("text")).split(":").mapNotNull { it.trim().toLongOrNull() }
            if (parts.isNotEmpty()) return parts.fold(0L) { acc, part -> acc * 60 + part }
        }
        return -1
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("nextContinuationData")?.optString("continuation")
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
                node.keys().forEach { key -> findContinuation(node.opt(key))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findContinuation(node.opt(i))?.let { return it }
        }
        return null
    }

    /** Texto do YouTube: {"simpleText": "..."} ou {"runs": [{"text": "..."}]}. */
    private fun text(obj: JSONObject?): String {
        if (obj == null) return ""
        obj.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = obj.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
}
