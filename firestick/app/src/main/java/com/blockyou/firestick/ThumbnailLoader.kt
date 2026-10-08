package com.blockyou.firestick

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Carregador de miniaturas simples: baixa com o OkHttp e guarda em cache na memória. */
object ThumbnailLoader {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(url: String?, target: ImageView) {
        target.tag = url
        target.setImageDrawable(null)
        if (url.isNullOrEmpty()) return

        cache.get(url)?.let {
            target.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = try {
                val request = okhttp3.Request.Builder().url(url).build()
                OkHttpDownloader.client.newCall(request).execute().use { response ->
                    response.body?.byteStream()?.let(BitmapFactory::decodeStream)
                }
            } catch (e: Exception) {
                null
            } ?: return@execute

            cache.put(url, bitmap)
            mainHandler.post {
                // A view pode ter sido reciclada para outro vídeo enquanto baixávamos
                if (target.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }
}
