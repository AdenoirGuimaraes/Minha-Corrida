# Minha Corrida

Mostra, em cima do Uber Driver e do inDrive, **quanto você ganha por km rodado**:

    R$/km = valor da corrida ÷ (km até o passageiro + km da viagem)

## Arquitetura
- **core** (Kotlin puro): parsers por plataforma → cálculo do R$/km → classificação contra o mínimo. Testado na JVM.
- **app** (Android): `RideReaderService` (Acessibilidade) lê os textos da tela, `RideOverlayController` mostra o resultado.
- Lê a árvore de acessibilidade em vez de print + OCR, por isso responde em ~0,1 s.

## Como rodar
1. Abra a pasta no **Android Studio** (Ladybug ou mais novo) e aguarde o *Gradle Sync*.
2. Rode os testes do núcleo: `gradle :core:test`.
3. Instale no celular (*Run ▶ app*).
4. No app, toque em **Ativar leitura de tela** → *Apps instalados* → **Minha Corrida** → ativar.
   - Android 13+: se aparecer "configuração restrita", vá em *Configurações → Apps → Minha Corrida → ⋮ → Permitir configurações restritas* e tente de novo.
5. Defina o **mínimo R$/km**. Overlay verde = atinge o mínimo; vermelho = abaixo.

## Exemplos (das telas reais)
| App | Valor | Busca | Viagem | Total | R$/km |
|---|---|---|---|---|---|
| Uber | R$ 8,91 | 6,6 km | 2,3 km | 8,9 km | **R$ 1,00** |
| inDrive (detalhe) | R$ 10 | 3,3 km | 7,3 km | 10,6 km | **R$ 0,94** |
| inDrive (lista) | R$ 10 | — | 7,0 km | 7,0 km | R$ 1,43 (só trajeto) |

## Limitações conhecidas (MVP)
- Na **lista** do inDrive não aparece a distância até o passageiro; o cálculo usa só o trajeto até você abrir a oferta.
- Se a Uber renderizar o cartão sem texto acessível em alguma versão, será preciso um modo OCR de reserva.
- Na lista do inDrive com várias ofertas, é lida a primeira.

## Adicionar outro app (ex.: 99)
1. Nova entrada em `Platform` com o pacote do app.
2. Nova classe `XxxOfferParser : RideOfferParser` + teste com os textos da tela.
3. Registrar em `AppContainer`.

## Gerar o APK sem computador
A cada envio para `main`, o GitHub Actions roda os testes e publica o APK em
**Releases → ultima-versao → MinhaCorrida.apk**.
