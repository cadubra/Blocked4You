package com.blockyou.firestick

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Tela do player: recebe a URL do vídeo, extrai os streams e toca sem anúncios. */
class PlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var qualityButton: Button
    private lateinit var audioButton: Button
    private var player: ExoPlayer? = null

    private lateinit var videoUrl: String
    private var queue = emptyList<String>() // URLs dos vídeos; um só quando não é playlist
    private var queueIndex = 0
    private var recoveries = 0
    private var qualityApplied = false
    private var appliedByApp: TrackSelectionParameters? = null
    private var segments = emptyList<SkipSegment>()
    private val handler = Handler(Looper.getMainLooper())
    private val skipChecker = object : Runnable {
        override fun run() {
            checkSkip()
            handler.postDelayed(this, SKIP_CHECK_INTERVAL_MS)
        }
    }

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(
            OkHttpDownloader.client.newBuilder()
                .addInterceptor(YoutubeStreamInterceptor())
                .build(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        status = findViewById(R.id.status)
        qualityButton = findViewById(R.id.quality_button)

        audioButton = findViewById(R.id.audio_button)
        val playerButtons = findViewById<View>(R.id.player_buttons)

        qualityButton.setOnClickListener { showQualityDialog() }
        audioButton.setOnClickListener { showAudioDialog() }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> playerButtons.visibility = visibility },
        )

        val playlistUrl = intent.getStringExtra(EXTRA_PLAYLIST)
        if (playlistUrl != null) {
            loadPlaylist(playlistUrl)
        } else {
            queue = listOf(intent.getStringExtra(EXTRA_URL) ?: return finish())
            playQueueItem(0)
        }
    }

    /** Playlist (ou mix): busca a lista de vídeos e toca um atrás do outro. */
    private fun loadPlaylist(url: String) {
        status.text = getString(R.string.loading)
        Thread {
            try {
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                val videos = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.url }
                Log.i(TAG, "Playlist \"${info.name}\": ${videos.size} vídeos")
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (videos.isEmpty()) {
                        status.text = getString(R.string.no_results)
                    } else {
                        queue = videos
                        playQueueItem(0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao carregar a playlist $url", e)
                runOnUiThread { status.text = getString(R.string.error, e.message) }
            }
        }.start()
    }

    private fun playQueueItem(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        videoUrl = queue[index]
        // Estado que é de cada vídeo
        releasePlayer()
        recoveries = 0
        qualityApplied = false
        appliedByApp = null
        segments = emptyList()
        status.visibility = View.VISIBLE
        loadVideo(videoUrl)
    }

    private fun hasNext() = queueIndex < queue.size - 1
    private fun hasPrevious() = queueIndex > 0

    private fun releasePlayer() {
        handler.removeCallbacks(skipChecker)
        player?.release()
        player = null
    }

    /**
     * O ExoPlayer só conhece o vídeo atual (cada vídeo precisa ser extraído na hora);
     * este embrulho faz os botões ⏮ ⏭ do player e do controle andarem pela nossa fila.
     */
    private fun queueAwarePlayer(exo: ExoPlayer): Player = object : ForwardingPlayer(exo) {
        override fun isCommandAvailable(command: Int) = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> hasNext()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addIf(COMMAND_SEEK_TO_NEXT, hasNext())
            .addIf(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext())
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        override fun hasNextMediaItem() = hasNext()
        override fun hasPreviousMediaItem() = hasPrevious()
        override fun seekToNext() = seekToNextMediaItem()
        override fun seekToNextMediaItem() = playQueueItem(queueIndex + 1)

        // Como no YouTube: depois de 3 s, "anterior" volta ao começo do vídeo atual
        override fun seekToPrevious() {
            if (currentPosition > 3000 || !hasPrevious()) seekTo(0) else playQueueItem(queueIndex - 1)
        }

        override fun seekToPreviousMediaItem() = seekToPrevious()
    }

    private fun loadVideo(url: String) {
        status.text = getString(R.string.loading)

        // SponsorBlock em paralelo com a extração, para não atrasar o início do vídeo
        Thread {
            val videoId = ServiceList.YouTube.streamLHFactory.getId(url)
            val found = SponsorBlock.fetchSegments(videoId)
            Log.i(TAG, "SponsorBlock: ${found.size} trecho(s) para $videoId")
            runOnUiThread { segments = found }
        }.start()

        extract(url) { info, source -> play(info.name, source) }
    }

    /** Extrai os streams numa thread e entrega a fonte de mídia pronta na thread principal. */
    private fun extract(url: String, onReady: (StreamInfo, MediaSource) -> Unit) {
        Thread {
            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                val source = buildMediaSource(info)
                runOnUiThread { if (!isDestroyed) onReady(info, source) }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao carregar $url", e)
                runOnUiThread {
                    status.text = getString(R.string.error, e.message)
                    status.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /**
     * O servidor de vídeo do YouTube às vezes recusa uma requisição isolada (ex.: HTTP 403).
     * Em vez de mostrar erro, busca links novos e continua do mesmo ponto.
     */
    private fun recover(exo: ExoPlayer) {
        recoveries++
        val position = exo.currentPosition
        Log.w(TAG, "Recuperando reprodução em ${position}ms (tentativa $recoveries)")
        extract(videoUrl) { _, source ->
            qualityApplied = false // as faixas são novas: reaplica a qualidade salva
            exo.setMediaSource(source, position)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun buildMediaSource(info: StreamInfo): MediaSource {
        // Preferido: manifesto DASH com todas as qualidades (troca automática pela velocidade da rede)
        YoutubeDashManifest.build(info)?.let { xml ->
            val manifest = DashManifestParser().parse(Uri.parse(info.url), xml.byteInputStream())
            val sets = manifest.getPeriod(0).adaptationSets
            Log.i(TAG, "DASH com ${sets[0].representations.size} qualidades e ${sets.size - 1} faixa(s) de áudio")
            return DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(manifest, MediaItem.fromUri(info.url))
        }

        // Plano B: melhor vídeo + melhor áudio, qualidade fixa
        val video = info.videoOnlyStreams
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.format == MediaFormat.MPEG_4 && // H.264: suportado por todo Fire TV
                    it.height in 1..(QualityPreference.get(this).takeIf { h -> h > 0 } ?: MAX_HEIGHT)
            }
            .maxByOrNull { it.height }
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }

        if (video != null && audio != null) {
            Log.i(TAG, "Vídeo ${video.resolution} itag=${video.itag} / áudio itag=${audio.itag}")
            val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
            return MergingMediaSource(
                progressive.createMediaSource(MediaItem.fromUri(video.content)),
                progressive.createMediaSource(MediaItem.fromUri(audio.content)),
            )
        }

        // Plano B: lives e vídeos sem streams progressivos
        if (info.hlsUrl.isNotEmpty()) {
            return HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(info.hlsUrl))
        }
        if (info.dashMpdUrl.isNotEmpty()) {
            return DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(info.dashMpdUrl))
        }
        error("Nenhum stream reproduzível encontrado")
    }

    private fun play(title: String, source: MediaSource) {
        // Idioma de áudio salvo: se o vídeo tiver essa dublagem, toca nela; senão, o original
        val trackSelector = DefaultTrackSelector(
            this,
            DefaultTrackSelector.Parameters.Builder(this)
                .setPreferredAudioLanguage(AudioLanguagePreference.get(this))
                .build(),
        )
        val exo = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        var lastParameters = exo.trackSelectionParameters
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Erro no player", error)
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && recoveries < MAX_RECOVERIES) {
                    recover(exo)
                    return
                }
                status.text = getString(R.string.error, error.errorCodeName)
                status.visibility = View.VISIBLE
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.i(TAG, "Resolução atual: ${videoSize.width}x${videoSize.height}")
                updateQualityButton()
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentAudioFormat(tracks)?.let { Log.i(TAG, "Áudio tocando: ${it.language} (${it.label})") }
                updateAudioButton(tracks)
                if (!qualityApplied) {
                    qualityApplied = true
                    applySavedQuality(exo, tracks)
                }
            }

            // Escolhas do usuário (qualidade e idioma do áudio): lembra para os próximos vídeos.
            // Só salva o que mudou, para trocar o áudio não apagar a qualidade escolhida e vice-versa.
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                val previous = lastParameters
                lastParameters = parameters
                if (parameters == appliedByApp) return // mudança feita pelo próprio app, não pelo usuário

                val video = selectedFormat(parameters, C.TRACK_TYPE_VIDEO)
                if (video != selectedFormat(previous, C.TRACK_TYPE_VIDEO)) {
                    val height = video?.height ?: 0
                    QualityPreference.set(this@PlayerActivity, height)
                    Log.i(TAG, "Qualidade preferida: ${if (height == 0) "automática" else "${height}p"}")
                    updateQualityButton()
                }

                val audio = selectedFormat(parameters, C.TRACK_TYPE_AUDIO)
                if (audio != null && audio != selectedFormat(previous, C.TRACK_TYPE_AUDIO)) {
                    // Escolheu a faixa original: volta a seguir o idioma original de cada vídeo
                    val language = if (audio.roleFlags and C.ROLE_FLAG_MAIN != 0) null else audio.language
                    AudioLanguagePreference.set(this@PlayerActivity, language)
                    Log.i(TAG, "Áudio preferido: ${language ?: "original"} (${audio.label})")
                }
            }
        })
        player = exo
        // Fim do vídeo: na playlist, segue para o próximo sozinho
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && hasNext()) playQueueItem(queueIndex + 1)
            }
        })
        playerView.player = queueAwarePlayer(exo)
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        if (queue.size > 1) {
            Toast.makeText(this, getString(R.string.queue_position, queueIndex + 1, queue.size, title), Toast.LENGTH_LONG).show()
        }

        Log.i(TAG, "Tocando: $title")
        status.visibility = View.GONE
        playerView.requestFocus()
        handler.removeCallbacks(skipChecker)
        handler.post(skipChecker)
    }

    /** Formato escolhido manualmente (override) para o tipo de faixa, ou null se automático. */
    private fun selectedFormat(parameters: TrackSelectionParameters, type: Int): Format? {
        val override = parameters.overrides.values.firstOrNull { it.type == type && it.trackIndices.isNotEmpty() }
            ?: return null
        return override.mediaTrackGroup.getFormat(override.trackIndices[0])
    }

    /** Botão ☰ (menu) do controle remoto: atalho para Qualidade e Áudio. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            showMenuDialog()
            return true
        }
        // Teclas de próximo/anterior (controles com essas teclas, teclado, celular)
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { playerView.player?.seekToNext(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playerView.player?.seekToPrevious(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMenuDialog() {
        val exo = player ?: return
        if (audioGroups(exo.currentTracks).size <= 1) return showQualityDialog() // sem dublagens: direto na qualidade
        AlertDialog.Builder(this)
            .setItems(arrayOf(qualityButton.text, audioButton.text)) { _, which ->
                if (which == 0) showQualityDialog() else showAudioDialog()
            }
            .show()
    }

    /** Cada idioma é um grupo de faixas de áudio (uma AdaptationSet no manifesto). */
    private fun audioGroups(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    private fun currentAudioFormat(tracks: Tracks): Format? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
        return (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let(group::getTrackFormat)
    }

    /** Nome do idioma em português; marca a faixa original. */
    private fun audioLabel(format: Format): String {
        val name = format.language
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale("pt", "BR")) }
            ?.replaceFirstChar { it.uppercase() }
            ?: format.label ?: "?"
        return if (format.roleFlags and C.ROLE_FLAG_MAIN != 0) getString(R.string.audio_original, name) else name
    }

    private fun showAudioDialog() {
        val exo = player ?: return
        val groups = audioGroups(exo.currentTracks)
        if (groups.isEmpty()) return
        // Português primeiro, depois a original, depois o resto em ordem alfabética
        val sorted = groups.sortedWith(
            compareBy<Tracks.Group>(
                { if (it.getTrackFormat(0).language?.startsWith("pt") == true) 0 else 1 },
                { if (it.getTrackFormat(0).roleFlags and C.ROLE_FLAG_MAIN != 0) 0 else 1 },
                { audioLabel(it.getTrackFormat(0)) },
            ),
        )
        val checked = sorted.indexOfFirst { it.isSelected }

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_title)
            .setSingleChoiceItems(sorted.map { audioLabel(it.getTrackFormat(0)) }.toTypedArray(), checked) { dialog, which ->
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(sorted[which].mediaTrackGroup, 0))
                    .build()
                dialog.dismiss()
            }
            .show()
    }

    /** O botão Áudio só aparece quando o vídeo tem mais de um idioma. */
    private fun updateAudioButton(tracks: Tracks) {
        if (audioGroups(tracks).size <= 1) {
            audioButton.visibility = View.GONE
            return
        }
        audioButton.visibility = View.VISIBLE
        val current = currentAudioFormat(tracks)
        audioButton.text = getString(R.string.audio_button, current?.let(::audioLabel) ?: "")
    }

    private fun showQualityDialog() {
        val exo = player ?: return
        val group = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        // Uma opção por altura (ex.: 1080p), da maior para a menor
        val options = (0 until group.length)
            .groupBy { group.getTrackFormat(it).height }
            .toSortedMap(compareByDescending { it })
            .map { (height, indices) -> height to indices.first() }

        val labels = listOf(getString(R.string.quality_auto)) + options.map { "${it.first}p" }
        val saved = QualityPreference.get(this)
        val checked = if (saved == 0) 0 else options.indexOfFirst { it.first == saved } + 1

        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                val builder = exo.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                } else {
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, options[which - 1].second))
                }
                exo.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateQualityButton() {
        val current = player?.videoSize?.height ?: 0
        val saved = QualityPreference.get(this)
        qualityButton.text = if (saved == 0 && current == 0) {
            getString(R.string.quality_title)
        } else if (saved == 0) {
            getString(R.string.quality_button_auto, current)
        } else {
            getString(R.string.quality_button_fixed, saved)
        }
    }

    /** Fixa a qualidade salva (a maior que não passe dela); 0 = automática. */
    private fun applySavedQuality(exo: ExoPlayer, tracks: Tracks) {
        val maxHeight = QualityPreference.get(this)
        if (maxHeight <= 0) return
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
        val best = (0 until group.length)
            .filter { group.getTrackFormat(it).height <= maxHeight }
            .maxByOrNull { group.getTrackFormat(it).height } ?: return

        val parameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, best))
            .build()
        appliedByApp = parameters
        exo.trackSelectionParameters = parameters
    }

    /** Se a posição atual cair dentro de um trecho do SponsorBlock, pula para o fim dele. */
    private fun checkSkip() {
        val exo = player ?: return
        if (!exo.isPlaying) return
        val position = exo.currentPosition
        // Margem no fim para não ficar pulando de novo logo após o seek
        val segment = segments.firstOrNull { position >= it.startMs && position < it.endMs - 500 } ?: return

        Log.i(TAG, "Pulando ${segment.category}: ${segment.startMs}..${segment.endMs} ms")
        exo.seekTo(segment.endMs)
        Toast.makeText(this, getString(R.string.skipped, categoryLabel(segment.category)), Toast.LENGTH_SHORT).show()
    }

    private fun categoryLabel(category: String) = getString(
        when (category) {
            "sponsor" -> R.string.category_sponsor
            "selfpromo" -> R.string.category_selfpromo
            "interaction" -> R.string.category_interaction
            else -> R.string.category_other
        },
    )

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_PLAYLIST = "playlist"
        private const val TAG = "BlockYou"
        private const val MAX_HEIGHT = 1080
        private const val SKIP_CHECK_INTERVAL_MS = 500L
        private const val MAX_RECOVERIES = 5
    }
}
