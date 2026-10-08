package com.blockyou.firestick

import android.util.Log
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Faz as requisições de mídia ao googlevideo.com do jeito que o YouTube espera
 * (mesma lógica do YoutubeHttpDataSource do NewPipe):
 * - POST com corpo "x\0" em vez de GET;
 * - faixa de bytes no parâmetro `&range=` em vez do header Range;
 * - parâmetro `&rn=` com o número sequencial da requisição;
 * - User-Agent compatível com o cliente que gerou a URL.
 */
class YoutubeStreamInterceptor : Interceptor {
    private val requestNumber = AtomicInteger(0)
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.host.endsWith("googlevideo.com")) return chain.proceed(original)
        val url = original.url.toString()

        // "bytes=100-" ou "bytes=100-199"
        val range = original.header("Range")?.removePrefix("bytes=")
        val newUrl = buildString {
            append(url)
            if (range != null) append("&range=").append(range)
            append("&rn=").append(requestNumber.incrementAndGet())
        }

        val builder = original.newBuilder()
            .url(newUrl)
            .removeHeader("Range")
            .post(byteArrayOf(0x78, 0).toRequestBody())
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                    YoutubeParsingHelper.getVisionOsUserAgent(null)
                } else {
                    OkHttpDownloader.USER_AGENT
                },
            )
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com")
        }

        val response = chain.proceed(builder.build())
        if (!response.isSuccessful) {
            val u = original.url
            Log.w(
                "BlockYou",
                "HTTP ${response.code} itag=${u.queryParameter("itag")} c=${u.queryParameter("c")} " +
                    "range=$range rn=${requestNumber.get()} expire=${u.queryParameter("expire")}",
            )
        }
        // Com &range= o servidor responde 200, mas o ExoPlayer espera 206 em pedidos parciais
        // (com 200 ele descartaria de novo os bytes iniciais).
        return if (range != null && !range.startsWith("0-") && response.code == 200) {
            response.newBuilder().code(206).build()
        } else {
            response
        }
    }
}
