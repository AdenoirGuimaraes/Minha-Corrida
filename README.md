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

## Prioridade de leitura (vários apps abertos)
1. **Cartão da Uber em sobreposição** — lido mesmo com o inDrive na frente; o OCR cobre só a área do cartão (partes de cima e de baixo).
2. **App em primeiro plano** — ex.: oferta do inDrive aberta em tela inteira (com leitura do mapa).
3. Nada a ler (tela inicial do inDrive, só a bolha da Uber).

O cartão da Uber só é mostrado **completo** (valor + distância até o passageiro + distância da viagem). Se vier só a parte de cima, o leitor insiste a cada 0,5 s e, depois de 2 leituras, amplia o OCR até o fim da tela para pegar a parte de baixo — sem devolver a vez ao inDrive nesse meio-tempo. Os textos de todas as janelas da Uber (partes de cima e de baixo) são juntados numa leitura só.

Quando o cartão da Uber fecha, o inDrive recomeça a leitura do mapa do zero. Uma sobreposição grande da Uber que não seja oferta é ignorada após algumas leituras sem resultado.

## Lucro, nota, apps e voz
- **Lucro real líquido**: informe preço do combustível (R$/l), consumo (km/l) e, se quiser, outros
  custos por km. O card mostra `valor − km total × custo por km` ("Lucro" ou "Prejuízo"). A tela de
  configurações mostra o custo por km e quanto o mínimo de R$/km deixa de lucro por km.
- **Nota mínima do passageiro**: abaixo dela a corrida fica vermelha, mesmo com bom R$/km.
  Passageiro sem nota (novo) não é recusado. O app não toca em "Recusar": só avisa.
- **Apps lidos**: Uber e inDrive podem ser ligados/desligados; app desligado nem é lido.
- **Voz**: "Aceitar corrida. 8 reais e 50 centavos. inDrive" só nas corridas verdes, uma vez por
  oferta, depois de o card aparecer. Pode ser desligada.

## Card da corrida
De um lado ao outro da tela. **Faixa do topo na cor do app** (Uber preta com letras brancas,
inDrive verde-limão com letras pretas) e a nota do passageiro. Três quadrados:
**R$/km** (com km total e a soma), **Lucro** (com valor da corrida e gasto; "Prejuízo" se negativo)
e **Mínimo** (valor da corrida para atingir o mínimo de R$/km e o lucro que ela daria). Fundo verde = boa, vermelho = abaixo.
Voz em velocidade 1,4×.

## Tela do app (tema preto e amarelo)
- Abas **Configurações** e **Simulador**. O simulador mostra o mesmo card da leitura real, recalculado a cada número digitado
  (corrida de exemplo, mínimo R$/km, nota mínima e custos), antes mesmo de salvar.
  Usa a mesma regra do leitor (`EvaluateRideUseCase`). Botão **Testar voz** fala a corrida simulada.
- **Checklist** de apps lidos e de "Falar corridas": verde marcado, vermelho desmarcado.

## Encerrar
O botão **Encerrar** pausa a leitura (serviço ocioso, sem OCR nem leitor na tela) e fecha o app.
A acessibilidade continua ativada, porque o Android não deixa o app religá-la sozinho;
abrir o app retoma a leitura automaticamente.

## Limitações conhecidas (MVP)
- Na **lista** do inDrive não aparece a distância até o passageiro; o cálculo usa só o trajeto até você abrir a oferta.
- Se a Uber renderizar o cartão sem texto acessível em alguma versão, será preciso um modo OCR de reserva.
- Na lista do inDrive com várias ofertas, é lida a primeira.

## Adicionar outro app (ex.: 99)
1. Nova entrada em `Platform` com o pacote do app.
2. Nova classe `XxxOfferParser : RideOfferParser` + teste com os textos da tela.
3. Registrar em `AppContainer`.

## Atualização pelo próprio app
Ao abrir o Minha Corrida, ele consulta a release `ultima-versao` no GitHub. Havendo versão nova,
aparece **"Nova versão disponível"** com o botão **Atualizar**: o app baixa o APK e abre o
instalador do Android (o Android sempre pede um toque de confirmação em "Instalar").

Requisitos:
- Repositório **público** (o app consulta o GitHub sem login).
- Segredos **SIGNING_KEYSTORE_BASE64** (a chave de assinatura em base64) e
  **SIGNING_STORE_PASSWORD** em *Settings → Secrets and variables → Actions*. A chave nunca
  vai para o repositório; sem ela, cada APK sai com assinatura diferente e o Android recusa
  instalar por cima.

## Gerar o APK sem computador
A cada envio para `main`, o GitHub Actions roda os testes e publica o APK em
**Releases → ultima-versao → MinhaCorrida.apk**.
