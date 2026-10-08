# Blocked4You para TV Samsung (Tizen)

Módulo do [TizenBrew](https://github.com/reisxd/TizenBrew) que abre o YouTube oficial de TV e:

- remove os anúncios (vídeos, fileiras e Shorts patrocinados);
- pula trechos patrocinados com o [SponsorBlock](https://sponsor.ajay.app) (dados CC BY-NC-SA 4.0);
- começa no áudio em português quando o vídeo tem dublagem.

Login, menus, qualidade e busca por voz continuam sendo os do app oficial.

## Instalar na TV

1. Instale o TizenBrew na TV (veja a documentação do TizenBrew).
2. No TizenBrew: **Module Manager → Add GitHub module** → digite `cadubra/Blocked4You`.
3. Abra o **Blocked4You** pela tela do TizenBrew.

## Desenvolvimento

- O manifesto do módulo é o `package.json` da raiz do repositório; o código injetado é `tizen-5.5-plus/dist/userScript.js` (JavaScript compatível com o Chromium 69 do Tizen 5.5, sem etapa de build).
- `npm test` (na raiz do repositório) roda os testes de limpeza de anúncios e áudio no Node.
- O TizenBrew baixa o módulo pelo jsDelivr, que guarda cópia em cache por até ~12 h. Depois de publicar
  uma mudança, limpe o cache em `https://purge.jsdelivr.net/gh/cadubra/Blocked4You@main/tizen-5.5-plus/dist/userScript.js`
  e reabra o módulo no TizenBrew.

## Configuração

Fica no `localStorage` da página do YouTube, na chave `blocked4you.config`:

| Opção | Padrão | O que faz |
|---|---|---|
| `adblock` | `true` | Remove anúncios |
| `sponsorBlock` | `true` | Pula trechos patrocinados |
| `sponsorCategories` | `["sponsor","selfpromo","interaction"]` | Categorias puladas |
| `audioLanguage` | `"pt"` | Idioma de áudio preferido (`""` = sempre o original) |
