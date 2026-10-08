/*
 * Blocked4You para TV Samsung (módulo do TizenBrew).
 *
 * O TizenBrew abre o YouTube oficial de TV (youtube.com/tv) e injeta este script
 * antes da página carregar. O script:
 *  1. limpa os anúncios das respostas do YouTube antes que o app oficial as leia;
 *  2. pula trechos patrocinados (SponsorBlock);
 *  3. deixa o áudio em português como padrão quando o vídeo tem dublagem.
 *
 * Escrito em JavaScript compatível com o navegador do Tizen 5.5 (Chromium 69):
 * sem "?.", sem "??" e sem módulos.
 */
(function () {
  'use strict';

  if (window.__blocked4you) return; // evita rodar duas vezes
  window.__blocked4you = true;

  var CONFIG_KEY = 'blocked4you.config';
  var config = loadConfig();

  function loadConfig() {
    var defaults = {
      adblock: true,
      sponsorBlock: true,
      sponsorCategories: ['sponsor', 'selfpromo', 'interaction'],
      audioLanguage: 'pt' // prefixo do idioma preferido; '' = sempre o original
    };
    try {
      var saved = JSON.parse(localStorage.getItem(CONFIG_KEY) || '{}');
      for (var key in saved) defaults[key] = saved[key];
    } catch (e) { /* configuração corrompida: usa o padrão */ }
    return defaults;
  }

  function log() {
    var args = Array.prototype.slice.call(arguments);
    args.unshift('[Blocked4You]');
    console.log.apply(console, args);
  }

  // ---------------------------------------------------------------------------
  // 1. Anúncios
  // ---------------------------------------------------------------------------

  // Só vale a pena percorrer respostas que mencionam anúncios (as respostas são grandes
  // e a TV é lenta); a busca no texto é bem mais barata que percorrer o objeto.
  var AD_HINTS = ['adPlacements', 'playerAds', 'adSlots', 'adSlotRenderer', '"isAd"', 'adBreakHeartbeatParams'];

  function mentionsAds(text) {
    for (var i = 0; i < AD_HINTS.length; i++) {
      if (text.indexOf(AD_HINTS[i]) !== -1) return true;
    }
    return false;
  }

  /** Um item de lista que é um anúncio (fileira patrocinada, Short patrocinado...). */
  function isAdItem(item) {
    if (!item || typeof item !== 'object') return false;
    if (item.adSlotRenderer || item.promotedVideoRenderer || item.compactPromotedVideoRenderer) return true;
    var reel = item.command && item.command.reelWatchEndpoint;
    return !!(reel && reel.adClientParams && reel.adClientParams.isAd);
  }

  /** Remove anúncios do objeto, em profundidade. Devolve quantos itens foram removidos. */
  function stripAds(node, depth) {
    if (!node || typeof node !== 'object' || depth > 40) return 0;
    var removed = 0;

    if (Array.isArray(node)) {
      for (var i = node.length - 1; i >= 0; i--) {
        if (isAdItem(node[i])) {
          node.splice(i, 1);
          removed++;
        } else {
          removed += stripAds(node[i], depth + 1);
        }
      }
      return removed;
    }

    if (node.adPlacements) { node.adPlacements = []; removed++; }
    if (node.adSlots) { node.adSlots = []; removed++; }
    if (node.playerAds) { delete node.playerAds; removed++; }
    if (node.adBreakHeartbeatParams) { delete node.adBreakHeartbeatParams; removed++; }

    for (var key in node) {
      if (Object.prototype.hasOwnProperty.call(node, key)) removed += stripAds(node[key], depth + 1);
    }
    return removed;
  }

  // ---------------------------------------------------------------------------
  // 3. Áudio padrão (dublagens)
  // ---------------------------------------------------------------------------

  /**
   * Na resposta do player, cada faixa de áudio traz "audioTrack.audioIsDefault".
   * Marcamos como padrão a faixa no idioma preferido (se existir); o player oficial
   * passa a começar nela, e o usuário ainda pode trocar no menu do YouTube.
   */
  function preferAudioLanguage(response) {
    var lang = config.audioLanguage;
    var formats = response && response.streamingData && response.streamingData.adaptiveFormats;
    if (!lang || !formats) return false;

    var hasPreferred = formats.some(function (f) {
      return f.audioTrack && f.audioTrack.id && f.audioTrack.id.toLowerCase().indexOf(lang) === 0;
    });
    if (!hasPreferred) return false;

    formats.forEach(function (f) {
      if (f.audioTrack && f.audioTrack.id) {
        f.audioTrack.audioIsDefault = f.audioTrack.id.toLowerCase().indexOf(lang) === 0;
      }
    });
    return true;
  }

  // ---------------------------------------------------------------------------
  // Gancho no JSON.parse: o app de TV lê todas as respostas da API por ele
  // ---------------------------------------------------------------------------

  var originalParse = JSON.parse;
  JSON.parse = function (text, reviver) {
    var result = originalParse.call(this, text, reviver);
    if (typeof text !== 'string' || !result || typeof result !== 'object') return result;
    try {
      if (config.adblock && mentionsAds(text)) {
        var removed = stripAds(result, 0);
        if (removed) log('anúncios removidos:', removed);
      }
      if (text.indexOf('"audioTrack"') !== -1 && preferAudioLanguage(result)) {
        log('áudio padrão:', config.audioLanguage);
      }
    } catch (e) {
      log('erro ao processar resposta', e); // nunca quebrar o app oficial
    }
    return result;
  };

  // ---------------------------------------------------------------------------
  // 2. SponsorBlock
  // ---------------------------------------------------------------------------

  var segments = [];
  var currentVideoId = null;

  /** No app de TV a URL fica como ".../tv#/watch?v=ID&...". */
  function videoIdFromUrl() {
    var match = /[?&]v=([\w-]{11})/.exec(location.hash) || /[?&]v=([\w-]{11})/.exec(location.search);
    return match ? match[1] : null;
  }

  function sha256Hex(text) {
    var data = new TextEncoder().encode(text);
    return crypto.subtle.digest('SHA-256', data).then(function (buffer) {
      return Array.prototype.map.call(new Uint8Array(buffer), function (b) {
        return ('0' + b.toString(16)).slice(-2);
      }).join('');
    });
  }

  /** Consulta por prefixo do hash: o servidor não fica sabendo qual vídeo exato está tocando. */
  function loadSegments(videoId) {
    segments = [];
    if (!config.sponsorBlock || !videoId) return;
    sha256Hex(videoId).then(function (hash) {
      var url = 'https://sponsor.ajay.app/api/skipSegments/' + hash.slice(0, 4) +
        '?categories=' + encodeURIComponent(JSON.stringify(config.sponsorCategories));
      return fetch(url);
    }).then(function (response) {
      return response.ok ? response.json() : [];
    }).then(function (videos) {
      if (videoId !== currentVideoId) return; // o usuário já trocou de vídeo
      var mine = videos.filter(function (v) { return v.videoID === videoId; })[0];
      segments = mine ? mine.segments.filter(function (s) {
        return !s.actionType || s.actionType === 'skip';
      }) : [];
      log('SponsorBlock:', segments.length, 'trecho(s) para', videoId);
    }).catch(function (e) {
      log('SponsorBlock indisponível', e);
    });
  }

  var CATEGORY_LABELS = {
    sponsor: 'Patrocínio',
    selfpromo: 'Autopromoção',
    interaction: 'Pedido de inscrição'
  };

  function checkSkip(video) {
    var t = video.currentTime;
    for (var i = 0; i < segments.length; i++) {
      var start = segments[i].segment[0];
      var end = segments[i].segment[1];
      // Margem no fim para não pular de novo logo depois do seek
      if (t >= start && t < end - 0.5) {
        video.currentTime = end;
        toast((CATEGORY_LABELS[segments[i].category] || 'Trecho') + ' pulado');
        log('pulou', segments[i].category, start, '→', end);
        return;
      }
    }
  }

  function watchVideoChanges() {
    var id = videoIdFromUrl();
    if (id !== currentVideoId) {
      currentVideoId = id;
      loadSegments(id);
    }
  }

  // O <video> do app de TV é criado uma vez e reaproveitado; o evento timeupdate
  // é capturado no documento para pegar qualquer <video>, mesmo criado depois.
  document.addEventListener('timeupdate', function (event) {
    if (segments.length && event.target && event.target.tagName === 'VIDEO') checkSkip(event.target);
  }, true);
  window.addEventListener('hashchange', watchVideoChanges);
  setInterval(watchVideoChanges, 1000); // nem toda troca de vídeo muda o hash na hora

  // ---------------------------------------------------------------------------
  // Avisos na tela
  // ---------------------------------------------------------------------------

  var toastTimer = null;

  function toast(message) {
    if (!document.body) return;
    var el = document.getElementById('blocked4you-toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'blocked4you-toast';
      el.style.cssText = [
        'position:fixed', 'right:48px', 'bottom:48px', 'z-index:2147483647',
        'background:rgba(20,20,25,0.92)', 'color:#fff', 'padding:16px 28px',
        'border-left:6px solid #E8302F', 'border-radius:10px',
        'font:500 26px/1.3 Roboto,Arial,sans-serif', 'transition:opacity .3s', 'opacity:0'
      ].join(';');
      document.body.appendChild(el);
    }
    el.textContent = message;
    el.style.opacity = '1';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.style.opacity = '0'; }, 3000);
  }

  function onReady() {
    toast('Blocked4You ativo');
    log('ativo', config);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onReady);
  } else {
    onReady();
  }

  // Exposto para os testes (Node) e para depuração pelo console
  window.__blocked4youInternals = { stripAds: stripAds, preferAudioLanguage: preferAudioLanguage, mentionsAds: mentionsAds };
})();
