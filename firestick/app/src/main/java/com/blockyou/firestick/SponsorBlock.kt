package com.blockyou.firestick

import android.util.Log
import org.json.JSONArray
import java.net.URLEncoder
import java.security.MessageDigest

/** Trecho a pular, em milissegundos. */
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Cliente da API do SponsorBlock (https://sponsor.ajay.app).
 * Usa a consulta por prefixo do hash do ID: o servidor nunca sabe qual vídeo exato estamos vendo.
 */
object SponsorBlock {
    private const val API = "https://sponsor.ajay.app/api/skipSegments"
    private const val TAG = "BlockYou"

    /** Categorias puladas automaticamente. */
    private val CATEGORIES = listOf(
        "sponsor",     // patrocínio
        "selfpromo",   // autopromoção (loja, curso do próprio canal)
        "interaction", // "deixa o like e se inscreve"
    )

    /** Busca os trechos do vídeo; em caso de erro devolve lista vazia (o vídeo toca normalmente). */
    fun fetchSegments(videoId: String): List<SkipSegment> = try {
        val hashPrefix = sha256(videoId).take(4)
        val categories = URLEncoder.encode(JSONArray(CATEGORIES).toString(), "UTF-8")
        val request = okhttp3.Request.Builder()
            .url("$API/$hashPrefix?categories=$categories")
            .build()

        OkHttpDownloader.client.newCall(request).execute().use { response ->
            // 404 = nenhum vídeo com esse prefixo tem trechos marcados
            if (!response.isSuccessful) return emptyList()
            parse(response.body?.string().orEmpty(), videoId)
        }
    } catch (e: Exception) {
        Log.w(TAG, "SponsorBlock indisponível", e)
        emptyList()
    }

    private fun parse(json: String, videoId: String): List<SkipSegment> {
        val videos = JSONArray(json)
        for (i in 0 until videos.length()) {
            val video = videos.getJSONObject(i)
            if (video.getString("videoID") != videoId) continue

            val segments = video.getJSONArray("segments")
            return (0 until segments.length())
                .map { segments.getJSONObject(it) }
                .filter { it.optString("actionType", "skip") == "skip" }
                .map {
                    val range = it.getJSONArray("segment")
                    SkipSegment(
                        startMs = (range.getDouble(0) * 1000).toLong(),
                        endMs = (range.getDouble(1) * 1000).toLong(),
                        category = it.getString("category"),
                    )
                }
                .sortedBy { it.startMs }
        }
        return emptyList()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
