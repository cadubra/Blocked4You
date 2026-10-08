// Testa a limpeza de anúncios e o áudio padrão sem precisar da TV.
// Simula o mínimo de "window"/"document" que o script usa e roda o userScript real.
const assert = require('assert');
const fs = require('fs');
const path = require('path');

global.window = global;
global.localStorage = { getItem: () => null };
global.location = { hash: '', search: '' };
global.document = { readyState: 'complete', addEventListener() {}, body: null, getElementById: () => null };
global.addEventListener = () => {};
global.setInterval = () => 0;

eval(fs.readFileSync(path.join(__dirname, '..', 'dist', 'userScript.js'), 'utf8'));

// Resposta do player com anúncios e duas faixas de áudio (original em inglês + dublagem pt-BR)
const player = JSON.parse(JSON.stringify({
  adPlacements: [{ adPlacementRenderer: {} }],
  playerAds: [{ playerLegacyDesktopWatchAdsRenderer: {} }],
  adSlots: [{ adSlotRenderer: {} }],
  streamingData: {
    adaptiveFormats: [
      { itag: 137, mimeType: 'video/mp4' },
      { itag: 140, audioTrack: { id: 'en-US.4', displayName: 'English (US) original', audioIsDefault: true } },
      { itag: 140, audioTrack: { id: 'pt-BR.3', displayName: 'Portuguese (Brazil)', audioIsDefault: false } },
    ],
  },
}));
assert.deepStrictEqual(player.adPlacements, []);
assert.strictEqual(player.playerAds, undefined);
assert.deepStrictEqual(player.adSlots, []);
const audio = player.streamingData.adaptiveFormats.filter((f) => f.audioTrack);
assert.strictEqual(audio[0].audioTrack.audioIsDefault, false, 'original deixa de ser o padrão');
assert.strictEqual(audio[1].audioTrack.audioIsDefault, true, 'pt-BR vira o padrão');

// Página inicial da TV com uma fileira patrocinada no meio e um Short patrocinado
const browse = JSON.parse(JSON.stringify({
  contents: { tvBrowseRenderer: { content: { tvSurfaceContentRenderer: { content: { sectionListRenderer: { contents: [
    { shelfRenderer: { title: 'Recomendados', content: { horizontalListRenderer: { items: [
      { tileRenderer: { id: 'v1' } },
      { adSlotRenderer: { id: 'ad' } },
      { tileRenderer: { id: 'v2' } },
    ] } } } },
    { adSlotRenderer: { id: 'fileira-de-anuncio' } },
  ] } } } } } },
  entries: [
    { command: { reelWatchEndpoint: { videoId: 's1' } } },
    { command: { reelWatchEndpoint: { videoId: 's2', adClientParams: { isAd: true } } } },
  ],
}));
const sections = browse.contents.tvBrowseRenderer.content.tvSurfaceContentRenderer.content.sectionListRenderer.contents;
assert.strictEqual(sections.length, 1, 'fileira de anúncio removida');
assert.deepStrictEqual(
  sections[0].shelfRenderer.content.horizontalListRenderer.items.map((i) => i.tileRenderer.id),
  ['v1', 'v2'],
  'anúncio dentro da fileira removido',
);
assert.deepStrictEqual(browse.entries.map((e) => e.command.reelWatchEndpoint.videoId), ['s1'], 'Short patrocinado removido');

// Respostas sem anúncios passam intactas (e sem custo de percorrer)
const clean = JSON.parse('{"a":[1,2,3],"b":{"c":"d"}}');
assert.deepStrictEqual(clean, { a: [1, 2, 3], b: { c: 'd' } });

// Vídeo sem dublagem em português: o original continua padrão
const noPt = JSON.parse(JSON.stringify({ streamingData: { adaptiveFormats: [
  { audioTrack: { id: 'en-US.4', audioIsDefault: true } },
  { audioTrack: { id: 'es-US.3', audioIsDefault: false } },
] } }));
assert.strictEqual(noPt.streamingData.adaptiveFormats[0].audioTrack.audioIsDefault, true);

console.log('OK: todos os testes passaram');
