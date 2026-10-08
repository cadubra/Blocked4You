# Blocked4You

YouTube sem anúncios na TV.

| Pasta | Plataforma | O que é |
|---|---|---|
| [`firestick/`](firestick/) | Amazon Fire TV / Fire Stick (Android 6+) | App próprio em Kotlin: NewPipeExtractor + Media3, login da conta, SponsorBlock, qualidade e áudio dublado |
| [`tizen-5.5-plus/`](tizen-5.5-plus/) | TV Samsung com Tizen 5.5 ou mais novo (2020+) | Módulo do TizenBrew que remove anúncios do YouTube oficial de TV |

O `package.json` da raiz é o manifesto do módulo do TizenBrew (ele procura esse arquivo na raiz do repositório).

## Fire Stick

Abra `firestick/` no Android Studio ou compile com `firestick\gradlew.bat assembleDebug`. O APK fica em
`firestick/app/build/outputs/apk/debug/app-debug.apk`; instale com `adb install -r`.

## TV Samsung

1. Instale o [TizenBrew](https://github.com/reisxd/TizenBrew) na TV.
2. No TizenBrew: **Module Manager → Add GitHub module** → `cadubra/Blocked4You`.
3. Abra o **Blocked4You** pela tela do TizenBrew.

Detalhes em [`tizen-5.5-plus/README.md`](tizen-5.5-plus/README.md).

## Licenças

- `firestick/`: GPL-3.0, porque usa o NewPipeExtractor (GPL-3.0).
- `tizen-5.5-plus/` e o `package.json` da raiz: MIT (código próprio).

## Créditos

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0), usado pelo app do Fire Stick.
- Dados de trechos patrocinados: [SponsorBlock](https://sponsor.ajay.app) (CC BY-NC-SA 4.0).
