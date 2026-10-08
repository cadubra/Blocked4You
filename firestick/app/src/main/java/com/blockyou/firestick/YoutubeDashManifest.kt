package com.blockyou.firestick

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Monta um manifesto DASH com todas as qualidades do vídeo a partir dos streams progressivos.
 * Com isso o ExoPlayer escolhe a qualidade sozinho pela velocidade da internet e a engrenagem
 * do player lista as opções para escolha manual.
 */
object YoutubeDashManifest {

    /** Devolve o XML do manifesto, ou null se faltar informação para montá-lo. */
    fun build(info: StreamInfo): String? {
        val durationSec = info.duration
        if (durationSec <= 0) return null

        // H.264 (avc1): suportado por todo Fire TV
        val videos = info.videoOnlyStreams
            .filter { it.format == MediaFormat.MPEG_4 && it.isDashCompatible() }
            .distinctBy { it.itag }
        // Uma faixa por idioma (original, dublagens, descrição de áudio), na melhor qualidade de cada;
        // a original vem primeiro
        val audioTracks = info.audioStreams
            .filter { it.format == MediaFormat.M4A && it.isDashCompatible() }
            .groupBy { it.audioTrackId ?: "" }
            .values
            .map { track -> track.maxBy { it.averageBitrate } }
            .sortedBy { if (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) 0 else 1 }

        if (videos.isEmpty() || audioTracks.isEmpty()) return null

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """)
            append("""type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${durationSec}S">""")
            append("<Period>")

            append("""<AdaptationSet id="0" contentType="video" mimeType="video/mp4" subsegmentAlignment="true">""")
            videos.forEach { stream ->
                val itag = stream.itagItem!!
                append("""<Representation id="v${stream.itag}" codecs="${itag.codec}" bandwidth="${itag.bitrate}" """)
                append("""width="${itag.width}" height="${itag.height}" frameRate="${itag.fps}">""")
                appendSegmentBase(stream, itag)
                append("</Representation>")
            }
            append("</AdaptationSet>")

            audioTracks.forEachIndexed { index, audio ->
                val audioItag = audio.itagItem!!
                val lang = audio.audioLocale?.toLanguageTag()?.let { """ lang="$it"""" }.orEmpty()
                append("""<AdaptationSet id="${index + 1}" contentType="audio" mimeType="audio/mp4"$lang subsegmentAlignment="true">""")
                // O papel ajuda o player a escolher a original quando não há preferência de idioma
                val role = when (audio.audioTrackType) {
                    null, AudioTrackType.ORIGINAL -> "main"
                    AudioTrackType.DUBBED -> "dub"
                    AudioTrackType.DESCRIPTIVE -> "description"
                    else -> "alternate"
                }
                append("""<Role schemeIdUri="urn:mpeg:dash:role:2011" value="$role"/>""")
                audio.audioTrackName?.let { append("<Label>").append(escapeXml(it)).append("</Label>") }
                append("""<Representation id="a${audio.itag}-$index" codecs="${audioItag.codec}" bandwidth="${audioItag.bitrate}" """)
                append("""audioSamplingRate="${audioItag.sampleRate}">""")
                append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${audioItag.audioChannels}"/>""")
                appendSegmentBase(audio, audioItag)
                append("</Representation>")
                append("</AdaptationSet>")
            }

            append("</Period></MPD>")
        }
    }

    /** Precisamos das posições do cabeçalho (init) e do índice (sidx) dentro do arquivo. */
    private fun Stream.isDashCompatible(): Boolean {
        val itag = itagItem ?: return false
        return deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            itag.codec != null && itag.bitrate > 0 &&
            itag.initEnd > 0 && itag.indexEnd > 0
    }

    private fun StringBuilder.appendSegmentBase(stream: Stream, itag: ItagItem) {
        append("<BaseURL>").append(escapeXml(stream.content)).append("</BaseURL>")
        append("""<SegmentBase indexRange="${itag.indexStart}-${itag.indexEnd}">""")
        append("""<Initialization range="${itag.initStart}-${itag.initEnd}"/>""")
        append("</SegmentBase>")
    }

    private fun escapeXml(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
